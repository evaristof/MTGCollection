import type {
  CardCatalogSetOption,
  CardPrice,
  CollectionCard,
  DataJobSnapshot,
  DataManagementStats,
  ImportJobSnapshot,
  Location,
  ReconciliationResult,
  MagicSet,
  PriceMoversResponse,
  ScannerMatchResult,
  ScannerSplitResult,
  ScryfallSet,
} from '../types/mtg'

/**
 * Base URL for the backend API.
 *
 * In development we rely on Vite's proxy (see vite.config.ts) so relative
 * `/api/...` URLs work out of the box.
 *
 * For production/preview builds, set VITE_API_BASE_URL to the absolute URL
 * of the backend (e.g. https://mtg.example.com).
 */
const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL ?? '').replace(/\/$/, '')

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  let res: Response
  try {
    res = await fetch(`${API_BASE_URL}${path}`, {
      headers: {
        Accept: 'application/json',
        ...(init?.body ? { 'Content-Type': 'application/json' } : {}),
      },
      ...init,
    })
  } catch (err) {
    throw new Error(
      `Não foi possível conectar ao backend em ${API_BASE_URL || window.location.origin}. ` +
        'Verifique se o Spring Boot está rodando em http://localhost:8080. ' +
        `(${err instanceof Error ? err.message : String(err)})`,
    )
  }
  if (!res.ok) {
    if (res.status === 502 || res.status === 503 || res.status === 504) {
      throw new Error(
        `Backend indisponível (HTTP ${res.status}). ` +
          'O proxy do Vite não conseguiu alcançar o Spring Boot. ' +
          'Verifique se a aplicação Java está rodando em http://localhost:8080.',
      )
    }
    let details = ''
    try {
      details = await res.text()
    } catch {
      // ignore
    }
    throw new Error(
      `Request failed ${res.status} ${res.statusText}${details ? `: ${details}` : ''}`,
    )
  }
  if (res.status === 204) {
    return undefined as T
  }
  return res.json() as Promise<T>
}

export interface SetInput {
  set_code: string
  set_name: string
  release_date?: string | null
  set_type?: string | null
  card_count?: number | null
  printed_size?: number | null
  block_code?: string | null
  block_name?: string | null
}

export interface AddCardInput {
  card_name: string
  set_code: string
  foil: boolean
  language: string
  quantity: number
  /**
   * Optional physical location, by NAME. The backend resolves it against the
   * LOCATION catalog and creates the row the first time a name shows up, so
   * typing a brand-new location works from any screen.
   */
  localizacao?: string
  /** Optional explicit FK into the location catalog (wins over `localizacao`). */
  location_id?: number
  /**
   * Optional collector number. When set, the backend resolves the card by
   * (set, number) instead of by name — more precise (e.g. from the scanner).
   */
  card_number?: string
}

export interface UpdateCardInput {
  card_name?: string
  set_code?: string
  foil: boolean
  language: string
  quantity: number
  /**
   * Campos adicionais editáveis na tela Cartas.
   *
   * - `undefined` / ausente → backend mantém o valor atual (não troca).
   * - `""` → limpa o campo (backend persiste `null`).
   * - Qualquer outro valor → grava.
   *
   * Para `price`, `null` também significa "não alterar" porque o backend
   * diferencia `null` (não setado) de números via `BigDecimal`.
   */
  card_type?: string
  price?: number | null
  comentario?: string
  /** Location NAME; created on first use. `""` clears the card's location. */
  localizacao?: string
  /** Explicit FK into the location catalog (wins over `localizacao`). */
  location_id?: number
}

