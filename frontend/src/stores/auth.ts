import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import type { User } from '../types';
import api from '../services/api';

interface AuthState {
  user: User | null;
  token: string | null;
  isAuthenticated: boolean;
  isLoading: boolean;
  login: (email: string, password: string, tenantId?: string) => Promise<void>;
  register: (email: string, password: string, tenantName?: string, tenantId?: string) => Promise<void>;
  logout: () => void;
  checkAuth: () => Promise<void>;
}

export const useAuthStore = create<AuthState>()(
  persist(
    (set) => ({
      user: null,
      token: null,
      isAuthenticated: false,
      isLoading: true,

      login: async (email: string, password: string, tenantId?: string) => {
        const response = await api.login(email, password, tenantId);
        localStorage.setItem('token', response.token);
        set({ user: response.user, token: response.token, isAuthenticated: true });
      },

      register: async (email: string, password: string, tenantName?: string, tenantId?: string) => {
        const response = await api.register(email, password, tenantName, tenantId);
        localStorage.setItem('token', response.token);
        set({ user: response.user, token: response.token, isAuthenticated: true });
      },

      logout: () => {
        localStorage.removeItem('token');
        set({ user: null, token: null, isAuthenticated: false });
      },

      checkAuth: async () => {
        const token = localStorage.getItem('token');
        if (!token) {
          set({ isLoading: false, isAuthenticated: false });
          return;
        }

        try {
          const user = await api.getCurrentUser();
          set({ user, token, isAuthenticated: true, isLoading: false });
        } catch {
          localStorage.removeItem('token');
          set({ user: null, token: null, isAuthenticated: false, isLoading: false });
        }
      },
    }),
    {
      name: 'auth-storage',
      partialize: (state) => ({ token: state.token }),
    }
  )
);
