import { useEffect, useRef, useState } from 'react'
import { api } from '../api/client'

interface Props {
  cardId: number
  cardName: string
}

/**
 * Shows the card image in a floating tooltip anchored to the mouse pointer.
 * The image URL triggers the backend on-demand fetch (Scryfall → MinIO cache).
 */
export function CardImageTooltip({ cardId, cardName }: Props) {
  const [visible, setVisible] = useState(false)
  const [loaded, setLoaded] = useState(false)
  const [errored, setErrored] = useState(false)
  const tooltipRef = useRef<HTMLDivElement>(null)

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

  const onEnter = () => {
    setVisible(true)
    setLoaded(false)
    setErrored(false)
  }

  const onLeave = () => {
    setVisible(false)
  }

  const imgUrl = api.cardImageUrl(cardId)

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
            background: '#1a1a2e',
            borderRadius: 8,
            boxShadow: '0 4px 20px rgba(0,0,0,0.5)',
            padding: 4,
            maxWidth: 260,
          }}
        >
          {!loaded && !errored && (
            <div style={{ color: '#aaa', padding: '24px 16px', fontSize: 13 }}>
              Carregando…
            </div>
          )}
          {errored && (
            <div style={{ color: '#f66', padding: '24px 16px', fontSize: 13 }}>
              Imagem indisponível
            </div>
          )}
          <img
            src={imgUrl}
            alt={cardName}
            onLoad={() => setLoaded(true)}
            onError={() => setErrored(true)}
            style={{
              display: loaded && !errored ? 'block' : 'none',
              width: 250,
              borderRadius: 6,
            }}
          />
        </div>
      )}
    </span>
  )
}
