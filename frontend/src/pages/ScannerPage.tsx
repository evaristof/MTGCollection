import { useEffect, useMemo, useState } from 'react'
import { api } from '../api/client'
import type { MagicSet, ScannerMatchResult } from '../types/mtg'
import { createWorker } from 'tesseract.js'

type ScanMode = 'phash' | 'ocr'

interface ScannedCard {
  name: string
  set: string
  collectorNumber: string
  type: string
  mode: ScanMode
  confidence: number
}

export default function ScannerPage() {
  const [scanMode, setScanMode] = useState<ScanMode>('phash')
  const [imageFile, setImageFile] = useState<File | null>(null)
  const [imagePreview, setImagePreview] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const [ocrText, setOcrText] = useState('')
  const [setCode, setSetCode] = useState('')
  const [scannedCards, setScannedCards] = useState<ScannedCard[]>([])
  const [error, setError] = useState<string | null>(null)
  const [sets, setSets] = useState<MagicSet[]>([])

  useEffect(() => {
    void api.listSets().then(setSets).catch(() => {})
  }, [])

  const setOptions = useMemo(
    () =>
      sets
        .slice()
        .sort((a, b) => a.set_code.localeCompare(b.set_code))
        .map((s) => ({ code: s.set_code, label: `${s.set_code} — ${s.set_name}` })),
    [sets],
  )

  const onFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0] ?? null
    setImageFile(file)
    setError(null)
    if (file) {
      const reader = new FileReader()
      reader.onload = () => setImagePreview(reader.result as string)
      reader.readAsDataURL(file)
    } else {
      setImagePreview(null)
    }
  }

  const onScan = async () => {
    if (!imageFile) {
      setError('Selecione uma imagem primeiro.')
      return
    }

    setLoading(true)
    setError(null)

    try {
      if (scanMode === 'phash') {
        const result: ScannerMatchResult = await api.scannerMatch(imageFile)
        if (result.matched && result.card_name) {
          setScannedCards((prev) => [
            ...prev,
            {
              name: result.card_name!,
              set: result.set_code ?? '',
              collectorNumber: result.collector_number ?? '',
              type: '',
              mode: 'phash',
              confidence: result.confidence,
            },
          ])
        } else {
          setError('Nenhuma carta correspondente encontrada. Tente outra imagem ou use o modo OCR.')
        }
      } else {
        const worker = await createWorker('eng')
        try {
          const { data } = await worker.recognize(imageFile)
          setOcrText(data.text.trim())
        } finally {
          await worker.terminate()
        }
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    } finally {
      setLoading(false)
    }
  }

  const onOcrSearch = async () => {
    if (!ocrText.trim()) {
      setError('Digite ou edite o texto reconhecido antes de buscar.')
      return
    }
    if (!setCode) {
      setError('Selecione um set para buscar a carta.')
      return
    }

    setLoading(true)
    setError(null)

    try {
      const card = await api.cardByName(ocrText.trim(), setCode)
      setScannedCards((prev) => [
        ...prev,
        {
          name: card.name,
          set: card.set,
          collectorNumber: card.collector_number,
          type: card.type_line ?? '',
          mode: 'ocr',
          confidence: 1.0,
        },
      ])
      setOcrText('')
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    } finally {
      setLoading(false)
    }
  }

  const onRemoveCard = (index: number) => {
    setScannedCards((prev) => prev.filter((_, i) => i !== index))
  }

  return (
    <section className="page">
      <div className="toolbar">
        <h2>Scanner MTG Cards {loading && <span className="muted">(processando…)</span>}</h2>
      </div>

      {error && <p className="error">{error}</p>}

      {/* Mode selector */}
      <div className="form">
        <h3>Modo de reconhecimento</h3>
        <div className="form__grid">
          <label className="checkbox">
            <input
              type="radio"
              name="scanMode"
              value="phash"
              checked={scanMode === 'phash'}
              onChange={() => setScanMode('phash')}
            />
            <span>Comparação por Imagem (pHash)</span>
          </label>
          <label className="checkbox">
            <input
              type="radio"
              name="scanMode"
              value="ocr"
              checked={scanMode === 'ocr'}
              onChange={() => setScanMode('ocr')}
            />
            <span>OCR (Leitura de Texto)</span>
          </label>
        </div>
      </div>

      {/* Upload area */}
      <div className="form">
        <h3>Upload de imagem</h3>
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

      {/* OCR text + search */}
      {scanMode === 'ocr' && ocrText && (
        <div className="form">
          <h3>Texto reconhecido (editável)</h3>
          <div className="form__grid">
            <label>
              <span>Nome da carta</span>
              <input
                type="text"
                value={ocrText}
                onChange={(e) => setOcrText(e.target.value)}
              />
            </label>
            <label>
              <span>Set</span>
              <select value={setCode} onChange={(e) => setSetCode(e.target.value)}>
                <option value="">-- Selecione um set --</option>
                {setOptions.map((s) => (
                  <option key={s.code} value={s.code}>
                    {s.label}
                  </option>
                ))}
              </select>
            </label>
          </div>
          <div style={{ marginTop: '1rem' }}>
            <button className="btn" onClick={onOcrSearch} disabled={loading}>
              Buscar
            </button>
          </div>
        </div>
      )}

      {/* Scanned cards table */}
      {scannedCards.length > 0 && (
        <div className="form">
          <h3>Cartas escaneadas ({scannedCards.length})</h3>
          <table className="table">
            <thead>
              <tr>
                <th>#</th>
                <th>Nome</th>
                <th>Set</th>
                <th>Tipo / Número</th>
                <th>Modo</th>
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
                  <td>{card.type || card.collectorNumber}</td>
                  <td>{card.mode === 'phash' ? 'pHash' : 'OCR'}</td>
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
