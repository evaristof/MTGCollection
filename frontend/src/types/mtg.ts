/**
 * Shape of a Magic set as returned by GET /api/sets (live Scryfall).
 * Matches the Scryfall payload (snake_case) via Jackson's
 * spring.jackson.property-naming-strategy=SNAKE_CASE.
 */
export interface ScryfallSet {
  code: string
  name: string
  released_at?: string | null
  set_type?: string | null
  card_count?: number | null
  printed_size?: number | null
  block_code?: string | null
  block?: string | null
}

/**
 * Persisted Magic set (GET /api/sets/db).
 * The JPA entity exposes `set_code` / `set_name` / `release_date` /
 * `block_name` — different shape from the Scryfall DTO above.
 */
export interface MagicSet {
  set_code: string
  set_name: string
  release_date?: string | null
  set_type?: string | null
  card_count?: number | null
  printed_size?: number | null
  block_code?: string | null
  block_name?: string | null
  icon_svg_uri?: string | null
}

/**
 * Collection card row (GET /api/collection/cards).
 */
export interface CollectionCard {
  id: number
  card_number: string
  card_name: string
  set_code: string
  foil: boolean
  card_type?: string | null
  language: string
  quantity: number
  price?: number | null
  comentario?: string | null
  localizacao?: string | null
}

/**
 * Snapshot of an async collection-import job.
 * Returned by POST /api/collection/import and
 * GET /api/collection/import/{id}/status.
 */
export interface ImportJobSnapshot {
  id: string
  file_name: string | null
  status: 'PENDING' | 'RUNNING' | 'DONE' | 'FAILED'
  total: number
  processed: number
  persisted: number
  errors: string[]
  current_sheet: string | null
  result_file_name: string | null
  error_message: string | null
}

/**
 * A single card entry in the price-movers response.
 */
export interface CardMover {
  card_name: string
  set_code: string
  set_name_raw: string | null
  foil: boolean
  source_card_id: number | null
  price_old: number
  price_new: number
  price_diff: number
}

/**
 * Response from GET /api/collection/datadumps/stats/price-movers.
 * Contains top gainers and top losers between the two most recent snapshots.
 */
export interface PriceMoversResponse {
  old_timestamp: string
  new_timestamp: string
  top_gainers: CardMover[]
  top_losers: CardMover[]
}

/**
 * Shape of a price lookup response (GET /api/prices/by-name or /by-number).
 */
export interface CardPrice {
  name?: string
  set: string
  collector_number?: string
  foil: boolean
  currency: string
  price: number | null
}
