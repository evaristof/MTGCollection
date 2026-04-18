import { PAGE_SIZE_OPTIONS, type PageSize } from '../hooks/useTableControls'

interface PaginationBarProps {
  page: number
  pageCount: number
  pageSize: PageSize
  totalCount: number
  onPageChange: (p: number) => void
  onPageSizeChange: (n: PageSize) => void
}

export function PaginationBar({
  page,
  pageCount,
  pageSize,
  totalCount,
  onPageChange,
  onPageSizeChange,
}: PaginationBarProps) {
  const startItem = totalCount === 0 ? 0 : (page - 1) * pageSize + 1
  const endItem = Math.min(page * pageSize, totalCount)

  return (
    <div className="pagination">
      <label className="inline">
        Itens por página:&nbsp;
        <select
          value={pageSize}
          onChange={(e) => onPageSizeChange(Number(e.target.value) as PageSize)}
        >
          {PAGE_SIZE_OPTIONS.map((n) => (
            <option key={n} value={n}>
              {n}
            </option>
          ))}
        </select>
      </label>
      <span className="pagination__info muted">
        {totalCount === 0 ? '0 itens' : `${startItem}–${endItem} de ${totalCount}`}
      </span>
      <div className="pagination__nav">
        <button
          type="button"
          onClick={() => onPageChange(1)}
          disabled={page <= 1}
          aria-label="Primeira página"
        >
          «
        </button>
        <button
          type="button"
          onClick={() => onPageChange(page - 1)}
          disabled={page <= 1}
        >
          ‹ Anterior
        </button>
        <span className="pagination__page">
          Página {page} de {pageCount}
        </span>
        <button
          type="button"
          onClick={() => onPageChange(page + 1)}
          disabled={page >= pageCount}
        >
          Próxima ›
        </button>
        <button
          type="button"
          onClick={() => onPageChange(pageCount)}
          disabled={page >= pageCount}
          aria-label="Última página"
        >
          »
        </button>
      </div>
    </div>
  )
}
