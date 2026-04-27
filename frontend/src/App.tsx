import { useEffect, useState } from 'react'
import { NavLink, Outlet } from 'react-router-dom'
import './App.css'

const STORAGE_KEY = 'mtgcollection.sidebar.collapsed'

function App() {
  const [collapsed, setCollapsed] = useState<boolean>(() => {
    if (typeof window === 'undefined') return false
    return window.localStorage.getItem(STORAGE_KEY) === '1'
  })

  useEffect(() => {
    window.localStorage.setItem(STORAGE_KEY, collapsed ? '1' : '0')
  }, [collapsed])

  return (
    <div className={`app ${collapsed ? 'app--collapsed' : ''}`}>
      <aside className="sidebar" aria-label="Navegação principal">
        <div className="sidebar__top">
          {!collapsed && (
            <div className="sidebar__brand">
              <strong>MTGCollection</strong>
              <img
                src="/mtg-logo.png"
                alt="Magic: The Gathering"
                className="sidebar__brand-logo"
              />
            </div>
          )}
          <button
            type="button"
            className="sidebar__toggle"
            onClick={() => setCollapsed((c) => !c)}
            aria-label={collapsed ? 'Expandir menu' : 'Recolher menu'}
            aria-expanded={!collapsed}
            title={collapsed ? 'Expandir menu' : 'Recolher menu'}
          >
            <span aria-hidden>{collapsed ? '»' : '«'}</span>
          </button>
        </div>
        <nav className="sidebar__nav">
          <NavLink to="/" end title="Home">
            <span className="sidebar__icon" aria-hidden>⌂</span>
            <span className="sidebar__label">Home</span>
          </NavLink>
          <NavLink to="/sets" title="Sets">
            <span className="sidebar__icon" aria-hidden>▦</span>
            <span className="sidebar__label">Sets</span>
          </NavLink>
          <NavLink to="/cards" title="Cartas">
            <span className="sidebar__icon" aria-hidden>♦</span>
            <span className="sidebar__label">Cartas</span>
          </NavLink>
          <NavLink to="/charts" title="Gráficos">
            <span className="sidebar__icon" aria-hidden>📈</span>
            <span className="sidebar__label">Gráficos</span>
          </NavLink>
        </nav>
      </aside>

      <main className="app__main">
        <Outlet />
      </main>
    </div>
  )
}

export default App
