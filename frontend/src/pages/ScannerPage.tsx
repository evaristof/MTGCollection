import { useEffect, useRef, useState } from 'react'
import { api } from '../api/client'
import type { MagicSet, ScannerMatchResult } from '../types/mtg'

type RowStatus = 'scanning' | 'matched' | 'notfound' | 'error'

interface ScanRow {
  id: string
  fileName: string
  previewUrl: string // objectURL of the uploaded photo — provisional, lives in memory
  status: RowStatus
  confidence?: number
  scanError?: string
  // editable fields (prefilled from the match, blank when not found)
  name: string
  set: string
  number: string
  language: string
  quantity: number
  foil: boolean
  localizacao: string
  // add-to-collection state
  adding?: boolean
  added?: boolean
  addError?: string
}

const uid = () =>
  typeof crypto !== 'undefined' && 'randomUUID' in crypto
    ? crypto.randomUUID()
    : Math.random().toString(36).slice(2)

export default function ScannerPage() {
  const [files, setFiles] = useState<File[]>([])
  const [rows, setRows] = useState<ScanRow[]>([])
  const [sets, setSets] = useState<MagicSet[]>([])
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [hovered, setHovered] = useState<string | null>(null)
  const fileInputRef = useRef<HTMLInputElement | null>(null)

  // Keep a live ref of preview URLs so we can revoke them all on unmount.
  const rowsRef = useRef<ScanRow[]>([])
  rowsRef.current = rows

  useEffect(() => {
    void (async () => {
      try {
        setSets(await api.listSets())
      } catch {
        // ignore — set list stays empty (the select just won't be populated)
      }
    })()
    return () => {
      rowsRef.current.forEach((r) => URL.revokeObjectURL(r.previewUrl))
    }
  }, [])

  const patchRow = (id: string, patch: Partial<ScanRow>) =>
    setRows((prev) => prev.map((r) => (r.id === id ? { ...r, ...patch } : r)))

  const onFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    setFiles(Array.from(e.target.files ?? []))
    setError(null)
  }

  const onScan = async () => {
    if (files.length === 0) {
      setError('Selecione ao menos uma imagem.')
      return
    }
    setBusy(true)
    setError(null)

    // Create a row per image up front (status "scanning"), then match them ONE
    // AT A TIME. The backend serializes the ORB match under a lock anyway, and
    // the perspective-correction stage uses OpenCV native code that isn't
    // thread-safe — concurrent requests crash it (HTTP 500). Sequential is the
    // same total time and keeps the progressive-fill UX.
    const newRows: ScanRow[] = files.map((f) => ({
      id: uid(),
      fileName: f.name,
      previewUrl: URL.createObjectURL(f),
      status: 'scanning',
      name: '',
      set: '',
      number: '',
      language: '',
      quantity: 1,
      foil: false,
      localizacao: '',
    }))
    setRows((prev) => [...prev, ...newRows])

    for (let i = 0; i < newRows.length; i++) {
      const row = newRows[i]
      try {
        const res: ScannerMatchResult = await api.scannerMatch(files[i])
        if (res.matched && res.card_name) {
          patchRow(row.id, {
            status: 'matched',
            confidence: res.confidence,
            name: res.card_name,
            set: res.set_code ?? '',
            number: res.collector_number ?? '',
          })
        } else {
          // Not found → keep the row blank/editable for manual entry.
          patchRow(row.id, { status: 'notfound' })
        }
      } catch (err) {
        patchRow(row.id, {
          status: 'error',
          scanError: err instanceof Error ? err.message : String(err),
        })
      }
    }

    setBusy(false)
    setFiles([])
    if (fileInputRef.current) fileInputRef.current.value = ''
  }

  const onAddRow = async (id: string) => {
    const row = rows.find((r) => r.id === id)
    if (!row) return
    const hasName = !!row.name.trim()
    const hasNumber = !!row.number.trim()
    if ((!hasName && !hasNumber) || !row.set.trim() || !row.language.trim()) {
      patchRow(id, { addError: 'Set e linguagem são obrigatórios, além do nome OU do número.' })
      return
    }
    if (row.quantity < 1) {
      patchRow(id, { addError: 'Quantidade precisa ser >= 1.' })
      return
    }
    patchRow(id, { adding: true, addError: undefined })
    try {
      await api.addCard({
        card_name: row.name.trim(),
        set_code: row.set.trim(),
        foil: row.foil,
        language: row.language.trim(),
        quantity: row.quantity,
        localizacao: row.localizacao.trim() || undefined,
        card_number: row.number.trim() || undefined,
      })
      patchRow(id, { adding: false, added: true, addError: undefined })
    } catch (err) {
      patchRow(id, { adding: false, addError: err instanceof Error ? err.message : String(err) })
    }
  }

  const onRemoveRow = (id: string) => {
    setRows((prev) => {
      const row = prev.find((r) => r.id === id)
      if (row) URL.revokeObjectURL(row.previewUrl)
      return prev.filter((r) => r.id !== id)
    })
  }

  const onClearAll = () => {
    rows.forEach((r) => URL.revokeObjectURL(r.previewUrl))
    setRows([])
  }

  const hoveredRow = hovered ? rows.find((r) => r.id === hovered) : null

  return (
    <section className="page">
      <div className="toolbar">
        <h2>Scanner MTG Cards {busy && <span className="muted">(processando…)</span>}</h2>
      </div>

      {error && <p className="error">{error}</p>}

      <div className="form">
        <h3>Reconhecimento por imagem</h3>
        <p className="muted">
          Reconhece a carta pela arte (OpenCV ORB), mesmo com foto de celular, foil ou em outro
          idioma. Você pode selecionar <strong>várias imagens</strong> de uma vez.
        </p>
        <div className="form__grid">
          <label>
            <span>Selecionar imagem(ns)</span>
            <input
              ref={fileInputRef}
              type="file"
              accept="image/*"
              capture="environment"
              multiple
              onChange={onFileChange}
            />
          </label>
        </div>

        <div style={{ marginTop: '1rem', display: 'flex', gap: '0.5rem', flexWrap: 'wrap' }}>
          <button className="btn" onClick={() => void onScan()} disabled={busy || files.length === 0}>
            {busy ? 'Processando…' : `Escanear${files.length ? ` (${files.length})` : ''}`}
          </button>
          {rows.length > 0 && (
            <button className="btn btn--danger" onClick={onClearAll} disabled={busy}>
              Limpar tudo
            </button>
          )}
        </div>
      </div>

      {rows.length > 0 && (
        <div className="form">
          <h3>Cartas escaneadas ({rows.length})</h3>
          <p className="muted">
            Passe o mouse sobre a linha para ver a foto escaneada (ela existe só provisoriamente).
            Ajuste os campos e clique em <strong>Adicionar à coleção</strong>.
          </p>
          <table className="table">
            <thead>
              <tr>
                <th>#</th>
                <th>Nome</th>
                <th>Set</th>
                <th>Número</th>
                <th>Linguagem</th>
                <th>Qtd</th>
                <th>Foil</th>
                <th>Localização</th>
                <th>Conf.</th>
                <th>Ações</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((row, i) => (
                <tr
                  key={row.id}
                  onMouseEnter={() => setHovered(row.id)}
                  onMouseLeave={() => setHovered((h) => (h === row.id ? null : h))}
                >
                  <td>{i + 1}</td>
                  <td>
                    <input
                      value={row.name}
                      placeholder={row.status === 'scanning' ? 'escaneando…' : 'Nome'}
                      onChange={(e) => patchRow(row.id, { name: e.target.value })}
                      style={{ minWidth: 140 }}
                    />
                  </td>
                  <td>
                    <select
                      value={row.set}
                      onChange={(e) => patchRow(row.id, { set: e.target.value })}
                      style={{ minWidth: 90 }}
                    >
                      <option value="">—</option>
                      {sets
                        .filter((s) => !s.blacklisted)
                        .map((s) => (
                          <option key={s.set_code} value={s.set_code}>
                            {s.set_code} · {s.set_name}
                          </option>
                        ))}
                    </select>
                  </td>
                  <td>
                    <input
                      value={row.number}
                      onChange={(e) => patchRow(row.id, { number: e.target.value })}
                      style={{ width: 60 }}
                    />
                  </td>
                  <td>
                    <input
                      value={row.language}
                      placeholder="pt/en…"
                      onChange={(e) => patchRow(row.id, { language: e.target.value })}
                      style={{ width: 70 }}
                    />
                  </td>
                  <td>
                    <input
                      type="number"
                      min={1}
                      value={row.quantity}
                      onChange={(e) => patchRow(row.id, { quantity: Number(e.target.value) })}
                      style={{ width: 56 }}
                    />
                  </td>
                  <td style={{ textAlign: 'center' }}>
                    <input
                      type="checkbox"
                      checked={row.foil}
                      onChange={(e) => patchRow(row.id, { foil: e.target.checked })}
                    />
                  </td>
                  <td>
                    <input
                      value={row.localizacao}
                      placeholder="Caixa 3"
                      onChange={(e) => patchRow(row.id, { localizacao: e.target.value })}
                      style={{ width: 90 }}
                    />
                  </td>
                  <td>
                    {row.status === 'matched' && row.confidence != null
                      ? `${(row.confidence * 100).toFixed(0)}%`
                      : row.status === 'scanning'
                        ? '…'
                        : row.status === 'notfound'
                          ? '—'
                          : '⚠'}
                  </td>
                  <td style={{ whiteSpace: 'nowrap' }}>
                    {row.added ? (
                      <span className="muted">✓ adicionada</span>
                    ) : (
                      <button
                        className="btn btn--sm"
                        onClick={() => void onAddRow(row.id)}
                        disabled={row.adding || row.status === 'scanning'}
                      >
                        {row.adding ? 'Adicionando…' : 'Adicionar à coleção'}
                      </button>
                    )}{' '}
                    <button
                      className="btn btn--danger btn--sm"
                      onClick={() => onRemoveRow(row.id)}
                      disabled={row.adding}
                    >
                      Remover
                    </button>
                    {row.addError && <div className="error" style={{ marginTop: 4 }}>{row.addError}</div>}
                    {row.status === 'error' && row.scanError && (
                      <div className="error" style={{ marginTop: 4 }}>Falha no scan: {row.scanError}</div>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {/* Floating preview of the scanned photo for the hovered row. */}
      {hoveredRow && (
        <img
          src={hoveredRow.previewUrl}
          alt="Carta escaneada"
          style={{
            position: 'fixed',
            top: 80,
            right: 20,
            maxWidth: 300,
            maxHeight: 440,
            borderRadius: 8,
            boxShadow: '0 8px 30px rgba(0,0,0,0.4)',
            zIndex: 1000,
            pointerEvents: 'none',
            background: 'var(--bg, #fff)',
          }}
        />
      )}
    </section>
  )
}
