import { useEffect, useRef, useState, useCallback } from 'react'
import { api } from '../api/client'

interface Props {
  cardId: number
  cardName: string
}

const MARGIN = 12
const SINGLE_WIDTH = 340
const DUAL_WIDTH = 270
const ZOOM_STEP = 0.1
const ZOOM_MIN = 0.5
const ZOOM_MAX = 2.5

/**
 * Shows the card image in a floating tooltip anchored to the mouse pointer.
 * For double-faced cards, both faces are displayed side by side.
 * Repositions automatically to avoid being clipped by viewport edges.
 * Scroll the mouse wheel to zoom in/out.
 */
export function CardImageTooltip({ cardId, cardName }: Props) {
  const [visible, setVisible] = useState(false)
  const [faceCount, setFaceCount] = useState(1)
  const [loadedFaces, setLoadedFaces] = useState<Set<number>>(new Set())
  const [erroredFaces, setErroredFaces] = useState<Set<number>>(new Set())
  const [zoom, setZoom] = useState(1)
  const tooltipRef = useRef<HTMLDivElement>(null)
  const faceCountFetched = useRef(false)
  const mousePos = useRef({ x: 0, y: 0 })

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
  }, [visible, zoom, repositionTooltip])

  useEffect(() => {
    if (!visible) return
    const el = tooltipRef.current
    if (!el) return
    const handler = (e: WheelEvent) => {
      e.preventDefault()
      e.stopPropagation()
      setZoom((prev) => {
        const delta = e.deltaY < 0 ? ZOOM_STEP : -ZOOM_STEP
        return Math.min(ZOOM_MAX, Math.max(ZOOM_MIN, prev + delta))
      })
    }
    el.addEventListener('wheel', handler, { passive: false })
    return () => el.removeEventListener('wheel', handler)
  }, [visible])

  useEffect(() => {
    if (!visible || faceCountFetched.current) return
    api.cardImageInfo(cardId).then((info) => {
      faceCountFetched.current = true
      setFaceCount(info.face_count)
    }).catch(() => {
      faceCountFetched.current = false
      setFaceCount(1)
    })
  }, [visible, cardId])

  const onEnter = () => {
    setVisible(true)
    setLoadedFaces(new Set())
    setErroredFaces(new Set())
    setZoom(1)
  }

  const onLeave = () => {
    setVisible(false)
  }

  const onFaceLoad = (face: number) => {
    setLoadedFaces((prev) => new Set(prev).add(face))
  }

  const onFaceError = (face: number) => {
    setErroredFaces((prev) => new Set(prev).add(face))
  }

  const isDoubleFaced = faceCount > 1
  const baseWidth = isDoubleFaced ? DUAL_WIDTH : SINGLE_WIDTH
  const imgWidth = Math.round(baseWidth * zoom)

  return (
    <span
      onMouseEnter={onEnter}
      onMouseLeave={onLeave}
      style={{ cursor: 'pointer' }}
    >
      {cardName}
      {visible && (
        <div
          ref={tooltipRef}
          style={{
            position: 'fixed',
            left: -9999,
            top: -9999,
            zIndex: 9999,
            pointerEvents: 'auto',
            background: 'var(--bg-alt)',
            border: '1px solid var(--border)',
            borderRadius: 8,
            boxShadow: '0 4px 20px rgba(0,0,0,0.25)',
            padding: 4,
            display: 'flex',
            gap: 4,
          }}
        >
          {Array.from({ length: faceCount }, (_, i) => (
            <div key={i} style={{ position: 'relative' }}>
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
                src={api.cardImageUrl(cardId, i)}
                alt={`${cardName}${isDoubleFaced ? ` (face ${i + 1})` : ''}`}
                onLoad={() => onFaceLoad(i)}
                onError={() => onFaceError(i)}
                style={{
                  display: loadedFaces.has(i) && !erroredFaces.has(i) ? 'block' : 'none',
                  width: imgWidth,
                  borderRadius: 6,
                  transition: 'width 0.1s ease-out',
                }}
              />
            </div>
          ))}
        </div>
      )}
    </span>
  )
}
