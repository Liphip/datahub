import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import type { User, TenantContext } from '../types';
import api from '../services/api';

/**
 * Defines the interface `AuthState`, the contract implemented by this source unit.
 */
interface AuthState {
  user: User | null;
  token: string | null;
  selectedTenantId: string | null;
  tenantContexts: TenantContext[];
  isAuthenticated: boolean;
  isLoading: boolean;
  login: (email: string, password: string, tenantId?: string) => Promise<void>;
  register: (email: string, password: string, tenantName?: string, tenantId?: string) => Promise<void>;
  switchTenant: (tenantId: string) => Promise<void>;
  logout: () => void;
  checkAuth: () => Promise<void>;
}

export const useAuthStore = create<AuthState>()(
  /**
   * Creates or persists the requested state.
   */
  persist(
    (set) => ({
      user: null,
      token: null,
      selectedTenantId: null,
      tenantContexts: [],
      isAuthenticated: false,
      isLoading: true,

      login: async (email: string, password: string, tenantId?: string) => {
        const response = await api.login(email, password, tenantId);
        localStorage.setItem('token', response.token);
        const contexts = await api.getAuthTenants().catch(() => []);
        const nextTenantId = response.user.tenantId || contexts[0]?.tenantId || null;
        set({
          user: response.user,
          token: response.token,
          selectedTenantId: nextTenantId,
          tenantContexts: contexts,
          isAuthenticated: true,
        });
      },

      register: async (email: string, password: string, tenantName?: string, tenantId?: string) => {
        const response = await api.register(email, password, tenantName, tenantId);
        localStorage.setItem('token', response.token);
        const contexts = await api.getAuthTenants().catch(() => []);
        const nextTenantId = response.user.tenantId || contexts[0]?.tenantId || null;
        set({
          user: response.user,
          token: response.token,
          selectedTenantId: nextTenantId,
          tenantContexts: contexts,
          isAuthenticated: true,
        });
      },

      switchTenant: async (tenantId: string) => {
        const response = await api.switchTenant(tenantId);
        localStorage.setItem('token', response.token);
        set((state) => ({
          user: response.user,
          token: response.token,
          selectedTenantId: response.user.tenantId || tenantId,
          isAuthenticated: true,
          tenantContexts: state.tenantContexts,
        }));
      },

      logout: () => {
        localStorage.removeItem('token');
        set({ user: null, token: null, selectedTenantId: null, tenantContexts: [], isAuthenticated: false });
      },

      checkAuth: async () => {
        const token = localStorage.getItem('token');
        if (!token) {
          set({ isLoading: false, isAuthenticated: false, selectedTenantId: null, tenantContexts: [] });
          return;
        }

        try {
          const [user, contexts] = await Promise.all([
            api.getCurrentUser(),
            api.getAuthTenants().catch(() => []),
          ]);
          const nextTenantId = user.tenantId || contexts[0]?.tenantId || null;
          set({
            user,
            token,
            selectedTenantId: nextTenantId,
            tenantContexts: contexts,
            isAuthenticated: true,
            isLoading: false,
          });
        } catch (err: unknown) {
          // Only clear auth state on 401 (invalid/expired token).
          // Network errors (no response) should NOT log the user out —
          // they'll see the API-unavailable banner and can retry.
          const status = (err as { response?: { status?: number } })?.response?.status;
          if (status === 401 || status === 403) {
            localStorage.removeItem('token');
            set({ user: null, token: null, selectedTenantId: null, tenantContexts: [], isAuthenticated: false, isLoading: false });
          } else {
            // Network error or 5xx — keep existing auth state, just stop loading
            set({ isLoading: false });
          }
        }
      },
    }),
    {
      name: 'auth-storage',
      partialize: (state) => ({ token: state.token }),
      onRehydrateStorage: () => (state) => {
        state?.checkAuth();
      },
    }
  )
);
