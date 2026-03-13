import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { useAuthStore } from '../stores/auth';
import api from '../services/api';
import type { CodeModule } from '../types';

export default function Modules() {
  const { user } = useAuthStore();
  const [modules, setModules] = useState<CodeModule[]>([]);
  const [loading, setLoading] = useState(true);
  const [filter, setFilter] = useState<string>('all');

  useEffect(() => {
    const fetchModules = async () => {
      if (!user?.tenantId) return;
      
      try {
        const data = await api.getModules(user.tenantId, filter === 'all' ? undefined : filter);
        setModules(data);
      } catch (error) {
        console.error('Error fetching modules:', error);
      } finally {
        setLoading(false);
      }
    };
    
    fetchModules();
  }, [user?.tenantId, filter]);

  const moduleTypeLabels: Record<string, string> = {
    IMPORT: 'Import',
    PROCESSING: 'Processing',
    EXPORT: 'Export',
  };

  const moduleTypeColors: Record<string, string> = {
    IMPORT: 'bg-blue-100 text-blue-800',
    PROCESSING: 'bg-purple-100 text-purple-800',
    EXPORT: 'bg-green-100 text-green-800',
  };

  return (
    <div className="px-4 py-6 sm:px-0">
      <div className="flex justify-between items-center mb-6">
        <h1 className="text-2xl font-bold text-gray-900">Modules</h1>
        <button className="btn-primary">
          Create Module
        </button>
      </div>

      <div className="mb-4 flex space-x-2">
        {['all', 'IMPORT', 'PROCESSING', 'EXPORT'].map((type) => (
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
                <span>v{module.versions[0]?.version || 1}</span>
                <span>{module.versions.length} version(s)</span>
              </div>
            </Link>
          ))}
        </div>
      ) : (
        <div className="card text-center py-12">
          <p className="text-gray-500 mb-4">No modules yet</p>
          <button className="btn-primary">Create Your First Module</button>
        </div>
      )}
    </div>
  );
}
