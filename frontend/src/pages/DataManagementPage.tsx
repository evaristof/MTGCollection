import { useCallback, useEffect, useRef, useState } from 'react'
import { api } from '../api/client'
import type { DataJobSnapshot, DataManagementStats } from '../types/mtg'

type JobKind = 'download' | 'prune'

export default function DataManagementPage() {
  const [stats, setStats] = useState<DataManagementStats | null>(null)
  const [loadingStats, setLoadingStats] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const [job, setJob] = useState<DataJobSnapshot | null>(null)
  const [jobKind, setJobKind] = useState<JobKind | null>(null)
  const [populateStatus, setPopulateStatus] = useState<string | null>(null)
  const [populating, setPopulating] = useState(false)
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null)

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

  useEffect(() => {
    void loadStats()
    return () => {
      if (pollRef.current) clearInterval(pollRef.current)
    }
  }, [loadStats])

  const jobRunning = job != null && (job.status === 'PENDING' || job.status === 'RUNNING')
  const busy = jobRunning || populating

  const pollJob = useCallback(
    (jobId: string) => {
      if (pollRef.current) clearInterval(pollRef.current)
      pollRef.current = setInterval(async () => {
        try {
          const snap = await api.dataJob(jobId)
          setJob(snap)
          if (snap.status === 'DONE' || snap.status === 'FAILED') {
            if (pollRef.current) clearInterval(pollRef.current)
            pollRef.current = null
            void loadStats()
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

  const onDownloadCollection = async () => {
    setError(null)
    setJobKind('download')
    setJob(null)
    try {
      const { job_id } = await api.dataDownloadCollection()
      pollJob(job_id)
    } catch (err) {
      setJobKind(null)
      setError(err instanceof Error ? err.message : String(err))
    }
  }

  const onPrune = async () => {
    if (
      !confirm(
        'Isto vai APAGAR do MinIO todas as imagens que não são cartas da sua coleção. Tem certeza?',
      )
    ) {
      return
    }
    setError(null)
    setJobKind('prune')
    setJob(null)
    try {
      const { job_id } = await api.dataPruneOutsideCollection()
      pollJob(job_id)
    } catch (err) {
      setJobKind(null)
      setError(err instanceof Error ? err.message : String(err))
    }
  }

  const onPopulateHashes = async () => {
    setPopulating(true)
    setPopulateStatus('Populando hashes a partir das imagens do MinIO…')
    setError(null)
    try {
      await api.scannerPopulateHashes()
      setPopulateStatus(
        'Processo de população de hashes iniciado em background. Aguarde alguns minutos.',
      )
      setTimeout(() => {
        setPopulating(false)
        setPopulateStatus(null)
        void loadStats()
      }, 30000)
    } catch (err) {
      setPopulating(false)
      setPopulateStatus(null)
      setError(err instanceof Error ? err.message : String(err))
    }
  }

  const jobLabel = jobKind === 'download' ? 'Download de imagens' : 'Remoção de imagens'
  const pct =
    job && job.total > 0 ? Math.round((job.processed / job.total) * 100) : job ? 0 : null

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
        <h3>Imagens da coleção</h3>
        <p className="muted">
          Baixa do Scryfall as imagens de todas as cartas da sua coleção, guarda no MinIO e
          registra na base do scanner (card_image_hash).
        </p>
        <button className="btn" onClick={() => void onDownloadCollection()} disabled={busy}>
          {jobRunning && jobKind === 'download' ? 'Baixando…' : 'Download Imagens Coleção'}
        </button>
      </div>

      <div className="form">
        <h3>Base de referência do scanner</h3>
        <p className="muted">
          Cria os hashes que faltam a partir das imagens já presentes no MinIO.
        </p>
        <button className="btn" onClick={() => void onPopulateHashes()} disabled={busy}>
          Popular Hashes do MinIO
        </button>
        {populateStatus && <p className="muted">{populateStatus}</p>}
      </div>

      <div className="form">
        <h3>Limpeza</h3>
        <p className="muted">
          Remove do MinIO todas as imagens que não pertencem à sua coleção (mantém só o que você
          possui).
        </p>
        <button className="btn btn--danger" onClick={() => void onPrune()} disabled={busy}>
          {jobRunning && jobKind === 'prune' ? 'Removendo…' : 'Deletar Imagens Fora Coleção'}
        </button>
      </div>

      {job && (
        <div className="form">
          <h3>
            {jobLabel} — {job.status}
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
          <p className="muted">
            {job.processed}/{job.total} processadas · {job.succeeded} ok · {job.skipped} puladas ·{' '}
            {job.errors.length} erros
          </p>
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
