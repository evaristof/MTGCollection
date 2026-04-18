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
 * - Expanded: every option is displayed as "Name — CODE".
 * A native <select> can't render different text for collapsed vs expanded
 * states, which is why we roll our own lightweight dropdown here.
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
  const [activeIdx, setActiveIdx] = useState(0)
  const rootRef = useRef<HTMLDivElement>(null)

  const items = useMemo<SetComboOption[]>(
    () => [{ code: '', name: emptyLabel }, ...options],
    [emptyLabel, options],
  )

  const selectedName = useMemo(() => {
    if (value === '') return emptyLabel
    const hit = options.find((o) => o.code === value)
    return hit ? hit.name : value
  }, [value, options, emptyLabel])

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

  // When opening, focus the currently-selected item.
  useEffect(() => {
    if (!open) return
    const idx = items.findIndex((i) => i.code === value)
    setActiveIdx(idx >= 0 ? idx : 0)
  }, [open, items, value])

  function pick(code: string) {
    onChange(code)
    setOpen(false)
  }

  function onKey(e: React.KeyboardEvent) {
    if (!open) {
      if (e.key === 'Enter' || e.key === ' ' || e.key === 'ArrowDown') {
        e.preventDefault()
        setOpen(true)
      }
      return
    }
    switch (e.key) {
      case 'Escape':
        e.preventDefault()
        setOpen(false)
        break
      case 'ArrowDown':
        e.preventDefault()
        setActiveIdx((i) => Math.min(items.length - 1, i + 1))
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
        setActiveIdx(items.length - 1)
        break
      case 'Enter':
      case ' ':
        e.preventDefault()
        pick(items[activeIdx]?.code ?? '')
        break
      default:
        break
    }
  }

  const listId = id ? `${id}-list` : undefined

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
        onKeyDown={onKey}
      >
        <span className="combo__label">{selectedName}</span>
        <span className="combo__arrow" aria-hidden="true">
          ▾
        </span>
      </button>
      {open && (
        <ul
          className="combo__list"
          role="listbox"
          id={listId}
          onKeyDown={onKey}
          tabIndex={-1}
        >
          {items.map((item, idx) => {
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
      )}
    </div>
  )
}
