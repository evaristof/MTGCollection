import { useEffect, useRef, useState, useCallback } from 'react'
import { api } from '../api/client'

interface Props {
  /** Text rendered as the hover target when no `children` are given. */
  cardName: string
  /**
   * Collection card id — images are served by
   * {@code /api/collection/cards/{id}/image} and the face count comes from
   * {@code /image/info}. Mutually exclusive with `resolveImageUrls`.
   */
  cardId?: number
  /**
   * Image source for cards that are not in the collection (yet) — e.g. the
   * "Cadastro Cartas" form, which previews the scanner's reference image by
   * (set, collector number). Called on every hover; return an empty array
   * when there is nothing to show.
   */
  resolveImageUrls?: () => Promise<string[]>
  /** Custom hover target (an input, a table cell…). Defaults to `cardName`. */
  children?: React.ReactNode
}

const MARGIN = 12
const SINGLE_WIDTH = 340
const DUAL_WIDTH = 270
const ZOOM_STEP = 0.1
const ZOOM_MIN = 0.5
const ZOOM_MAX = 2.5
const CARD_ASPECT = 7 / 5

/**
 * Shows the card image in a floating tooltip anchored to the mouse pointer.
 * For double-faced cards, both faces are displayed side by side.
 * Repositions automatically to avoid being clipped by viewport edges.
 * Scroll the mouse wheel to zoom in/out.
 */
