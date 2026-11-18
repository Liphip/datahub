import { it, expect, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import MembershipValidityEditor from '../../components/MembershipValidityEditor';
import api from '../../services/api';
vi.mock('../../services/api', () => ({ default: { updateMembershipValidity: vi.fn(async () => ({})) } }));
it('clears boundaries and rejects reversed intervals before sending a change', async () => {
  const saved = vi.fn();
  render(<MembershipValidityEditor membership={{ id: 'm1', authUserId: 'u1', validFrom: '2030-01-01T00:00:00Z' }} tenantId="t1" onSaved={saved} onClose={vi.fn()} />);
  fireEvent.change(screen.getByLabelText('Valid from'), { target: { value: '' } });
  fireEvent.click(screen.getByRole('button', { name: 'Save' }));
  await waitFor(() => expect(api.updateMembershipValidity).toHaveBeenCalledWith('t1', 'm1', null, null));
  await waitFor(() => expect(saved).toHaveBeenCalled());
  vi.mocked(api.updateMembershipValidity).mockClear();
  fireEvent.change(screen.getByLabelText('Valid from'), { target: { value: '2030-02-01T00:00:00' } });
  fireEvent.change(screen.getByLabelText('Valid until'), { target: { value: '2030-01-01T00:00:00' } });
  fireEvent.click(screen.getByRole('button', { name: 'Save' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('The end must be after the start.');
  expect(api.updateMembershipValidity).not.toHaveBeenCalled();
});
