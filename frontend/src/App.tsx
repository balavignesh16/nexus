import { BrowserRouter, Routes, Route } from 'react-router-dom'
import Layout from './components/Layout'
import HomePage from './pages/HomePage'
import AboutPage from './pages/AboutPage'
import './App.css'
import { SitesPage } from './pages/SitesPage'
import { SiteDetailsPage } from './pages/SiteDetailsPage'
import { BuildingDetailsPage } from './pages/BuildingDetailsPage'
import { SpaceDetailsPage } from './pages/SpaceDetailsPage'

function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<Layout />}>
          <Route index element={<HomePage />} />
          <Route path="about" element={<AboutPage />} />
          <Route path="sites" element={<SitesPage />} />
          <Route path="sites/:siteId" element={<SiteDetailsPage />} />
          <Route path="buildings/:buildingId" element={<BuildingDetailsPage />} />
          <Route path="spaces/:spaceId" element={<SpaceDetailsPage />} />
          <Route
            path="*"
            element={
              <main>
                <h1>404 Not Found</h1>
              </main>
            }
          />
        </Route>
      </Routes>
    </BrowserRouter>
  )
}

export default App
