import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  Bar,
  BarChart,
  CartesianGrid,
  LabelList,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import * as XLSX from 'xlsx'
import { api } from '../api/client'
import type { CardMover, LocationValue, PriceMoversResponse } from '../types/mtg'
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

function moversToRows(cards: CardMover[]) {
  return cards.map((c, i) => ({
    '#': i + 1,
    Carta: c.card_name,
    'Coleção': c.set_name_raw || c.set_code,
    'Set Code': c.set_code,
    Foil: c.foil ? 'Sim' : 'Não',
    Idioma: c.language ?? '',
    'Preço Anterior (USD)': c.price_old,
    'Preço Atual (USD)': c.price_new,
    'Variação (USD)': c.price_diff,
  }))
}

function writeMoversWorkbook(data: PriceMoversResponse) {
  const wb = XLSX.utils.book_new()

  const wsGainers = XLSX.utils.json_to_sheet(moversToRows(data.top_gainers))
  XLSX.utils.book_append_sheet(wb, wsGainers, 'Valorizaram')

  const wsLosers = XLSX.utils.json_to_sheet(moversToRows(data.top_losers))
  XLSX.utils.book_append_sheet(wb, wsLosers, 'Desvalorizaram')

  const ts = formatTimestamp(data.new_timestamp).replace(/[/:]/g, '-').replace(/\s+/g, '_')
  XLSX.writeFile(wb, `variacoes_preco_${ts}.xlsx`)
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
  const [exporting, setExporting] = useState(false)
  const [byLocation, setByLocation] = useState<LocationValue[]>([])

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const params = { from: from.trim() || undefined, to: to.trim() || undefined }
      const [rows, moversResult, locationRows] = await Promise.all([
        api.dumpTotalValues(params),
        api.dumpPriceMovers(params).catch((e) => {
          console.error('dumpPriceMovers failed:', e)
          return undefined
        }),
        // Valor por localização é da coleção ATUAL — não depende do intervalo
        // e não pode derrubar o resto da tela se falhar.
        api.collectionValueByLocation().catch((e) => {
          console.error('collectionValueByLocation failed:', e)
          return [] as LocationValue[]
        }),
      ])
      setPoints(
        rows.map((r) => ({
          timestamp: r.data_dump_date_time,
          value: Number(r.total_value),
        })),
      )
      setMovers(moversResult ?? null)
      setByLocation(locationRows)
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    } finally {
      setLoading(false)
    }
  }, [from, to])

  const handleExport = useCallback(async () => {
    setExporting(true)
    try {
      const params = { from: from.trim() || undefined, to: to.trim() || undefined, limit: 0 }
      const allMovers = await api.dumpPriceMovers(params)
      if (allMovers) {
        writeMoversWorkbook(allMovers)
      }
    } catch (e) {
      console.error('Export failed:', e)
    } finally {
      setExporting(false)
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
              <h3 style={{ marginTop: 24, display: 'flex', alignItems: 'center', gap: 8 }}>
                Maiores variações de preço{' '}
                <span className="muted" style={{ fontWeight: 400, textTransform: 'none', letterSpacing: 0 }}>
                  ({formatTimestamp(movers.old_timestamp)} → {formatTimestamp(movers.new_timestamp)})
                </span>
                <button
                  type="button"
                  title="Exportar todas as variações para Excel"
                  disabled={exporting}
                  onClick={() => void handleExport()}
                  style={{
                    background: 'none',
                    border: 'none',
                    cursor: exporting ? 'wait' : 'pointer',
                    padding: 4,
                    display: 'inline-flex',
                    alignItems: 'center',
                    opacity: exporting ? 0.5 : 1,
                  }}
                >
                  <svg width="22" height="22" viewBox="0 0 24 24" fill="#1D6F42">
                    <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8l-6-6zM6 20V4h7v5h5v11H6zm2-7.5L10.5 16 8 19.5h1.7l1.8-2.6 1.8 2.6H15L12.5 16 15 12.5h-1.7l-1.8 2.6-1.8-2.6H8z" />
                  </svg>
                </button>
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
      <LocationValuePanel rows={byLocation} loading={loading} />
    </section>
  )
}

const NO_LOCATION_LABEL = '(sem localização)'

/**
 * "Valor por localização" — quanto vale hoje o que está guardado em cada
 * pasta/caixa. Barras horizontais porque os nomes das localizações são
 * longos e a comparação é de magnitude; uma cor só, já que a identidade de
 * cada barra já está no eixo (colorir por localização não acrescentaria
 * informação nenhuma). A tabela ao lado dá os números exatos.
 */
function LocationValuePanel({ rows, loading }: { rows: LocationValue[]; loading: boolean }) {
  const data = useMemo(
    () =>
      rows.map((r) => ({
        label: r.location ?? NO_LOCATION_LABEL,
        value: Number(r.total_value),
        quantity: r.total_quantity,
        cards: r.card_count,
      })),
    [rows],
  )

  const totals = useMemo(
    () =>
      data.reduce(
        (acc, r) => ({
          value: acc.value + r.value,
          quantity: acc.quantity + r.quantity,
          cards: acc.cards + r.cards,
        }),
        { value: 0, quantity: 0, cards: 0 },
      ),
    [data],
  )

  return (
    <div className="form" style={{ marginTop: 24 }}>
      <h3>Valor por localização</h3>
      <p className="muted">
        Soma de <code>preço × quantidade</code> das cartas guardadas em cada localização, na
        coleção <strong>atual</strong> — este painel não depende do intervalo de datas acima.
        Cartas sem preço entram como zero e continuam contando nas quantidades.
      </p>

      {data.length === 0 ? (
        <p className="muted">
          {loading
            ? 'Carregando…'
            : 'Nenhuma carta cadastrada ainda — o valor por localização aparece aqui assim que a coleção tiver cartas.'}
        </p>
      ) : (
        <>
          <ul
            className="muted"
            style={{ listStyle: 'none', padding: 0, display: 'flex', gap: 16, flexWrap: 'wrap' }}
          >
            <li><strong>Total:</strong> {formatMoney(totals.value)}</li>
            <li><strong>Localizações:</strong> {data.length}</li>
            <li><strong>Cópias:</strong> {totals.quantity}</li>
            <li><strong>Linhas:</strong> {totals.cards}</li>
          </ul>

          <div style={{ width: '100%', height: Math.max(180, data.length * 44 + 48) }}>
            <ResponsiveContainer>
              <BarChart
                data={data}
                layout="vertical"
                margin={{ top: 8, right: 96, left: 8, bottom: 8 }}
              >
                <CartesianGrid strokeDasharray="3 3" stroke="#e5e5e5" horizontal={false} />
                <XAxis
                  type="number"
                  tick={{ fontSize: 12 }}
                  tickFormatter={(v: number) =>
                    v.toLocaleString('en-US', { maximumFractionDigits: 0 })
                  }
                />
                <YAxis
                  type="category"
                  dataKey="label"
                  tick={{ fontSize: 12 }}
                  width={190}
                  interval={0}
                />
                <Tooltip
                  formatter={(v) => [formatMoney(Number(v)), 'Valor']}
                  labelFormatter={(label) => String(label)}
                />
                <Bar
                  dataKey="value"
                  name="Valor"
                  fill="#1976d2"
                  radius={[0, 4, 4, 0]}
                  isAnimationActive={false}
                >
                  <LabelList
                    dataKey="value"
                    position="right"
                    formatter={(v) => (v == null ? '' : formatMoney(Number(v)))}
                    style={{ fontSize: 12, fill: 'var(--fg-soft, #555)' }}
                  />
                </Bar>
              </BarChart>
            </ResponsiveContainer>
          </div>

          <div className="table-wrapper" style={{ marginTop: 12 }}>
            <table style={{ minWidth: 'auto' }}>
              <thead>
                <tr>
                  <th>Localização</th>
                  <th>Valor (US$)</th>
                  <th>% do total</th>
                  <th>Cópias</th>
                  <th>Linhas</th>
                </tr>
              </thead>
              <tbody>
                {data.map((r) => (
                  <tr key={r.label}>
                    <td>{r.label}</td>
                    <td>{formatMoney(r.value)}</td>
                    <td>
                      {totals.value > 0
                        ? `${((r.value / totals.value) * 100).toFixed(1)}%`
                        : '—'}
                    </td>
                    <td>{r.quantity}</td>
                    <td>{r.cards}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}
    </div>
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
