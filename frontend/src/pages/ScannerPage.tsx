import { useEffect, useState } from 'react'
import { api } from '../api/client'
import type { ScannerMatchResult } from '../types/mtg'

interface ScannedCard {
  name: string
  set: string
  collectorNumber: string
  confidence: number
}

export default function ScannerPage() {
  const [imageFile, setImageFile] = useState<File | null>(null)
  const [imagePreview, setImagePreview] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const [scannedCards, setScannedCards] = useState<ScannedCard[]>([])
  const [error, setError] = useState<string | null>(null)
  const [populateStatus, setPopulateStatus] = useState<string | null>(null)

  useEffect(() => {
    return () => {
      if (imagePreview) {
        URL.revokeObjectURL(imagePreview)
      }
    }
  }, [imagePreview])

  const onFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0] ?? null
    setImageFile(file)
    setError(null)
    if (imagePreview) {
      URL.revokeObjectURL(imagePreview)
    }
    setImagePreview(file ? URL.createObjectURL(file) : null)
  }

  const onScan = async () => {
    if (!imageFile) {
      setError('Selecione uma imagem primeiro.')
      return
    }

    setLoading(true)
    setError(null)

    try {
      const result: ScannerMatchResult = await api.scannerMatch(imageFile)
      if (result.matched && result.card_name) {
        setScannedCards((prev) => [
          ...prev,
          {
            name: result.card_name!,
            set: result.set_code ?? '',
            collectorNumber: result.collector_number ?? '',
            confidence: result.confidence,
          },
        ])
      } else {
        setError('Nenhuma carta correspondente encontrada. Tente outra foto mais reta ou repopule a base de hashes do MinIO.')
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    } finally {
      setLoading(false)
    }
  }

  const onRemoveCard = (index: number) => {
    setScannedCards((prev) => prev.filter((_, i) => i !== index))
  }

  const onPopulateHashes = async () => {
    setPopulateStatus('Populando hashes a partir das imagens do MinIO…')
    setError(null)
    try {
      await api.scannerPopulateHashes()
      setPopulateStatus('Processo de população de hashes iniciado em background. Aguarde alguns minutos e tente escanear.')
    } catch (err) {
      setPopulateStatus(null)
      setError(err instanceof Error ? err.message : String(err))
    }
  }

  return (
    <section className="page">
      <div className="toolbar">
        <h2>Scanner MTG Cards {loading && <span className="muted">(processando…)</span>}</h2>
      </div>

      {error && <p className="error">{error}</p>}
      {populateStatus && <p className="muted">{populateStatus}</p>}

      <div className="form">
        <h3>Base de referência</h3>
        <p className="muted">
          Popula a tabela de hashes a partir das imagens já armazenadas no MinIO.
        </p>
        <button className="btn" onClick={onPopulateHashes} disabled={loading || populateStatus !== null}>
          Popular Hashes do MinIO
        </button>
      </div>

      <div className="form">
        <h3>Reconhecimento por imagem</h3>
        <p className="muted">
          Usa pHash para shortlist e OpenCV ORB para validar a melhor carta mesmo com foto de celular.
        </p>
        <div className="form__grid">
          <label>
            <span>Selecionar imagem</span>
            <input type="file" accept="image/*" capture="environment" onChange={onFileChange} />
          </label>
        </div>

        {imagePreview && (
          <div style={{ marginTop: '1rem' }}>
            <img
              src={imagePreview}
              alt="Preview"
              style={{ maxWidth: '300px', maxHeight: '300px', borderRadius: '8px' }}
            />
          </div>
        )}

        <div style={{ marginTop: '1rem' }}>
          <button className="btn" onClick={onScan} disabled={loading || !imageFile}>
            {loading ? 'Processando…' : 'Escanear'}
          </button>
        </div>
      </div>

      {scannedCards.length > 0 && (
        <div className="form">
          <h3>Cartas escaneadas ({scannedCards.length})</h3>
          <table className="table">
            <thead>
              <tr>
                <th>#</th>
                <th>Nome</th>
                <th>Set</th>
                <th>Número</th>
                <th>Confiança</th>
                <th>Ações</th>
              </tr>
            </thead>
            <tbody>
              {scannedCards.map((card, i) => (
                <tr key={i}>
                  <td>{i + 1}</td>
                  <td>{card.name}</td>
                  <td>{card.set}</td>
                  <td>{card.collectorNumber}</td>
                  <td>{(card.confidence * 100).toFixed(0)}%</td>
                  <td>
                    <button className="btn btn--danger btn--sm" onClick={() => onRemoveCard(i)}>
                      Remover
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}
