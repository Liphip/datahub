import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import Audit from '../../pages/Audit'
import { useAuthStore } from '../../stores/auth'

vi.mock('../../stores/auth', () => ({ useAuthStore: vi.fn() }))

vi.mock('../../services/api', () => ({
  default: {
    searchAudit: vi.fn(async () => ({
      total: 1, limit: 50, offset: 0,
      items: [{ id: 'e1', occurredAt: '2026-01-01T10:00:00Z', actorId: null, tenantId: 't1', action: 'retention.cleanup',
        resourceType: 'retention_cleanup', resourceId: null, outcome: 'SUCCESS', metadata: { pipeline_runs: 3 } }],
    })),
    exportAudit: vi.fn(async () => new Blob(['x'])),
    verifyAuditChain: vi.fn(async () => ({ intact: false, checked: 4, unchained: 0, brokenAtSeq: 5, problem: 'record content does not match its hash' })),
    getAuditNotes: vi.fn(async () => [{ id: 'n1', note: 'Approved in change 4711', createdAt: '2026-01-01T11:00:00Z' }]),
    addAuditNote: vi.fn(async (_id: string, note: string) => ({ id: 'n2', note, createdAt: '2026-01-01T12:00:00Z' })),
    getDeadLetters: vi.fn(async () => [{ id: 'd1', channel: 'pipeline-input', payload: 'not-a-json', error: 'Unparseable', createdAt: '2026-01-01T10:00:00Z' }]),
  },
}))

import api from '../../services/api'

const asUser = (user: Record<string, unknown>) =>
  vi.mocked(useAuthStore).mockReturnValue({ user, selectedTenantId: 't1', tenantContexts: [] } as never)

describe('Audit page', () => {
  beforeEach(() => vi.clearAllMocks())

  it('refuses users without audit:read and does not query', () => {
    asUser({ abilities: ['pipeline:read'] })
    render(<Audit />)
    expect(screen.getByText(/audit:read/)).toBeInTheDocument()
    expect(api.searchAudit).not.toHaveBeenCalled()
  })

  it('lists the tenant\'s records, applies filters and shows system as the actor', async () => {
    asUser({ abilities: ['audit:read'] })
    render(<Audit />)
    await waitFor(() => expect(api.searchAudit).toHaveBeenCalledWith(expect.objectContaining({ tenantId: 't1' }), 50, 0))
    expect(await screen.findByText('retention.cleanup')).toBeInTheDocument()
    expect(screen.getByText('System')).toBeInTheDocument()
    // A tenant auditor gets neither the installation-wide scope nor the dead-letter tab.
    expect(screen.queryByRole('tab')).toBeNull()

    fireEvent.change(screen.getByPlaceholderText(/connector\.\*/), { target: { value: 'auth.*' } })
    fireEvent.click(screen.getByRole('button', { name: 'Apply' }))
    await waitFor(() => expect(api.searchAudit).toHaveBeenLastCalledWith(expect.objectContaining({ tenantId: 't1', action: 'auth.*' }), 50, 0))
  })

  it('lets a platform administrator read the whole installation and the dead letters', async () => {
    asUser({ superadmin: true, abilities: ['*'] })
    render(<Audit />)
    await waitFor(() => expect(api.searchAudit).toHaveBeenCalled())
    fireEvent.change(screen.getByDisplayValue('This tenant'), { target: { value: 'all' } })
    await waitFor(() => expect(api.searchAudit).toHaveBeenLastCalledWith(expect.objectContaining({ tenantId: undefined }), 50, 0))

    fireEvent.click(screen.getByRole('button', { name: 'Verify chain' }))
    expect(await screen.findByRole('status')).toHaveTextContent('broken at record 5')

    fireEvent.click(screen.getByRole('button', { name: 'Details' }))
    expect(await screen.findByText(/Approved in change 4711/)).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText(/Add a note/), { target: { value: 'Checked' } })
    fireEvent.click(screen.getByRole('button', { name: 'Add' }))
    await waitFor(() => expect(api.addAuditNote).toHaveBeenCalledWith('e1', 'Checked'))
    expect(await screen.findByText(/Checked/)).toBeInTheDocument()

    fireEvent.click(screen.getByRole('tab', { name: 'Dead letters' }))
    expect(await screen.findByText('not-a-json')).toBeInTheDocument()
    expect(api.getDeadLetters).toHaveBeenCalled()
  })
})
