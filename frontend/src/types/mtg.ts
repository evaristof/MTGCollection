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
  /** When true, hidden from the set dropdowns (still shown in the Sets grid). */
  blacklisted?: boolean
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
  /** Location NAME, derived from the LOCATION_ID FK (read-only). */
  localizacao?: string | null
  /** FK into the location catalog (`null` when the card has no location). */
  location_id?: number | null
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
  language: string | null
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
 * Response from POST /api/scanner/match.
 */
export interface ScannerMatchResult {
  matched: boolean
  card_name: string | null
  set_code: string | null
  collector_number: string | null
  confidence: number
  image_url: string | null
}

/**
 * One detected card crop from POST /api/scanner/split: its position in the photo
 * and the crop image as a base64 data URL (no match yet — that's done per-crop
 * afterwards through /match).
 */
export interface ScannerSplitCrop {
  index: number
  crop_image: string
}

/**
 * Response from POST /api/scanner/split — a photo with many cards divided into
 * one crop per detected card.
 */
export interface ScannerSplitResult {
  count: number
  crops: ScannerSplitCrop[]
}

/**
 * Aggregate counts shown on the Magic Data Management screen
 * (GET /api/data-management/stats).
 */
export interface DataManagementStats {
  sets_in_minio: number
  photos_in_minio: number
  cards_in_collection: number
  cards_with_hash: number
}

/**
 * Progress snapshot of an async data-management job
 * (GET /api/data-management/jobs/{id}).
 */
export interface DataJobSnapshot {
  id: string
  type: string
  status: 'PENDING' | 'RUNNING' | 'DONE' | 'FAILED' | 'CANCELLED'
  total: number
  processed: number
  succeeded: number
  skipped: number
  errors: string[]
  message: string | null
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

/**
 * A physical storage location for cards (GET/POST/PUT /api/locations).
 */
export interface Location {
  id: number
  name: string
  description?: string | null
}

/**
 * One entry in GET /api/card-catalog/sets?name=... — a set that contains a
 * printing of the given card, sourced from the CARD_IMAGE_HASH catalog.
 */
export interface CardCatalogSetOption {
  set_code: string
  set_name: string
}

/**
 * Uma diferença apontada pela tela Reconciliação Coleção
 * (POST /api/collection/reconcile).
 */
export interface ReconciliationDiffRow {
  card_name: string
  /** `null` quando o set da planilha não existe na tabela MAGIC_SET. */
  set_code: string | null
  set_name: string | null
  card_number: string | null
  foil: boolean
  /** Idioma canônico ("en" e "English" viram a mesma coisa). */
  language: string
  /** Idioma como está gravado na nossa linha — é o que um update precisa devolver. */
  collection_language: string | null
  location: string
  excel_quantity: number
  collection_quantity: number
  /** Nossas linhas por trás dessa diferença (mais de uma quando há duplicatas). */
  card_ids: number[]
  /** Células da planilha por trás dessa diferença, ex.: "Blue!12". */
  sheet_rows: string[]
}

export interface ReconciliationSummary {
  sheet_rows: number
  expanded_rows: number
  matched: number
  only_in_excel: number
  only_in_collection: number
  quantity_mismatch: number
  locations: number
}

export interface ReconciliationResult {
  only_in_excel: ReconciliationDiffRow[]
  only_in_collection: ReconciliationDiffRow[]
  quantity_mismatch: ReconciliationDiffRow[]
  ignored: string[]
  summary: ReconciliationSummary
}
