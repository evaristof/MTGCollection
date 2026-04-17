import { Link } from 'react-router-dom'

export default function HomePage() {
  return (
    <section className="page">
      <h2>Bem-vindo</h2>
      <p>
        Use o menu acima para navegar. Você pode gerenciar as <Link to="/sets">coleções (Sets)</Link>{' '}
        cadastradas no banco e as <Link to="/cards">cartas da sua coleção</Link>.
      </p>

      <h3>Fluxo sugerido</h3>
      <ol>
        <li>
          Vá em <Link to="/sets">Sets</Link> e clique em <strong>“Sincronizar do Scryfall”</strong> para
          popular o banco com todos os sets de Magic.
        </li>
        <li>
          Depois, em <Link to="/cards">Cartas</Link>, adicione cartas fornecendo nome, código do set,
          se é foil, linguagem e quantidade. O back busca automaticamente no Scryfall o número da carta
          (collector number) e o tipo (type line).
        </li>
      </ol>

      <p className="muted">
        Toda a persistência é em memória (H2). Ao reiniciar o back-end, os dados são perdidos.
      </p>
    </section>
  )
}
