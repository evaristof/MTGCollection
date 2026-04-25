import { useEffect, useRef, useState } from 'react'
import { api } from '../api/client'

interface Props {
  cardId: number
  cardName: string
}

/**
 * Shows the card image in a floating tooltip anchored to the mouse pointer.
 * For double-faced cards, both faces are displayed side by side.
 */
export function CardImageTooltip({ cardId, cardName }: Props) {
  const [visible, setVisible] = useState(false)
  const [faceCount, setFaceCount] = useState(1)
  const [loadedFaces, setLoadedFaces] = useState<Set<number>>(new Set())
  const [erroredFaces, setErroredFaces] = useState<Set<number>>(new Set())
  const tooltipRef = useRef<HTMLDivElement>(null)
  const faceCountFetched = useRef(false)

  useEffect(() => {
    if (!visible) return
    const handler = (e: MouseEvent) => {
      if (tooltipRef.current) {
        tooltipRef.current.style.left = `${e.clientX + 16}px`
        tooltipRef.current.style.top = `${e.clientY + 16}px`
      }
    }
    document.addEventListener('mousemove', handler)
    return () => document.removeEventListener('mousemove', handler)
  }, [visible])

  useEffect(() => {
    if (!visible || faceCountFetched.current) return
    faceCountFetched.current = true
    api.cardImageInfo(cardId).then((info) => {
      setFaceCount(info.face_count)
    }).catch(() => {
      setFaceCount(1)
    })
  }, [visible, cardId])

  const onEnter = () => {
    setVisible(true)
    setLoadedFaces(new Set())
    setErroredFaces(new Set())
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
  const imgWidth = isDoubleFaced ? 200 : 250

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
                }}
              />
            </div>
          ))}
        </div>
      )}
    </span>
  )
}
