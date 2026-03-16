import { useEffect, useState } from 'react';
import { useAuthStore } from '../stores/auth';
import api from '../services/api';
import type { Connector } from '../types';

const connectorTypes = ['MQTT', 'REST', 'WEBSOCKET', 'UDP', 'FILE', 'CUSTOM'] as const;
const directions = ['INPUT', 'OUTPUT', 'BOTH'] as const;

export default function Connectors() {
  const { user } = useAuthStore();
  const [connectors, setConnectors] = useState<Connector[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [showModal, setShowModal] = useState(false);
  const [formData, setFormData] = useState({
    name: '',
    connectorType: 'MQTT',
    direction: 'INPUT',
    config: '{}',
    description: ''
  });

  const tenantId = user?.tenantId || user?.id;

  useEffect(() => {
    const fetchConnectors = async () => {
      if (!tenantId) {
        setLoading(false);
        return;
      }

      try {
        const data = await api.getConnectors(tenantId);
        setConnectors(data);
      } catch (err) {
        console.error('Error fetching connectors:', err);
        setError('Failed to load connectors');
      } finally {
        setLoading(false);
      }
    };

    fetchConnectors();
  }, [tenantId]);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!tenantId) return;

    try {
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

      setShowModal(false);
      setFormData({
        name: '',
        connectorType: 'MQTT',
        direction: 'INPUT',
        config: '{}',
        description: ''
      });

      const data = await api.getConnectors(tenantId);
      setConnectors(data);
    } catch (err) {
      console.error('Error creating connector:', err);
      setError('Failed to create connector');
    }
  };

  const handleDelete = async (id: string) => {
    if (!confirm('Are you sure you want to delete this connector?')) return;

    try {
      await api.deleteConnector(id);
      if (tenantId) {
        const data = await api.getConnectors(tenantId);
        setConnectors(data);
      }
    } catch (err) {
      console.error('Error deleting connector:', err);
      setError('Failed to delete connector');
    }
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
      <div className="mb-6 flex justify-between items-center">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">Connectors</h1>
          <p className="mt-1 text-sm text-gray-600">
            Manage protocol interface definitions (MQTT, REST, WebSocket, UDP, etc.)
          </p>
        </div>
        <button
          onClick={() => setShowModal(true)}
          className="btn-primary"
        >
          Create Connector
        </button>
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
              onClick={() => setShowModal(true)}
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
                  onClick={() => handleDelete(connector.id)}
                  className="text-red-600 hover:text-red-800 text-sm"
                >
                  Delete
                </button>
              </div>
            </div>
          ))}
        </div>
      )}

      {showModal && (
        <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
          <div className="bg-white rounded-lg p-6 max-w-md w-full mx-4">
            <h2 className="text-xl font-bold mb-4">Create Connector</h2>
            
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
                    Config (JSON)
                  </label>
                  <textarea
                    value={formData.config}
                    onChange={(e) => setFormData({ ...formData, config: e.target.value })}
                    className="input font-mono text-sm"
                    rows={4}
                    placeholder='{"broker": "mqtt://localhost:1883", "topic": "sensors/#"}'
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
                  onClick={() => setShowModal(false)}
                  className="btn-secondary"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  className="btn-primary"
                >
                  Create
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}
