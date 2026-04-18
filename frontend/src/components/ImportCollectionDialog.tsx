import { useEffect, useRef, useState } from 'react'
import { api } from '../api/client'
import type { ImportJobSnapshot } from '../types/mtg'

interface Props {
  open: boolean
  onClose: () => void
  /** Fired after a successful import so the parent can reload its data. */
  onImported?: () => void
}

type DialogState =
  | { kind: 'idle' }
  | { kind: 'uploading' }
  | { kind: 'running'; job: ImportJobSnapshot }
  | { kind: 'done'; job: ImportJobSnapshot; downloadUrl: string }
  | { kind: 'failed'; message: string; job?: ImportJobSnapshot }

/**
 * Modal overlay that drives the collection-import workflow:
 * pick a file → upload → poll status → auto-download the enriched xlsx.
 *
 * The parent only needs to toggle {@code open}. When an import finishes we
 * call {@link Props.onImported} so the page can refresh its card list.
 */
export function ImportCollectionDialog({ open, onClose, onImported }: Props) {
  const [state, setState] = useState<DialogState>({ kind: 'idle' })
  const fileInputRef = useRef<HTMLInputElement>(null)
  const pollIdRef = useRef<number | null>(null)
  const downloadTriggeredRef = useRef(false)

  useEffect(() => {
    if (!open) {
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setState({ kind: 'idle' })
      downloadTriggeredRef.current = false
      if (pollIdRef.current !== null) {
        window.clearInterval(pollIdRef.current)
        pollIdRef.current = null
      }
    }
  }, [open])

  useEffect(() => {
    return () => {
      if (pollIdRef.current !== null) {
        window.clearInterval(pollIdRef.current)
      }
    }
  }, [])

  const startPolling = (jobId: string) => {
    const tick = async () => {
      try {
        const snap = await api.importStatus(jobId)
        if (snap.status === 'DONE') {
          if (pollIdRef.current !== null) {
            window.clearInterval(pollIdRef.current)
            pollIdRef.current = null
          }
          const downloadUrl = api.importDownloadUrl(snap.id)
          setState({ kind: 'done', job: snap, downloadUrl })
          if (!downloadTriggeredRef.current) {
            downloadTriggeredRef.current = true
            triggerDownload(downloadUrl, snap.result_file_name || 'colecao.xlsx')
          }
          onImported?.()
        } else if (snap.status === 'FAILED') {
          if (pollIdRef.current !== null) {
            window.clearInterval(pollIdRef.current)
            pollIdRef.current = null
          }
          setState({ kind: 'failed', message: snap.error_message || 'Falha no processamento', job: snap })
        } else {
          setState({ kind: 'running', job: snap })
        }
      } catch (err) {
        if (pollIdRef.current !== null) {
          window.clearInterval(pollIdRef.current)
          pollIdRef.current = null
        }
        setState({
          kind: 'failed',
          message: err instanceof Error ? err.message : String(err),
        })
      }
    }
    void tick()
    pollIdRef.current = window.setInterval(() => void tick(), 1200)
  }

  const onFileChange = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    e.target.value = '' // allow re-selecting the same file later
    if (!file) return
    setState({ kind: 'uploading' })
    downloadTriggeredRef.current = false
    try {
      const snap = await api.importCollection(file)
      setState({ kind: 'running', job: snap })
      startPolling(snap.id)
    } catch (err) {
      setState({
        kind: 'failed',
        message: err instanceof Error ? err.message : String(err),
      })
    }
  }

  if (!open) return null

  const job =
    state.kind === 'running' || state.kind === 'done' || (state.kind === 'failed' && state.job)
      ? (state as { job: ImportJobSnapshot }).job
      : null
  const percent =
    job && job.total > 0 ? Math.round((job.processed / job.total) * 100) : 0

  return (
    <div
      className="modal-backdrop"
      role="dialog"
      aria-modal="true"
      aria-labelledby="import-dialog-title"
    >
      <div className="modal">
        <header className="modal__header">
          <h3 id="import-dialog-title">Importar coleção (.xlsx)</h3>
          <button
            type="button"
            className="modal__close"
            onClick={onClose}
            aria-label="Fechar"
          >
            ×
          </button>
        </header>

        <div className="modal__body">
          {state.kind === 'idle' && (
            <>
              <p className="muted">
                Selecione um arquivo Excel (.xlsx) no layout padrão (linha 3 com os
                cabeçalhos <code>Number (Optional)</code>, <code>Card</code>,{' '}
                <code>Set</code>, <code>Foil</code>). As colunas <b>Type</b> e{' '}
                <b>Price</b> serão preenchidas via Scryfall e as cartas cadastradas
                no banco.
              </p>
              <p className="muted">
                Linhas com <code>*Conferir sempre Manualmente</code> na coluna
                Comentário não sofrem lookup no Scryfall.
              </p>
              <button
                type="button"
                onClick={() => fileInputRef.current?.click()}
              >
                Escolher arquivo…
              </button>
              <input
                ref={fileInputRef}
                type="file"
                accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                onChange={(e) => void onFileChange(e)}
                style={{ display: 'none' }}
              />
            </>
          )}

          {state.kind === 'uploading' && <p>Enviando arquivo…</p>}

          {(state.kind === 'running' || state.kind === 'done') && job && (
            <>
              <p>
                <b>Status:</b>{' '}
                {state.kind === 'done' ? 'Concluído' : 'Processando…'}
                {job.current_sheet && state.kind === 'running' && (
                  <>
                    {' '}
                    — aba <code>{job.current_sheet}</code>
                  </>
                )}
              </p>
              <div className="progress" aria-label="Progresso do import">
                <div className="progress__bar" style={{ width: `${percent}%` }} />
              </div>
              <p className="muted">
                {job.processed} / {job.total} linhas processadas
                {job.persisted > 0 && <> · {job.persisted} gravadas no banco</>}
              </p>
              {job.errors.length > 0 && (
                <details className="import-errors">
                  <summary>{job.errors.length} aviso(s)</summary>
                  <ul>
                    {job.errors.slice(0, 50).map((msg, i) => (
                      <li key={i}>{msg}</li>
                    ))}
                    {job.errors.length > 50 && (
                      <li className="muted">
                        … e mais {job.errors.length - 50}
                      </li>
                    )}
                  </ul>
                </details>
              )}
              {state.kind === 'done' && (
                <p>
                  <a href={state.downloadUrl}>
                    Baixar novamente {job.result_file_name}
                  </a>
                </p>
              )}
            </>
          )}

          {state.kind === 'failed' && (
            <>
              <p className="error">{state.message}</p>
              {state.job && state.job.errors.length > 0 && (
                <details className="import-errors" open>
                  <summary>Erros coletados</summary>
                  <ul>
                    {state.job.errors.slice(0, 50).map((msg, i) => (
                      <li key={i}>{msg}</li>
                    ))}
                  </ul>
                </details>
              )}
            </>
          )}
        </div>

        <footer className="modal__footer">
          <button type="button" onClick={onClose}>
            {state.kind === 'done' || state.kind === 'failed' || state.kind === 'idle'
              ? 'Fechar'
              : 'Rodar em segundo plano'}
          </button>
        </footer>
      </div>
    </div>
  )
}

function triggerDownload(url: string, filename: string) {
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  a.remove()
}
