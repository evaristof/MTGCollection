import { useEffect, useState } from 'react'
import { api } from '../api/client'
import type { Location } from '../types/mtg'

interface FormState {
  name: string
  description: string
}

const emptyForm = (): FormState => ({ name: '', description: '' })

export default function LocationPage() {
  const [locations, setLocations] = useState<Location[]>([])
  const [loading, setLoading] = useState(false)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [form, setForm] = useState<FormState>(emptyForm())

  const [editingId, setEditingId] = useState<number | null>(null)
  const [editForm, setEditForm] = useState<FormState>(emptyForm())

  const load = async () => {
    setLoading(true)
    setError(null)
    try {
      setLocations(await api.listLocations())
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void load()
  }, [])

  const onAdd = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!form.name.trim()) {
      setError('Informe o nome da localização.')
      return
    }
    setSaving(true)
    setError(null)
    try {
      await api.createLocation({
        name: form.name.trim(),
        description: form.description.trim() || undefined,
      })
      setForm(emptyForm())
      await load()
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    } finally {
      setSaving(false)
    }
  }

  const onStartEdit = (loc: Location) => {
    setEditingId(loc.id)
    setEditForm({ name: loc.name, description: loc.description ?? '' })
  }

  const onCancelEdit = () => {
    setEditingId(null)
    setEditForm(emptyForm())
  }

  const onSaveEdit = async (id: number) => {
    if (!editForm.name.trim()) {
      setError('Informe o nome da localização.')
      return
    }
    setSaving(true)
    setError(null)
    try {
      await api.updateLocation(id, {
        name: editForm.name.trim(),
        description: editForm.description.trim() || undefined,
      })
      setEditingId(null)
      await load()
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    } finally {
      setSaving(false)
    }
  }

  const onDelete = async (loc: Location) => {
    if (!window.confirm(`Remover a localização "${loc.name}"?`)) return
    setError(null)
    try {
      await api.deleteLocation(loc.id)
      await load()
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    }
  }

  return (
    <section className="page">
      <div className="toolbar">
        <h2>Cadastro de Localização {loading && <span className="muted">(carregando…)</span>}</h2>
      </div>

      {error && <p className="error">{error}</p>}

      <form className="form" onSubmit={(e) => void onAdd(e)}>
        <h3>Nova localização</h3>
        <p className="muted">
          Ex.: "Caixa 3", "Binder Vermelho". Essas localizações alimentam o autocomplete da tela
          Cadastro Cartas.
        </p>
        <div className="form__grid">
          <label>
            <span>Nome*</span>
            <input
              value={form.name}
              onChange={(e) => setForm({ ...form, name: e.target.value })}
              placeholder="Caixa 3"
            />
          </label>
          <label>
            <span>Descrição</span>
            <input
              value={form.description}
              onChange={(e) => setForm({ ...form, description: e.target.value })}
              placeholder="opcional"
            />
          </label>
        </div>
        <div className="form__actions">
          <button type="submit" disabled={saving}>
            {saving ? 'Salvando…' : 'Adicionar localização'}
          </button>
        </div>
      </form>

      <div className="table-wrapper">
        <table className="table">
          <thead>
            <tr>
              <th>Nome</th>
              <th>Descrição</th>
              <th>Ações</th>
            </tr>
          </thead>
          <tbody>
            {locations.map((loc) =>
              editingId === loc.id ? (
                <tr key={loc.id}>
                  <td>
                    <input
                      value={editForm.name}
                      onChange={(e) => setEditForm({ ...editForm, name: e.target.value })}
                    />
                  </td>
                  <td>
                    <input
                      value={editForm.description}
                      onChange={(e) => setEditForm({ ...editForm, description: e.target.value })}
                    />
                  </td>
                  <td className="actions">
                    <button onClick={() => void onSaveEdit(loc.id)} disabled={saving}>
                      Salvar
                    </button>
                    <button onClick={onCancelEdit} disabled={saving}>
                      Cancelar
                    </button>
                  </td>
                </tr>
              ) : (
                <tr key={loc.id}>
                  <td>{loc.name}</td>
                  <td>{loc.description || <span className="muted">—</span>}</td>
                  <td className="actions">
                    <button onClick={() => onStartEdit(loc)}>Editar</button>
                    <button className="danger" onClick={() => void onDelete(loc)}>
                      Excluir
                    </button>
                  </td>
                </tr>
              ),
            )}
            {locations.length === 0 && !loading && (
              <tr>
                <td colSpan={3} className="muted">
                  Nenhuma localização cadastrada ainda.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </section>
  )
}
