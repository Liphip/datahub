import { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuthStore } from '../stores/auth';
import api from '../services/api';
import type { CodeModule } from '../types';

export default function Modules() {
  const { user } = useAuthStore();
  const navigate = useNavigate();
  const [modules, setModules] = useState<CodeModule[]>([]);
  const [loading, setLoading] = useState(true);
  const [filter, setFilter] = useState<string>('all');
  const [showCreateModal, setShowCreateModal] = useState(false);
  const [newModuleName, setNewModuleName] = useState('');
  const [newModuleType, setNewModuleType] = useState('PROCESSING');
  const [newModuleDescription, setNewModuleDescription] = useState('');
  const [creating, setCreating] = useState(false);

  const tenantId = user?.tenantId || user?.id;

  useEffect(() => {
    const fetchModules = async () => {
      if (!tenantId) {
        setLoading(false);
        return;
      }
      
      try {
        const data = await api.getModules(tenantId, filter === 'all' ? undefined : filter);
        setModules(data);
      } catch (error) {
        console.error('Error fetching modules:', error);
      } finally {
        setLoading(false);
      }
    };
    
    fetchModules();
  }, [tenantId, filter]);

  const handleCreateModule = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!tenantId || !newModuleName) return;
    
    setCreating(true);
    try {
      const newModule = await api.createModule(tenantId, newModuleName, newModuleType, newModuleDescription);
      setShowCreateModal(false);
      setNewModuleName('');
      setNewModuleType('PROCESSING');
      setNewModuleDescription('');
      navigate(`/modules/${newModule.id}`);
    } catch (error) {
      console.error('Error creating module:', error);
    } finally {
      setCreating(false);
    }
  };

  const moduleTypeLabels: Record<string, string> = {
    IMPORT: 'Import',
    PROCESSING: 'Processing',
    EXPORT: 'Export',
    ALERT: 'Alert',
  };

  const moduleTypeColors: Record<string, string> = {
    IMPORT: 'bg-blue-100 text-blue-800',
    PROCESSING: 'bg-purple-100 text-purple-800',
    EXPORT: 'bg-green-100 text-green-800',
    ALERT: 'bg-red-100 text-red-800',
  };

  return (
    <div className="px-4 py-6 sm:px-0">
      <div className="flex justify-between items-center mb-6">
        <h1 className="text-2xl font-bold text-gray-900">Modules</h1>
        <button onClick={() => setShowCreateModal(true)} className="btn-primary">
          Create Module
        </button>
      </div>

      <div className="mb-4 flex space-x-2">
        {['all', 'IMPORT', 'PROCESSING', 'EXPORT', 'ALERT'].map((type) => (
          <button
            key={type}
            onClick={() => setFilter(type)}
            className={`px-4 py-2 rounded-lg text-sm font-medium ${
              filter === type
                ? 'bg-primary-600 text-white'
                : 'bg-gray-100 text-gray-700 hover:bg-gray-200'
            }`}
          >
            {type === 'all' ? 'All' : moduleTypeLabels[type]}
          </button>
        ))}
      </div>

      {loading ? (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
          {[1, 2, 3].map(i => (
            <div key={i} className="card animate-pulse">
              <div className="h-4 bg-gray-200 rounded w-1/2 mb-4"></div>
              <div className="h-3 bg-gray-200 rounded w-3/4"></div>
            </div>
          ))}
        </div>
      ) : !tenantId ? (
        <div className="card text-center py-12">
          <p className="text-gray-500">No tenant ID found. Please contact support.</p>
        </div>
      ) : modules.length > 0 ? (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
          {modules.map((module) => (
            <Link
              key={module.id}
              to={`/modules/${module.id}`}
              className="card hover:shadow-lg transition-shadow"
            >
              <div className="flex justify-between items-start mb-2">
                <h3 className="text-lg font-semibold text-gray-900">{module.name}</h3>
                <span className={`px-2 py-1 text-xs font-medium rounded ${moduleTypeColors[module.moduleType]}`}>
                  {moduleTypeLabels[module.moduleType]}
                </span>
              </div>
              <p className="text-sm text-gray-500 mb-4">
                {module.description || 'No description'}
              </p>
              <div className="flex justify-between items-center text-sm text-gray-500">
                <span>v{module.versions?.[0]?.version || 1}</span>
                <span>{module.versions?.length || 0} version(s)</span>
              </div>
            </Link>
          ))}
        </div>
      ) : (
        <div className="card text-center py-12">
          <p className="text-gray-500 mb-4">No modules yet</p>
          <button onClick={() => setShowCreateModal(true)} className="btn-primary">Create Your First Module</button>
        </div>
      )}

      {showCreateModal && (
        <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
          <div className="bg-white rounded-lg p-6 max-w-md w-full">
            <h2 className="text-xl font-bold mb-4">Create Module</h2>
            <form onSubmit={handleCreateModule}>
              <div className="mb-4">
                <label className="block text-sm font-medium text-gray-700 mb-1">Name</label>
                <input
                  type="text"
                  value={newModuleName}
                  onChange={(e) => setNewModuleName(e.target.value)}
                  className="input w-full"
                  required
                />
              </div>
              <div className="mb-4">
                <label className="block text-sm font-medium text-gray-700 mb-1">Type</label>
                <select
                  value={newModuleType}
                  onChange={(e) => setNewModuleType(e.target.value)}
                  className="input w-full"
                >
                  <option value="IMPORT">Import</option>
                  <option value="PROCESSING">Processing</option>
                  <option value="EXPORT">Export</option>
                  <option value="ALERT">Alert</option>
                </select>
              </div>
              <div className="mb-4">
                <label className="block text-sm font-medium text-gray-700 mb-1">Description</label>
                <textarea
                  value={newModuleDescription}
                  onChange={(e) => setNewModuleDescription(e.target.value)}
                  className="input w-full"
                  rows={3}
                />
              </div>
              <div className="flex justify-end space-x-2">
                <button
                  type="button"
                  onClick={() => setShowCreateModal(false)}
                  className="px-4 py-2 border border-gray-300 rounded-md text-gray-700 hover:bg-gray-50"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  disabled={creating}
                  className="btn-primary"
                >
                  {creating ? 'Creating...' : 'Create'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}
