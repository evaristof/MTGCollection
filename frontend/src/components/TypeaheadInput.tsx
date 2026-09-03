import { useEffect, useMemo, useRef, useState } from 'react'

interface TypeaheadInputProps {
  value: string
  onChange: (value: string) => void
  /** Fired only when the user explicitly picks a suggestion (click / Enter on a highlighted option). */
  onSelect?: (value: string) => void
  options: string[]
  placeholder?: string
  disabled?: boolean
  id?: string
  /** Max number of suggestions shown at once. */
  limit?: number
  onFocus?: () => void
  onBlur?: () => void
  onMouseEnter?: () => void
  onMouseLeave?: () => void
  className?: string
  style?: React.CSSProperties
}

/**
 * Free-text input with a filtered suggestion dropdown — unlike {@link SetCombo}
 * (which hides the search box behind a button), the text is always directly
 * editable, which is what makes fast bulk entry practical: type a few letters,
 * see matches, keep typing or pick one.
 *
 * The full `options` list is filtered client-side (case-insensitive
 * substring match) on every keystroke, capped to `limit` results. Any value
 * can be typed and kept even if it doesn't match an option ("freeSolo") —
 * callers that need to restrict to the list validate on submit instead.
 */
export function TypeaheadInput({
  value,
  onChange,
  onSelect,
  options,
  placeholder,
  disabled,
  id,
  limit = 25,
  onFocus,
  onBlur,
  onMouseEnter,
  onMouseLeave,
  className,
  style,
}: TypeaheadInputProps) {
  const [open, setOpen] = useState(false)
  const [activeIdx, setActiveIdx] = useState(0)
  const rootRef = useRef<HTMLDivElement>(null)
  const inputRef = useRef<HTMLInputElement>(null)
  const listRef = useRef<HTMLUListElement>(null)

  const filtered = useMemo(() => {
    const q = value.trim().toLowerCase()
    if (q === '') return options.slice(0, limit)
    return options.filter((o) => o.toLowerCase().includes(q)).slice(0, limit)
  }, [value, options, limit])

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

  useEffect(() => {
    if (activeIdx > filtered.length - 1) {
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setActiveIdx(Math.max(0, filtered.length - 1))
    }
  }, [filtered, activeIdx])

  useEffect(() => {
    if (!open) return
    const list = listRef.current
    if (!list) return
    const el = list.children.item(activeIdx) as HTMLElement | null
    el?.scrollIntoView({ block: 'nearest' })
  }, [activeIdx, open])

  function pick(option: string) {
    onChange(option)
    onSelect?.(option)
    setOpen(false)
  }

  function onKeyDown(e: React.KeyboardEvent<HTMLInputElement>) {
    if (!open && (e.key === 'ArrowDown' || e.key === 'ArrowUp')) {
      e.preventDefault()
      setOpen(true)
      return
    }
    if (!open) return
    switch (e.key) {
      case 'Escape':
        setOpen(false)
        break
      case 'ArrowDown':
        e.preventDefault()
        setActiveIdx((i) => Math.min(filtered.length - 1, i + 1))
        break
      case 'ArrowUp':
        e.preventDefault()
        setActiveIdx((i) => Math.max(0, i - 1))
        break
      case 'Enter':
        if (filtered[activeIdx] !== undefined) {
          e.preventDefault()
          pick(filtered[activeIdx])
        }
        break
      default:
        break
    }
  }

  const listId = id ? `${id}-list` : undefined

  return (
    <div className={`typeahead ${className ?? ''}`} ref={rootRef} style={style}>
      <input
        ref={inputRef}
        id={id}
        type="text"
        role="combobox"
        aria-expanded={open}
        aria-controls={listId}
        aria-autocomplete="list"
        autoComplete="off"
        className="typeahead__input"
        value={value}
        placeholder={placeholder}
        disabled={disabled}
        onChange={(e) => {
          onChange(e.target.value)
          setActiveIdx(0)
          setOpen(true)
        }}
        onFocus={() => {
          setOpen(true)
          onFocus?.()
        }}
        onBlur={onBlur}
        onMouseEnter={onMouseEnter}
        onMouseLeave={onMouseLeave}
        onKeyDown={onKeyDown}
      />
      {open && filtered.length > 0 && (
        <ul className="typeahead__list" role="listbox" id={listId} ref={listRef}>
          {filtered.map((option, idx) => (
            <li
              key={option}
              role="option"
              aria-selected={option === value}
              className={
                'typeahead__option' +
                (idx === activeIdx ? ' typeahead__option--active' : '')
              }
              onMouseEnter={() => setActiveIdx(idx)}
              onMouseDown={(e) => {
                e.preventDefault()
                pick(option)
              }}
            >
              {option}
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
