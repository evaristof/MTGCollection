import type { CSSProperties } from 'react'
import type { SortDirection } from '../hooks/useTableControls'

interface SortableThProps<T> {
  label: string
  field: keyof T
  sortKey: keyof T
  sortDirection: SortDirection
  onToggle: (k: keyof T) => void
  style?: CSSProperties
}

export function SortableTh<T>({
  label,
  field,
  sortKey,
  sortDirection,
  onToggle,
  style,
}: SortableThProps<T>) {
  const active = field === sortKey
  const arrow = active ? (sortDirection === 'asc' ? '▲' : '▼') : '↕'
  const ariaSort: 'ascending' | 'descending' | 'none' = active
    ? sortDirection === 'asc'
      ? 'ascending'
      : 'descending'
    : 'none'
  return (
    <th style={style} aria-sort={ariaSort}>
      <button
        type="button"
        className={`sort-th${active ? ' sort-th--active' : ''}`}
        onClick={() => onToggle(field)}
      >
        <span>{label}</span>
        <span className="sort-th__arrow" aria-hidden="true">
          {arrow}
        </span>
      </button>
    </th>
  )
}
