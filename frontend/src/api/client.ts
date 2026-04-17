import type { CardPrice, MagicSet } from '../types/mtg'

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
  const res = await fetch(`${API_BASE_URL}${path}`, {
    headers: { Accept: 'application/json' },
    ...init,
  })
  if (!res.ok) {
    throw new Error(`Request failed ${res.status} ${res.statusText}`)
  }
  return res.json() as Promise<T>
}

export const api = {
  listSets: () => request<MagicSet[]>('/api/sets'),
  syncSets: () => request<MagicSet[]>('/api/sets/sync', { method: 'POST' }),
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
