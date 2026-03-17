import { useEffect, useState } from 'react';
import { useAuthStore } from '../stores/auth';
import api from '../services/api';
import type { Connector } from '../types';

const connectorTypes = ['MQTT', 'REST', 'WEBSOCKET', 'UDP', 'FILE', 'CUSTOM'] as const;
const directions = ['INPUT', 'OUTPUT', 'BOTH'] as const;
type ConnectorType = typeof connectorTypes[number];

type ConnectorConfigField = {
  key: string;
  label: string;
  type?: 'text' | 'url' | 'number' | 'password';
  placeholder?: string;
  required?: boolean;
  hint?: string;
};

const connectorConfigSchema: Record<ConnectorType, ConnectorConfigField[]> = {
  MQTT: [
    { key: 'broker', label: 'Broker URL', type: 'url', required: true, placeholder: 'mqtt://broker:1883' },
    { key: 'topic', label: 'Topic', required: true, placeholder: 'sensors/+/telemetry' },
    { key: 'clientId', label: 'Client ID', placeholder: 'dt-edge-ingest' },
    { key: 'username', label: 'Username' },
    { key: 'password', label: 'Password', type: 'password' },
  ],
  REST: [
    { key: 'baseUrl', label: 'Base URL', type: 'url', required: true, placeholder: 'https://api.example.com' },
    { key: 'path', label: 'Path', placeholder: '/ingest' },
    { key: 'method', label: 'Method', placeholder: 'POST' },
    { key: 'authToken', label: 'Auth Token', type: 'password' },
  ],
  WEBSOCKET: [
    { key: 'url', label: 'WebSocket URL', type: 'url', required: true, placeholder: 'wss://stream.example.com/events' },
    { key: 'path', label: 'Path', placeholder: '/socket' },
    { key: 'authToken', label: 'Auth Token', type: 'password' },
  ],
  UDP: [
    { key: 'host', label: 'Host', required: true, placeholder: 'udp-gateway.internal' },
    { key: 'port', label: 'Port', type: 'number', required: true, placeholder: '5140' },
  ],
  FILE: [
    { key: 'path', label: 'File Path', required: true, placeholder: '/var/data/input.ndjson' },
    { key: 'format', label: 'Format', placeholder: 'jsonl' },
  ],
  CUSTOM: [
    { key: 'handler', label: 'Handler ID', placeholder: 'custom.transform.v1' },
    { key: 'endpoint', label: 'Endpoint', placeholder: 'plugin://transformers/default' },
  ],
};

const initialConfigByType = (type: ConnectorType, existing?: Record<string, unknown>) => {
  const fields = connectorConfigSchema[type] || [];
  return fields.reduce<Record<string, string>>((acc, field) => {
    const value = existing?.[field.key];
    acc[field.key] = value == null ? '' : String(value);
    return acc;
  }, {});
};

