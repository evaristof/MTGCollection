import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { api, type AddCardInput } from '../api/client'
import type { CollectionCard, MagicSet } from '../types/mtg'
import { useTableControls } from '../hooks/useTableControls'
import { SortableTh } from '../components/SortableTh'
import { PaginationBar } from '../components/PaginationBar'
import { SetCombo } from '../components/SetCombo'
import { ImportCollectionDialog } from '../components/ImportCollectionDialog'
import { CardImageTooltip } from '../components/CardImageTooltip'

type AddFormState = AddCardInput
type EditFormState = {
  id: number
  card_name: string
  set_code: string
  foil: boolean
  language: string
  quantity: number
  card_type: string
  // Campos opcionais ficam como string no form (`''` = limpar ao salvar).
  price: string
  comentario: string
  localizacao: string
}

const EMPTY_ADD: AddFormState = {
  card_name: '',
  set_code: '',
  foil: false,
  language: '',
  quantity: 0,
}

/**
 * Pulls a user-friendly message out of an error thrown by `api.*`.
 *
 * The backend answers sync failures with a JSON body like `{"message": "..."}`;
 * `request()` wraps the raw body into `Request failed <status>: <body>`. We try
 * to parse the trailing JSON so the popup shows just the message instead of
 * the full noisy envelope.
 */
function extractErrorMessage(err: unknown): string {
  const raw = err instanceof Error ? err.message : String(err)
  const jsonStart = raw.indexOf('{')
  if (jsonStart >= 0) {
    try {
      const parsed = JSON.parse(raw.slice(jsonStart)) as { message?: unknown }
      if (typeof parsed.message === 'string' && parsed.message.trim()) {
        return parsed.message
      }
    } catch {
      // fall through to raw
    }
  }
  return raw
}

/**
 * Formats an ISO-8601 local-datetime string (e.g. `2025-11-18T10:22:31`) as
 * a human-friendly label for the data-dump combo. Invalid inputs fall back
 * to the raw string so nothing ever becomes unselectable.
 */
function formatDumpLabel(iso: string): string {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  return d.toLocaleString('pt-BR', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  })
}

