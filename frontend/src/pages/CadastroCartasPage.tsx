import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { api } from '../api/client'
import { CardImageTooltip } from '../components/CardImageTooltip'
import { SetCombo, type SetComboOption } from '../components/SetCombo'
import { TypeaheadInput } from '../components/TypeaheadInput'
import type { CardCatalogSetOption, Location, MagicSet } from '../types/mtg'

const LANGUAGES = [
  'English',
  'Japanese',
  'French',
  'German',
  'Italian',
  'Spanish',
  'Portuguese',
  'Chinese',
  'Korean',
  'Russian',
]

interface CardFormState {
  name: string
  setCode: string
  number: string
  language: string
  foil: boolean
  localizacao: string
  quantity: number
}

interface CardRow extends CardFormState {
  id: string
  editing: boolean
  adding?: boolean
  added?: boolean
  addError?: string
  /** Preço consultado no Scryfall quando a linha entrou no lote. */
  price?: number | null
  /** Moeda do preço: normalmente USD, EUR quando o foil só tem preço em euro. */
  priceCurrency?: string
  /** Origem do preço quando não é o usd_foil esperado (etched, euro). */
  priceNote?: string | null
  priceStatus?: 'loading' | 'done' | 'error'
}

const emptyForm = (): CardFormState => ({
  name: '',
  setCode: '',
  number: '',
  language: '',
  foil: false,
  localizacao: '',
  quantity: 1,
})

const formatMoney = (value: number | null | undefined, currency = 'USD'): string => {
  if (value === null || value === undefined) return '-'
  return value.toLocaleString('en-US', {
    style: 'currency',
    currency,
    minimumFractionDigits: 2,
  })
}

const uid = () =>
  typeof crypto !== 'undefined' && 'randomUUID' in crypto
    ? crypto.randomUUID()
    : Math.random().toString(36).slice(2)

// Fields that are usually the same across consecutive cards from the same
// box/binder — kept as-is after "Adicionar" so registering hundreds of cards
// doesn't mean re-picking the set/language/location every single time.
const nextForm = (prev: CardFormState): CardFormState => ({
  ...emptyForm(),
  setCode: prev.setCode,
  language: prev.language,
  foil: prev.foil,
  localizacao: prev.localizacao,
})

