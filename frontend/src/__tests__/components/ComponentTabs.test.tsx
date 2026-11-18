import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import ComponentTabs from '../../components/ComponentTabs';
import { useAuthStore } from '../../stores/auth';
import api from '../../services/api';
vi.mock('../../stores/auth', () => ({ useAuthStore: vi.fn() }));
vi.mock('../../services/api', () => ({ default: {
  searchAudit: vi.fn(async () => ({ total: 21, limit: 20, offset: 0, items: [{ id: 'a1', occurredAt: '2026-11-16T12:00:00Z', action: 'tenant.updated', outcome: 'SUCCESS' }] })),
  getComponentNotes: vi.fn(async () => [{ id: 'n1', createdAt: '2026-11-16T12:00:00Z', note: 'Approved', authorId: 'admin1' }]),
  addComponentNote: vi.fn(async () => ({})),
} }));
const show = () => render(<ComponentTabs tenantId="tenant1" resourceType="tenant" resourceId="tenant1" onClose={vi.fn()}><p>Configuration form</p></ComponentTabs>);
describe('component activity', () => {
  beforeEach(() => { vi.clearAllMocks(); vi.mocked(useAuthStore).mockReturnValue({ user: { abilities: ['audit:read', 'audit.note:write'] } } as never); });
  it('does not query or show the tab without audit read permission', () => {
    vi.mocked(useAuthStore).mockReturnValue({ user: { abilities: ['tenant:manage'] } } as never);
    show(); expect(screen.getByText('Configuration form')).toBeInTheDocument();
    expect(screen.queryByRole('tab')).not.toBeInTheDocument(); expect(api.searchAudit).not.toHaveBeenCalled();
  });
  it('loads only on selection, scopes events and notes, pages, and adds a permanent note', async () => {
    show(); expect(api.searchAudit).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('tab', { name: 'Activity' }));
    expect(await screen.findByText('Approved')).toBeInTheDocument();
    expect(api.searchAudit).toHaveBeenCalledWith({ tenantId: 'tenant1', resourceType: 'tenant', resourceId: 'tenant1' }, 20, 0);
    expect(api.getComponentNotes).toHaveBeenCalledWith('tenant1', 'tenant', 'tenant1');
    fireEvent.click(screen.getByRole('button', { name: 'Next' }));
    await waitFor(() => expect(api.searchAudit).toHaveBeenLastCalledWith(expect.anything(), 20, 20));
    fireEvent.change(screen.getByLabelText('Add a note'), { target: { value: 'Checked locally' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));
    await waitFor(() => expect(api.addComponentNote).toHaveBeenCalledWith({ tenantId: 'tenant1', resourceType: 'tenant', resourceId: 'tenant1', note: 'Checked locally' }));
    fireEvent.click(screen.getByRole('tab', { name: 'Configuration' }));
    expect(screen.getByText('Configuration form')).toBeInTheDocument();
  });
  it('lets read-only auditors inspect activity without offering note creation', async () => {
    vi.mocked(useAuthStore).mockReturnValue({ user: { abilities: ['audit:read'] } } as never);
    show();fireEvent.click(screen.getByRole('tab', { name: 'Activity' }));
    expect(await screen.findByText('Approved')).toBeInTheDocument();expect(screen.queryByLabelText('Add a note')).not.toBeInTheDocument();
  });
});
