import { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuthStore } from '../stores/auth';
import api from '../services/api';
import type { Pipeline } from '../types';

export default function Pipelines() {
  const { user } = useAuthStore();
  const navigate = useNavigate();
  const [pipelines, setPipelines] = useState<Pipeline[]>([]);
  const [loading, setLoading] = useState(true);
  const [showCreateModal, setShowCreateModal] = useState(false);
  const [newPipelineName, setNewPipelineName] = useState('');
  const [newPipelineDescription, setNewPipelineDescription] = useState('');
  const [creating, setCreating] = useState(false);

  const tenantId = user?.tenantId;

  useEffect(() => {
    const fetchPipelines = async () => {
      if (!tenantId) {
        setLoading(false);
        return;
      }
      
      try {
        const data = await api.getPipelines(tenantId);
        setPipelines(data);
      } catch (error) {
        console.error('Error fetching pipelines:', error);
      } finally {
        setLoading(false);
      }
    };
    
    fetchPipelines();
  }, [tenantId]);

  const handleCreatePipeline = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!tenantId || !newPipelineName) return;
    
    setCreating(true);
    try {
      const newPipeline = await api.createPipeline(tenantId, newPipelineName, { description: newPipelineDescription });
      setShowCreateModal(false);
      setNewPipelineName('');
      setNewPipelineDescription('');
      navigate(`/pipelines/${newPipeline.id}`);
    } catch (error) {
      console.error('Error creating pipeline:', error);
    } finally {
      setCreating(false);
    }
  };

  const handleRunPipeline = async (pipelineId: string, e: React.MouseEvent) => {
    e.preventDefault();
    e.stopPropagation();
    try {
      await api.triggerPipeline(pipelineId);
      alert('Pipeline triggered successfully!');
    } catch (error) {
      console.error('Error running pipeline:', error);
      alert('Failed to trigger pipeline');
    }
  };

  return (
    <div className="px-4 py-6 sm:px-0">
      <div className="flex justify-between items-center mb-6">
        <h1 className="text-2xl font-bold text-gray-900">Pipelines</h1>
        <button onClick={() => setShowCreateModal(true)} className="btn-primary">
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
      ) : !tenantId ? (
        <div className="card text-center py-12">
          <p className="text-gray-500">No tenant ID found. Please contact support.</p>
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
                <button 
                  onClick={(e) => handleRunPipeline(pipeline.id, e)}
                  className="btn-primary text-sm"
                >
                  Run
                </button>
              </div>
              
              <div className="mt-4 flex space-x-6 text-sm text-gray-500">
                <span>Import: {pipeline.importModuleVersionId ? 'Configured' : 'None'}</span>
                <span>Processing: {pipeline.processingModuleVersionIds?.length || 0} module(s)</span>
                <span>Export: {pipeline.exportModuleVersionIds?.length || 0} module(s)</span>
              </div>
            </Link>
          ))}
        </div>
      ) : (
        <div className="card text-center py-12">
          <p className="text-gray-500 mb-4">No pipelines yet</p>
          <button onClick={() => setShowCreateModal(true)} className="btn-primary">Create Your First Pipeline</button>
        </div>
      )}

      {showCreateModal && (
        <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
          <div className="bg-white rounded-lg p-6 max-w-md w-full">
            <h2 className="text-xl font-bold mb-4">Create Pipeline</h2>
            <form onSubmit={handleCreatePipeline}>
              <div className="mb-4">
                <label className="block text-sm font-medium text-gray-700 mb-1">Name</label>
                <input
                  type="text"
                  value={newPipelineName}
                  onChange={(e) => setNewPipelineName(e.target.value)}
                  className="input w-full"
                  required
                />
              </div>
              <div className="mb-4">
                <label className="block text-sm font-medium text-gray-700 mb-1">Description</label>
                <textarea
                  value={newPipelineDescription}
                  onChange={(e) => setNewPipelineDescription(e.target.value)}
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