export default function Connectors() {
  const { user, selectedTenantId, tenantContexts } = useAuthStore();
  const abilities = user?.abilities || [];
  const hasAbility = (ability: string) => Boolean(user?.superadmin || abilities.includes('*') || abilities.includes(ability));
  const canManageTenant = hasAbility('manage:tenant');
  const canReadConnectors = canManageTenant || hasAbility('read:connector') || hasAbility('write:connector') || hasAbility('delete:connector');
  const canWriteConnectors = canManageTenant || hasAbility('write:connector');
  const canDeleteConnectors = canManageTenant || hasAbility('delete:connector');
  const [connectors, setConnectors] = useState<Connector[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [showModal, setShowModal] = useState(false);
  const [editingConnector, setEditingConnector] = useState<Connector | null>(null);
  const [savingConnector, setSavingConnector] = useState(false);
  const [deletingId, setDeletingId] = useState<string | null>(null);
  const [togglingId, setTogglingId] = useState<string | null>(null);
  const [testingId, setTestingId] = useState<string | null>(null);
  const [confirmDeleteId, setConfirmDeleteId] = useState<string | null>(null);
  const [filterType, setFilterType] = useState<string>('ALL');
  const [filterDirection, setFilterDirection] = useState<string>('ALL');
  const [toast, setToast] = useState<{ message: string; type: 'success' | 'error' } | null>(null);
  const [formData, setFormData] = useState({
    name: '',
    connectorType: 'MQTT' as ConnectorType,
    direction: 'INPUT' as Connector['direction'],
    config: initialConfigByType('MQTT'),
    description: ''
  });

  const tenantId = selectedTenantId || user?.tenantId || tenantContexts[0]?.tenantId;

  const showToast = (message: string, type: 'success' | 'error') => {
    setToast({ message, type });
    setTimeout(() => setToast(null), 3500);
  };

  const loadConnectors = async (tid: string) => {
    const type = filterType === 'ALL' ? undefined : filterType;
    const direction = filterDirection === 'ALL' ? undefined : filterDirection;
    const data = await api.getConnectors(tid, type, direction);
    setConnectors(data);
  };

  useEffect(() => {
    const fetchConnectors = async () => {
      if (!tenantId) {
        setLoading(false);
        return;
      }
      if (!canReadConnectors) {
        setConnectors([]);
        setError('');
        setLoading(false);
        return;
      }

      try {
        await loadConnectors(tenantId);
        setError('');
      } catch (err) {
        console.error('Error fetching connectors:', err);
        setError('Failed to load connectors');
      } finally {
        setLoading(false);
      }
    };

    fetchConnectors();
  }, [tenantId, filterType, filterDirection, canReadConnectors]);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!canWriteConnectors) {
      showToast('You do not have permission to manage connectors', 'error');
      return;
    }
    if (!tenantId) return;

    try {
      setSavingConnector(true);

      if (editingConnector) {
        const requiredMissing = connectorConfigSchema[formData.connectorType]
          .filter((field) => field.required && !formData.config[field.key]?.trim())
          .map((field) => field.label);
        if (requiredMissing.length > 0) {
          setError(`Missing required config fields: ${requiredMissing.join(', ')}`);
          return;
        }

        const parsedConfig = Object.entries(formData.config).reduce<Record<string, unknown>>((acc, [key, value]) => {
          const trimmed = value.trim();
          if (!trimmed) return acc;
          if (key === 'port') {
            const parsed = Number(trimmed);
            acc[key] = Number.isFinite(parsed) ? parsed : trimmed;
          } else {
            acc[key] = trimmed;
          }
          return acc;
        }, {});

        const updatePayload: Partial<Connector> = {
          name: formData.name,
          connectorType: formData.connectorType as Connector['connectorType'],
          direction: formData.direction as Connector['direction'],
          description: formData.description,
          config: parsedConfig,
        };

        await api.updateConnector(editingConnector.id, updatePayload);
      } else {
        const requiredMissing = connectorConfigSchema[formData.connectorType]
          .filter((field) => field.required && !formData.config[field.key]?.trim())
          .map((field) => field.label);
        if (requiredMissing.length > 0) {
          setError(`Missing required config fields: ${requiredMissing.join(', ')}`);
          return;
        }

        const config = Object.entries(formData.config).reduce<Record<string, unknown>>((acc, [key, value]) => {
          const trimmed = value.trim();
          if (!trimmed) return acc;
          if (key === 'port') {
            const parsed = Number(trimmed);
            acc[key] = Number.isFinite(parsed) ? parsed : trimmed;
          } else {
            acc[key] = trimmed;
          }
          return acc;
        }, {});

        await api.createConnector(
          tenantId,
          formData.name,
          formData.connectorType,
          formData.direction,
          config,
          formData.description
        );
      }

      setShowModal(false);
      setEditingConnector(null);
      setFormData({
        name: '',
        connectorType: 'MQTT',
        direction: 'INPUT',
        config: initialConfigByType('MQTT'),
        description: ''
      });

      await loadConnectors(tenantId);
      setError('');
      showToast(editingConnector ? 'Connector updated' : 'Connector created', 'success');
    } catch (err) {
      console.error('Error saving connector:', err);
      setError(editingConnector ? 'Failed to update connector' : 'Failed to create connector');
    } finally {
      setSavingConnector(false);
    }
  };

  const handleDelete = async (id: string) => {
    if (!canDeleteConnectors) {
      showToast('You do not have permission to delete connectors', 'error');
      return;
    }
    setDeletingId(id);
    try {
      await api.deleteConnector(id);
      if (tenantId) {
        await loadConnectors(tenantId);
      }
      setConfirmDeleteId(null);
      setError('');
      showToast('Connector deleted', 'success');
    } catch (err) {
      console.error('Error deleting connector:', err);
      setError('Failed to delete connector');
    } finally {
      setDeletingId(null);
    }
  };

  const handleToggleConnection = async (connector: Connector) => {
    if (!canWriteConnectors) {
      showToast('You do not have permission to change connector state', 'error');
      return;
    }

    setTogglingId(connector.id);
    try {
      const updated = connector.isActive
        ? await api.disconnectConnector(connector.id)
        : await api.connectConnector(connector.id);
      setConnectors((prev) => prev.map((c) => (c.id === connector.id ? { ...c, ...updated } : c)));
      showToast(connector.isActive ? 'Connector disconnected' : 'Connector connected', 'success');
    } catch {
      showToast(connector.isActive ? 'Failed to disconnect connector' : 'Failed to connect connector', 'error');
    } finally {
      setTogglingId(null);
    }
  };

  const handleTestConnection = async (connector: Connector) => {
    setTestingId(connector.id);
    try {
      const result = await api.testConnector(connector.id);
      showToast(result.message || 'Connector test succeeded', 'success');
    } catch (err: any) {
      const missing = err?.response?.data?.missing;
      if (Array.isArray(missing) && missing.length > 0) {
        showToast(`Missing config: ${missing.join(', ')}`, 'error');
      } else {
        showToast(err?.response?.data?.error || 'Connector test failed', 'error');
      }
    } finally {
      setTestingId(null);
    }
  };

  const handleEdit = (connector: Connector) => {
    if (!canWriteConnectors) return;
    setEditingConnector(connector);
    setFormData({
      name: connector.name,
      connectorType: connector.connectorType,
      direction: connector.direction,
      config: initialConfigByType(connector.connectorType, connector.config),
      description: connector.description || '',
    });
    setShowModal(true);
  };

  const handleOpenCreate = () => {
    if (!canWriteConnectors) return;
    setEditingConnector(null);
    setFormData({
      name: '',
      connectorType: 'MQTT',
      direction: 'INPUT',
      config: initialConfigByType('MQTT'),
      description: '',
    });
    setShowModal(true);
  };

  const getTypeColor = (type: string) => {
    switch (type) {
      case 'MQTT': return 'bg-blue-100 text-blue-800';
      case 'REST': return 'bg-green-100 text-green-800';
      case 'WEBSOCKET': return 'bg-purple-100 text-purple-800';
      case 'UDP': return 'bg-orange-100 text-orange-800';
      case 'FILE': return 'bg-gray-100 text-gray-800';
      default: return 'bg-yellow-100 text-yellow-800';
    }
  };

  const getDirectionColor = (direction: string) => {
    switch (direction) {
      case 'INPUT': return 'bg-green-100 text-green-800';
      case 'OUTPUT': return 'bg-red-100 text-red-800';
      case 'BOTH': return 'bg-purple-100 text-purple-800';
      default: return 'bg-gray-100 text-gray-800';
    }
  };

  if (loading) {
    return (
      <div className="px-4 py-6 sm:px-0">
        <div className="animate-pulse space-y-4">
          <div className="h-8 bg-gray-200 rounded w-1/4"></div>
          <div className="h-32 bg-gray-200 rounded"></div>
        </div>
      </div>
    );
  }

  if (!canReadConnectors) {
    return (
      <div className="px-4 py-6 sm:px-0">
        <div className="card text-center py-12">
          <p className="text-gray-500">You do not have permission to view connectors for this tenant.</p>
        </div>
      </div>
    );
  }

  return (
    <div className="px-4 py-6 sm:px-0">
      {toast && (
        <div className={`fixed top-4 right-4 z-50 px-4 py-3 rounded shadow-lg text-white text-sm ${
          toast.type === 'success' ? 'bg-green-600' : 'bg-red-600'
        }`}>
          {toast.message}
        </div>
      )}

      <div className="mb-6 flex justify-between items-center">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">Connectors</h1>
          <p className="mt-1 text-sm text-gray-600">
            Manage protocol interface definitions (MQTT, REST, WebSocket, UDP, etc.)
          </p>
        </div>
        {canWriteConnectors && (
          <button
            onClick={handleOpenCreate}
            className="btn-primary"
          >
            Create Connector
          </button>
        )}
      </div>

      <div className="card mb-4">
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">Type Filter</label>
            <select value={filterType} onChange={(e) => setFilterType(e.target.value)} className="input">
              <option value="ALL">All types</option>
              {connectorTypes.map((type) => (
                <option key={type} value={type}>{type}</option>
              ))}
            </select>
          </div>
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">Direction Filter</label>
            <select value={filterDirection} onChange={(e) => setFilterDirection(e.target.value)} className="input">
              <option value="ALL">All directions</option>
              {directions.map((direction) => (
                <option key={direction} value={direction}>{direction}</option>
              ))}
            </select>
          </div>
        </div>
      </div>

      {error && (
        <div className="mb-4 bg-red-50 border border-red-200 text-red-700 px-4 py-3 rounded">
          {error}
        </div>
      )}

      {connectors.length === 0 ? (
        <div className="card text-center py-12">
          <svg className="mx-auto h-12 w-12 text-gray-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13.828 10.172a4 4 0 00-5.656 0l-4 4a4 4 0 105.656 5.656l1.102-1.101m-.758-4.899a4 4 0 005.656 0l4-4a4 4 0 00-5.656-5.656l-1.1 1.1" />
          </svg>
          <h3 className="mt-2 text-sm font-medium text-gray-900">No connectors</h3>
          <p className="mt-1 text-sm text-gray-500">Get started by creating your first connector.</p>
          {canWriteConnectors && (
            <div className="mt-6">
              <button
                onClick={handleOpenCreate}
                className="btn-primary"
              >
                Create Connector
              </button>
            </div>
          )}
        </div>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
          {connectors.map((connector) => (
            <div key={connector.id} className="card">
              <div className="flex justify-between items-start mb-2">
                <h3 className="text-lg font-semibold text-gray-900">{connector.name}</h3>
                <div className="flex space-x-1">
                  <span className={`px-2 py-1 text-xs font-medium rounded ${getTypeColor(connector.connectorType)}`}>
                    {connector.connectorType}
                  </span>
                  <span className={`px-2 py-1 text-xs font-medium rounded ${getDirectionColor(connector.direction)}`}>
                    {connector.direction}
                  </span>
                  <span className={`px-2 py-1 text-xs font-medium rounded ${connector.isActive ? 'bg-emerald-100 text-emerald-800' : 'bg-gray-100 text-gray-700'}`}>
                    {connector.isActive ? 'Connected' : 'Disconnected'}
                  </span>
                </div>
              </div>
              
              {connector.description && (
                <p className="text-sm text-gray-600 mb-3">{connector.description}</p>
              )}
              
              <div className="text-xs text-gray-500 mb-3">
                Created: {new Date(connector.createdAt).toLocaleDateString()}
              </div>
              
              <div className="flex justify-end space-x-2">
                <button
                  onClick={() => handleTestConnection(connector)}
                  disabled={testingId === connector.id}
                  className="text-indigo-600 hover:text-indigo-800 text-sm disabled:opacity-40"
                >
                  {testingId === connector.id ? 'Testing...' : 'Test'}
                </button>
                {canWriteConnectors && (
                  <button
                    onClick={() => handleToggleConnection(connector)}
                    disabled={togglingId === connector.id}
                    className="text-emerald-700 hover:text-emerald-900 text-sm disabled:opacity-40"
                  >
                    {togglingId === connector.id ? 'Working...' : connector.isActive ? 'Disconnect' : 'Connect'}
                  </button>
                )}
                {canWriteConnectors && (
                  <button
                    onClick={() => handleEdit(connector)}
                    className="text-blue-600 hover:text-blue-800 text-sm"
                  >
                    Edit
                  </button>
                )}
                {canDeleteConnectors && (confirmDeleteId === connector.id ? (
                  <>
                    <span className="text-xs text-gray-500">Delete?</span>
                    <button
                      onClick={() => handleDelete(connector.id)}
                      disabled={deletingId === connector.id}
                      className="text-red-600 hover:text-red-800 text-sm"
                    >
                      {deletingId === connector.id ? 'Deleting...' : 'Yes'}
                    </button>
                    <button
                      onClick={() => setConfirmDeleteId(null)}
                      className="text-gray-500 hover:text-gray-700 text-sm"
                    >
                      No
                    </button>
                  </>
                ) : (
                  <button
                    onClick={() => setConfirmDeleteId(connector.id)}
                    className="text-red-600 hover:text-red-800 text-sm"
                  >
                    Delete
                  </button>
                ))}
              </div>
            </div>
          ))}
        </div>
      )}

      {showModal && canWriteConnectors && (
        <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
          <div className="bg-white rounded-lg p-6 max-w-md w-full mx-4">
            <h2 className="text-xl font-bold mb-4">
              {editingConnector ? 'Edit Connector' : 'Create Connector'}
            </h2>
            
            <form onSubmit={handleSubmit}>
              <div className="space-y-4">
                <div>
                  <label className="block text-sm font-medium text-gray-700 mb-1">
                    Name
                  </label>
                  <input
                    type="text"
                    required
                    value={formData.name}
                    onChange={(e) => setFormData({ ...formData, name: e.target.value })}
                    className="input"
                    placeholder="My MQTT Broker"
                  />
                </div>
                
                <div>
                  <label className="block text-sm font-medium text-gray-700 mb-1">
                    Type
                  </label>
                  <select
                    value={formData.connectorType}
                    onChange={(e) => {
                      const nextType = e.target.value as ConnectorType;
                      setFormData({
                        ...formData,
                        connectorType: nextType,
                        config: initialConfigByType(nextType, formData.config),
                      });
                    }}
                    className="input"
                  >
                    {connectorTypes.map((type) => (
                      <option key={type} value={type}>{type}</option>
                    ))}
                  </select>
                </div>
                
                <div>
                  <label className="block text-sm font-medium text-gray-700 mb-1">
                    Direction
                  </label>
                  <select
                    value={formData.direction}
                    onChange={(e) => setFormData({ ...formData, direction: e.target.value as Connector['direction'] })}
                    className="input"
                  >
                    {directions.map((dir) => (
                      <option key={dir} value={dir}>{dir}</option>
                    ))}
                  </select>
                </div>
                
                <div className="rounded border border-slate-200 dark:border-slate-700 p-3">
                  <label className="block text-sm font-medium text-slate-700 dark:text-slate-200 mb-2">
                    {formData.connectorType} Configuration
                  </label>
                  <div className="grid grid-cols-1 gap-3">
                    {(connectorConfigSchema[formData.connectorType] || []).map((field) => (
                      <div key={field.key}>
                        <label className="block text-sm text-slate-600 dark:text-slate-300 mb-1">
                          {field.label}{field.required ? ' *' : ''}
                        </label>
                        <input
                          type={field.type || 'text'}
                          value={formData.config[field.key] || ''}
                          onChange={(e) => setFormData({
                            ...formData,
                            config: { ...formData.config, [field.key]: e.target.value },
                          })}
                          className="input"
                          placeholder={field.placeholder}
                          required={field.required}
                        />
                        {field.hint && <p className="text-xs text-slate-500 mt-1">{field.hint}</p>}
                      </div>
                    ))}
                  </div>
                  <p className="mt-2 text-xs text-slate-500">
                    Direction: <strong>{formData.direction}</strong> - use INPUT for ingest, OUTPUT for delivery, BOTH for bidirectional adapters.
                  </p>
                </div>
                
                <div>
                  <label className="block text-sm font-medium text-gray-700 mb-1">
                    Description
                  </label>
                  <textarea
                    value={formData.description}
                    onChange={(e) => setFormData({ ...formData, description: e.target.value })}
                    className="input"
                    rows={2}
                    placeholder="Optional description"
                  />
                </div>
              </div>
              
              <div className="mt-6 flex justify-end space-x-3">
                <button
                  type="button"
                  onClick={() => {
                    setShowModal(false);
                    setEditingConnector(null);
                  }}
                  className="btn-secondary"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  disabled={savingConnector}
                  className="btn-primary"
                >
                  {savingConnector ? 'Saving...' : editingConnector ? 'Save Changes' : 'Create'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}