export function CardImageTooltip({ cardId, cardName, resolveImageUrls, children }: Props) {
  const [visible, setVisible] = useState(false)
  const [faceCount, setFaceCount] = useState(1)
  const [loadedFaces, setLoadedFaces] = useState<Set<number>>(new Set())
  const [erroredFaces, setErroredFaces] = useState<Set<number>>(new Set())
  const [zoom, setZoom] = useState(1)
  const [layout, setLayout] = useState('normal')
  // Resolved URLs when `resolveImageUrls` is used: `null` = still resolving.
  const [urls, setUrls] = useState<string[] | null>(null)
  const tooltipRef = useRef<HTMLDivElement>(null)
  const wrapperRef = useRef<HTMLSpanElement>(null)
  const faceCountFetched = useRef(false)
  const mousePos = useRef({ x: 0, y: 0 })
  // Guards against a slow resolve landing after the pointer left / moved on.
  const resolveSeq = useRef(0)

  const repositionTooltip = useCallback(() => {
    const el = tooltipRef.current
    if (!el) return

    const vw = window.innerWidth
    const vh = window.innerHeight
    const tw = el.offsetWidth
    const th = el.offsetHeight
    const mx = mousePos.current.x
    const my = mousePos.current.y

    let x = mx + MARGIN
    let y = my + MARGIN

    if (x + tw > vw - MARGIN) {
      x = mx - tw - MARGIN
    }
    if (y + th > vh - MARGIN) {
      y = my - th - MARGIN
    }

    x = Math.max(MARGIN, x)
    y = Math.max(MARGIN, y)

    el.style.left = `${x}px`
    el.style.top = `${y}px`
  }, [])

  useEffect(() => {
    if (!visible) return
    const handler = (e: MouseEvent) => {
      mousePos.current = { x: e.clientX, y: e.clientY }
      repositionTooltip()
    }
    document.addEventListener('mousemove', handler)
    return () => document.removeEventListener('mousemove', handler)
  }, [visible, repositionTooltip])

  useEffect(() => {
    if (!visible) return
    repositionTooltip()
  }, [visible, zoom, urls, repositionTooltip])

  useEffect(() => {
    if (!visible) return
    const wrapper = wrapperRef.current
    if (!wrapper) return
    const handler = (e: WheelEvent) => {
      e.preventDefault()
      e.stopPropagation()
      setZoom((prev) => {
        const delta = e.deltaY < 0 ? ZOOM_STEP : -ZOOM_STEP
        return Math.min(ZOOM_MAX, Math.max(ZOOM_MIN, prev + delta))
      })
    }
    wrapper.addEventListener('wheel', handler, { passive: false })
    return () => wrapper.removeEventListener('wheel', handler)
  }, [visible])

  useEffect(() => {
    if (!visible || cardId == null || faceCountFetched.current) return
    api.cardImageInfo(cardId).then((info) => {
      faceCountFetched.current = true
      setFaceCount(info.face_count)
      setLayout(info.layout)
    }).catch(() => {
      faceCountFetched.current = false
      setFaceCount(1)
      setLayout('normal')
    })
  }, [visible, cardId])

  const onEnter = () => {
    setVisible(true)
    setLoadedFaces(new Set())
    setErroredFaces(new Set())
    setZoom(1)
    if (resolveImageUrls) {
      // Resolved per hover: the card under the pointer changes as the user
      // types, so there is nothing stable to cache at this level (callers
      // that hit the network memoize it themselves).
      setUrls(null)
      const seq = ++resolveSeq.current
      void resolveImageUrls()
        .then((resolved) => {
          if (resolveSeq.current === seq) setUrls(resolved)
        })
        .catch(() => {
          if (resolveSeq.current === seq) setUrls([])
        })
    }
  }

  const onLeave = () => {
    resolveSeq.current++
    setVisible(false)
  }

  const onFaceLoad = (face: number) => {
    setLoadedFaces((prev) => new Set(prev).add(face))
  }

  const onFaceError = (face: number) => {
    setErroredFaces((prev) => new Set(prev).add(face))
  }

  const byUrl = resolveImageUrls != null
  const resolving = byUrl && urls == null
  const faces = byUrl ? (urls?.length ?? 0) : faceCount
  const isSplit = layout === 'split'
  const isDoubleFaced = faces > 1
  const baseWidth = isDoubleFaced ? DUAL_WIDTH : SINGLE_WIDTH
  const imgWidth = Math.round(baseWidth * zoom)
  const imgHeight = Math.round(imgWidth * CARD_ASPECT)
  const rotatedW = isSplit ? imgHeight : imgWidth
  const rotatedH = isSplit ? imgWidth : imgHeight
  const faceUrl = (face: number) =>
    byUrl ? (urls?.[face] ?? '') : api.cardImageUrl(cardId as number, face)
  // Nothing to show: no card selected yet, or no reference image for it.
  const empty = byUrl && !resolving && faces === 0

  return (
    <span
      ref={wrapperRef}
      onMouseEnter={onEnter}
      onMouseLeave={onLeave}
      // Wrapping a form field / table cell: behave like a block so the child
      // keeps its own width. As a plain label it stays inline and hoverable.
      style={children ? { display: 'block' } : { cursor: 'pointer' }}
    >
      {children ?? cardName}
      {visible && !empty && (
        <div
          ref={tooltipRef}
          style={{
            position: 'fixed',
            left: -9999,
            top: -9999,
            zIndex: 9999,
            pointerEvents: 'none',
            background: 'var(--bg-alt)',
            border: '1px solid var(--border)',
            borderRadius: 8,
            boxShadow: '0 4px 20px rgba(0,0,0,0.25)',
            padding: 4,
            display: 'flex',
            gap: 4,
          }}
        >
          {resolving && (
            <div style={{ color: 'var(--muted)', padding: '24px 16px', fontSize: 13 }}>
              Carregando…
            </div>
          )}
          {!resolving &&
            Array.from({ length: faces }, (_, i) => (
              <div
                key={i}
                style={{
                  position: 'relative',
                  width: rotatedW,
                  height: rotatedH,
                  overflow: 'hidden',
                }}
              >
                {!loadedFaces.has(i) && !erroredFaces.has(i) && (
                  <div
                    style={{
                      color: 'var(--muted)',
                      padding: '24px 16px',
                      fontSize: 13,
                    }}
                  >
                    Carregando…
                  </div>
                )}
                {erroredFaces.has(i) && (
                  <div
                    style={{
                      color: 'var(--danger)',
                      padding: '24px 16px',
                      fontSize: 13,
                    }}
                  >
                    Imagem indisponível
                  </div>
                )}
                <img
                  src={faceUrl(i)}
                  alt={`${cardName}${isDoubleFaced ? ` (face ${i + 1})` : ''}`}
                  onLoad={() => onFaceLoad(i)}
                  onError={() => onFaceError(i)}
                  style={{
                    display: loadedFaces.has(i) && !erroredFaces.has(i) ? 'block' : 'none',
                    width: imgWidth,
                    borderRadius: 6,
                    transition: 'width 0.1s ease-out',
                    ...(isSplit ? {
                      transform: 'rotate(90deg)',
                      transformOrigin: 'top left',
                      position: 'absolute',
                      top: 0,
                      left: imgHeight,
                    } : {}),
                  }}
                />
              </div>
            ))}
        </div>
      )}
    </span>
  )
}
