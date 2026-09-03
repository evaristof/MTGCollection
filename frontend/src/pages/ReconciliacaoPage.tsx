import { useMemo, useRef, useState } from 'react'
import { api } from '../api/client'
import type { ReconciliationDiffRow, ReconciliationResult } from '../types/mtg'

type Applied = 'added' | 'deleted' | 'equalized'

interface RowState {
  busy?: boolean
  applied?: Applied
  error?: string
  /** Quantidade escolhida no dash de quantidades (default: a da planilha). */
  target?: number
}

/** Identidade estável de uma linha de diferença, para guardar o estado local. */
const rowKey = (d: ReconciliationDiffRow) =>
  [d.card_name, d.set_code ?? d.set_name ?? '', d.foil ? 'foil' : '', d.language, d.location].join('|')

const foilLabel = (foil: boolean) => (foil ? '✦' : '—')

export default function ReconciliacaoPage() {
  const [file, setFile] = useState<File | null>(null)
  const [result, setResult] = useState<ReconciliationResult | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [rows, setRows] = useState<Record<string, RowState>>({})
  const fileInputRef = useRef<HTMLInputElement | null>(null)

  const patch = (key: string, state: RowState) =>
    setRows((prev) => ({ ...prev, [key]: { ...prev[key], ...state } }))

  const analisar = async (selected: File | null) => {
    if (!selected) {
      setError('Selecione a planilha da coleção.')
      return
    }
    setBusy(true)
    setError(null)
    try {
      const res = await api.reconcileCollection(selected)
      setResult(res)
      setRows({})
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
      setResult(null)
    } finally {
      setBusy(false)
    }
  }

  // --- Ações por linha -----------------------------------------------------

  const cadastrar = async (d: ReconciliationDiffRow) => {
    const key = rowKey(d)
    patch(key, { busy: true, error: undefined })
    try {
      await api.addCard({
        card_name: d.card_name,
        set_code: d.set_code as string,
        foil: d.foil,
        language: d.language,
        quantity: d.excel_quantity,
        localizacao: d.location || undefined,
        card_number: d.card_number || undefined,
      })
      patch(key, { busy: false, applied: 'added' })
    } catch (err) {
      patch(key, { busy: false, error: err instanceof Error ? err.message : String(err) })
    }
  }

  const apagar = async (d: ReconciliationDiffRow) => {
    const key = rowKey(d)
    const quantas = d.card_ids.length
    if (
      !window.confirm(
        `Apagar ${d.card_name} (${d.collection_quantity} cópia(s) em ${d.location || 'sem localização'})` +
          `${quantas > 1 ? ` — ${quantas} linhas da base` : ''}?`,
      )
    ) {
      return
    }
    patch(key, { busy: true, error: undefined })
    try {
      for (const id of d.card_ids) {
        await api.deleteCard(id)
      }
      patch(key, { busy: false, applied: 'deleted' })
    } catch (err) {
      patch(key, { busy: false, error: err instanceof Error ? err.message : String(err) })
    }
  }

  const equalizar = async (d: ReconciliationDiffRow) => {
    const key = rowKey(d)
    const target = rows[key]?.target ?? d.excel_quantity
    if (!Number.isFinite(target) || target < 1) {
      patch(key, { error: 'Quantidade precisa ser >= 1.' })
      return
    }
    patch(key, { busy: true, error: undefined })
    try {
      const [first, ...extras] = d.card_ids
      await api.updateCard(first, {
        foil: d.foil,
        language: d.collection_language || d.language,
        quantity: target,
      })
      // Quando a base tinha mais de uma linha para a mesma carta/localização,
      // a primeira fica com a quantidade escolhida e as demais somem.
      for (const id of extras) {
        await api.deleteCard(id)
      }
      patch(key, { busy: false, applied: 'equalized' })
    } catch (err) {
      patch(key, { busy: false, error: err instanceof Error ? err.message : String(err) })
    }
  }

  // --- Render --------------------------------------------------------------

  const summary = result?.summary
  const pendentes = useMemo(() => {
    if (!result) return 0
    const all = [...result.only_in_excel, ...result.only_in_collection, ...result.quantity_mismatch]
    return all.filter((d) => !rows[rowKey(d)]?.applied).length
  }, [result, rows])

  const appliedLabel: Record<Applied, string> = {
    added: '✓ cadastrada',
    deleted: '✓ apagada',
    equalized: '✓ ajustada',
  }

  const statusCell = (d: ReconciliationDiffRow, acao: React.ReactNode) => {
    const state = rows[rowKey(d)]
    if (state?.applied) {
      return <span className="muted">{appliedLabel[state.applied]}</span>
    }
    return (
      <>
        {acao}
        {state?.error && (
          <div className="error" style={{ marginTop: 4 }}>
            {state.error}
          </div>
        )}
      </>
    )
  }

  const identidade = (d: ReconciliationDiffRow) => (
    <>
      <td title={d.sheet_rows.join(', ')}>{d.card_name}</td>
      <td title={d.set_code ?? undefined}>{d.set_name ?? d.set_code ?? '-'}</td>
      <td>{d.card_number ?? '-'}</td>
      <td style={{ textAlign: 'center' }}>{foilLabel(d.foil)}</td>
      <td>{d.language || '-'}</td>
      <td>{d.location || <span className="muted">sem localização</span>}</td>
    </>
  )

  return (
    <section className="page">
      <div className="toolbar">
        <h2>Reconciliação Coleção {busy && <span className="muted">(analisando…)</span>}</h2>
      </div>

      {error && <p className="error">{error}</p>}

      <div className="form">
        <h3>Planilha</h3>
        <p className="muted">
          Use a mesma planilha do <strong>Importar coleção</strong> da tela Cartas. Nada é gravado
          na análise: cada diferença é aplicada por você, linha a linha. Localização no formato{' '}
          <code>Pasta A (3) e Pasta B (5)</code> é lida como 3 cópias numa pasta e 5 na outra. A
          comparação é por set + nome + foil + idioma + localização (o número do coletor é
          opcional na planilha, então fica fora da chave), e só as localizações citadas na
          planilha entram na conta.
        </p>
        <div className="form__grid">
          <label>
            <span>Arquivo .xlsx</span>
            <input
              ref={fileInputRef}
              type="file"
              accept=".xlsx"
              onChange={(e) => {
                setFile(e.target.files?.[0] ?? null)
                setError(null)
              }}
            />
          </label>
        </div>
        <div className="form__actions">
          <button type="button" onClick={() => void analisar(file)} disabled={busy || !file}>
            {busy ? 'Analisando…' : 'Analisar'}
          </button>
          {result && (
            <button type="button" onClick={() => void analisar(file)} disabled={busy || !file}>
              Reanalisar
            </button>
          )}
        </div>
      </div>

      {summary && (
        <div className="form">
          <h3>Resumo</h3>
          <p className="muted">
            {summary.sheet_rows} linha(s) na planilha → {summary.expanded_rows} entrada(s) depois
            de separar as localizações, em {summary.locations} localização(ões).{' '}
            <strong>{summary.matched}</strong> conferem;{' '}
            <strong>{summary.only_in_excel}</strong> só na planilha,{' '}
            <strong>{summary.only_in_collection}</strong> só na base,{' '}
            <strong>{summary.quantity_mismatch}</strong> com quantidade diferente.
            {pendentes === 0 && ' Todas as diferenças foram tratadas — reanalise para conferir.'}
          </p>
          {result && result.ignored.length > 0 && (
            <p className="muted">
              Linhas ignoradas ({result.ignored.length}): {result.ignored.slice(0, 10).join('; ')}
              {result.ignored.length > 10 ? '…' : ''}
            </p>
          )}
        </div>
      )}

      {result && (
        <div className="form">
          <h3>Só na planilha ({result.only_in_excel.length})</h3>
          <p className="muted">
            Cartas que a planilha tem naquela localização e a base não. <strong>Cadastrar</strong>{' '}
            adiciona à coleção com a quantidade da planilha (o backend busca número/tipo/preço no
            Scryfall, como na tela Cartas).
          </p>
          {result.only_in_excel.length === 0 ? (
            <p className="muted">Nenhuma diferença aqui.</p>
          ) : (
            <div className="table-wrapper">
              <table>
                <thead>
                  <tr>
                    <th>Carta</th>
                    <th>Set</th>
                    <th>Nº</th>
                    <th>Foil</th>
                    <th>Idioma</th>
                    <th>Localização</th>
                    <th>Qtd planilha</th>
                    <th style={{ width: 220 }}>Ação</th>
                  </tr>
                </thead>
                <tbody>
                  {result.only_in_excel.map((d) => {
                    const key = rowKey(d)
                    const state = rows[key]
                    const semSet = !d.set_code
                    const semIdioma = !d.language
                    return (
                      <tr key={key}>
                        {identidade(d)}
                        <td>{d.excel_quantity}</td>
                        <td>
                          {statusCell(
                            d,
                            <button
                              type="button"
                              onClick={() => void cadastrar(d)}
                              disabled={state?.busy || semSet || semIdioma}
                              title={
                                semSet
                                  ? 'Set da planilha não existe na tabela de sets — sincronize os sets primeiro'
                                  : semIdioma
                                    ? 'Linha da planilha sem idioma'
                                    : undefined
                              }
                            >
                              {state?.busy ? 'Cadastrando…' : 'Cadastrar'}
                            </button>,
                          )}
                        </td>
                      </tr>
                    )
                  })}
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}

      {result && (
        <div className="form">
          <h3>Só na base ({result.only_in_collection.length})</h3>
          <p className="muted">
            Cartas cadastradas naquelas localizações que a planilha não tem. <strong>Apagar</strong>{' '}
            remove da coleção (todas as linhas por trás da diferença).
          </p>
          {result.only_in_collection.length === 0 ? (
            <p className="muted">Nenhuma diferença aqui.</p>
          ) : (
            <div className="table-wrapper">
              <table>
                <thead>
                  <tr>
                    <th>Carta</th>
                    <th>Set</th>
                    <th>Nº</th>
                    <th>Foil</th>
                    <th>Idioma</th>
                    <th>Localização</th>
                    <th>Qtd base</th>
                    <th style={{ width: 220 }}>Ação</th>
                  </tr>
                </thead>
                <tbody>
                  {result.only_in_collection.map((d) => {
                    const key = rowKey(d)
                    const state = rows[key]
                    return (
                      <tr key={key}>
                        {identidade(d)}
                        <td>
                          {d.collection_quantity}
                          {d.card_ids.length > 1 && (
                            <span className="muted"> ({d.card_ids.length} linhas)</span>
                          )}
                        </td>
                        <td>
                          {statusCell(
                            d,
                            <button
                              type="button"
                              className="danger"
                              onClick={() => void apagar(d)}
                              disabled={state?.busy}
                            >
                              {state?.busy ? 'Apagando…' : 'Apagar'}
                            </button>,
                          )}
                        </td>
                      </tr>
                    )
                  })}
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}

      {result && (
        <div className="form">
          <h3>Quantidades diferentes ({result.quantity_mismatch.length})</h3>
          <p className="muted">
            Estão nos dois lados, na mesma localização, com quantidades diferentes. Escolha a
            quantidade e clique em <strong>Equalizar</strong> — o campo já vem com a da planilha.
          </p>
          {result.quantity_mismatch.length === 0 ? (
            <p className="muted">Nenhuma diferença aqui.</p>
          ) : (
            <div className="table-wrapper">
              <table>
                <thead>
                  <tr>
                    <th>Carta</th>
                    <th>Set</th>
                    <th>Nº</th>
                    <th>Foil</th>
                    <th>Idioma</th>
                    <th>Localização</th>
                    <th>Qtd planilha</th>
                    <th>Qtd base</th>
                    <th style={{ width: 260 }}>Equalizar para</th>
                  </tr>
                </thead>
                <tbody>
                  {result.quantity_mismatch.map((d) => {
                    const key = rowKey(d)
                    const state = rows[key]
                    const target = state?.target ?? d.excel_quantity
                    return (
                      <tr key={key}>
                        {identidade(d)}
                        <td>{d.excel_quantity}</td>
                        <td>
                          {d.collection_quantity}
                          {d.card_ids.length > 1 && (
                            <span className="muted"> ({d.card_ids.length} linhas)</span>
                          )}
                        </td>
                        <td>
                          {statusCell(
                            d,
                            <span style={{ display: 'inline-flex', gap: '0.4rem', alignItems: 'center' }}>
                              <input
                                type="number"
                                min={1}
                                value={target}
                                onChange={(e) => patch(key, { target: Number(e.target.value) })}
                                style={{ width: 70 }}
                                aria-label={`Quantidade para ${d.card_name}`}
                              />
                              <button
                                type="button"
                                onClick={() => void equalizar(d)}
                                disabled={state?.busy}
                              >
                                {state?.busy ? 'Ajustando…' : 'Equalizar'}
                              </button>
                            </span>,
                          )}
                        </td>
                      </tr>
                    )
                  })}
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}
    </section>
  )
}
