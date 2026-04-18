import { useEffect, useMemo, useState } from 'react'

export type SortDirection = 'asc' | 'desc'

export const PAGE_SIZE_OPTIONS = [25, 50, 100, 200] as const
export type PageSize = (typeof PAGE_SIZE_OPTIONS)[number]

export interface UseTableControlsOptions<T> {
  rows: T[]
  initialSortKey: keyof T
  initialDirection?: SortDirection
  initialPageSize?: PageSize
  /**
   * When this value changes, the current page resets to 1. Use it to tie the
   * page reset to the current filter / search string.
   */
  resetKey?: unknown
  /**
   * Optional per-column comparators for fields that aren't simple scalars.
   * Each comparator receives two rows and must return a negative, zero, or
   * positive number following the standard Array.prototype.sort contract.
   */
  comparators?: Partial<Record<keyof T, (a: T, b: T) => number>>
}

export interface UseTableControlsResult<T> {
  pageRows: T[]
  totalCount: number
  pageCount: number
  page: number
  setPage: (p: number) => void
  pageSize: PageSize
  setPageSize: (n: PageSize) => void
  sortKey: keyof T
  sortDirection: SortDirection
  toggleSort: (k: keyof T) => void
}

function defaultCompare<T>(a: T, b: T, key: keyof T): number {
  const av = a[key] as unknown
  const bv = b[key] as unknown
  if (av == null && bv == null) return 0
  if (av == null) return 1
  if (bv == null) return -1
  if (typeof av === 'number' && typeof bv === 'number') return av - bv
  if (typeof av === 'boolean' && typeof bv === 'boolean') {
    return av === bv ? 0 : av ? 1 : -1
  }
  return String(av).localeCompare(String(bv), undefined, {
    numeric: true,
    sensitivity: 'base',
  })
}

export function useTableControls<T>(opts: UseTableControlsOptions<T>): UseTableControlsResult<T> {
  const {
    rows,
    initialSortKey,
    initialDirection = 'asc',
    initialPageSize = 25,
    resetKey,
    comparators,
  } = opts

  const [sortKey, setSortKey] = useState<keyof T>(initialSortKey)
  const [sortDirection, setSortDirection] = useState<SortDirection>(initialDirection)
  const [pageSize, setPageSize] = useState<PageSize>(initialPageSize)
  const [page, setPage] = useState(1)

  // Reset to first page whenever the underlying data, filter, or page size
  // changes so the user doesn't stare at an empty page past the new total.
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setPage(1)
  }, [resetKey, pageSize, rows.length])

  const sorted = useMemo(() => {
    const cmp = comparators?.[sortKey]
    const copy = rows.slice()
    copy.sort((a, b) => {
      const n = cmp ? cmp(a, b) : defaultCompare(a, b, sortKey)
      return sortDirection === 'asc' ? n : -n
    })
    return copy
  }, [rows, sortKey, sortDirection, comparators])

  const totalCount = sorted.length
  const pageCount = Math.max(1, Math.ceil(totalCount / pageSize))
  const currentPage = Math.min(page, pageCount)

  const pageRows = useMemo(() => {
    const start = (currentPage - 1) * pageSize
    return sorted.slice(start, start + pageSize)
  }, [sorted, currentPage, pageSize])

  const toggleSort = (k: keyof T) => {
    if (k === sortKey) {
      setSortDirection((d) => (d === 'asc' ? 'desc' : 'asc'))
    } else {
      setSortKey(k)
      setSortDirection('asc')
    }
  }

  return {
    pageRows,
    totalCount,
    pageCount,
    page: currentPage,
    setPage,
    pageSize,
    setPageSize,
    sortKey,
    sortDirection,
    toggleSort,
  }
}
