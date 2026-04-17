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
