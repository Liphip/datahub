import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { useAuthStore } from '../stores/auth';
import api from '../services/api';
import type { Pipeline } from '../types';

export default function Pipelines() {
  const { user } = useAuthStore();
  const [pipelines, setPipelines] = useState<Pipeline[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const fetchPipelines = async () => {
      if (!user?.tenantId) return;
      
      try {
        const data = await api.getPipelines(user.tenantId);
        setPipelines(data);
      } catch (error) {
        console.error('Error fetching pipelines:', error);
      } finally {
        setLoading(false);
      }
    };
    
    fetchPipelines();
  }, [user?.tenantId]);

  return (
    <div className="px-4 py-6 sm:px-0">
      <div className="flex justify-between items-center mb-6">
        <h1 className="text-2xl font-bold text-gray-900">Pipelines</h1>
        <button className="btn-primary">
          Create Pipeline
        </button>
      </div>

      {loading ? (
        <div className="space-y-4">
          {[1, 2, 3].map(i => (
            <div key={i} className="card animate-pulse">
              <div className="h-4 bg-gray-200 rounded w-1/3 mb-2"></div>
              <div className="h-3 bg-gray-200 rounded w-1/2"></div>
            </div>
          ))}
        </div>
      ) : pipelines.length > 0 ? (
        <div className="space-y-4">
          {pipelines.map((pipeline) => (
            <Link
              key={pipeline.id}
              to={`/pipelines/${pipeline.id}`}
              className="card hover:shadow-lg transition-shadow block"
            >
              <div className="flex justify-between items-start">
                <div>
                  <div className="flex items-center space-x-3">
                    <h3 className="text-lg font-semibold">{pipeline.name}</h3>
                    <span className={`px-2 py-1 text-xs font-medium rounded ${
                      pipeline.isActive 
                        ? 'bg-green-100 text-green-800' 
                        : 'bg-gray-100 text-gray-800'
                    }`}>
                      {pipeline.isActive ? 'Active' : 'Inactive'}
                    </span>
                  </div>
                  <p className="text-sm text-gray-500 mt-1">
                    {pipeline.description || 'No description'}
                  </p>
                </div>
                <button className="btn-primary text-sm">
                  Run
                </button>
              </div>
              
              <div className="mt-4 flex space-x-6 text-sm text-gray-500">
                <span>Import: {pipeline.importModuleVersionId ? 'Configured' : 'None'}</span>
                <span>Processing: {pipeline.processingModuleVersionIds.length} module(s)</span>
                <span>Export: {pipeline.exportModuleVersionIds.length} module(s)</span>
              </div>
            </Link>
          ))}
        </div>
      ) : (
        <div className="card text-center py-12">
          <p className="text-gray-500 mb-4">No pipelines yet</p>
          <button className="btn-primary">Create Your First Pipeline</button>
        </div>
      )}
    </div>
  );
}
