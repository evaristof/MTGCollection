/**
 * Shape of a Magic set as returned by GET /api/sets.
 * Matches the Scryfall payload (snake_case) via Jackson's
 * spring.jackson.property-naming-strategy=SNAKE_CASE.
 */
export interface MagicSet {
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
