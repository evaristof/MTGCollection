import { useEffect, useMemo, useRef, useState } from 'react'

export interface SetComboOption {
  code: string
  name: string
}

interface SetComboProps {
  /** Currently selected set_code, or '' when no set is chosen. */
  value: string
  onChange: (code: string) => void
  options: SetComboOption[]
  /** Label shown (and first option) when no set is selected. */
  emptyLabel?: string
  id?: string
  disabled?: boolean
}

/**
 * Custom combobox for picking a set where:
 * - Collapsed: only the set name is shown (or the empty label).
 * - Expanded: a search input filters the list by name or code, and every
 *   option is displayed as "Name — CODE".
 * A native <select> can't render different text for collapsed vs expanded
 * states nor offer typeahead search, which is why we roll our own lightweight
 * dropdown here.
 */
export function SetCombo({
  value,
  onChange,
  options,
  emptyLabel = '— todos —',
  id,
  disabled,
}: SetComboProps) {
  const [open, setOpen] = useState(false)
  const [query, setQuery] = useState('')
  const [activeIdx, setActiveIdx] = useState(0)
  const rootRef = useRef<HTMLDivElement>(null)
  const inputRef = useRef<HTMLInputElement>(null)
  const listRef = useRef<HTMLUListElement>(null)

  const selectedName = useMemo(() => {
    if (value === '') return emptyLabel
    const hit = options.find((o) => o.code === value)
    return hit ? hit.name : value
  }, [value, options, emptyLabel])

  const filteredItems = useMemo<SetComboOption[]>(() => {
    const q = query.trim().toLowerCase()
    // The empty option is always available when there is no search, so the
    // user can clear a selection (e.g. "— todos —").
    if (q === '') return [{ code: '', name: emptyLabel }, ...options]
    return options.filter(
      (o) => o.name.toLowerCase().includes(q) || o.code.toLowerCase().includes(q),
    )
  }, [query, options, emptyLabel])

  // Close when clicking outside.
  useEffect(() => {
    if (!open) return
    function onDown(e: MouseEvent) {
      if (rootRef.current && !rootRef.current.contains(e.target as Node)) {
        setOpen(false)
      }
    }
    document.addEventListener('mousedown', onDown)
    return () => document.removeEventListener('mousedown', onDown)
  }, [open])

  // When opening, reset the query and highlight the currently-selected item
  // (or the first match if the query is set). Also focus the search input.
  useEffect(() => {
    if (!open) return
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setQuery('')
    const idx = filteredItems.findIndex((i) => i.code === value)
    setActiveIdx(idx >= 0 ? idx : 0)
    // Focus on next tick so the input exists in the DOM.
    const t = window.setTimeout(() => inputRef.current?.focus(), 0)
    return () => window.clearTimeout(t)
    // We intentionally only react to `open` so typing in the input doesn't
    // re-focus it on every keystroke.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open])

  // Keep activeIdx within bounds as the filtered list shrinks/grows.
  useEffect(() => {
    if (!open) return
    if (activeIdx > filteredItems.length - 1) {
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setActiveIdx(Math.max(0, filteredItems.length - 1))
    }
  }, [filteredItems, activeIdx, open])

  // Scroll the active item into view when it changes.
  useEffect(() => {
    if (!open) return
    const list = listRef.current
    if (!list) return
    const el = list.children.item(activeIdx) as HTMLElement | null
    el?.scrollIntoView({ block: 'nearest' })
  }, [activeIdx, open])

  function pick(code: string | undefined) {
    if (code === undefined) return
    onChange(code)
    setOpen(false)
  }

  function onButtonKey(e: React.KeyboardEvent) {
    if (open) return
    if (e.key === 'Enter' || e.key === ' ' || e.key === 'ArrowDown') {
      e.preventDefault()
      setOpen(true)
    }
  }

  function onInputKey(e: React.KeyboardEvent) {
    switch (e.key) {
      case 'Escape':
        e.preventDefault()
        setOpen(false)
        break
      case 'ArrowDown':
        e.preventDefault()
        setActiveIdx((i) => Math.min(filteredItems.length - 1, i + 1))
        break
      case 'ArrowUp':
        e.preventDefault()
        setActiveIdx((i) => Math.max(0, i - 1))
        break
      case 'Home':
        e.preventDefault()
        setActiveIdx(0)
        break
      case 'End':
        e.preventDefault()
        setActiveIdx(filteredItems.length - 1)
        break
      case 'Enter':
        e.preventDefault()
        pick(filteredItems[activeIdx]?.code)
        break
      default:
        break
    }
  }

  const listId = id ? `${id}-list` : undefined
  const searchId = id ? `${id}-search` : undefined

  return (
    <div className="combo" ref={rootRef}>
      <button
        type="button"
        id={id}
        className="combo__button"
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-controls={listId}
        disabled={disabled}
        onClick={() => setOpen((v) => !v)}
        onKeyDown={onButtonKey}
      >
        <span className="combo__label">{selectedName}</span>
        <span className="combo__arrow" aria-hidden="true">
          ▾
        </span>
      </button>
      {open && (
        <div className="combo__popover">
          <input
            ref={inputRef}
            id={searchId}
            className="combo__search"
            type="text"
            role="combobox"
            aria-controls={listId}
            aria-autocomplete="list"
            aria-expanded="true"
            placeholder="Buscar por nome ou código…"
            value={query}
            onChange={(e) => {
              setQuery(e.target.value)
              setActiveIdx(0)
            }}
            onKeyDown={onInputKey}
          />
          <ul
            className="combo__list"
            role="listbox"
            id={listId}
            ref={listRef}
            tabIndex={-1}
          >
            {filteredItems.length === 0 && (
              <li className="combo__option combo__option--empty" aria-disabled="true">
                Nenhum set encontrado
              </li>
            )}
            {filteredItems.map((item, idx) => {
              const selected = item.code === value
              const active = idx === activeIdx
              return (
                <li
                  key={item.code === '' ? '__empty__' : item.code}
                  role="option"
                  aria-selected={selected}
                  className={
                    'combo__option' +
                    (active ? ' combo__option--active' : '') +
                    (selected ? ' combo__option--selected' : '')
                  }
                  onMouseEnter={() => setActiveIdx(idx)}
                  onMouseDown={(e) => {
                    // Prevent the blur that would close the list before click fires.
                    e.preventDefault()
                    pick(item.code)
                  }}
                >
                  {item.code === '' ? item.name : `${item.name} — ${item.code}`}
                </li>
              )
            })}
          </ul>
        </div>
      )}
    </div>
  )
}
