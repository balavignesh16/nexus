const API_BASE_URL = 'http://localhost:8080/api/v1'

export class ApiError extends Error {
  status: number
  data?: unknown

  constructor(status: number, message: string, data?: unknown) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.data = data
  }
}

async function request<T>(endpoint: string, options: RequestInit = {}): Promise<T> {
  const url = `${API_BASE_URL}${endpoint}`
  const headers = {
    'Content-Type': 'application/json',
    ...options.headers
  }

  const response = await fetch(url, { ...options, headers })

  if (!response.ok) {
    let message = 'API request failed'
    let data
    try {
      data = await response.json()
      message = data.message || message
    } catch {
      // Not JSON
    }
    throw new ApiError(response.status, message, data)
  }

  if (response.status === 204) {
    return undefined as unknown as T
  }

  return response.json()
}

export const apiClient = {
  get: <T>(endpoint: string) => request<T>(endpoint, { method: 'GET' }),
  post: <T>(endpoint: string, body: unknown) =>
    request<T>(endpoint, { method: 'POST', body: JSON.stringify(body) }),
  put: <T>(endpoint: string, body: unknown) =>
    request<T>(endpoint, { method: 'PUT', body: JSON.stringify(body) }),
  delete: (endpoint: string) => request<void>(endpoint, { method: 'DELETE' })
}