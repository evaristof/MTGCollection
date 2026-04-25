import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  CartesianGrid,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import { api } from '../api/client'
import type { CardMover, PriceMoversResponse } from '../types/mtg'
import { CardImageTooltip } from '../components/CardImageTooltip'

interface DumpTotalPoint {
  timestamp: string
  value: number
}

/**
 * Formats an ISO-8601 local-datetime (e.g. `2025-11-18T10:22:31`) as a
 * compact Brazilian label for chart axes/tooltips. Falls back to the raw
 * string when the date is unparseable so no data point is ever hidden.
 */
function formatTimestamp(iso: string): string {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  return d.toLocaleString('pt-BR', {
    day: '2-digit',
    month: '2-digit',
    year: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}

function formatMoney(value: number): string {
  return value.toLocaleString('en-US', {
    style: 'currency',
    currency: 'USD',
    minimumFractionDigits: 2,
  })
}

/**
 * "Gráficos" page — first chart plots the total collection value
 * (SUM(price * quantity) per snapshot) across every data dump in the
 * selected date-time range. Future charts will be added to this same
 * page as additional sections.
 */
export default function ChartsPage() {
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [points, setPoints] = useState<DumpTotalPoint[]>([])
  const [movers, setMovers] = useState<PriceMoversResponse | null | undefined>(null)

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const params = { from: from.trim() || undefined, to: to.trim() || undefined }
      const [rows, moversResult] = await Promise.all([
        api.dumpTotalValues(params),
        api.dumpPriceMovers(params).catch((e) => {
          console.error('dumpPriceMovers failed:', e)
          return undefined
        }),
      ])
      setPoints(
        rows.map((r) => ({
          timestamp: r.data_dump_date_time,
          value: Number(r.total_value),
        })),
      )
      setMovers(moversResult ?? null)
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    } finally {
      setLoading(false)
    }
  }, [from, to])

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void load()
  }, [load])

  const summary = useMemo(() => {
    if (points.length === 0) return null
    const values = points.map((p) => p.value)
    const min = Math.min(...values)
    const max = Math.max(...values)
    const first = points[0].value
    const last = points[points.length - 1].value
    const delta = last - first
    return { min, max, first, last, delta, count: points.length }
  }, [points])

  return (
    <section className="page">
      <div className="toolbar">
        <h2>Gráficos {loading && <span className="muted">(carregando…)</span>}</h2>
      </div>

      <form
        className="form"
        onSubmit={(e) => {
          e.preventDefault()
          void load()
        }}
      >
        <h3>Valor total da coleção ao longo do tempo</h3>
        <p className="muted">
          Mostra o valor total (soma de <code>preço × quantidade</code>) de cada data dump
          capturado dentro do intervalo. Deixe em branco para usar todo o histórico.
        </p>
        <div className="form__grid">
          <label>
            <span>De</span>
            <input
              type="datetime-local"
              step="1"
              value={from}
              onChange={(e) => setFrom(e.target.value)}
            />
          </label>
          <label>
            <span>Até</span>
            <input
              type="datetime-local"
              step="1"
              value={to}
              onChange={(e) => setTo(e.target.value)}
            />
          </label>
        </div>
        <div className="form__actions">
          <button type="submit" disabled={loading}>
            {loading ? 'Atualizando…' : 'Atualizar'}
          </button>
          <button
            type="button"
            onClick={() => {
              setFrom('')
              setTo('')
            }}
            disabled={loading || (!from && !to)}
          >
            Limpar filtros
          </button>
        </div>
      </form>

      {error && <p className="error">{error}</p>}

      {!loading && points.length === 0 && !error && (
        <p className="muted">
          Nenhum data dump encontrado no intervalo. Gere um snapshot na tela de
          Cartas (botão <strong>Data Dump</strong>) para começar a ver a evolução.
        </p>
      )}

      {points.length > 0 && (
        <>
          {summary && (
            <ul className="muted" style={{ listStyle: 'none', padding: 0, display: 'flex', gap: 16, flexWrap: 'wrap' }}>
              <li><strong>Snapshots:</strong> {summary.count}</li>
              <li><strong>Inicial:</strong> {formatMoney(summary.first)}</li>
              <li><strong>Atual:</strong> {formatMoney(summary.last)}</li>
              <li>
                <strong>Variação:</strong>{' '}
                <span style={{ color: summary.delta >= 0 ? '#2e7d32' : '#c62828' }}>
                  {summary.delta >= 0 ? '+' : ''}
                  {formatMoney(summary.delta)}
                </span>
              </li>
              <li><strong>Mínimo:</strong> {formatMoney(summary.min)}</li>
              <li><strong>Máximo:</strong> {formatMoney(summary.max)}</li>
            </ul>
          )}
          <div style={{ width: '100%', height: 400 }}>
            <ResponsiveContainer>
              <LineChart data={points} margin={{ top: 16, right: 24, left: 8, bottom: 8 }}>
                <CartesianGrid strokeDasharray="3 3" stroke="#e5e5e5" />
                <XAxis
                  dataKey="timestamp"
                  tick={{ fontSize: 12 }}
                  tickFormatter={formatTimestamp}
                />
                <YAxis
                  tick={{ fontSize: 12 }}
                  tickFormatter={(v: number) =>
                    v.toLocaleString('en-US', { maximumFractionDigits: 0 })
                  }
                  width={80}
                />
                <Tooltip
                  formatter={(v) => [formatMoney(Number(v)), 'Valor total']}
                  labelFormatter={(label) => `Snapshot: ${formatTimestamp(String(label))}`}
                />
                <Line
                  type="monotone"
                  dataKey="value"
                  name="Valor total"
                  stroke="#1976d2"
                  strokeWidth={2}
                  dot={{ r: 3 }}
                  activeDot={{ r: 6 }}
                  isAnimationActive={false}
                />
              </LineChart>
            </ResponsiveContainer>
          </div>

          {movers && (movers.top_gainers.length > 0 || movers.top_losers.length > 0) && (
            <>
              <h3 style={{ marginTop: 24 }}>
                Maiores variações de preço{' '}
                <span className="muted" style={{ fontWeight: 400, textTransform: 'none', letterSpacing: 0 }}>
                  ({formatTimestamp(movers.old_timestamp)} → {formatTimestamp(movers.new_timestamp)})
                </span>
              </h3>
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 24 }}>
                <MoverTable
                  title={`Top ${movers.top_gainers.length} — Valorizaram`}
                  cards={movers.top_gainers}
                  positive
                />
                <MoverTable
                  title={`Top ${movers.top_losers.length} — Desvalorizaram`}
                  cards={movers.top_losers}
                  positive={false}
                />
              </div>
            </>
          )}
        </>
      )}
    </section>
  )
}

