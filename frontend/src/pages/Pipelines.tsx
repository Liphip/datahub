import { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuthStore } from '../stores/auth';
import api from '../services/api';
import type { Pipeline, PipelineRun } from '../types';

type RunMap = Record<string, PipelineRun[]>;

export default function Pipelines() {
  const { user, selectedTenantId } = useAuthStore();
  const navigate = useNavigate();
  const [pipelines, setPipelines] = useState<Pipeline[]>([]);
  const [runsMap, setRunsMap] = useState<RunMap>({});
  const [loading, setLoading] = useState(true);
  const [showCreateModal, setShowCreateModal] = useState(false);
  const [newPipelineName, setNewPipelineName] = useState('');
  const [newPipelineDescription, setNewPipelineDescription] = useState('');
  const [creating, setCreating] = useState(false);
  const [triggeringId, setTriggeringId] = useState<string | null>(null);
  const [deletingId, setDeletingId] = useState<string | null>(null);
  const [confirmDeleteId, setConfirmDeleteId] = useState<string | null>(null);
  const [toast, setToast] = useState<{ message: string; type: 'success' | 'error' } | null>(null);
  const [error, setError] = useState('');

  const tenantId = selectedTenantId || user?.tenantId;

  const showToast = (message: string, type: 'success' | 'error') => {
    setToast({ message, type });
    setTimeout(() => setToast(null), 3500);
  };

  const loadPipelines = async (tid: string) => {
    const data = await api.getPipelines(tid);
    setPipelines(data);
    return data;
  };

  useEffect(() => {
    const fetchAll = async () => {
      if (!tenantId) { setLoading(false); return; }
      try {
        const data = await loadPipelines(tenantId);
        // Fetch latest 3 runs for each pipeline concurrently
        const runsEntries = await Promise.all(
          data.map(async (p) => {
            try {
              const runs = await api.getPipelineRuns(p.id);
              return [p.id, runs.slice(0, 3)] as [string, PipelineRun[]];
            } catch {
              return [p.id, []] as [string, PipelineRun[]];
            }
          })
        );
        setRunsMap(Object.fromEntries(runsEntries));
        setError('');
      } catch (e) {
        console.error('Error fetching pipelines:', e);
        setError('Failed to load pipelines');
      } finally {
        setLoading(false);
      }
    };
    fetchAll();
  }, [tenantId]);

  useEffect(() => {
    if (!tenantId || pipelines.length === 0) return;

    const hasActiveRuns = Object.values(runsMap).some((runs) =>
      runs.some((run) => run.status === 'RUNNING' || run.status === 'PENDING')
    );
    if (!hasActiveRuns) return;

    const intervalId = window.setInterval(async () => {
      const entries = await Promise.all(
        pipelines.map(async (p) => {
          try {
            const runs = await api.getPipelineRuns(p.id);
            return [p.id, runs.slice(0, 3)] as [string, PipelineRun[]];
          } catch {
            return [p.id, runsMap[p.id] ?? []] as [string, PipelineRun[]];
          }
        })
      );
      setRunsMap(Object.fromEntries(entries));
    }, 8000);

    return () => window.clearInterval(intervalId);
  }, [tenantId, pipelines, runsMap]);

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
    } catch {
      showToast('Failed to create pipeline', 'error');
    } finally {
      setCreating(false);
    }
  };

  const handleRunPipeline = async (pipelineId: string, e: React.MouseEvent) => {
    e.preventDefault();
    e.stopPropagation();
    setTriggeringId(pipelineId);
    try {
      await api.triggerPipeline(pipelineId);
      showToast('Pipeline triggered successfully', 'success');
      // Refresh runs for this pipeline
      const runs = await api.getPipelineRuns(pipelineId);
      setRunsMap((prev) => ({ ...prev, [pipelineId]: runs.slice(0, 3) }));
    } catch {
      showToast('Failed to trigger pipeline', 'error');
    } finally {
      setTriggeringId(null);
    }
  };

  const handleDeletePipeline = async (pipelineId: string, e: React.MouseEvent) => {
    e.preventDefault();
    e.stopPropagation();
    setDeletingId(pipelineId);
    try {
      await api.deletePipeline(pipelineId);
      if (tenantId) await loadPipelines(tenantId);
      setRunsMap((prev) => { const next = { ...prev }; delete next[pipelineId]; return next; });
      setConfirmDeleteId(null);
      showToast('Pipeline deleted', 'success');
    } catch {
      showToast('Failed to delete pipeline', 'error');
    } finally {
      setDeletingId(null);
    }
  };

  const runStatusColor: Record<string, string> = {
    SUCCESS: 'bg-green-500',
    FAILED: 'bg-red-500',
    RUNNING: 'bg-yellow-400 animate-pulse',
    PENDING: 'bg-gray-400',
    CANCELLED: 'bg-gray-300',
  };

  const graphNodeCount = (p: Pipeline) =>
    (p.graphDefinition?.nodes?.length ?? 0) ||
    (1 + (p.processingModuleVersionIds?.length ?? 0) + (p.exportModuleVersionIds?.length ?? 0));

  return (
    <div className="px-4 py-6 sm:px-0">
      {/* Toast */}
      {toast && (
        <div className={`fixed top-4 right-4 z-50 px-4 py-3 rounded shadow-lg text-white text-sm ${
          toast.type === 'success' ? 'bg-green-600' : 'bg-red-600'
        }`}>
          {toast.message}
        </div>
      )}

      <div className="flex justify-between items-center mb-6">
        <h1 className="text-2xl font-bold text-gray-900">Pipelines</h1>
        <button onClick={() => setShowCreateModal(true)} className="btn-primary">
          Create Pipeline
        </button>
      </div>

      {error && (
        <div className="mb-4 bg-red-50 border border-red-200 text-red-700 px-4 py-3 rounded">
          {error}
        </div>
      )}

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
          <p className="text-gray-500">No tenant ID found.</p>
        </div>
      ) : pipelines.length > 0 ? (
        <div className="space-y-4">
          {pipelines.map((pipeline) => {
            const runs = runsMap[pipeline.id] ?? [];
            const isTriggering = triggeringId === pipeline.id;
            const isDeleting = deletingId === pipeline.id;
            const confirmingDelete = confirmDeleteId === pipeline.id;

            return (
              <div key={pipeline.id} className="card hover:shadow-md transition-shadow">
                {/* Header row */}
                <div className="flex justify-between items-start">
                  <Link to={`/pipelines/${pipeline.id}`} className="flex-1 min-w-0 group">
                    <div className="flex items-center space-x-3">
                      <h3 className="text-lg font-semibold group-hover:text-primary-600 truncate">
                        {pipeline.name}
                      </h3>
                      <span className={`shrink-0 px-2 py-1 text-xs font-medium rounded ${
                        pipeline.isActive ? 'bg-green-100 text-green-800' : 'bg-gray-100 text-gray-800'
                      }`}>
                        {pipeline.isActive ? 'Active' : 'Inactive'}
                      </span>
                    </div>
                    <p className="text-sm text-gray-500 mt-1 truncate">
                      {pipeline.description || 'No description'}
                    </p>
                  </Link>

                  <div className="flex items-center space-x-2 ml-4 shrink-0">
                    {confirmingDelete ? (
                      <>
                        <span className="text-sm text-gray-600">Delete?</span>
                        <button
                          onClick={(e) => handleDeletePipeline(pipeline.id, e)}
                          disabled={isDeleting}
                          className="text-sm text-red-600 hover:text-red-800 font-medium"
                        >
                          {isDeleting ? 'Deleting…' : 'Yes'}
                        </button>
                        <button
                          onClick={(e) => { e.preventDefault(); setConfirmDeleteId(null); }}
                          className="text-sm text-gray-500 hover:text-gray-700"
                        >
                          No
                        </button>
                      </>
                    ) : (
                      <>
                        <button
                          onClick={(e) => handleRunPipeline(pipeline.id, e)}
                          disabled={isTriggering}
                          className="btn-primary text-sm"
                        >
                          {isTriggering ? 'Triggering…' : 'Run'}
                        </button>
                        <button
                          onClick={(e) => { e.preventDefault(); setConfirmDeleteId(pipeline.id); }}
                          className="text-sm text-red-600 hover:text-red-800"
                        >
                          Delete
                        </button>
                      </>
                    )}
                  </div>
                </div>

                {/* Stats row */}
                <div className="mt-3 flex space-x-6 text-sm text-gray-500">
                  <span>Nodes: {graphNodeCount(pipeline)}</span>
                  {pipeline.graphDefinition?.edges?.length != null && (
                    <span>Edges: {pipeline.graphDefinition.edges.length}</span>
                  )}
                  <span>Runs: {runs.length > 0 ? `${runs.length} recent` : 'None yet'}</span>
                </div>

                {/* Recent runs strip */}
                {runs.length > 0 && (
                  <div className="mt-3 flex items-center space-x-2">
                    <span className="text-xs text-gray-400 mr-1">Recent:</span>
                    {runs.map((run) => (
                      <span
                        key={run.id}
                        title={`${run.status} — ${new Date(run.startedAt).toLocaleString()}`}
                        className={`w-3 h-3 rounded-full ${runStatusColor[run.status] ?? 'bg-gray-400'}`}
                      />
                    ))}
                    <span className="text-xs text-gray-500 ml-2">
                      {runs[0].status === 'RUNNING' || runs[0].status === 'PENDING' ? 'Updating...' : `Last: ${runs[0].status}`}
                    </span>
                  </div>
                )}
              </div>
            );
          })}
        </div>
      ) : (
        <div className="card text-center py-12">
          <p className="text-gray-500 mb-4">No pipelines yet</p>
          <button onClick={() => setShowCreateModal(true)} className="btn-primary">
            Create Your First Pipeline
          </button>
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
                  autoFocus
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
                <button type="submit" disabled={creating} className="btn-primary">
                  {creating ? 'Creating…' : 'Create'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}