export default function CardsPage() {
  const [cards, setCards] = useState<CollectionCard[]>([])
  const [sets, setSets] = useState<MagicSet[]>([])
  const [loading, setLoading] = useState(false)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [addForm, setAddForm] = useState<AddFormState>(EMPTY_ADD)
  const [editing, setEditing] = useState<EditFormState | null>(null)
  const [importOpen, setImportOpen] = useState(false)
  const [syncingId, setSyncingId] = useState<number | null>(null)
  // Data-dump state. `dumpTimestamps` is the list rendered in the combo;
  // `selectedDump === null` means "load the live collection", any other
  // value means "load that snapshot". The grid is read-only while a dump
  // is selected (editing/deleting/syncing only make sense on live rows).
  const [dumpTimestamps, setDumpTimestamps] = useState<string[]>([])
  const [selectedDump, setSelectedDump] = useState<string | null>(null)
  const [dumpBusy, setDumpBusy] = useState(false)
  // Seleção em lote (ids de `CollectionCard`). O checkbox do header é um
  // master controlado pelo estado: checked quando todas as linhas visíveis
  // estão selecionadas, indeterminate quando apenas parte está.
  const [selectedIds, setSelectedIds] = useState<Set<number>>(new Set())
  const [batchBusy, setBatchBusy] = useState(false)
  const masterCheckboxRef = useRef<HTMLInputElement>(null)

  const loadDumps = useCallback(async () => {
    try {
      const list = await api.listCollectionDumps()
      setDumpTimestamps(list)
      return list
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
      return [] as string[]
    }
  }, [])

  const loadCards = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const data = selectedDump
        ? await api.listCardsFromDump(selectedDump)
        : await api.listCards()
      setCards(data)
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    } finally {
      setLoading(false)
    }
  }, [selectedDump])

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void loadCards()
  }, [loadCards])

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void loadDumps()
  }, [loadDumps])

  useEffect(() => {
    // load sets for the dropdown — best-effort; not fatal if it fails
    void api
      .listSets()
      .then(setSets)
      .catch(() => {
        /* ignore */
      })
  }, [])

  const setOptions = useMemo(
    () =>
      sets
        .slice()
        .sort((a, b) =>
          a.set_name.localeCompare(b.set_name, undefined, { sensitivity: 'base' }),
        )
        .map((s) => ({ code: s.set_code, name: s.set_name })),
    [sets],
  )

  const setNameByCode = useMemo(() => {
    const m = new Map<string, string>()
    for (const s of sets) m.set(s.set_code, s.set_name)
    return m
  }, [sets])

  const getSetName = useCallback(
    (code: string) => setNameByCode.get(code) ?? code,
    [setNameByCode],
  )

  // The "Adicionar carta" form fields double as live filters for the grid:
  // typing in Nome / Linguagem, picking a Set, changing Quantidade, or
  // toggling Foil narrows the visible rows. Empty string / zero / false are
  // treated as "no filter for this field".
  const filteredCards = useMemo(() => {
    const nameQ = addForm.card_name.trim().toLowerCase()
    const langQ = addForm.language.trim().toLowerCase()
    const setQ = addForm.set_code.trim()
    const qty = addForm.quantity
    const foilOnly = addForm.foil
    if (!nameQ && !langQ && !setQ && (!qty || qty <= 0) && !foilOnly) {
      return cards
    }
    return cards.filter((c) => {
      if (nameQ && !c.card_name.toLowerCase().includes(nameQ)) return false
      if (langQ && !c.language.toLowerCase().includes(langQ)) return false
      if (setQ && c.set_code !== setQ) return false
      if (qty && qty > 0 && c.quantity !== qty) return false
      if (foilOnly && !c.foil) return false
      return true
    })
  }, [cards, addForm])

  // Reset to first page whenever any of the filters change.
  const filterKey = `${addForm.card_name}|${addForm.set_code}|${addForm.language}|${addForm.quantity}|${addForm.foil ? '1' : '0'}`

  const {
    pageRows,
    totalCount,
    pageCount,
    page,
    setPage,
    pageSize,
    setPageSize,
    sortKey,
    sortDirection,
    toggleSort,
  } = useTableControls<CollectionCard>({
    rows: filteredCards,
    initialSortKey: 'card_name',
    resetKey: filterKey,
    comparators: {
      // Sort the "Set" column by the resolved set name (fallback to code).
      set_code: (a, b) =>
        getSetName(a.set_code).localeCompare(getSetName(b.set_code), undefined, {
          numeric: true,
          sensitivity: 'base',
        }),
    },
  })

  const onAdd = async (e: React.FormEvent) => {
    e.preventDefault()
    setError(null)
    if (!addForm.card_name.trim() || !addForm.set_code.trim() || !addForm.language.trim()) {
      setError('Nome, set e linguagem são obrigatórios.')
      return
    }
    if (addForm.quantity < 1) {
      setError('Quantidade precisa ser >= 1.')
      return
    }
    setSaving(true)
    try {
      await api.addCard({
        card_name: addForm.card_name.trim(),
        set_code: addForm.set_code.trim(),
        foil: addForm.foil,
        language: addForm.language.trim(),
        quantity: addForm.quantity,
      })
      setAddForm(EMPTY_ADD)
      await loadCards()
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    } finally {
      setSaving(false)
    }
  }

  const onStartEdit = (c: CollectionCard) => {
    setEditing({
      id: c.id,
      card_name: c.card_name,
      set_code: c.set_code,
      foil: c.foil,
      language: c.language,
      quantity: c.quantity,
      card_type: c.card_type ?? '',
      price: c.price === null || c.price === undefined ? '' : String(c.price),
      comentario: c.comentario ?? '',
      localizacao: c.localizacao ?? '',
    })
  }

  const onSaveEdit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!editing) return
    setError(null)
    setSaving(true)
    try {
      // Para preço, string vazia = limpar (mandamos `null`); caso contrário
      // parseamos como float. Parse inválido → mantém no frontend (não
      // envia `price`, então o backend não altera).
      let priceField: number | null | undefined
      const rawPrice = editing.price.trim()
      if (rawPrice === '') {
        priceField = null
      } else {
        const parsed = Number(rawPrice.replace(',', '.'))
        priceField = Number.isFinite(parsed) ? parsed : undefined
      }
      await api.updateCard(editing.id, {
        card_name: editing.card_name.trim() || undefined,
        set_code: editing.set_code.trim() || undefined,
        foil: editing.foil,
        language: editing.language.trim(),
        quantity: editing.quantity,
        card_type: editing.card_type,
        price: priceField,
        comentario: editing.comentario,
        localizacao: editing.localizacao,
      })
      setEditing(null)
      await loadCards()
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    } finally {
      setSaving(false)
    }
  }

  const formatMoney = (value: number | null | undefined): string => {
    if (value === null || value === undefined) return '-'
    return value.toLocaleString('en-US', {
      style: 'currency',
      currency: 'USD',
      minimumFractionDigits: 2,
    })
  }

  const totalFor = (c: CollectionCard): number | null => {
    if (c.price === null || c.price === undefined) return null
    return Number(c.price) * c.quantity
  }

  // Mantém apenas ids que ainda estão visíveis após reload/filtro mudar.
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setSelectedIds((prev) => {
      if (prev.size === 0) return prev
      const visible = new Set(filteredCards.map((c) => c.id))
      let changed = false
      const next = new Set<number>()
      for (const id of prev) {
        if (visible.has(id)) next.add(id)
        else changed = true
      }
      return changed ? next : prev
    })
  }, [filteredCards])

  const allSelected =
    filteredCards.length > 0 && selectedIds.size === filteredCards.length
  const someSelected = selectedIds.size > 0 && !allSelected

  // `indeterminate` só existe na DOM — mexemos via ref depois do render.
  useEffect(() => {
    if (masterCheckboxRef.current) {
      masterCheckboxRef.current.indeterminate = someSelected
    }
  }, [someSelected])

  const onToggleSelectAll = () => {
    if (allSelected) {
      setSelectedIds(new Set())
    } else {
      setSelectedIds(new Set(filteredCards.map((c) => c.id)))
    }
  }

  const onToggleSelectOne = (id: number) => {
    setSelectedIds((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }

  const onDelete = async (id: number) => {
    if (!confirm(`Deletar a carta #${id}?`)) return
    setError(null)
    try {
      await api.deleteCard(id)
      await loadCards()
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    }
  }

  const onSync = async (id: number) => {
    setSyncingId(id)
    try {
      const updated = await api.syncCard(id)
      // Merge the refreshed row in place — avoids a full reload and
      // preserves the user's current sort/page/filter.
      setCards((prev) => prev.map((c) => (c.id === id ? updated : c)))
    } catch (err) {
      alert(`Falha ao sincronizar carta #${id}:\n\n${extractErrorMessage(err)}`)
    } finally {
      setSyncingId(null)
    }
  }

  const onBatchDelete = async () => {
    if (selectedIds.size === 0) return
    const ids = Array.from(selectedIds)
    if (!confirm(`Deletar ${ids.length} carta(s) selecionada(s)?`)) return
    setBatchBusy(true)
    setError(null)
    const failures: { id: number; err: unknown }[] = []
    try {
      for (const id of ids) {
        try {
          await api.deleteCard(id)
        } catch (err) {
          failures.push({ id, err })
        }
      }
      setSelectedIds(new Set())
      await loadCards()
      if (failures.length) {
        alert(
          `Falha ao deletar ${failures.length} carta(s):\n\n` +
            failures
              .map((f) => `#${f.id}: ${extractErrorMessage(f.err)}`)
              .join('\n'),
        )
      }
    } finally {
      setBatchBusy(false)
    }
  }

  const onBatchSync = async () => {
    if (selectedIds.size === 0) return
    const ids = Array.from(selectedIds)
    setBatchBusy(true)
    setError(null)
    const failures: { id: number; err: unknown }[] = []
    try {
      for (const id of ids) {
        try {
          const updated = await api.syncCard(id)
          setCards((prev) => prev.map((c) => (c.id === id ? updated : c)))
        } catch (err) {
          failures.push({ id, err })
        }
      }
      if (failures.length) {
        alert(
          `Falha ao sincronizar ${failures.length} carta(s):\n\n` +
            failures
              .map((f) => `#${f.id}: ${extractErrorMessage(f.err)}`)
              .join('\n'),
        )
      }
    } finally {
      setBatchBusy(false)
    }
  }

  const onCreateDump = async () => {
    setDumpBusy(true)
    setError(null)
    try {
      await api.createCollectionDump()
      await loadDumps()
    } catch (err) {
      alert(`Falha ao gerar data dump:\n\n${extractErrorMessage(err)}`)
    } finally {
      setDumpBusy(false)
    }
  }

  const onDeleteSelectedDump = async () => {
    if (!selectedDump) return
    if (!confirm(`Excluir o data dump de ${formatDumpLabel(selectedDump)}?\n\nEssa ação é irreversível.`)) return
    setDumpBusy(true)
    setError(null)
    try {
      await api.deleteCollectionDump(selectedDump)
      // Back to "live collection" after deleting the viewed snapshot.
      setSelectedDump(null)
      await loadDumps()
    } catch (err) {
      alert(`Falha ao excluir data dump:\n\n${extractErrorMessage(err)}`)
    } finally {
      setDumpBusy(false)
    }
  }

  // A dump is read-only history — never let the user mutate rows that
  // reflect a past snapshot.
  const viewingDump = selectedDump !== null

  return (
    <section className="page">
      <div className="toolbar">
        <h2>
          Cartas da coleção {loading && <span className="muted">(carregando…)</span>}
          {viewingDump && (
            <span className="muted"> — snapshot de {formatDumpLabel(selectedDump!)}</span>
          )}
        </h2>
        <div className="toolbar__actions">
          <button onClick={() => void loadCards()} disabled={loading}>
            Recarregar
          </button>
          <button
            type="button"
            className="danger"
            onClick={() => void onBatchDelete()}
            disabled={selectedIds.size === 0 || batchBusy || viewingDump}
            title={
              viewingDump
                ? 'Snapshots são somente leitura'
                : selectedIds.size === 0
                  ? 'Selecione pelo menos uma carta'
                  : `Deletar ${selectedIds.size} carta(s) selecionada(s)`
            }
          >
            {batchBusy ? 'Processando…' : `Deletar selecionados (${selectedIds.size})`}
          </button>
          <button
            type="button"
            onClick={() => void onBatchSync()}
            disabled={selectedIds.size === 0 || batchBusy || viewingDump}
            title={
              viewingDump
                ? 'Snapshots são somente leitura'
                : selectedIds.size === 0
                  ? 'Selecione pelo menos uma carta'
                  : `Sincronizar ${selectedIds.size} carta(s) selecionada(s) no Scryfall`
            }
          >
            {batchBusy ? 'Processando…' : `Sincronizar selecionados (${selectedIds.size})`}
          </button>
          <button
            type="button"
            onClick={() => setImportOpen(true)}
            disabled={viewingDump}
            title={viewingDump ? 'Selecione "— atual —" para importar' : undefined}
          >
            Importar coleção…
          </button>
          <button
            type="button"
            onClick={() => void onCreateDump()}
            disabled={dumpBusy || viewingDump}
            title={
              viewingDump
                ? 'Selecione "— atual —" para gerar um novo dump'
                : 'Gerar um snapshot da coleção atual'
            }
          >
            {dumpBusy ? 'Gerando…' : 'Data Dump'}
          </button>
          <label className="muted" style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
            <span>Snapshot:</span>
            <select
              value={selectedDump ?? ''}
              onChange={(e) => setSelectedDump(e.target.value === '' ? null : e.target.value)}
              disabled={dumpBusy}
            >
              <option value="">— atual —</option>
              {dumpTimestamps.map((ts) => (
                <option key={ts} value={ts}>
                  {formatDumpLabel(ts)}
                </option>
              ))}
            </select>
          </label>
          <button
            type="button"
            className="danger"
            onClick={() => void onDeleteSelectedDump()}
            disabled={!viewingDump || dumpBusy}
            title={viewingDump ? 'Excluir o snapshot selecionado' : 'Selecione um snapshot para excluir'}
          >
            Excluir snapshot
          </button>
        </div>
      </div>

      <ImportCollectionDialog
        open={importOpen}
        onClose={() => setImportOpen(false)}
        onImported={() => void loadCards()}
      />

      {error && <p className="error">{error}</p>}

      <form className="form" onSubmit={onAdd}>
        <h3>Adicionar / filtrar cartas</h3>
        <p className="muted">
          Os campos abaixo também filtram o grid conforme você digita. Clique em{' '}
          <strong>Adicionar carta</strong> para criar uma entrada com esses valores — o backend
          consulta o Scryfall automaticamente para preencher <code>collector_number</code> e{' '}
          <code>type_line</code>.
        </p>
        <div className="form__grid">
          <label>
            <span>Nome da carta*</span>
            <input
              required
              value={addForm.card_name}
              onChange={(e) => setAddForm({ ...addForm, card_name: e.target.value })}
              placeholder="Lightning Bolt"
            />
          </label>
          <label>
            <span>Set*</span>
            <SetCombo
              id="add-set"
              value={addForm.set_code}
              onChange={(code) => setAddForm({ ...addForm, set_code: code })}
              options={setOptions}
              emptyLabel="— selecione um set —"
            />
          </label>
          <label>
            <span>Linguagem*</span>
            <input
              required
              value={addForm.language}
              onChange={(e) => setAddForm({ ...addForm, language: e.target.value })}
              placeholder="en / pt / ja…"
            />
          </label>
          <label>
            <span>Quantidade*</span>
            <input
              type="number"
              min={1}
              required
              value={addForm.quantity}
              onChange={(e) => setAddForm({ ...addForm, quantity: Number(e.target.value) })}
            />
          </label>
          <label className="checkbox">
            <input
              type="checkbox"
              checked={addForm.foil}
              onChange={(e) => setAddForm({ ...addForm, foil: e.target.checked })}
            />
            <span>Foil</span>
          </label>
        </div>
        <div className="form__actions">
          <button type="submit" disabled={saving}>
            {saving ? 'Salvando…' : 'Adicionar carta'}
          </button>
        </div>
      </form>

      {editing && (
        <form className="form form--edit" onSubmit={onSaveEdit}>
          <h3>Editar carta #{editing.id}</h3>
          <div className="form__grid">
            <label>
              <span>Nome</span>
              <input
                value={editing.card_name}
                onChange={(e) => setEditing({ ...editing, card_name: e.target.value })}
              />
            </label>
            <label>
              <span>Set</span>
              <SetCombo
                id="edit-set"
                value={editing.set_code}
                onChange={(code) => setEditing({ ...editing, set_code: code })}
                options={setOptions}
                emptyLabel="— selecione um set —"
              />
            </label>
            <label>
              <span>Linguagem*</span>
              <input
                required
                value={editing.language}
                onChange={(e) => setEditing({ ...editing, language: e.target.value })}
              />
            </label>
            <label>
              <span>Quantidade*</span>
              <input
                type="number"
                min={1}
                required
                value={editing.quantity}
                onChange={(e) => setEditing({ ...editing, quantity: Number(e.target.value) })}
              />
            </label>
            <label className="checkbox">
              <input
                type="checkbox"
                checked={editing.foil}
                onChange={(e) => setEditing({ ...editing, foil: e.target.checked })}
              />
              <span>Foil</span>
            </label>
            <label>
              <span>Tipo</span>
              <input
                value={editing.card_type}
                onChange={(e) => setEditing({ ...editing, card_type: e.target.value })}
                placeholder="ex.: Instant"
              />
            </label>
            <label>
              <span>Preço (US$)</span>
              <input
                type="number"
                step="0.01"
                min={0}
                value={editing.price}
                onChange={(e) => setEditing({ ...editing, price: e.target.value })}
                placeholder="ex.: 1.23"
              />
            </label>
            <label>
              <span>Comentário</span>
              <input
                value={editing.comentario}
                onChange={(e) => setEditing({ ...editing, comentario: e.target.value })}
                placeholder="observações"
              />
            </label>
            <label>
              <span>Localização</span>
              <input
                value={editing.localizacao}
                onChange={(e) => setEditing({ ...editing, localizacao: e.target.value })}
                placeholder="ex.: Caixa A / Página 3"
              />
            </label>
          </div>
          <div className="form__actions">
            <button type="submit" disabled={saving}>
              {saving ? 'Salvando…' : 'Salvar alterações'}
            </button>
            <button type="button" onClick={() => setEditing(null)} disabled={saving}>
              Cancelar
            </button>
          </div>
        </form>
      )}

      {totalCount === 0 && !loading && (
        <p className="muted">Nenhuma carta encontrada. Adicione a primeira no formulário acima.</p>
      )}

      {totalCount > 0 && (
        <>
          <div className="table-wrapper">
            <table>
              <thead>
                <tr>
                  <th style={{ width: 32 }}>
                    <input
                      ref={masterCheckboxRef}
                      type="checkbox"
                      checked={allSelected}
                      onChange={onToggleSelectAll}
                      disabled={viewingDump || filteredCards.length === 0}
                      aria-label="Selecionar todas as cartas visíveis"
                      title={
                        viewingDump
                          ? 'Snapshots são somente leitura'
                          : allSelected
                            ? 'Desmarcar todas'
                            : 'Marcar todas as cartas visíveis'
                      }
                    />
                  </th>
                  <SortableTh<CollectionCard> label="#" field="id" sortKey={sortKey} sortDirection={sortDirection} onToggle={toggleSort} />
                  <SortableTh<CollectionCard> label="Nº" field="card_number" sortKey={sortKey} sortDirection={sortDirection} onToggle={toggleSort} />
                  <SortableTh<CollectionCard> label="Nome" field="card_name" sortKey={sortKey} sortDirection={sortDirection} onToggle={toggleSort} />
                  <SortableTh<CollectionCard> label="Set" field="set_code" sortKey={sortKey} sortDirection={sortDirection} onToggle={toggleSort} />
                  <SortableTh<CollectionCard> label="Tipo" field="card_type" sortKey={sortKey} sortDirection={sortDirection} onToggle={toggleSort} />
                  <SortableTh<CollectionCard> label="Foil" field="foil" sortKey={sortKey} sortDirection={sortDirection} onToggle={toggleSort} />
                  <SortableTh<CollectionCard> label="Lang" field="language" sortKey={sortKey} sortDirection={sortDirection} onToggle={toggleSort} />
                  <SortableTh<CollectionCard> label="Qtd" field="quantity" sortKey={sortKey} sortDirection={sortDirection} onToggle={toggleSort} />
                  <SortableTh<CollectionCard> label="Preço (US$)" field="price" sortKey={sortKey} sortDirection={sortDirection} onToggle={toggleSort} />
                  <th>Total (US$)</th>
                  <SortableTh<CollectionCard> label="Comentário" field="comentario" sortKey={sortKey} sortDirection={sortDirection} onToggle={toggleSort} />
                  <SortableTh<CollectionCard> label="Localização" field="localizacao" sortKey={sortKey} sortDirection={sortDirection} onToggle={toggleSort} />
                  <th style={{ width: 160 }}>Ações</th>
                </tr>
              </thead>
              <tbody>
                {pageRows.map((c) => (
                  <tr key={c.id}>
                    <td>
                      <input
                        type="checkbox"
                        checked={selectedIds.has(c.id)}
                        onChange={() => onToggleSelectOne(c.id)}
                        disabled={viewingDump}
                        aria-label={`Selecionar carta #${c.id}`}
                      />
                    </td>
                    <td>{c.id}</td>
                    <td>{c.card_number}</td>
                    <td>
                      <CardImageTooltip cardId={c.id} cardName={c.card_name} />
                    </td>
                    <td title={c.set_code}>{getSetName(c.set_code)}</td>
                    <td>{c.card_type ?? '-'}</td>
                    <td>{c.foil ? '✦' : '—'}</td>
                    <td>{c.language}</td>
                    <td>{c.quantity}</td>
                    <td>{formatMoney(c.price)}</td>
                    <td>{formatMoney(totalFor(c))}</td>
                    <td>{c.comentario ?? '-'}</td>
                    <td>{c.localizacao ?? '-'}</td>
                    <td className="actions">
                      <button
                        type="button"
                        onClick={() => onStartEdit(c)}
                        disabled={viewingDump}
                        title={viewingDump ? 'Snapshots são somente leitura' : undefined}
                      >
                        Editar
                      </button>
                      <button
                        type="button"
                        className="danger"
                        onClick={() => void onDelete(c.id)}
                        disabled={viewingDump}
                        title={viewingDump ? 'Snapshots são somente leitura' : undefined}
                      >
                        Deletar
                      </button>
                      <button
                        type="button"
                        onClick={() => void onSync(c.id)}
                        disabled={syncingId === c.id || viewingDump}
                        title={
                          viewingDump
                            ? 'Snapshots são somente leitura'
                            : 'Buscar tipo e preço no Scryfall'
                        }
                      >
                        {syncingId === c.id ? 'Sincronizando…' : 'Sincronizar'}
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <PaginationBar
            page={page}
            pageCount={pageCount}
            pageSize={pageSize}
            totalCount={totalCount}
            onPageChange={setPage}
            onPageSizeChange={setPageSize}
          />
        </>
      )}
    </section>
  )
}
