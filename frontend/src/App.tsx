import { NavLink, Outlet } from 'react-router-dom'
import './App.css'

function App() {
  return (
    <div className="app">
      <header className="app__header">
        <div className="app__brand">
          <h1>MTGCollection</h1>
          <p className="muted">Gestão da sua coleção de Magic: The Gathering</p>
        </div>
        <nav className="app__nav">
          <NavLink to="/" end>
            Home
          </NavLink>
          <NavLink to="/sets">Sets</NavLink>
          <NavLink to="/cards">Cartas</NavLink>
        </nav>
      </header>

      <main className="app__main">
        <Outlet />
      </main>
    </div>
  )
}

export default App
