import { Link, Outlet } from 'react-router-dom'

function Layout() {
  return (
    <div className="layout">
      <nav className="layout-nav">
        <ul>
          <li>
            <Link to="/">Home</Link>
          </li>
          <li>
            <Link to="/about">About</Link>
          </li>
        </ul>
      </nav>
      <main className="layout-content">
        <Outlet />
      </main>
    </div>
  )
}

export default Layout
