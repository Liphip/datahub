import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import Tenants from '../../pages/Tenants';
import api from '../../services/api';

vi.mock('../../stores/auth', () => ({ useAuthStore: () => ({ user: { superadmin: true }, tenantContexts: [], selectedTenantId: 't1' }) }));
vi.mock('../../services/api', () => ({ default: {
  getTenantsRecursive: vi.fn(async () => [{ id: 't1', name: 'Root', quota: { max_users: 100, retentionPipelineRunsDays: 30, ingestRecordsPerMinute: 600 } }]),
  updateTenant: vi.fn(async () => ({})),
} }));

describe('Tenant quota settings', () => {
  beforeEach(() => vi.clearAllMocks());

  it('edits limits, clears overrides and preserves unrelated quotas', async () => {
    render(<Tenants />);
    fireEvent.click(await screen.findByRole('button', { name: 'Edit' }));
    const retention = screen.getByLabelText('Run history retention (days)');
    expect(retention).toHaveValue(30);
    fireEvent.change(retention, { target: { value: '' } });
    fireEvent.change(screen.getByLabelText('Alert history retention (days)'), { target: { value: '-1' } });
    fireEvent.change(screen.getByLabelText('Ingest records per minute'), { target: { value: '0' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(api.updateTenant).toHaveBeenCalledWith('t1', { name: 'Root', quota: { max_users: 100, ingestRecordsPerMinute: 0, retentionAlertHistoryDays: -1 } }));
  });

  it('keeps edits visible when the server refuses the update', async () => {
    vi.mocked(api.updateTenant).mockRejectedValueOnce({ response: { data: { error: 'Quota update refused' } } });
    render(<Tenants />);
    fireEvent.click(await screen.findByRole('button', { name: 'Edit' }));
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Quota update refused');
    expect(screen.getByLabelText('Run history retention (days)')).toHaveValue(30);
  });
});
