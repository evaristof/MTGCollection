import type {
  CardPrice,
  CollectionCard,
  ImportJobSnapshot,
  MagicSet,
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
}

export interface UpdateCardInput {
  card_name?: string
  set_code?: string
  foil: boolean
  language: string
  quantity: number
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
}
