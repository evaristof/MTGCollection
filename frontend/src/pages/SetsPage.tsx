import { useCallback, useEffect, useMemo, useState } from 'react'
import { api, type SetInput } from '../api/client'
import type { MagicSet } from '../types/mtg'
import { useTableControls } from '../hooks/useTableControls'
import { SortableTh } from '../components/SortableTh'
import { PaginationBar } from '../components/PaginationBar'

type FormState = SetInput & { _editingCode?: string | null }

const EMPTY_FORM: FormState = {
  set_code: '',
  set_name: '',
  release_date: '',
  set_type: '',
  card_count: null,
  printed_size: null,
  block_code: '',
  block_name: '',
  _editingCode: null,
}

function toInputValue(value: string | number | null | undefined): string {
  if (value === null || value === undefined) return ''
  return String(value)
}

function normalize(body: FormState): SetInput {
  const asNumber = (v: string | number | null | undefined) => {
    if (v === null || v === undefined || v === '') return null
    const n = typeof v === 'number' ? v : Number(v)
    return Number.isFinite(n) ? n : null
  }
  const asString = (v: string | null | undefined) => {
    if (v === null || v === undefined) return null
    const trimmed = v.trim()
    return trimmed === '' ? null : trimmed
  }
  return {
    set_code: (body.set_code ?? '').trim(),
    set_name: (body.set_name ?? '').trim(),
    release_date: asString(body.release_date as string),
    set_type: asString(body.set_type as string),
    card_count: asNumber(body.card_count),
    printed_size: asNumber(body.printed_size),
    block_code: asString(body.block_code as string),
    block_name: asString(body.block_name as string),
  }
}

