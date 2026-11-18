import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import Login from '../../pages/Login';
import api from '../../services/api';
const { complete, navigate } = vi.hoisted(() => ({ complete: vi.fn(async () => {}), navigate: vi.fn() }));
vi.mock('../../stores/auth', () => ({ useAuthStore: () => ({ login: vi.fn(), completeOidcLogin: complete }) }));
vi.mock('../../services/api', () => ({ default: { oidcStatus: vi.fn(async () => ({ enabled: true })) } }));
vi.mock('react-router', () => ({ useNavigate: () => navigate, Link: ({ children }: { children: React.ReactNode }) => children }));
describe('OIDC login UI', () => {
  beforeEach(() => { vi.clearAllMocks(); vi.stubGlobal('location', { pathname: '/login', search: '' }); });
  it('offers the configured provider without starting a login automatically', async () => {
    render(<Login />);
    expect(await screen.findByRole('link', { name: 'Sign in with your identity provider' })).toHaveAttribute('href', '/api/internal/v1/auth/oidc/login');
    expect(complete).not.toHaveBeenCalled();
  });
  it('removes the handoff code from browser history and completes the login', async () => {
    vi.stubGlobal('location', { pathname: '/login', search: '?oidcCode=browser-bound-code' });
    const replace = vi.spyOn(window.history, 'replaceState');
    render(<Login />);
    await waitFor(() => expect(complete).toHaveBeenCalledWith('browser-bound-code'));
    expect(replace).toHaveBeenCalledWith(null, '', '/login');
    await waitFor(() => expect(navigate).toHaveBeenCalledWith('/'));
  });
  it('keeps password login available if the provider status is unavailable', async () => {
    vi.mocked(api.oidcStatus).mockRejectedValueOnce(new Error('unavailable'));
    render(<Login />);
    expect(screen.getByLabelText('Password')).toBeInTheDocument();
    await waitFor(() => expect(api.oidcStatus).toHaveBeenCalled());
    expect(screen.queryByRole('link', { name: 'Sign in with your identity provider' })).not.toBeInTheDocument();
  });
});
