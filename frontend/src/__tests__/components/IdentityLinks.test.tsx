import { it, expect, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import IdentityLinks from '../../components/IdentityLinks';
import api from '../../services/api';
vi.mock('../../services/api', () => ({ default: {
  getIdentityLinks: vi.fn(async () => [{ id: 'link1', issuer: 'https://id.example', subject: 'person-123' }]),
  linkIdentity: vi.fn(async () => {}), unlinkIdentity: vi.fn(async () => {}),
} }));
it('lists exact identities, links a subject and confirms revocation on unlink', async () => {
  vi.spyOn(window, 'confirm').mockReturnValue(true);
  render(<IdentityLinks userId="user1" name="Ada" onClose={vi.fn()} />);
  expect(await screen.findByText('person-123')).toBeInTheDocument();
  fireEvent.change(screen.getByLabelText('Issuer URL'), { target: { value: 'https://other.example' } });
  fireEvent.change(screen.getByLabelText('Subject (sub)'), { target: { value: 'new-subject' } });
  fireEvent.click(screen.getByRole('button', { name: 'Link identity' }));
  await waitFor(() => expect(api.linkIdentity).toHaveBeenCalledWith('user1', 'https://other.example', 'new-subject'));
  await waitFor(() => expect(screen.getByRole('button', { name: 'Unlink' })).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: 'Unlink' }));
  await waitFor(() => expect(api.unlinkIdentity).toHaveBeenCalledWith('user1', 'link1'));
});