export const api = {
  // Scryfall live
  listScryfallSets: () => request<ScryfallSet[]>('/api/sets'),

  // Sets (persisted)
  listSets: () => request<MagicSet[]>('/api/sets/db'),
  getSet: (code: string) =>
    request<MagicSet>(`/api/sets/db/${encodeURIComponent(code)}`),
  createSet: (body: SetInput) =>
    request<MagicSet>('/api/sets/db', {
      method: 'POST',
      body: JSON.stringify(body),
    }),
  updateSet: (code: string, body: SetInput) =>
    request<MagicSet>(`/api/sets/db/${encodeURIComponent(code)}`, {
      method: 'PUT',
      body: JSON.stringify(body),
    }),
  deleteSet: (code: string) =>
    request<void>(`/api/sets/db/${encodeURIComponent(code)}`, {
      method: 'DELETE',
    }),
  syncSets: () => request<MagicSet[]>('/api/sets/sync', { method: 'POST' }),

  // Collection cards
  listCards: (setCode?: string) =>
    request<CollectionCard[]>(
      setCode ? `/api/collection/cards?set=${encodeURIComponent(setCode)}` : '/api/collection/cards',
    ),
  getCard: (id: number) => request<CollectionCard>(`/api/collection/cards/${id}`),
  addCard: (body: AddCardInput) =>
    request<CollectionCard>('/api/collection/cards', {
      method: 'POST',
      body: JSON.stringify(body),
    }),
  updateCard: (id: number, body: UpdateCardInput) =>
    request<CollectionCard>(`/api/collection/cards/${id}`, {
      method: 'PUT',
      body: JSON.stringify(body),
    }),
  deleteCard: (id: number) =>
    request<void>(`/api/collection/cards/${id}`, { method: 'DELETE' }),
  syncCard: (id: number) =>
    request<CollectionCard>(`/api/collection/cards/${id}/sync`, { method: 'POST' }),

  // Collection data dumps (point-in-time snapshots of the collection)
  createCollectionDump: () =>
    request<{ data_dump_date_time: string }>('/api/collection/datadumps', {
      method: 'POST',
    }),
  listCollectionDumps: () =>
    request<string[]>('/api/collection/datadumps'),
  listCardsFromDump: (timestamp: string) =>
    request<CollectionCard[]>(
      `/api/collection/datadumps/${encodeURIComponent(timestamp)}/cards`,
    ),
  deleteCollectionDump: (timestamp: string) =>
    request<void>(`/api/collection/datadumps/${encodeURIComponent(timestamp)}`, {
      method: 'DELETE',
    }),
  dumpTotalValues: (params: { from?: string; to?: string }) => {
    const qs = new URLSearchParams()
    if (params.from) qs.set('from', params.from)
    if (params.to) qs.set('to', params.to)
    const suffix = qs.toString() ? `?${qs.toString()}` : ''
    return request<Array<{ data_dump_date_time: string; total_value: number }>>(
      `/api/collection/datadumps/stats/total-value${suffix}`,
    )
  },
  dumpPriceMovers: (params: { from?: string; to?: string; limit?: number }): Promise<PriceMoversResponse | undefined> => {
    const qs = new URLSearchParams()
    if (params.from) qs.set('from', params.from)
    if (params.to) qs.set('to', params.to)
    if (params.limit != null) qs.set('limit', String(params.limit))
    const suffix = qs.toString() ? `?${qs.toString()}` : ''
    return request<PriceMoversResponse>(
      `/api/collection/datadumps/stats/price-movers${suffix}`,
    )
  },

  // Collection reconciliation: compares a spreadsheet against the registered
  // collection and returns the differences (read-only — the screen applies
  // each one through the normal cards API).
  reconcileCollection: async (file: File): Promise<ReconciliationResult> => {
    const body = new FormData()
    body.append('file', file)
    const res = await fetch(`${API_BASE_URL}/api/collection/reconcile`, {
      method: 'POST',
      body,
    })
    if (!res.ok) {
      const text = await res.text().catch(() => '')
      let message = `HTTP ${res.status}`
      try {
        const parsed = JSON.parse(text) as { message?: string }
        if (parsed.message) message = parsed.message
      } catch {
        if (text) message = text
      }
      throw new Error(`Falha na reconciliação: ${message}`)
    }
    return (await res.json()) as ReconciliationResult
  },

  // Collection import (async)
  importCollection: async (file: File): Promise<ImportJobSnapshot> => {
    const body = new FormData()
    body.append('file', file)
    const res = await fetch(`${API_BASE_URL}/api/collection/import`, {
      method: 'POST',
      body,
    })
    if (!res.ok) {
      const text = await res.text().catch(() => '')
      throw new Error(
        `Falha ao iniciar import: HTTP ${res.status}${text ? ` — ${text}` : ''}`,
      )
    }
    return (await res.json()) as ImportJobSnapshot
  },
  importStatus: (jobId: string) =>
    request<ImportJobSnapshot>(
      `/api/collection/import/${encodeURIComponent(jobId)}/status`,
    ),
  importDownloadUrl: (jobId: string) =>
    `${API_BASE_URL}/api/collection/import/${encodeURIComponent(jobId)}/download`,

  // Set icons
  setIconUrl: (code: string) =>
    `${API_BASE_URL}/api/sets/${encodeURIComponent(code)}/icon`,
  syncSetIcons: () => request<{ synced: number }>('/api/sets/sync-icons', { method: 'POST' }),

  // Card images
  cardImageUrl: (id: number, face = 0) =>
    `${API_BASE_URL}/api/collection/cards/${id}/image?face=${face}`,
  cardImageInfo: (id: number) =>
    request<{ face_count: number; layout: string }>(`/api/collection/cards/${id}/image/info`),

  // Prices
  priceByName: (name: string, set: string, foil: boolean) =>
    request<CardPrice>(
      `/api/prices/by-name?name=${encodeURIComponent(name)}&set=${encodeURIComponent(
        set,
      )}&foil=${foil}`,
    ),
  priceByNumber: (set: string, number: string, foil: boolean) =>
    request<CardPrice>(
      `/api/prices/by-number?set=${encodeURIComponent(set)}&number=${encodeURIComponent(
        number,
      )}&foil=${foil}`,
    ),

  // Scanner
  scannerMatch: (file: File): Promise<ScannerMatchResult> => {
    const form = new FormData()
    form.append('image', file)
    return fetch(`${API_BASE_URL}/api/scanner/match`, { method: 'POST', body: form }).then(
      (res) => {
        if (!res.ok) throw new Error(`Scanner match failed: ${res.status}`)
        return res.json() as Promise<ScannerMatchResult>
      },
    )
  },

  // Bulk scan phase 1: one photo with many cards → split into per-card crops.
  // (Each crop is then matched individually via scannerMatch.)
  scannerSplit: (file: File): Promise<ScannerSplitResult> => {
    const form = new FormData()
    form.append('image', file)
    return fetch(`${API_BASE_URL}/api/scanner/split`, { method: 'POST', body: form }).then(
      (res) => {
        if (!res.ok) throw new Error(`Bulk split failed: ${res.status}`)
        return res.json() as Promise<ScannerSplitResult>
      },
    )
  },

  scannerSyncImages: (setCode: string) =>
    request<{ status: string; message: string }>(`/api/scanner/sync-images?set=${encodeURIComponent(setCode)}`, {
      method: 'POST',
    }),

  scannerPopulateHashes: () =>
    request<{ status: string; message: string }>('/api/scanner/populate-hashes', {
      method: 'POST',
    }),

  // Magic Data Management
  dataStats: () => request<DataManagementStats>('/api/data-management/stats'),

  dataDownloadCollection: () =>
    request<{ job_id: string; message: string }>('/api/data-management/download-collection', {
      method: 'POST',
    }),

  dataPruneOutsideCollection: () =>
    request<{ job_id: string; message: string }>('/api/data-management/prune-outside-collection', {
      method: 'DELETE',
    }),

  dataDownloadAllScryfall: () =>
    request<{ job_id: string; message: string }>('/api/data-management/download-all-scryfall', {
      method: 'POST',
    }),

  dataRebuildScannerModel: () =>
    request<{ job_id: string; message: string }>('/api/data-management/rebuild-scanner-model', {
      method: 'POST',
    }),

  dataDownloadSet: (setCode: string) =>
    request<{ job_id: string; message: string }>(
      `/api/data-management/download-set?set=${encodeURIComponent(setCode)}`,
      { method: 'POST' },
    ),

  dataDeleteSet: (setCode: string) =>
    request<{ job_id: string; message: string }>(
      `/api/data-management/delete-set?set=${encodeURIComponent(setCode)}`,
      { method: 'DELETE' },
    ),

  // Set blacklist
  dataBlacklist: () => request<MagicSet[]>('/api/data-management/blacklist'),
  dataBlacklistAdd: (setCode: string) =>
    request<{ message: string }>(
      `/api/data-management/blacklist?set=${encodeURIComponent(setCode)}`,
      { method: 'POST' },
    ),
  dataBlacklistRemove: (setCode: string) =>
    request<{ message: string }>(
      `/api/data-management/blacklist?set=${encodeURIComponent(setCode)}`,
      { method: 'DELETE' },
    ),
  dataBlacklistPurge: () =>
    request<{ job_id: string; message: string }>('/api/data-management/blacklist/purge', {
      method: 'POST',
    }),

  dataJob: (jobId: string) =>
    request<DataJobSnapshot>(`/api/data-management/jobs/${encodeURIComponent(jobId)}`),

  // Locations (physical storage — boxes, binders…), backing the
  // "Cadastro de Localização" screen and the location autocomplete on
  // "Cadastro Cartas".
  listLocations: () => request<Location[]>('/api/locations'),
  createLocation: (body: { name: string; description?: string }) =>
    request<Location>('/api/locations', {
      method: 'POST',
      body: JSON.stringify(body),
    }),
  updateLocation: (id: number, body: { name: string; description?: string }) =>
    request<Location>(`/api/locations/${id}`, {
      method: 'PUT',
      body: JSON.stringify(body),
    }),
  deleteLocation: (id: number) =>
    request<void>(`/api/locations/${id}`, { method: 'DELETE' }),

  // Card catalog (from CARD_IMAGE_HASH) — fast-entry helpers for "Cadastro
  // Cartas": name autocomplete, sets a card was printed in, and lookups
  // between collector number and name.
  cardCatalogNames: () => request<string[]>('/api/card-catalog/names'),
  cardCatalogSets: (name: string) =>
    request<CardCatalogSetOption[]>(`/api/card-catalog/sets?name=${encodeURIComponent(name)}`),
  cardCatalogLookupNumber: (setCode: string, number: string) =>
    request<{ card_name: string }>(
      `/api/card-catalog/lookup-number?set=${encodeURIComponent(setCode)}&number=${encodeURIComponent(number)}`,
    ),
  cardCatalogResolveNumber: (setCode: string, name: string) =>
    request<{ collector_number: string }>(
      `/api/card-catalog/resolve-number?set=${encodeURIComponent(setCode)}&name=${encodeURIComponent(name)}`,
    ),
  // Image preview by (set, collector number) for cards not yet in the
  // collection — same reference images the scanner uses.
  scannerImageUrl: (setCode: string, number: string) =>
    `${API_BASE_URL}/api/scanner/image/${encodeURIComponent(setCode)}/${encodeURIComponent(number)}`,

  // Requests cooperative cancellation of a running job (e.g. "finalizar
  // download"). The worker stops between cards and settles as CANCELLED.
  dataCancelJob: (jobId: string) =>
    request<DataJobSnapshot>(`/api/data-management/jobs/${encodeURIComponent(jobId)}/cancel`, {
      method: 'POST',
    }),

  // Returns the running job, or undefined (204) when nothing is running.
  dataActiveJob: () =>
    request<DataJobSnapshot | undefined>('/api/data-management/jobs/active'),
}
