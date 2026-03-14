import { describe, it, expect, vi, beforeEach } from 'vitest'
import { useAuthStore } from '../../stores/auth'
import api from '../../services/api'

vi.mock('../../services/api', () => ({
  default: {
    login: vi.fn(),
    register: vi.fn(),
    getCurrentUser: vi.fn(),
  },
}))

const store: Record<string, string> = {};
const localStorageMock = {
  getItem: vi.fn((key: string) => store[key] || null),
  setItem: vi.fn((key: string, value: string) => { store[key] = value; }),
  removeItem: vi.fn((key: string) => { delete store[key]; }),
  clear: vi.fn(() => { Object.keys(store).forEach(k => delete store[k]); }),
};

Object.defineProperty(globalThis, 'localStorage', { value: localStorageMock });

describe('Auth Store', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    Object.keys(store).forEach(k => delete store[k]);
    useAuthStore.setState({
      user: null,
      token: null,
      isAuthenticated: false,
      isLoading: false,
    })
  })

  it('initializes with no auth state', () => {
    const state = useAuthStore.getState()
    expect(state.isAuthenticated).toBe(false)
    expect(state.user).toBe(null)
    expect(state.token).toBe(null)
  })

  it('logs in successfully', async () => {
    const mockUser = {
      id: '1',
      email: 'test@example.com',
      abilities: ['module:read'],
      isSuperadmin: false,
    }

    vi.mocked(api.login).mockResolvedValue({
      token: 'test-token',
      user: mockUser,
    })

    const { login } = useAuthStore.getState()
    await login('test@example.com', 'password')

    const state = useAuthStore.getState()
    expect(state.isAuthenticated).toBe(true)
    expect(state.user?.email).toBe('test@example.com')
    expect(localStorage.setItem).toHaveBeenCalledWith('token', 'test-token')
  })

  it('logs out successfully', () => {
    useAuthStore.setState({
      user: { id: '1', email: 'test@example.com', abilities: [], isSuperadmin: false },
      token: 'test-token',
      isAuthenticated: true,
    })

    const { logout } = useAuthStore.getState()
    logout()

    const state = useAuthStore.getState()
    expect(state.isAuthenticated).toBe(false)
    expect(state.user).toBe(null)
    expect(state.token).toBe(null)
    expect(localStorage.removeItem).toHaveBeenCalledWith('token')
  })

  it('checks auth on startup with valid token', async () => {
    const mockUser = {
      id: '1',
      email: 'test@example.com',
      abilities: ['module:read'],
      isSuperadmin: false,
    }

    vi.mocked(api.getCurrentUser).mockResolvedValue(mockUser)
    vi.mocked(localStorage.getItem).mockReturnValue('existing-token')

    const { checkAuth } = useAuthStore.getState()
    await checkAuth()

    const state = useAuthStore.getState()
    expect(state.isAuthenticated).toBe(true)
    expect(state.user?.email).toBe('test@example.com')
    expect(state.isLoading).toBe(false)
  })

  it('clears auth on startup with no token', async () => {
    vi.mocked(localStorage.getItem).mockReturnValue(null)

    const { checkAuth } = useAuthStore.getState()
    await checkAuth()

    const state = useAuthStore.getState()
    expect(state.isAuthenticated).toBe(false)
    expect(state.isLoading).toBe(false)
  })
})
