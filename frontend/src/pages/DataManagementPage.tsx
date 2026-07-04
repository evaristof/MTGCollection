import { useCallback, useEffect, useRef, useState } from 'react'
import { api } from '../api/client'
import type { DataJobSnapshot, DataManagementStats, MagicSet } from '../types/mtg'

function jobLabel(type: string): string {
  switch (type) {
    case 'download-all-scryfall':
      return 'Download do Scryfall inteiro'
    case 'download-collection':
      return 'Download de imagens da coleção'
    case 'prune-outside-collection':
      return 'Remoção de imagens fora da coleção'
    case 'rebuild-scanner-model':
      return 'Reconstrução do modelo do scanner'
    case 'download-set':
      return 'Importação de imagens do set'
    case 'delete-set':
      return 'Remoção do set'
    default:
      return type
  }
}

export default function DataManagementPage() {
  const [stats, setStats] = useState<DataManagementStats | null>(null)
  const [loadingStats, setLoadingStats] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const [job, setJob] = useState<DataJobSnapshot | null>(null)
  const [sets, setSets] = useState<MagicSet[]>([])
  const [selectedSet, setSelectedSet] = useState('')
  // Stays true from the "finalizar download" click until the job settles, so
  // the button doesn't flip back to "Finalizar" while the worker winds down.
  const [cancelRequested, setCancelRequested] = useState(false)
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null)
  const hideRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  const loadStats = useCallback(async () => {
    setLoadingStats(true)
    setError(null)
    try {
      setStats(await api.dataStats())
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    } finally {
      setLoadingStats(false)
    }
  }, [])

  const pollJob = useCallback(
    (jobId: string) => {
      if (pollRef.current) clearInterval(pollRef.current)
      pollRef.current = setInterval(async () => {
        try {
          const snap = await api.dataJob(jobId)
          setJob(snap)
          if (snap.status === 'DONE' || snap.status === 'FAILED' || snap.status === 'CANCELLED') {
            if (pollRef.current) clearInterval(pollRef.current)
            pollRef.current = null
            void loadStats()
            // On success/cancel, let the final state linger then clear it. The
            // full-download summary (edições/imagens) stays up for 10 minutes so
            // it's easy to read after a long run; quick jobs clear after 6s.
            if (snap.status === 'DONE' || snap.status === 'CANCELLED') {
              const hideAfterMs = snap.type === 'download-all-scryfall' ? 10 * 60 * 1000 : 6000
              hideRef.current = setTimeout(() => setJob(null), hideAfterMs)
            }
          }
        } catch (err) {
          if (pollRef.current) clearInterval(pollRef.current)
          pollRef.current = null
          setError(err instanceof Error ? err.message : String(err))
        }
      }, 1500)
    },
    [loadStats],
  )

  // On mount: load stats, the set list, and resume any running job (so progress
  // reappears when you navigate away and come back).
  useEffect(() => {
    void loadStats()
    void (async () => {
      try {
        setSets(await api.listSets())
      } catch {
        // ignore — set list stays empty
      }
    })()
    void (async () => {
      try {
        const active = await api.dataActiveJob()
        if (active) {
          setJob(active)
          pollJob(active.id)
        }
      } catch {
        // ignore — no active job
      }
    })()
    return () => {
      if (pollRef.current) clearInterval(pollRef.current)
      if (hideRef.current) clearTimeout(hideRef.current)
    }
  }, [loadStats, pollJob])

  const jobRunning = job != null && (job.status === 'PENDING' || job.status === 'RUNNING')
  const busy = jobRunning

  const start = async (
    kick: () => Promise<{ job_id: string; message: string }>,
    confirmMsg?: string,
  ) => {
    if (confirmMsg && !confirm(confirmMsg)) return
    setError(null)
    setCancelRequested(false)
    if (hideRef.current) clearTimeout(hideRef.current)
    setJob(null)
    try {
      const { job_id } = await kick()
      pollJob(job_id)
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    }
  }

  const onCancelJob = async () => {
    if (!job) return
    if (!confirm('Finalizar o download agora? As imagens já baixadas são mantidas.')) return
    setCancelRequested(true)
    try {
      const snap = await api.dataCancelJob(job.id)
      setJob(snap) // reflects the cancel request immediately; polling settles it
    } catch (err) {
      setCancelRequested(false)
      setError(err instanceof Error ? err.message : String(err))
    }
  }

  const onPopulateHashes = async () => {
    setError(null)
    try {
      await api.scannerPopulateHashes()
      setError(null)
      setTimeout(() => void loadStats(), 30000)
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    }
  }

  const isDownloadAll = job?.type === 'download-all-scryfall'
  const pct = job && job.total > 0 ? Math.round((job.processed / job.total) * 100) : job ? 0 : null

  return (
    <section className="page">
      <div className="toolbar">
        <h2>Magic Data Management {loadingStats && <span className="muted">(carregando…)</span>}</h2>
        <div className="toolbar__actions">
          <button className="btn" onClick={() => void loadStats()} disabled={loadingStats}>
            Recarregar
          </button>
        </div>
      </div>

      {error && <p className="error">{error}</p>}

      <div className="form">
        <h3>Estatísticas</h3>
        <div
          style={{
            display: 'grid',
            gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))',
            gap: '1rem',
          }}
        >
          <StatCard label="Edições no MinIO" value={stats?.sets_in_minio} />
          <StatCard label="Fotos no MinIO" value={stats?.photos_in_minio} />
          <StatCard label="Cartas na coleção" value={stats?.cards_in_collection} />
          <StatCard label="Cartas com hash" value={stats?.cards_with_hash} />
        </div>
      </div>

      <div className="form">
        <h3>Base completa do Scryfall</h3>
        <p className="muted">
          Baixa as imagens de <strong>todas as edições</strong> para o MinIO e registra na base do
          scanner, para reconhecer qualquer carta (inclusive as que você não tem). É um processo
          longo (dezenas de GB / horas) que roda em segundo plano — o progresso reaparece ao voltar
          nesta tela. Ao final, reconstrói o modelo de reconhecimento.
        </p>
        <button
          className="btn"
          onClick={() =>
            void start(
              api.dataDownloadAllScryfall,
              'Isto vai baixar TODAS as edições do Scryfall (dezenas de GB, pode levar horas). Continuar?',
            )
          }
          disabled={busy}
        >
          {jobRunning && isDownloadAll ? 'Baixando…' : 'Download Scryfall Inteiro'}
        </button>
      </div>

      <div className="form">
        <h3>Imagens da coleção</h3>
        <p className="muted">
          Baixa do Scryfall as imagens de todas as cartas da sua coleção, guarda no MinIO e registra
          na base do scanner.
        </p>
        <button
          className="btn"
          onClick={() => void start(api.dataDownloadCollection)}
          disabled={busy}
        >
          {jobRunning && job?.type === 'download-collection' ? 'Baixando…' : 'Download Imagens Coleção'}
        </button>
      </div>

      <div className="form">
        <h3>Operações por coleção</h3>
        <p className="muted">
          Escolha um set para importar (baixa as imagens e registra na base do scanner, gerando os
          histogramas) ou deletar (remove as imagens do MinIO e os registros da base).
        </p>
        <div style={{ display: 'flex', gap: '0.5rem', flexWrap: 'wrap', alignItems: 'center' }}>
          <select
            value={selectedSet}
            onChange={(e) => setSelectedSet(e.target.value)}
            disabled={busy}
            style={{ minWidth: 260, padding: '0.4rem' }}
          >
            <option value="">Selecione um set…</option>
            {sets.map((s) => (
              <option key={s.set_code} value={s.set_code}>
                {s.set_name} ({s.set_code})
              </option>
            ))}
          </select>
          <button
            className="btn"
            disabled={busy || !selectedSet}
            onClick={() => void start(() => api.dataDownloadSet(selectedSet))}
          >
            {jobRunning && job?.type === 'download-set' ? 'Importando…' : 'Importar Imagens do Set'}
          </button>
          <button
            className="btn btn--danger"
            disabled={busy || !selectedSet}
            onClick={() =>
              void start(
                () => api.dataDeleteSet(selectedSet),
                `Isto vai APAGAR do MinIO e da base todas as cartas do set "${selectedSet}". Tem certeza?`,
              )
            }
          >
            {jobRunning && job?.type === 'delete-set' ? 'Deletando…' : 'Deletar Set'}
          </button>
        </div>
      </div>

      <div className="form">
        <h3>Modelo de reconhecimento</h3>
        <p className="muted">
          Reconstrói o modelo do scanner (vocabulário + histogramas) a partir das imagens no MinIO,
          e cria os hashes que faltam.
        </p>
        <div style={{ display: 'flex', gap: '0.5rem', flexWrap: 'wrap' }}>
          <button
            className="btn"
            onClick={() => void start(api.dataRebuildScannerModel)}
            disabled={busy}
          >
            {jobRunning && job?.type === 'rebuild-scanner-model' ? 'Reconstruindo…' : 'Reconstruir Modelo'}
          </button>
          <button className="btn" onClick={() => void onPopulateHashes()} disabled={busy}>
            Popular Hashes do MinIO
          </button>
        </div>
      </div>

      <div className="form">
        <h3>Limpeza</h3>
        <p className="muted">
          Remove do MinIO todas as imagens que não pertencem à sua coleção (mantém só o que você
          possui).
        </p>
        <button
          className="btn btn--danger"
          onClick={() =>
            void start(
              api.dataPruneOutsideCollection,
              'Isto vai APAGAR do MinIO todas as imagens que não são cartas da sua coleção. Tem certeza?',
            )
          }
          disabled={busy}
        >
          {jobRunning && job?.type === 'prune-outside-collection'
            ? 'Removendo…'
            : 'Deletar Imagens Fora Coleção'}
        </button>
      </div>

      {job && (
        <div className="form">
          <h3>
            {jobLabel(job.type)} — {job.status}
          </h3>
          {pct != null && (
            <div
              style={{
                background: 'var(--bg-alt, #eee)',
                borderRadius: 6,
                overflow: 'hidden',
                height: 14,
                margin: '0.5rem 0',
              }}
            >
              <div
                style={{
                  width: `${pct}%`,
                  height: '100%',
                  background: 'var(--accent, #2b6cb0)',
                  transition: 'width 0.3s',
                }}
              />
            </div>
          )}
          {isDownloadAll ? (
            <>
              <p className="muted">
                {job.processed}/{job.total} edições · {job.succeeded} imagens baixadas ·{' '}
                {job.skipped} já existentes · {job.errors.length} erros
              </p>
              {jobRunning && (
                <button
                  className="btn btn--danger"
                  onClick={() => void onCancelJob()}
                  disabled={cancelRequested}
                >
                  {cancelRequested ? 'Finalizando…' : 'Finalizar download'}
                </button>
              )}
            </>
          ) : (
            <p className="muted">
              {job.processed}/{job.total} · {job.succeeded} ok · {job.skipped} puladas ·{' '}
              {job.errors.length} erros
            </p>
          )}
          {job.message && <p className="muted">{job.message}</p>}
          {job.errors.length > 0 && (
            <details>
              <summary className="muted">Ver erros ({job.errors.length})</summary>
              <ul>
                {job.errors.slice(0, 50).map((e, i) => (
                  <li key={i} className="muted">
                    {e}
                  </li>
                ))}
              </ul>
            </details>
          )}
        </div>
      )}
    </section>
  )
}

function StatCard({ label, value }: { label: string; value?: number }) {
  return (
    <div>
      <p className="muted" style={{ margin: 0 }}>
        {label}
      </p>
      <p style={{ fontSize: '1.6rem', fontWeight: 700, margin: '0.2rem 0 0' }}>
        {value != null ? value.toLocaleString('pt-BR') : '—'}
      </p>
    </div>
  )
}
