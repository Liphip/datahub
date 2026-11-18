import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import ModuleEditor from '../../pages/ModuleEditor';
import api from '../../services/api';

vi.mock('react-router', () => ({ useParams: () => ({ id: 'm1' }), useNavigate: () => vi.fn() }));
vi.mock('@monaco-editor/react', () => ({ default: () => <div>Code editor</div> }));
vi.mock('../../stores/auth', () => ({ useAuthStore: () => ({ user: { superadmin: true }, selectedTenantId: 't1', tenantContexts: [] }) }));
vi.mock('../../services/api', () => ({ default: {
  getModule: vi.fn(), getModuleVersions: vi.fn(async () => []), createModuleVersion: vi.fn(async () => ({})),
} }));

const config = { table: 'measurements', nested: { enabled: true }, columns: ['time', 'value'] };
const version = (id: string, value: unknown) => ({ id, version: 1, code: 'print(1)', requirements: '', config: value, isActive: true, createdAt: '2026-01-01T00:00:00Z' });
const fixture = (versions: unknown[]) => ({ id: 'm1', name: 'Configuration test', moduleType: 'PROCESSING', versions });

describe('Module configuration editing', () => {
  beforeEach(() => vi.clearAllMocks());

  it.each([config, JSON.stringify(config), JSON.stringify(JSON.stringify(config))])('opens and saves config as an object: %j', async (stored) => {
    vi.mocked(api.getModule).mockResolvedValueOnce(fixture([version('v1', stored)]) as never);
    render(<ModuleEditor />);
    const editor = await screen.findByLabelText('Module config (JSON)');
    expect(JSON.parse((editor as HTMLTextAreaElement).value)).toEqual(config);
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(api.createModuleVersion).toHaveBeenCalledWith('m1', 'print(1)', expect.any(String), undefined, config));
    expect(await screen.findByText('Version saved successfully')).toBeInTheDocument();
  });

  it('loads historical JSON text without adding an escaping layer', async () => {
    vi.mocked(api.getModule).mockResolvedValueOnce(fixture([version('v2', {}), version('v1', JSON.stringify(config))]) as never);
    render(<ModuleEditor />);
    const editor = await screen.findByLabelText('Module config (JSON)');
    fireEvent.click(screen.getAllByRole('button', { name: 'Load' })[1]);
    expect(JSON.parse((editor as HTMLTextAreaElement).value)).toEqual(config);
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(api.createModuleVersion).toHaveBeenCalledWith('m1', 'print(1)', expect.any(String), undefined, config));
  });
});
