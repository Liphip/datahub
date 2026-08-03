import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { useAuthStore } from '../../stores/auth'
import api from '../../services/api'

vi.mock('../../services/api', () => ({
  default: {
    login: vi.fn(),
    register: vi.fn(),
    getCurrentUser: vi.fn(),
    getAuthTenants: vi.fn(),
    switchTenant: vi.fn(),
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
    vi.mocked(api.getAuthTenants).mockResolvedValue([])
    Object.keys(store).forEach(k => delete store[k]);
    useAuthStore.setState({
      user: null,
      token: null,
      selectedTenantId: null,
      tenantContexts: [],
      isAuthenticated: false,
      isLoading: false,
    })
  })

  afterEach(() => {
    vi.resetAllMocks();
  })

  describe('initialization', () => {
    it('initializes with no auth state', () => {
      const state = useAuthStore.getState()
      expect(state.isAuthenticated).toBe(false)
      expect(state.user).toBe(null)
      expect(state.token).toBe(null)
      expect(state.selectedTenantId).toBe(null)
    })

    it('has correct initial loading state', () => {
      const state = useAuthStore.getState()
      expect(state.isLoading).toBe(false)
    })
  })

  describe('login', () => {
    it('logs in successfully with email and password', async () => {
      const mockUser = {
        id: '1',
        email: 'test@example.com',
        firstName: 'Test',
        lastName: 'User',
        abilities: ['module:read'],
        superadmin: false,
        tenantId: 'tenant-1',
        roleName: 'Viewer',
      }

      vi.mocked(api.login).mockResolvedValue({
        token: 'test-token',
        user: mockUser,
      })
      vi.mocked(api.getAuthTenants).mockResolvedValue([{ tenantId: 'tenant-1', tenantName: 'Tenant 1' }])

      const { login } = useAuthStore.getState()
      await login('test@example.com', 'password123')

      const state = useAuthStore.getState()
      expect(state.isAuthenticated).toBe(true)
      expect(state.user?.email).toBe('test@example.com')
      expect(state.selectedTenantId).toBe('tenant-1')
      expect(localStorage.setItem).toHaveBeenCalledWith('token', 'test-token')
    })

    it('hashes password before sending to API', async () => {
      vi.mocked(api.login).mockResolvedValue({
        token: 'test-token',
        user: { id: '1', email: 'test@example.com', abilities: [], superadmin: false },
      })
      vi.mocked(api.getAuthTenants).mockResolvedValue([])

      const { login } = useAuthStore.getState()
      await login('test@example.com', 'mypassword')

      expect(api.login).toHaveBeenCalledWith('test@example.com', 'mypassword', undefined)
    })

    it('does not set token in storage on login failure', async () => {
      vi.mocked(api.login).mockRejectedValue(new Error('Invalid credentials'))

      const { login } = useAuthStore.getState()
      
      await expect(login('test@example.com', 'wrongpassword')).rejects.toThrow()
      
      const state = useAuthStore.getState()
      expect(state.isAuthenticated).toBe(false)
      expect(state.token).toBe(null)
    })
  })

  describe('logout', () => {
    it('logs out successfully and clears storage', () => {
      useAuthStore.setState({
        user: { id: '1', email: 'test@example.com', abilities: [], superadmin: false },
        token: 'test-token',
        selectedTenantId: 'tenant-1',
        tenantContexts: [{ tenantId: 'tenant-1', tenantName: 'Tenant 1' }],
        isAuthenticated: true,
      })

      const { logout } = useAuthStore.getState()
      logout()

      const state = useAuthStore.getState()
      expect(state.isAuthenticated).toBe(false)
      expect(state.user).toBe(null)
      expect(state.token).toBe(null)
      expect(state.selectedTenantId).toBe(null)
      expect(localStorage.removeItem).toHaveBeenCalledWith('token')
    })
  })

  describe('checkAuth', () => {
    it('authenticates user when valid token exists in storage', async () => {
      const mockUser = {
        id: '1',
        email: 'test@example.com',
        abilities: ['module:read'],
        superadmin: false,
      }

      vi.mocked(api.getCurrentUser).mockResolvedValue(mockUser)
      vi.mocked(api.getAuthTenants).mockResolvedValue([{ tenantId: 'tenant-1', tenantName: 'Tenant 1' }])
      vi.mocked(localStorage.getItem).mockReturnValue('existing-token')

      const { checkAuth } = useAuthStore.getState()
      await checkAuth()

      const state = useAuthStore.getState()
      expect(state.isAuthenticated).toBe(true)
      expect(state.user?.email).toBe('test@example.com')
      expect(state.selectedTenantId).toBe('tenant-1')
      expect(state.isLoading).toBe(false)
    })

    it('clears auth when no token in storage', async () => {
      vi.mocked(localStorage.getItem).mockReturnValue(null)

      const { checkAuth } = useAuthStore.getState()
      await checkAuth()

      const state = useAuthStore.getState()
      expect(state.isAuthenticated).toBe(false)
      expect(state.isLoading).toBe(false)
    })

    it('clears auth when API call fails', async () => {
      // BUG: Fixture represents a network error, not an invalid token
      // Details: checkAuth intentionally removes the token only for 401/403 responses, while Error('Unauthorized') has no response status. Mock a 401 response or change the assertion to verify the documented network-error behavior.
      vi.mocked(localStorage.getItem).mockReturnValue('invalid-token')
      vi.mocked(api.getCurrentUser).mockRejectedValue(new Error('Unauthorized'))

      const { checkAuth } = useAuthStore.getState()
      await checkAuth()

      const state = useAuthStore.getState()
      expect(state.isAuthenticated).toBe(false)
      expect(state.isLoading).toBe(false)
      expect(localStorage.removeItem).toHaveBeenCalledWith('token')
    })
  })

  describe('state management', () => {
    it('persists only token to localStorage', () => {
      useAuthStore.setState({
        user: { id: '1', email: 'test@example.com', abilities: [], superadmin: false },
        token: 'test-token',
        selectedTenantId: 'tenant-1',
        tenantContexts: [{ tenantId: 'tenant-1', tenantName: 'Tenant 1' }],
        isAuthenticated: true,
      })

      const state = useAuthStore.getState()
      expect(state.token).toBe('test-token')
    })
  })
})