export default function SetsPage() {
  const [sets, setSets] = useState<MagicSet[]>([])
  const [loading, setLoading] = useState(false)
  const [syncing, setSyncing] = useState(false)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [filter, setFilter] = useState('')
  const [form, setForm] = useState<FormState>(EMPTY_FORM)

  const loadSets = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const data = await api.listSets()
      setSets(data)
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void loadSets()
  }, [loadSets])

  const filteredSets = useMemo(() => {
    const q = filter.trim().toLowerCase()
    if (!q) return sets
    return sets.filter(
      (s) =>
        s.set_code.toLowerCase().includes(q) ||
        s.set_name.toLowerCase().includes(q) ||
        (s.block_name ?? '').toLowerCase().includes(q),
    )
  }, [sets, filter])

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
  } = useTableControls<MagicSet>({
    rows: filteredSets,
    initialSortKey: 'set_code',
    resetKey: filter,
  })

  const onSync = async () => {
    setSyncing(true)
    setError(null)
    try {
      const data = await api.syncSets()
      setSets(data)
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    } finally {
      setSyncing(false)
    }
  }

  const onEdit = (s: MagicSet) => {
    setForm({
      set_code: s.set_code,
      set_name: s.set_name,
      release_date: s.release_date ?? '',
      set_type: s.set_type ?? '',
      card_count: s.card_count ?? null,
      printed_size: s.printed_size ?? null,
      block_code: s.block_code ?? '',
      block_name: s.block_name ?? '',
      _editingCode: s.set_code,
    })
  }

  const onCancel = () => setForm(EMPTY_FORM)

  const onSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    const body = normalize(form)
    if (!body.set_code || !body.set_name) {
      setError('set_code e set_name são obrigatórios.')
      return
    }
    setSaving(true)
    setError(null)
    try {
      if (form._editingCode) {
        await api.updateSet(form._editingCode, body)
      } else {
        await api.createSet(body)
      }
      setForm(EMPTY_FORM)
      await loadSets()
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    } finally {
      setSaving(false)
    }
  }

  const onDelete = async (code: string) => {
    if (!confirm(`Deletar o set ${code}?`)) return
    setError(null)
    try {
      await api.deleteSet(code)
      await loadSets()
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    }
  }

  return (
    <section className="page">
      <div className="toolbar">
        <h2>Sets {loading && <span className="muted">(carregando…)</span>}</h2>
        <div className="toolbar__actions">
          <input
            type="search"
            placeholder="Filtrar por código / nome / bloco"
            value={filter}
            onChange={(e) => setFilter(e.target.value)}
          />
          <button onClick={() => void loadSets()} disabled={loading}>
            Recarregar
          </button>
          <button onClick={() => void onSync()} disabled={syncing}>
            {syncing ? 'Sincronizando…' : 'Sincronizar do Scryfall'}
          </button>
        </div>
      </div>

      {error && <p className="error">{error}</p>}

      <form className="form" onSubmit={onSubmit}>
        <h3>{form._editingCode ? `Editar set ${form._editingCode}` : 'Novo set'}</h3>
        <div className="form__grid">
          <label>
            <span>Code*</span>
            <input
              required
              maxLength={16}
              value={form.set_code}
              disabled={Boolean(form._editingCode)}
              onChange={(e) => setForm({ ...form, set_code: e.target.value })}
            />
          </label>
          <label>
            <span>Name*</span>
            <input
              required
              value={form.set_name}
              onChange={(e) => setForm({ ...form, set_name: e.target.value })}
            />
          </label>
          <label>
            <span>Release date (YYYY-MM-DD)</span>
            <input
              type="date"
              value={toInputValue(form.release_date)}
              onChange={(e) => setForm({ ...form, release_date: e.target.value })}
            />
          </label>
          <label>
            <span>Set type</span>
            <input
              value={toInputValue(form.set_type)}
              onChange={(e) => setForm({ ...form, set_type: e.target.value })}
            />
          </label>
          <label>
            <span>Card count</span>
            <input
              type="number"
              min={0}
              value={toInputValue(form.card_count)}
              onChange={(e) => setForm({ ...form, card_count: e.target.value === '' ? null : Number(e.target.value) })}
            />
          </label>
          <label>
            <span>Printed size</span>
            <input
              type="number"
              min={0}
              value={toInputValue(form.printed_size)}
              onChange={(e) => setForm({ ...form, printed_size: e.target.value === '' ? null : Number(e.target.value) })}
            />
          </label>
          <label>
            <span>Block code</span>
            <input
              value={toInputValue(form.block_code)}
              onChange={(e) => setForm({ ...form, block_code: e.target.value })}
            />
          </label>
          <label>
            <span>Block name</span>
            <input
              value={toInputValue(form.block_name)}
              onChange={(e) => setForm({ ...form, block_name: e.target.value })}
            />
          </label>
        </div>
        <div className="form__actions">
          <button type="submit" disabled={saving}>
            {saving ? 'Salvando…' : form._editingCode ? 'Salvar alterações' : 'Criar set'}
          </button>
          {form._editingCode && (
            <button type="button" onClick={onCancel} disabled={saving}>
              Cancelar
            </button>
          )}
        </div>
      </form>

      {totalCount === 0 && !loading && (
        <p className="muted">
          Nenhum set encontrado. Use “Sincronizar do Scryfall” ou crie um manualmente no formulário acima.
        </p>
      )}

      {totalCount > 0 && (
        <>
          <div className="table-wrapper">
            <table>
              <thead>
                <tr>
                  <SortableTh<MagicSet> label="Code" field="set_code" sortKey={sortKey} sortDirection={sortDirection} onToggle={toggleSort} />
                  <SortableTh<MagicSet> label="Name" field="set_name" sortKey={sortKey} sortDirection={sortDirection} onToggle={toggleSort} />
                  <SortableTh<MagicSet> label="Released" field="release_date" sortKey={sortKey} sortDirection={sortDirection} onToggle={toggleSort} />
                  <SortableTh<MagicSet> label="Type" field="set_type" sortKey={sortKey} sortDirection={sortDirection} onToggle={toggleSort} />
                  <SortableTh<MagicSet> label="Cards" field="card_count" sortKey={sortKey} sortDirection={sortDirection} onToggle={toggleSort} />
                  <SortableTh<MagicSet> label="Printed" field="printed_size" sortKey={sortKey} sortDirection={sortDirection} onToggle={toggleSort} />
                  <SortableTh<MagicSet> label="Block code" field="block_code" sortKey={sortKey} sortDirection={sortDirection} onToggle={toggleSort} />
                  <SortableTh<MagicSet> label="Block" field="block_name" sortKey={sortKey} sortDirection={sortDirection} onToggle={toggleSort} />
                  <th style={{ width: 160 }}>Ações</th>
                </tr>
              </thead>
              <tbody>
                {pageRows.map((s) => (
                  <tr key={s.set_code}>
                    <td><code>{s.set_code}</code></td>
                    <td>{s.set_name}</td>
                    <td>{s.release_date ?? '-'}</td>
                    <td>{s.set_type ?? '-'}</td>
                    <td>{s.card_count ?? '-'}</td>
                    <td>{s.printed_size ?? '-'}</td>
                    <td>{s.block_code ?? '-'}</td>
                    <td>{s.block_name ?? '-'}</td>
                    <td className="actions">
                      <button type="button" onClick={() => onEdit(s)}>Editar</button>
                      <button type="button" className="danger" onClick={() => void onDelete(s.set_code)}>
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
