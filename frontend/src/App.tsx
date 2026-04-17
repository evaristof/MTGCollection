import { useCallback, useEffect, useState } from 'react'
import { api } from './api/client'
import type { MagicSet } from './types/mtg'
import './App.css'

function App() {
  const [sets, setSets] = useState<MagicSet[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const loadSets = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const data = await api.listSets()
      setSets(data)
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    // Initial fetch on mount. The setState calls inside `loadSets` are wrapped
    // in async code (awaited fetch), so they happen after the current render
    // has committed — the new React 19 rule is a safety net aimed at
    // synchronous setState-in-effect patterns.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void loadSets()
  }, [loadSets])

  return (
    <div className="app">
      <header className="app__header">
        <h1>MTGCollection</h1>
        <p>
          Front-end inicial em React + Vite + TypeScript. Consome o backend
          Spring Boot em <code>/api</code> (proxy Vite &rarr;{' '}
          <code>http://localhost:8080</code>).
        </p>
      </header>

      <main className="app__main">
        <section>
          <div className="toolbar">
            <h2>Sets</h2>
            <button onClick={() => void loadSets()} disabled={loading}>
              {loading ? 'Carregando...' : 'Recarregar'}
            </button>
          </div>

          {error && <p className="error">Erro: {error}</p>}

          {!error && !loading && sets.length === 0 && (
            <p>Nenhum set retornado. Verifique se o backend está rodando.</p>
          )}

          {sets.length > 0 && (
            <table>
              <thead>
                <tr>
                  <th>Code</th>
                  <th>Name</th>
                  <th>Released</th>
                  <th>Type</th>
                  <th>Cards</th>
                </tr>
              </thead>
              <tbody>
                {sets.slice(0, 50).map((s) => (
                  <tr key={s.code}>
                    <td>{s.code}</td>
                    <td>{s.name}</td>
                    <td>{s.released_at ?? '-'}</td>
                    <td>{s.set_type ?? '-'}</td>
                    <td>{s.card_count ?? '-'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          {sets.length > 50 && (
            <p className="muted">
              Exibindo os primeiros 50 de {sets.length} sets.
            </p>
          )}
        </section>
      </main>
    </div>
  )
}

export default App
