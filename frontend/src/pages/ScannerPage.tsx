import { useEffect, useRef, useState } from 'react'
import { api } from '../api/client'
import type { MagicSet, ScannerMatchResult, ScannerSplitResult } from '../types/mtg'

type RowStatus = 'scanning' | 'matched' | 'notfound' | 'error'

// Revoke only blob: object URLs — bulk rows use base64 data: URLs (crops), for
// which revokeObjectURL is a no-op but we avoid calling it needlessly.
const revokePreview = (url: string) => {
  if (url.startsWith('blob:')) URL.revokeObjectURL(url)
}

// Turn a base64 data URL (a crop returned by /split) back into a File so it can
// be POSTed to the normal /match endpoint.
const dataUrlToFile = async (dataUrl: string, name: string): Promise<File> => {
  const blob = await (await fetch(dataUrl)).blob()
  return new File([blob], name, { type: blob.type || 'image/png' })
}

// "12.4s" under a minute, "1m 23s" above.
const formatDuration = (ms: number) => {
  const s = ms / 1000
  if (s < 60) return `${s.toFixed(1)}s`
  const m = Math.floor(s / 60)
  return `${m}m ${Math.round(s % 60)}s`
}

interface ScanRow {
  id: string
  fileName: string
  previewUrl: string // photo for the hover preview: object URL (single) or crop data URL (bulk)
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
  const [bulkFiles, setBulkFiles] = useState<File[]>([])
  const [rows, setRows] = useState<ScanRow[]>([])
  const [sets, setSets] = useState<MagicSet[]>([])
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [hovered, setHovered] = useState<string | null>(null)
  const fileInputRef = useRef<HTMLInputElement | null>(null)
  const bulkInputRef = useRef<HTMLInputElement | null>(null)