function MoverTable({
  title,
  cards,
  positive,
}: {
  title: string
  cards: CardMover[]
  positive: boolean
}) {
  if (cards.length === 0) {
    return (
      <div>
        <h3>{title}</h3>
        <p className="muted">Nenhuma carta encontrada.</p>
      </div>
    )
  }

  return (
    <div>
      <h3>{title}</h3>
      <div className="table-wrapper">
        <table style={{ minWidth: 'auto' }}>
          <thead>
            <tr>
              <th>#</th>
              <th>Carta</th>
              <th>Coleção</th>
              <th>Foil</th>
              <th>Idioma</th>
              <th>Anterior</th>
              <th>Atual</th>
              <th>Variação</th>
            </tr>
          </thead>
          <tbody>
            {cards.map((c, i) => (
              <tr key={c.source_card_id != null ? c.source_card_id : `${c.card_name}-${c.set_code}-${c.foil}-${i}`}>
                <td>{i + 1}</td>
                <td>
                  {c.source_card_id != null ? (
                    <CardImageTooltip cardId={c.source_card_id} cardName={c.card_name} />
                  ) : (
                    c.card_name
                  )}
                </td>
                <td title={c.set_code}>{c.set_name_raw || c.set_code}</td>
                <td>{c.foil ? 'Sim' : 'Não'}</td>
                <td>{c.language ?? '—'}</td>
                <td>{formatMoney(c.price_old)}</td>
                <td>{formatMoney(c.price_new)}</td>
                <td style={{ color: positive ? '#2e7d32' : '#c62828', fontWeight: 600 }}>
                  {positive ? '+' : ''}{formatMoney(c.price_diff)}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}
