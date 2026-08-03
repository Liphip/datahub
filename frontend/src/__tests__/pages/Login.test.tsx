import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { BrowserRouter } from 'react-router-dom'
import Login from '../../pages/Login'
import { useAuthStore } from '../../stores/auth'

const mockLogin = vi.fn()
const mockNavigate = vi.fn()

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual('react-router-dom')
  return {
    ...actual,
    useNavigate: () => mockNavigate,
  }
})

vi.mock('../../stores/auth', () => ({
  useAuthStore: vi.fn(() => ({
    login: mockLogin,
    isAuthenticated: false,
    isLoading: false,
    logout: vi.fn(),
    checkAuth: vi.fn(),
    user: null,
    token: null,
  })),
}))

// BUG: Login tests do not initialize the i18n provider
// Details: The component renders translation keys (for example auth.emailAddress) because the suite never imports or initializes src/i18n. Assertions for English labels therefore fail; initialize a deterministic test language or mock useTranslation.
describe('Login Page', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(useAuthStore).mockReturnValue({
      login: mockLogin,
      isAuthenticated: false,
      isLoading: false,
      logout: vi.fn(),
      checkAuth: vi.fn(),
      user: null,
      token: null,
    })
  })

  describe('rendering', () => {
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

    it('has email input with correct type', () => {
      render(
        <BrowserRouter>
          <Login />
        </BrowserRouter>
      )

      const emailInput = screen.getByLabelText(/Email address/i)
      expect(emailInput).toHaveAttribute('type', 'email')
    })

    it('has password input with correct type', () => {
      render(
        <BrowserRouter>
          <Login />
        </BrowserRouter>
      )

      const passwordInput = screen.getByLabelText(/Password/i)
      expect(passwordInput).toHaveAttribute('type', 'password')
    })
  })

  describe('form submission', () => {
    it('calls login on form submit', async () => {
      mockLogin.mockResolvedValue(undefined)
      
      render(
        <BrowserRouter>
          <Login />
        </BrowserRouter>
      )

      const emailInput = screen.getByLabelText(/Email address/i)
      const passwordInput = screen.getByLabelText(/Password/i)
      const submitButton = screen.getByRole('button', { name: /Sign in/i })

      fireEvent.change(emailInput, { target: { value: 'test@example.com' } })
      fireEvent.change(passwordInput, { target: { value: 'password123' } })
      fireEvent.click(submitButton)

      await waitFor(() => {
        expect(mockLogin).toHaveBeenCalledWith('test@example.com', 'password123')
      })
    })

    it('shows loading state during login', async () => {
      mockLogin.mockImplementation(() => new Promise<void>((resolve) => { setTimeout(() => resolve(), 100) }))
      
      render(
        <BrowserRouter>
          <Login />
        </BrowserRouter>
      )

      const emailInput = screen.getByLabelText(/Email address/i)
      const passwordInput = screen.getByLabelText(/Password/i)
      const submitButton = screen.getByRole('button', { name: /Sign in/i })

      fireEvent.change(emailInput, { target: { value: 'test@example.com' } })
      fireEvent.change(passwordInput, { target: { value: 'password123' } })
      fireEvent.click(submitButton)

      await waitFor(() => {
        expect(screen.getByRole('button', { name: /Signing in/i })).toBeInTheDocument()
      })
    })

    it('shows error message on login failure', async () => {
      mockLogin.mockRejectedValue(new Error('Invalid credentials'))
      
      render(
        <BrowserRouter>
          <Login />
        </BrowserRouter>
      )

      const emailInput = screen.getByLabelText(/Email address/i)
      const passwordInput = screen.getByLabelText(/Password/i)
      const submitButton = screen.getByRole('button', { name: /Sign in/i })

      fireEvent.change(emailInput, { target: { value: 'test@example.com' } })
      fireEvent.change(passwordInput, { target: { value: 'wrongpassword' } })
      fireEvent.click(submitButton)

      await waitFor(() => {
        expect(screen.getByText(/Invalid email or password/i)).toBeInTheDocument()
      })
    })
  })

  describe('redirect', () => {
    it('redirects to dashboard on successful login', async () => {
      mockLogin.mockResolvedValue(undefined)
      
      render(
        <BrowserRouter>
          <Login />
        </BrowserRouter>
      )

      const emailInput = screen.getByLabelText(/Email address/i)
      const passwordInput = screen.getByLabelText(/Password/i)
      const submitButton = screen.getByRole('button', { name: /Sign in/i })

      fireEvent.change(emailInput, { target: { value: 'test@example.com' } })
      fireEvent.change(passwordInput, { target: { value: 'password123' } })
      fireEvent.click(submitButton)

      await waitFor(() => {
        expect(mockNavigate).toHaveBeenCalledWith('/')
      })
    })
  })
})
