import { useEffect, useState } from 'react';
import { useAuthStore } from '../stores/auth';
import api from '../services/api';
import type { Connector } from '../types';

const connectorTypes = ['MQTT', 'REST', 'WEBSOCKET', 'UDP', 'FILE', 'CUSTOM'] as const;
const directions = ['INPUT', 'OUTPUT', 'BOTH'] as const;

export default function Connectors() {
  const { user, selectedTenantId } = useAuthStore();
  const [connectors, setConnectors] = useState<Connector[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [showModal, setShowModal] = useState(false);
  const [editingConnector, setEditingConnector] = useState<Connector | null>(null);
  const [savingConnector, setSavingConnector] = useState(false);
  const [deletingId, setDeletingId] = useState<string | null>(null);
  const [confirmDeleteId, setConfirmDeleteId] = useState<string | null>(null);
  const [filterType, setFilterType] = useState<string>('ALL');
  const [filterDirection, setFilterDirection] = useState<string>('ALL');
  const [toast, setToast] = useState<{ message: string; type: 'success' | 'error' } | null>(null);
  const [formData, setFormData] = useState({
    name: '',
    connectorType: 'MQTT',
    direction: 'INPUT',
    config: '{}',
    description: ''
  });

  const tenantId = selectedTenantId || user?.tenantId;

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
  }, [tenantId, filterType, filterDirection]);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!tenantId) return;

    try {
      setSavingConnector(true);

      if (editingConnector) {
        const updatePayload: Partial<Connector> = {
          name: formData.name,
          connectorType: formData.connectorType as Connector['connectorType'],
          direction: formData.direction as Connector['direction'],
          description: formData.description,
        };

        if (formData.config.trim()) {
          try {
            updatePayload.config = JSON.parse(formData.config);
          } catch {
            setError('Invalid JSON in config field');
            return;
          }
        }

        await api.updateConnector(editingConnector.id, updatePayload);
      } else {
        let config: Record<string, unknown> = {};
        try {
          config = JSON.parse(formData.config);
        } catch {
          setError('Invalid JSON in config field');
          return;
        }

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
        config: '{}',
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

  const handleEdit = (connector: Connector) => {
    setEditingConnector(connector);
    setFormData({
      name: connector.name,
      connectorType: connector.connectorType,
      direction: connector.direction,
      config: '',
      description: connector.description || '',
    });
    setShowModal(true);
  };

  const handleOpenCreate = () => {
    setEditingConnector(null);
    setFormData({
      name: '',
      connectorType: 'MQTT',
      direction: 'INPUT',
      config: '{}',
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
        <button
          onClick={handleOpenCreate}
          className="btn-primary"
        >
          Create Connector
        </button>
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
          <div className="mt-6">
            <button
              onClick={handleOpenCreate}
              className="btn-primary"
            >
              Create Connector
            </button>
          </div>
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
                  onClick={() => handleEdit(connector)}
                  className="text-blue-600 hover:text-blue-800 text-sm"
                >
                  Edit
                </button>
                {confirmDeleteId === connector.id ? (
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
                )}
              </div>
            </div>
          ))}
        </div>
      )}

      {showModal && (
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
                    onChange={(e) => setFormData({ ...formData, connectorType: e.target.value })}
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
                    onChange={(e) => setFormData({ ...formData, direction: e.target.value })}
                    className="input"
                  >
                    {directions.map((dir) => (
                      <option key={dir} value={dir}>{dir}</option>
                    ))}
                  </select>
                </div>
                
                <div>
                  <label className="block text-sm font-medium text-gray-700 mb-1">
                    {editingConnector ? 'Config Override (JSON)' : 'Config (JSON)'}
                  </label>
                  <textarea
                    value={formData.config}
                    onChange={(e) => setFormData({ ...formData, config: e.target.value })}
                    className="input font-mono text-sm"
                    rows={4}
                    placeholder={editingConnector
                      ? 'Leave empty to keep existing encrypted config'
                      : '{"broker": "mqtt://localhost:1883", "topic": "sensors/#"}'}
                  />
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
