import { render, screen } from '@testing-library/react'
import { describe, it, expect } from 'vitest'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { AppShell } from '../components/AppShell'
import HomePage from '../pages/HomePage'
import AboutPage from '../pages/AboutPage'

function TestApp() {
  return (
    <Routes>
      <Route path="/" element={<AppShell />}>
        <Route index element={<HomePage />} />
        <Route path="about" element={<AboutPage />} />
      </Route>
    </Routes>
  )
}

function renderRoute(path: string) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[path]}>
        <TestApp />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('App Routing', () => {
  it('renders Home page on default route', () => {
    renderRoute('/')
    expect(screen.getByRole('heading', { name: 'NEXUS' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Overview' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Digital Twin' })).toBeInTheDocument()
  })

  it('renders About page on /about route', () => {
    renderRoute('/about')
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('About NEXUS')
  })
})
