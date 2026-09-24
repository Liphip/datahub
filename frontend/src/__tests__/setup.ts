import '@testing-library/jest-dom/vitest'
import i18n from '../i18n'
import { vi } from 'vitest'

// Mock localStorage
const localStorageMock = {
  getItem: vi.fn(),
  setItem: vi.fn(),
  removeItem: vi.fn(),
  clear: vi.fn(),
}
vi.stubGlobal('localStorage', localStorageMock)

// Mock window.location
vi.stubGlobal('location', {
  href: 'http://localhost:3000',
  pathname: '/',
})

// Mock React Router
vi.mock('react-router', () => ({
  ...vi.importActual('react-router'),
  useNavigate: () => vi.fn(),
  useLocation: () => ({ pathname: '/' }),
  Link: ({ children }: { children: React.ReactNode }) => children,
}))

// Deterministic language for assertions on rendered text.
await i18n.changeLanguage('en')