export default function CadastroCartasPage() {
  const [form, setForm] = useState<CardFormState>(emptyForm())
  const [rows, setRows] = useState<CardRow[]>([])

  const [catalogNames, setCatalogNames] = useState<string[]>([])
  const [allSets, setAllSets] = useState<MagicSet[]>([])
  const [setOptionsForName, setSetOptionsForName] = useState<CardCatalogSetOption[] | null>(null)
  const [locations, setLocations] = useState<Location[]>([])

  const [error, setError] = useState<string | null>(null)
  const [summary, setSummary] = useState<string | null>(null)
  const [bulkAdding, setBulkAdding] = useState(false)
  const [numberLookup, setNumberLookup] = useState<'idle' | 'loading' | 'found' | 'notfound'>('idle')

  // Foco volta para o nome depois de empilhar uma carta, para cadastrar o
  // lote inteiro sem tirar a mão do teclado. O foco é pedido por um contador
  // e aplicado num efeito: chamar focus() dentro do próprio clique acontece
  // ANTES do React re-renderizar a lista/o formulário, e o foco se perdia.
  const nameInputRef = useRef<HTMLInputElement>(null)
  const [focusNameRequest, setFocusNameRequest] = useState(0)
  // (set|name) → reference image URL, or null when the catalog has none.
  // Keeps hovering the same card from re-querying the collector number.
  const previewCache = useRef<Map<string, string | null>>(new Map())

  useEffect(() => {
    void (async () => {
      try {
        const [names, sets, locs] = await Promise.all([
          api.cardCatalogNames(),
          api.listSets(),
          api.listLocations(),
        ])
        setCatalogNames(names)
        setAllSets(sets)
        setLocations(locs)
      } catch (err) {
        setError(err instanceof Error ? err.message : String(err))
      }
    })()
  }, [])

  useEffect(() => {
    if (focusNameRequest === 0) return
    const input =
      nameInputRef.current ?? (document.getElementById('cc-name') as HTMLInputElement | null)
    input?.focus()
  }, [focusNameRequest])

  const fullSetOptions: SetComboOption[] = useMemo(
    () =>
      allSets
        .filter((s) => !s.blacklisted)
        .map((s) => ({ code: s.set_code, name: s.set_name })),
    [allSets],
  )

  const activeSetOptions: SetComboOption[] = useMemo(() => {
    if (setOptionsForName == null) return fullSetOptions
    return setOptionsForName.map((s) => ({ code: s.set_code, name: s.set_name }))
  }, [setOptionsForName, fullSetOptions])

  const locationNames = useMemo(() => locations.map((l) => l.name), [locations])

  // --- Nome: autocomplete over CARD_IMAGE_HASH.card_name; selecting one
  // filters the Set combo to only the sets that actually have this card. ---
  const onNameChange = (value: string) => {
    setForm((f) => ({ ...f, name: value }))
    if (value.trim() === '') {
      setSetOptionsForName(null)
    }
  }

  const onNameSelect = async (name: string) => {
    setForm((f) => ({ ...f, name }))
    try {
      const sets = await api.cardCatalogSets(name)
      setSetOptionsForName(sets)
      setForm((f) => (sets.some((s) => s.set_code === f.setCode) ? f : { ...f, setCode: '' }))
    } catch {
      // Non-critical — the set field just falls back to the full list.
      setSetOptionsForName(null)
    }
  }

  // --- Número (opcional): resolves the card name within the chosen set. ---
  const onNumberBlur = async () => {
    const number = form.number.trim()
    if (!number || !form.setCode) return
    setNumberLookup('loading')
    try {
      const res = await api.cardCatalogLookupNumber(form.setCode, number)
      setForm((f) => ({ ...f, name: res.card_name }))
      setNumberLookup('found')
      try {
        const sets = await api.cardCatalogSets(res.card_name)
        setSetOptionsForName(sets)
      } catch {
        // ignore — set filtering is a convenience, not required here
      }
    } catch {
      setNumberLookup('notfound')
    }
  }

  /**
   * Reference image for (set, name, number), as the list of face URLs the
   * hover tooltip renders — empty when there is nothing to show.
   *
   * With a collector number the URL is built directly; without one we ask the
   * catalog for a number for that (set, name) first.
   */
  const resolveCardImage = useCallback(
    async (setCode: string, name: string, number: string): Promise<string[]> => {
      const set = setCode.trim()
      const num = number.trim()
      const cardName = name.trim()
      if (!set || (!cardName && !num)) return []
      if (num) return [api.scannerImageUrl(set, num)]

      const cacheKey = `${set}::${cardName.toLowerCase()}`
      const cached = previewCache.current.get(cacheKey)
      if (cached !== undefined) return cached ? [cached] : []
      try {
        const res = await api.cardCatalogResolveNumber(set, cardName)
        const url = api.scannerImageUrl(set, res.collector_number)
        previewCache.current.set(cacheKey, url)
        return [url]
      } catch {
        previewCache.current.set(cacheKey, null)
        return []
      }
    },
    [],
  )

  const resolveLanguage = (value: string): string | null => {
    const trimmed = value.trim()
    const hit = LANGUAGES.find((l) => l.toLowerCase() === trimmed.toLowerCase())
    return hit ?? null
  }

  const onAddRow = async () => {
    setError(null)
    const hasName = !!form.name.trim()
    const hasNumber = !!form.number.trim()
    if (!hasName && !hasNumber) {
      setError('Informe o nome da carta ou o número.')
      return
    }
    if (!form.setCode.trim()) {
      setError('Selecione o set.')
      return
    }
    const language = resolveLanguage(form.language)
    if (!language) {
      setError('Selecione uma linguagem válida da lista.')
      return
    }
    if (form.quantity < 1) {
      setError('Quantidade precisa ser >= 1.')
      return
    }

    const row: CardRow = {
      id: uid(),
      name: form.name.trim(),
      setCode: form.setCode.trim(),
      number: form.number.trim(),
      language,
      foil: form.foil,
      localizacao: form.localizacao.trim(),
      quantity: form.quantity,
      editing: false,
    }
    setRows((prev) => [row, ...prev])
    void lookupPrice(row)
    setForm((f) => nextForm(f))
    setNumberLookup('idle')
    setFocusNameRequest((n) => n + 1)
  }

  const patchRow = (id: string, patch: Partial<CardRow>) =>
    setRows((prev) => prev.map((r) => (r.id === id ? { ...r, ...patch } : r)))

  /**
   * Preço atual da carta, mostrado na linha assim que ela entra no lote.
   * Por número quando ele foi informado (mais preciso), senão por nome — a
   * mesma precedência que o backend usa ao gravar na coleção.
   */
  const lookupPrice = async (row: CardRow) => {
    if (!row.setCode || (!row.number && !row.name)) return
    patchRow(row.id, { priceStatus: 'loading' })
    try {
      const res = row.number
        ? await api.priceByNumber(row.setCode, row.number, row.foil)
        : await api.priceByName(row.name, row.setCode, row.foil)
      // Moeda e origem vêm do backend: foil sem usd_foil cai em usd_etched
      // (dólar) ou eur_foil (euro), e a origem é a mesma marca que vai para o
      // comentário da carta.
      patchRow(row.id, {
        price: res.price,
        priceCurrency: res.currency,
        priceNote: res.note ?? null,
        priceStatus: 'done',
      })
    } catch {
      // Carta sem preço no Scryfall, offline, etc. — a linha continua válida.
      patchRow(row.id, { price: null, priceStatus: 'error' })
    }
  }

  const onRemoveRow = (id: string) => setRows((prev) => prev.filter((r) => r.id !== id))

  const onStartEdit = (id: string) => patchRow(id, { editing: true })

  /** Fecha a edição e reconsulta o preço, que pode ter mudado de carta/set/foil. */
  const onFinishEdit = (row: CardRow) => {
    patchRow(row.id, { editing: false })
    void lookupPrice({ ...row, editing: false })
  }

  const onAddToCollection = async () => {
    if (rows.length === 0) {
      setError('Nenhuma carta adicionada ainda.')
      return
    }
    setError(null)
    setSummary(null)
    setBulkAdding(true)

    let ok = 0
    let failed = 0
    for (const row of rows) {
      const language = resolveLanguage(row.language) ?? row.language
      patchRow(row.id, { adding: true, addError: undefined })
      try {
        await api.addCard({
          card_name: row.name,
          set_code: row.setCode,
          foil: row.foil,
          language,
          quantity: row.quantity,
          localizacao: row.localizacao || undefined,
          card_number: row.number || undefined,
        })
        patchRow(row.id, { adding: false, added: true })
        ok++
      } catch (err) {
        patchRow(row.id, {
          adding: false,
          addError: err instanceof Error ? err.message : String(err),
        })
        failed++
      }
    }

    // Clear out what succeeded; keep failures in place so they can be fixed
    // and retried without re-typing everything.
    setRows((prev) => prev.filter((r) => !r.added))
    // Locations typed for the first time are created by the backend while
    // saving the cards — pull the catalog again so they show up in the
    // autocomplete right away.
    try {
      setLocations(await api.listLocations())
    } catch {
      // non-critical: the list just stays as it was
    }
    setSummary(
      failed === 0
        ? `${ok} carta(s) adicionada(s) à coleção.`
        : `${ok} carta(s) adicionada(s), ${failed} falharam — corrija e clique novamente em "Adicionar à coleção".`,
    )
    setBulkAdding(false)
  }

  const pendingCount = rows.filter((r) => !r.added).length

  const priceCell = (row: CardRow) => {
    if (row.priceStatus === 'loading') return <span className="muted">consultando…</span>
    if (row.price === null || row.price === undefined) {
      return <span className="muted">{row.priceStatus === 'error' ? 'sem preço' : '-'}</span>
    }
    return (
      <span title={row.priceNote ?? undefined}>
        {formatMoney(row.price, row.priceCurrency)}
        {row.priceNote && <span className="muted"> ⓘ</span>}
      </span>
    )
  }

  return (
    <section className="page">
      <div className="toolbar">
        <h2>Cadastro Cartas</h2>
      </div>

      {error && <p className="error">{error}</p>}
      {summary && <p className="muted">{summary}</p>}

      <div className="form">
        <h3>Nova carta</h3>
        <p className="muted">
          Digite o nome (com autocomplete) ou o número da carta no set. Passe o mouse sobre o
          nome ou número para ver a foto — use o <strong>scroll do mouse</strong> para dar zoom.
          Clique em <strong>Adicionar</strong> para empilhar a carta abaixo, e em{' '}
          <strong>Adicionar à coleção</strong> quando terminar o lote.
        </p>
        <div className="form__grid">
          <label>
            <span>Nome da carta</span>
            <CardImageTooltip
              cardName={form.name}
              resolveImageUrls={() => resolveCardImage(form.setCode, form.name, form.number)}
            >
              <TypeaheadInput
                id="cc-name"
                inputRef={nameInputRef}
                // Catálogo grande: a lista abre ao digitar, não ao focar —
                // senão, ao voltar o foco depois de "Adicionar", apareceriam
                // 25 nomes quaisquer.
                openOnFocus={false}
                value={form.name}
                onChange={onNameChange}
                onSelect={(v) => void onNameSelect(v)}
                options={catalogNames}
                placeholder="Lightning Bolt…"
              />
            </CardImageTooltip>
          </label>
          <label>
            <span>Set{setOptionsForName != null ? ' (filtrado pela carta)' : ''}</span>
            <SetCombo
              id="cc-set"
              value={form.setCode}
              onChange={(code) => setForm((f) => ({ ...f, setCode: code }))}
              options={activeSetOptions}
              emptyLabel="— selecione um set —"
            />
          </label>
          <label>
            <span>Número (opcional)</span>
            <CardImageTooltip
              cardName={form.name}
              resolveImageUrls={() => resolveCardImage(form.setCode, form.name, form.number)}
            >
              <input
                value={form.number}
                onChange={(e) => {
                  setForm((f) => ({ ...f, number: e.target.value }))
                  setNumberLookup('idle')
                }}
                onBlur={() => void onNumberBlur()}
                placeholder="ex: 123"
                style={{ width: '100%', boxSizing: 'border-box' }}
              />
            </CardImageTooltip>
            {numberLookup === 'loading' && <span className="muted">buscando…</span>}
            {numberLookup === 'notfound' && <span className="muted">número não encontrado nesse set</span>}
          </label>
          <label>
            <span>Linguagem</span>
            <TypeaheadInput
              id="cc-language"
              value={form.language}
              onChange={(v) => setForm((f) => ({ ...f, language: v }))}
              onSelect={(v) => setForm((f) => ({ ...f, language: v }))}
              options={LANGUAGES}
              placeholder="English…"
            />
          </label>
          <label className="checkbox">
            <input
              type="checkbox"
              checked={form.foil}
              onChange={(e) => setForm((f) => ({ ...f, foil: e.target.checked }))}
            />
            <span>Foil</span>
          </label>
          <label>
            <span>Localização</span>
            <TypeaheadInput
              id="cc-localizacao"
              freeSolo
              value={form.localizacao}
              onChange={(v) => setForm((f) => ({ ...f, localizacao: v }))}
              onSelect={(v) => setForm((f) => ({ ...f, localizacao: v }))}
              options={locationNames}
              placeholder="Caixa 3…"
            />
          </label>
          <label>
            <span>Quantidade</span>
            <input
              type="number"
              min={1}
              value={form.quantity}
              onChange={(e) => setForm((f) => ({ ...f, quantity: Number(e.target.value) }))}
            />
          </label>
        </div>
        <div className="form__actions">
          <button type="button" className="btn" onClick={() => void onAddRow()}>
            ＋ Adicionar
          </button>
        </div>
      </div>

      <div className="form">
        <h3>Cartas para cadastrar ({rows.length})</h3>
        <p className="muted">
          Passe o mouse sobre o nome/número da linha para ver a foto (scroll do mouse dá zoom).
          Use <strong>Editar</strong> para corrigir uma linha ou <strong>Remover</strong> para
          descartá-la. Cartas idênticas que já estejam na coleção (mesmo set, número, linguagem,
          foil e localização) somam na quantidade em vez de criar uma linha nova.
        </p>
        <div style={{ marginBottom: '0.75rem' }}>
          <button
            className="btn"
            onClick={() => void onAddToCollection()}
            disabled={bulkAdding || pendingCount === 0}
          >
            {bulkAdding ? 'Adicionando…' : `Adicionar à coleção (${pendingCount})`}
          </button>
        </div>

        {rows.length > 0 && (
          <table className="table">
            <thead>
              <tr>
                <th>#</th>
                <th>Nome</th>
                <th>Set</th>
                <th>Número</th>
                <th>Idioma</th>
                <th>Foil</th>
                <th>Localização</th>
                <th>Qtd</th>
                <th>Preço</th>
                <th>Ações</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((row, i) =>
                row.editing ? (
                  <tr key={row.id}>
                    <td>{i + 1}</td>
                    <td>
                      <TypeaheadInput
                        value={row.name}
                        onChange={(v) => patchRow(row.id, { name: v })}
                        onSelect={(v) => patchRow(row.id, { name: v })}
                        options={catalogNames}
                        style={{ minWidth: 140 }}
                      />
                    </td>
                    <td>
                      <SetCombo
                        value={row.setCode}
                        onChange={(code) => patchRow(row.id, { setCode: code })}
                        options={fullSetOptions}
                        emptyLabel="—"
                      />
                    </td>
                    <td>
                      <input
                        value={row.number}
                        onChange={(e) => patchRow(row.id, { number: e.target.value })}
                        style={{ width: 60 }}
                      />
                    </td>
                    <td>
                      <TypeaheadInput
                        value={row.language}
                        onChange={(v) => patchRow(row.id, { language: v })}
                        onSelect={(v) => patchRow(row.id, { language: v })}
                        options={LANGUAGES}
                        style={{ minWidth: 110 }}
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
                      <TypeaheadInput
                        freeSolo
                        value={row.localizacao}
                        onChange={(v) => patchRow(row.id, { localizacao: v })}
                        onSelect={(v) => patchRow(row.id, { localizacao: v })}
                        options={locationNames}
                        style={{ minWidth: 110 }}
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
                    <td className="muted">{priceCell(row)}</td>
                    <td style={{ whiteSpace: 'nowrap' }}>
                      <button className="btn btn--sm" onClick={() => onFinishEdit(row)}>
                        Concluir
                      </button>{' '}
                      <button className="btn btn--danger btn--sm" onClick={() => onRemoveRow(row.id)}>
                        Remover
                      </button>
                    </td>
                  </tr>
                ) : (
                  <tr key={row.id}>
                    <td>{i + 1}</td>
                    <td>
                      <CardImageTooltip
                        cardName={row.name}
                        resolveImageUrls={() => resolveCardImage(row.setCode, row.name, row.number)}
                      >
                        {row.name || <span className="muted">—</span>}
                      </CardImageTooltip>
                    </td>
                    <td>{row.setCode}</td>
                    <td>
                      <CardImageTooltip
                        cardName={row.name}
                        resolveImageUrls={() => resolveCardImage(row.setCode, row.name, row.number)}
                      >
                        {row.number || <span className="muted">—</span>}
                      </CardImageTooltip>
                    </td>
                    <td>{row.language}</td>
                    <td style={{ textAlign: 'center' }}>{row.foil ? '✓' : ''}</td>
                    <td>{row.localizacao || <span className="muted">—</span>}</td>
                    <td>{row.quantity}</td>
                    <td>{priceCell(row)}</td>
                    <td style={{ whiteSpace: 'nowrap' }}>
                      {row.added ? (
                        <span className="muted">✓ adicionada</span>
                      ) : (
                        <>
                          <button className="btn btn--sm" onClick={() => onStartEdit(row.id)} disabled={row.adding}>
                            Editar
                          </button>{' '}
                          <button
                            className="btn btn--danger btn--sm"
                            onClick={() => onRemoveRow(row.id)}
                            disabled={row.adding}
                          >
                            Remover
                          </button>
                        </>
                      )}
                      {row.addError && <div className="error" style={{ marginTop: 4 }}>{row.addError}</div>}
                    </td>
                  </tr>
                ),
              )}
            </tbody>
          </table>
        )}
      </div>
    </section>
  )
}
