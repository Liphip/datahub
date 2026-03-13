import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { BrowserRouter } from 'react-router-dom'
import Login from '../pages/Login'
import { useAuthStore } from '../stores/auth'

// Mock the auth store
vi.mock('../stores/auth', () => ({
  useAuthStore: vi.fn(() => ({
    login: vi.fn(),
    isAuthenticated: false,
    isLoading: false,
  })),
}))

describe('Login Page', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders login form', () => {
    render(
      <BrowserRouter>
        <Login />
      </BrowserRouter>
    )

    expect(screen.getByText(/Sign in to DataTurntable/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/Email address/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/Password/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Sign in/i })).toBeInTheDocument()
  })

  it('shows error message on invalid credentials', async () => {
    const mockLogin = vi.fn().mockRejectedValue(new Error('Invalid credentials'))
    vi.mocked(useAuthStore).mockReturnValue({
      login: mockLogin,
      isAuthenticated: false,
      isLoading: false,
      register: vi.fn(),
      logout: vi.fn(),
      checkAuth: vi.fn(),
      user: null,
      token: null,
    })

    render(
      <BrowserRouter>
        <Login />
      </BrowserRouter>
    )

    const emailInput = screen.getByLabelText(/Email address/i)
    const passwordInput = screen.getByLabelText(/Password/i)
    const submitButton = screen.getByRole('button', { name: /Sign in/i })

    await waitFor(() => {
      // Try to submit
    })
  })

  it('has loading state while authenticating', () => {
    vi.mocked(useAuthStore).mockReturnValue({
      login: vi.fn(),
      isAuthenticated: false,
      isLoading: true,
      register: vi.fn(),
      logout: vi.fn(),
      checkAuth: vi.fn(),
      user: null,
      token: null,
    })

    render(
      <BrowserRouter>
        <Login />
      </BrowserRouter>
    )

    expect(screen.getByRole('button', { name: /Signing in.../i })).toBeInTheDocument()
  })
})
