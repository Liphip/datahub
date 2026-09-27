import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { BrowserRouter } from 'react-router'
import Connectors from '../../pages/Connectors'
import { useAuthStore } from '../../stores/auth'

const createConnector = vi.fn()
const mockNavigate = vi.fn()

vi.mock('react-router', async () => {
  const actual = await vi.importActual('react-router')
  return { ...actual, useNavigate: () => mockNavigate }
})

vi.mock('../../stores/auth', () => ({ useAuthStore: vi.fn() }))

vi.mock('../../services/api', () => ({
  default: {
    getConnectors: vi.fn(async () => []),
    getMqttConnectorDefaults: vi.fn(async () => null),
    getMqttBrokerCredentials: vi.fn(async () => []),
    getConnectorStatuses: vi.fn(async () => ({})),
    getConnectorInboxItems: vi.fn(async () => []),
    retryConnectorInboxItem: vi.fn(async () => undefined),
    createConnector: vi.fn(),
    updateConnector: vi.fn(async () => ({})),
    createSecret: vi.fn(async (_tenant: string, name: string) => {
      const parts = name.split('.')
      return { id: `id-${parts[parts.length - 2]}`, name, version: 1 }
    }),
    rotateSecret: vi.fn(async (_tenant: string, id: string) => ({ id, version: 2 })),
  },
}))

import api from '../../services/api'

const renderPage = async () => {
  render(
    <BrowserRouter>
      <Connectors />
    </BrowserRouter>
  )
  await waitFor(() => expect(api.getConnectors).toHaveBeenCalled())
}

const openCreateModal = async () => {
  const [create] = await screen.findAllByRole('button', { name: /create connector/i })
  fireEvent.click(create)
}

describe('Connectors page: pull inputs', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(useAuthStore).mockReturnValue({
      user: { id: 'u1', tenantId: 't1', abilities: ['*'] },
      selectedTenantId: 't1',
      tenantContexts: [{ tenantId: 't1' }],
    } as unknown as ReturnType<typeof useAuthStore>)
    vi.mocked(api.createConnector).mockImplementation(createConnector)
  })

  it('offers every inbox type, each locked to the INPUT direction', async () => {
    await renderPage()
    await openCreateModal()

    const typeSelect = screen.getByLabelText('Type') as HTMLSelectElement
    expect(Array.from(typeSelect.options).map((option) => option.value)).toEqual(
      expect.arrayContaining(['FILE', 'FTP', 'SFTP', 'EMAIL'])
    )

    fireEvent.change(typeSelect, { target: { value: 'SFTP' } })
    const directionSelect = screen.getByLabelText('Direction') as HTMLSelectElement
    expect(directionSelect).toBeDisabled()
    expect(directionSelect.value).toBe('INPUT')
  })

  it('asks for the settings an SFTP source needs', async () => {
    await renderPage()
    await openCreateModal()
    fireEvent.change(screen.getByLabelText('Type'), { target: { value: 'SFTP' } })

    for (const label of ['Host', 'Username', 'Remote Directory', 'Host Key Fingerprint', 'After Read']) {
      expect(screen.getByLabelText(label)).toBeInTheDocument()
    }
    expect(screen.getByLabelText('Private Key')).toBeInTheDocument()
    expect(screen.getByText(/Enable polling/)).toBeInTheDocument()
  })

  it('sends credentials as secret references, never inline', async () => {
    await renderPage()
    await openCreateModal()
    fireEvent.change(screen.getByLabelText('Type'), { target: { value: 'SFTP' } })

    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Meter drop' } })
    fireEvent.change(screen.getByLabelText('Host'), { target: { value: 'sftp.example.com' } })
    fireEvent.change(screen.getByLabelText('Username'), { target: { value: 'drop' } })
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 's3cret' } })
    fireEvent.change(screen.getByLabelText('Remote Directory'), { target: { value: '/upload' } })
    fireEvent.click(screen.getByRole('button', { name: /^Create$/ }))

    await waitFor(() => expect(createConnector).toHaveBeenCalled())
    const config = createConnector.mock.calls[0][4] as Record<string, unknown>
    expect(config).not.toHaveProperty('password')
    expect(config).toMatchObject({
      host: 'sftp.example.com',
      username: 'drop',
      path: '/upload',
      secretRefs: { password: { id: 'id-password', version: 1 } },
    })
  })

  it('keeps a stored secret when the field is left empty while editing', async () => {
    vi.mocked(api.getConnectors).mockResolvedValue([
      {
        id: 'c1',
        name: 'Meter drop',
        connectorType: 'FTP',
        direction: 'INPUT',
        config: { host: 'ftp.example.com', username: 'drop', path: '/in', secretRefs: { password: { id: 's1', version: 1 } } },
        isActive: true,
        pollingEnabled: true,
        pollingIntervalSeconds: 60,
        createdAt: '2026-01-01T00:00:00Z',
      },
    ] as never)
    await renderPage()

    fireEvent.click(await screen.findByRole('button', { name: /^Edit$/ }))
    const passwordField = await screen.findByLabelText('Password')
    expect(passwordField).toHaveValue('')
    expect(screen.getByText(/secret stored/)).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: /save changes/i }))
    await waitFor(() => expect(api.updateConnector).toHaveBeenCalled())
    const payload = vi.mocked(api.updateConnector).mock.calls[0][1] as { config: Record<string, unknown> }
    expect(payload.config).toMatchObject({ secretRefs: { password: { id: 's1', version: 1 } } })
    expect(api.rotateSecret).not.toHaveBeenCalled()
  })

  it('lists the read items of an inbox and retries a failed one', async () => {
    vi.mocked(api.getConnectors).mockResolvedValue([
      {
        id: 'c1',
        name: 'Meter drop',
        connectorType: 'FILE',
        direction: 'INPUT',
        config: { path: 'inbox' },
        isActive: true,
        pollingEnabled: true,
        pollingIntervalSeconds: 60,
        createdAt: '2026-01-01T00:00:00Z',
      },
    ] as never)
    vi.mocked(api.getConnectorInboxItems).mockResolvedValue([
      {
        id: 'i1',
        name: 'broken.csv',
        size: 12,
        status: 'FAILED',
        chunks: 0,
        error: 'Line 1 is not JSON',
        firstSeenAt: '2026-01-01T00:00:00Z',
      },
    ] as never)
    await renderPage()

    fireEvent.click(await screen.findByRole('button', { name: /^Items$/ }))
    expect(await screen.findByText('broken.csv')).toBeInTheDocument()
    expect(screen.getByText('Line 1 is not JSON')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: /retry on next poll/i }))
    await waitFor(() => expect(api.retryConnectorInboxItem).toHaveBeenCalledWith('c1', 'i1'))
    await waitFor(() => expect(screen.queryByText('broken.csv')).not.toBeInTheDocument())
  })
})