  // Elapsed-time counter per section: ticks live while a scan runs, then shows
  // the total once it finishes.
  const [runningKind, setRunningKind] = useState<'single' | 'bulk' | null>(null)
  const [elapsed, setElapsed] = useState<{ single: number | null; bulk: number | null }>({
    single: null,
    bulk: null,
  })
  const timerStartRef = useRef(0)
  const timerIntervalRef = useRef<number | null>(null)
  const [, forceTick] = useState(0)

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
      rowsRef.current.forEach((r) => revokePreview(r.previewUrl))
      if (timerIntervalRef.current != null) window.clearInterval(timerIntervalRef.current)
    }
  }, [])

  const startTimer = (kind: 'single' | 'bulk') => {
    timerStartRef.current = performance.now()
    setRunningKind(kind)
    setElapsed((p) => ({ ...p, [kind]: null }))
    if (timerIntervalRef.current != null) window.clearInterval(timerIntervalRef.current)
    // Re-render ~10x/s so the live counter ticks while scanning.
    timerIntervalRef.current = window.setInterval(() => forceTick((t) => t + 1), 100)
  }

  const stopTimer = (kind: 'single' | 'bulk') => {
    if (timerIntervalRef.current != null) {
      window.clearInterval(timerIntervalRef.current)
      timerIntervalRef.current = null
    }
    setElapsed((p) => ({ ...p, [kind]: performance.now() - timerStartRef.current }))
    setRunningKind(null)
  }

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
    startTimer('single')

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

    stopTimer('single')
    setBusy(false)
    setFiles([])
    if (fileInputRef.current) fileInputRef.current.value = ''
  }

  const onBulkFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    setBulkFiles(Array.from(e.target.files ?? []))
    setError(null)
  }

  // Bulk scan: each selected photo contains MANY cards. Two phases so the user
  // sees progress: (1) SPLIT the photo — every detected card appears at once as
  // a row with its crop, status "scanning"; (2) MATCH each crop one at a time
  // through the normal single-card endpoint, filling each row in as it resolves.
  const onScanBulk = async () => {
    if (bulkFiles.length === 0) {
      setError('Selecione ao menos uma foto com várias cartas.')
      return
    }
    setBusy(true)
    setError(null)
    startTimer('bulk')

    // One photo at a time: the backend runs OpenCV (not thread-safe) per crop.
    for (const file of bulkFiles) {
      try {
        const split: ScannerSplitResult = await api.scannerSplit(file)

        // Phase 1: show a row per detected crop immediately.
        const created = split.crops.map((c) => ({
          crop: c,
          row: {
            id: uid(),
            fileName: `${file.name} · carta ${c.index + 1}`,
            previewUrl: c.crop_image, // base64 data URL of the crop
            status: 'scanning' as RowStatus,
            name: '',
            set: '',
            number: '',
            language: '',
            quantity: 1,
            foil: false,
            localizacao: '',
          } satisfies ScanRow,
        }))
        setRows((prev) => [...prev, ...created.map((x) => x.row)])

        // Phase 2: match each crop sequentially, updating its row as it resolves.
        for (const { crop, row } of created) {
          try {
            const cropFile = await dataUrlToFile(crop.crop_image, `crop_${crop.index}.png`)
            const res: ScannerMatchResult = await api.scannerMatch(cropFile)
            if (res.matched && res.card_name) {
              patchRow(row.id, {
                status: 'matched',
                confidence: res.confidence,
                name: res.card_name,
                set: res.set_code ?? '',
                number: res.collector_number ?? '',
              })
            } else {
              patchRow(row.id, { status: 'notfound' })
            }
          } catch (err) {
            patchRow(row.id, {
              status: 'error',
              scanError: err instanceof Error ? err.message : String(err),
            })
          }
        }
      } catch (err) {
        setError(
          `Falha ao escanear ${file.name}: ${err instanceof Error ? err.message : String(err)}`,
        )
      }
    }

    stopTimer('bulk')
    setBusy(false)
    setBulkFiles([])
    if (bulkInputRef.current) bulkInputRef.current.value = ''
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
      if (row) revokePreview(row.previewUrl)
      return prev.filter((r) => r.id !== id)
    })
  }

  const onClearAll = () => {
    rows.forEach((r) => revokePreview(r.previewUrl))
    setRows([])
  }

  const hoveredRow = hovered ? rows.find((r) => r.id === hovered) : null

  // Live counter while a scan runs, total once it finishes.
  const renderTimer = (kind: 'single' | 'bulk') => {
    if (runningKind === kind) {
      const live = performance.now() - timerStartRef.current
      return <span className="muted">⏱ {formatDuration(live)}…</span>
    }
    const total = elapsed[kind]
    if (total != null) {
      return <span className="muted">⏱ Concluído em {formatDuration(total)}</span>
    }
    return null
  }

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
          <span style={{ alignSelf: 'center' }}>{renderTimer('single')}</span>
        </div>
      </div>

      <div className="form">
        <h3>Escanear em massa</h3>
        <p className="muted">
          Envie fotos em que <strong>cada foto tem várias cartas</strong> (ex.: uma página de fichário).
          O sistema separa cada carta e tenta reconhecê-la individualmente — cada carta detectada vira
          uma linha editável abaixo. A detecção é aproximada: revise, ajuste ou remova as linhas.
        </p>
        <div className="form__grid">
          <label>
            <span>Foto(s) com várias cartas</span>
            <input
              ref={bulkInputRef}
              type="file"
              accept="image/*"
              capture="environment"
              multiple
              onChange={onBulkFileChange}
            />
          </label>
        </div>
        <div style={{ marginTop: '1rem', display: 'flex', gap: '0.5rem', flexWrap: 'wrap' }}>
          <button
            className="btn"
            onClick={() => void onScanBulk()}
            disabled={busy || bulkFiles.length === 0}
          >
            {busy ? 'Processando…' : `Escanear em massa${bulkFiles.length ? ` (${bulkFiles.length})` : ''}`}
          </button>
          <span style={{ alignSelf: 'center' }}>{renderTimer('bulk')}</span>
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
                <th>Arquivo</th>
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
                  <td className="muted" style={{ fontSize: '0.85em', maxWidth: 180, wordBreak: 'break-word' }} title={row.fileName}>
                    {row.fileName}
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
