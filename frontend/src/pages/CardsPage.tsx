import { useCallback, useEffect, useMemo, useState } from 'react'
import { api, type AddCardInput } from '../api/client'
import type { CollectionCard, MagicSet } from '../types/mtg'
import { useTableControls } from '../hooks/useTableControls'
import { SortableTh } from '../components/SortableTh'
import { PaginationBar } from '../components/PaginationBar'
import { SetCombo } from '../components/SetCombo'
import { ImportCollectionDialog } from '../components/ImportCollectionDialog'

type AddFormState = AddCardInput
type EditFormState = {
  id: number
  card_name: string
  set_code: string
  foil: boolean
  language: string
  quantity: number
}

const EMPTY_ADD: AddFormState = {
  card_name: '',
  set_code: '',
  foil: false,
  language: 'en',
  quantity: 1,
}

export default function CardsPage() {
  const [cards, setCards] = useState<CollectionCard[]>([])
  const [sets, setSets] = useState<MagicSet[]>([])
  const [loading, setLoading] = useState(false)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [filterSet, setFilterSet] = useState<string>('')
  const [addForm, setAddForm] = useState<AddFormState>(EMPTY_ADD)
  const [editing, setEditing] = useState<EditFormState | null>(null)
  const [importOpen, setImportOpen] = useState(false)

  const loadCards = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const data = await api.listCards(filterSet || undefined)
      setCards(data)
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    } finally {
      setLoading(false)
    }
  }, [filterSet])

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void loadCards()
  }, [loadCards])

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
    rows: cards,
    initialSortKey: 'card_name',
    resetKey: filterSet,
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
    })
  }

  const onSaveEdit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!editing) return
    setError(null)
    setSaving(true)
    try {
      await api.updateCard(editing.id, {
        card_name: editing.card_name.trim() || undefined,
        set_code: editing.set_code.trim() || undefined,
        foil: editing.foil,
        language: editing.language.trim(),
        quantity: editing.quantity,
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

  return (
    <section className="page">
      <div className="toolbar">
        <h2>Cartas da coleção {loading && <span className="muted">(carregando…)</span>}</h2>
        <div className="toolbar__actions">
          <label className="inline" htmlFor="filter-set">
            Filtrar por set:&nbsp;
          </label>
          <SetCombo
            id="filter-set"
            value={filterSet}
            onChange={setFilterSet}
            options={setOptions}
            emptyLabel="— todos —"
          />
          <button onClick={() => void loadCards()} disabled={loading}>
            Recarregar
          </button>
          <button type="button" onClick={() => setImportOpen(true)}>
            Importar coleção…
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
        <h3>Adicionar carta</h3>
        <p className="muted">
          O backend consulta o Scryfall automaticamente para preencher <code>collector_number</code>{' '}
          e <code>type_line</code>.
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
                    <td>{c.id}</td>
                    <td>{c.card_number}</td>
                    <td>{c.card_name}</td>
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
                      <button type="button" onClick={() => onStartEdit(c)}>Editar</button>
                      <button type="button" className="danger" onClick={() => void onDelete(c.id)}>
                        Deletar
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
