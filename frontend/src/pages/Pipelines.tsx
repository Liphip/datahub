import { useEffect, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { useAuthStore } from '../stores/auth';
import api from '../services/api';
import type { Pipeline, PipelineRun, TenantPipelineRun } from '../types';

type RunMap = Record<string, PipelineRun[]>;
type HealthFilter = 'ALL' | 'PROBLEMATIC' | 'RUNNING' | 'FAILED' | 'HEALTHY';
type SortMode = 'RECENT_RUN' | 'NAME';
type SinceHoursFilter = 1 | 24 | 168 | null;

export default function Pipelines() {
  const { user, selectedTenantId } = useAuthStore();
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
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
  const [expandedPipelineId, setExpandedPipelineId] = useState<string | null>(null);
  const [selectedRunIdByPipeline, setSelectedRunIdByPipeline] = useState<Record<string, string>>({});
  const initialHealth = (() => {
    const raw = (searchParams.get('health') || '').toUpperCase();
    return (['ALL', 'PROBLEMATIC', 'RUNNING', 'FAILED', 'HEALTHY'] as const).includes(raw as HealthFilter)
      ? (raw as HealthFilter)
      : 'ALL';
  })();
  const initialSinceHours = (() => {
    const raw = Number(searchParams.get('sinceHours'));
    return raw === 1 || raw === 24 || raw === 168 ? (raw as 1 | 24 | 168) : null;
  })();
  const [healthFilter, setHealthFilter] = useState<HealthFilter>(initialHealth);
  const [sinceHoursFilter, setSinceHoursFilter] = useState<SinceHoursFilter>(initialSinceHours);
  const [sortMode, setSortMode] = useState<SortMode>('RECENT_RUN');
  const [toast, setToast] = useState<{ message: string; type: 'success' | 'error' } | null>(null);
  const [error, setError] = useState('');
  const [bucketRuns, setBucketRuns] = useState<TenantPipelineRun[]>([]);
  const [loadingBucketRuns, setLoadingBucketRuns] = useState(false);

  const bucketStartParam = searchParams.get('bucketStart');
  const bucketSecondsParam = Number(searchParams.get('bucketSeconds') || '');
  const bucketStart = bucketStartParam ? new Date(bucketStartParam) : null;
  const bucketSeconds = Number.isFinite(bucketSecondsParam) && bucketSecondsParam > 0 ? bucketSecondsParam : null;
  const bucketEnd = bucketStart && bucketSeconds ? new Date(bucketStart.getTime() + bucketSeconds * 1000) : null;

  const tenantId = selectedTenantId || user?.tenantId;

  const showToast = (message: string, type: 'success' | 'error') => {
    setToast({ message, type });
    setTimeout(() => setToast(null), 3500);
  };

  const loadPipelines = async (tid: string, filter: HealthFilter = healthFilter) => {
    const data = await api.getPipelines(tid, {
      includeRunSummary: true,
      runHealth: filter,
      latestSinceHours: sinceHoursFilter ?? undefined,
      latestFrom: bucketStart ? bucketStart.toISOString() : undefined,
      latestTo: bucketEnd ? bucketEnd.toISOString() : undefined,
    });
    setPipelines(data);
    return data;
  };

  useEffect(() => {
    const fetchAll = async () => {
      if (!tenantId) { setLoading(false); return; }
      try {
        const data = await loadPipelines(tenantId, healthFilter);
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

        if (bucketStart && bucketEnd) {
          setLoadingBucketRuns(true);
          try {
            const runs = await api.getTenantPipelineRuns(tenantId, {
              from: bucketStart.toISOString(),
              to: bucketEnd.toISOString(),
              limit: 40,
            });
            setBucketRuns(runs);
          } catch {
            setBucketRuns([]);
          } finally {
            setLoadingBucketRuns(false);
          }
        } else {
          setBucketRuns([]);
        }

        setError('');
      } catch (e) {
        console.error('Error fetching pipelines:', e);
        setError('Failed to load pipelines');
      } finally {
        setLoading(false);
      }
    };
    fetchAll();
  }, [tenantId, healthFilter, sinceHoursFilter, bucketStartParam, bucketSeconds]);

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
      if (tenantId) await loadPipelines(tenantId, healthFilter);
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

  const getRunMetricNumber = (run: PipelineRun, key: string): number | null => {
    const metrics = run.metrics as Record<string, unknown> | undefined;
    if (!metrics) return null;
    const value = metrics[key];
    if (typeof value === 'number') return value;
    return null;
  };

  const getRunAlertsFired = (run: PipelineRun): number | null => {
    const metrics = run.metrics as Record<string, unknown> | undefined;
    if (!metrics) return null;
    const direct = metrics.alertsFired;
    if (typeof direct === 'number') return direct;
    const result = metrics.result as Record<string, unknown> | undefined;
    if (result && typeof result.alertsFired === 'number') return result.alertsFired;
    return null;
  };

  const formatRunTime = (value?: string) => {
    if (!value) return '-';
    return new Date(value).toLocaleString();
  };

  const getLatestStatus = (pipeline: Pipeline): PipelineRun['status'] | undefined => {
    return runsMap[pipeline.id]?.[0]?.status ?? pipeline.latestRunStatus;
  };

  const getLatestStartedAt = (pipeline: Pipeline): string | undefined => {
    return runsMap[pipeline.id]?.[0]?.startedAt ?? pipeline.latestRunStartedAt;
  };

  const isProblematic = (status?: PipelineRun['status']) => {
    return status === 'FAILED' || status === 'RUNNING' || status === 'PENDING';
  };

  const displayedPipelines = [...pipelines]
    .filter((pipeline) => {
      const status = getLatestStatus(pipeline);
      if (healthFilter === 'ALL') return true;
      if (healthFilter === 'PROBLEMATIC') return isProblematic(status);
      if (healthFilter === 'RUNNING') return status === 'RUNNING' || status === 'PENDING';
      if (healthFilter === 'FAILED') return status === 'FAILED';
      if (healthFilter === 'HEALTHY') return status === 'SUCCESS';
      return true;
    })
    .sort((a, b) => {
      if (sortMode === 'NAME') return a.name.localeCompare(b.name);
      const aTime = getLatestStartedAt(a) ? new Date(getLatestStartedAt(a) as string).getTime() : 0;
      const bTime = getLatestStartedAt(b) ? new Date(getLatestStartedAt(b) as string).getTime() : 0;
      return bTime - aTime;
    });

  const applyHealthFilter = (filter: HealthFilter) => {
    setHealthFilter(filter);
    const next = new URLSearchParams(searchParams);
    if (filter === 'ALL') {
      next.delete('health');
    } else {
      next.set('health', filter);
    }
    setSearchParams(next, { replace: true });
  };

  const applySinceHoursFilter = (hours: SinceHoursFilter) => {
    setSinceHoursFilter(hours);
    const next = new URLSearchParams(searchParams);
    if (hours == null) {
      next.delete('sinceHours');
    } else {
      next.set('sinceHours', String(hours));
    }
    setSearchParams(next, { replace: true });
  };

  const clearBucketFilter = () => {
    const next = new URLSearchParams(searchParams);
    next.delete('bucketStart');
    next.delete('bucketSeconds');
    setSearchParams(next, { replace: true });
  };

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

      <div className="mb-4 flex flex-wrap items-center gap-2">
        {(['ALL', 'PROBLEMATIC', 'RUNNING', 'FAILED', 'HEALTHY'] as const).map((filter) => (
          <button
            key={filter}
            onClick={() => applyHealthFilter(filter)}
            className={`px-3 py-1.5 text-xs rounded border ${
              healthFilter === filter ? 'bg-primary-600 text-white border-primary-600' : 'bg-white text-gray-700 border-gray-300 hover:bg-gray-50'
            }`}
          >
            {filter === 'ALL' ? 'All' : filter === 'PROBLEMATIC' ? 'Problematic' : filter === 'RUNNING' ? 'Running/Pending' : filter === 'FAILED' ? 'Failed' : 'Healthy'}
          </button>
        ))}
        <select
          value={sortMode}
          onChange={(e) => setSortMode(e.target.value as SortMode)}
          className="input text-xs max-w-[180px]"
        >
          <option value="RECENT_RUN">Sort: Recent Run</option>
          <option value="NAME">Sort: Name</option>
        </select>
        <select
          value={sinceHoursFilter ?? ''}
          onChange={(e) => applySinceHoursFilter(e.target.value === '' ? null : (Number(e.target.value) as 1 | 24 | 168))}
          className="input text-xs max-w-[170px]"
        >
          <option value="">All time</option>
          <option value={1}>Last 1h</option>
          <option value={24}>Last 24h</option>
          <option value={168}>Last 7d</option>
        </select>
      </div>

      {sinceHoursFilter && (
        <div className="mb-3 text-xs text-gray-600">
          Showing pipelines with latest run in last <span className="font-medium">{sinceHoursFilter}h</span>.
          <button onClick={() => applySinceHoursFilter(null)} className="ml-2 text-primary-600 hover:text-primary-800">Clear</button>
        </div>
      )}

      {bucketStart && bucketEnd && (
        <div className="mb-3 rounded border border-amber-200 bg-amber-50 px-3 py-2 text-xs text-amber-900">
          Bucket filter active: <span className="font-medium">{bucketStart.toLocaleString()}</span> to <span className="font-medium">{bucketEnd.toLocaleString()}</span>.
          <button onClick={clearBucketFilter} className="ml-2 text-primary-700 hover:text-primary-900">Clear bucket</button>
        </div>
      )}

      {bucketStart && bucketEnd && (
        <div className="mb-4 card">
          <div className="flex items-center justify-between mb-3">
            <h2 className="text-sm font-semibold text-gray-900">Runs In Selected Bucket</h2>
            <span className="text-xs text-gray-500">{bucketRuns.length} runs</span>
          </div>
          {loadingBucketRuns ? (
            <p className="text-sm text-gray-500">Loading bucket runs...</p>
          ) : bucketRuns.length > 0 ? (
            <div className="overflow-auto">
              <table className="min-w-full text-xs">
                <thead>
                  <tr className="text-left text-gray-500 border-b border-gray-200">
                    <th className="py-2 pr-3">Pipeline</th>
                    <th className="py-2 pr-3">Status</th>
                    <th className="py-2 pr-3">Started</th>
                    <th className="py-2 pr-3">Duration</th>
                  </tr>
                </thead>
                <tbody>
                  {bucketRuns.slice(0, 12).map((run) => (
                    <tr key={run.id} className="border-b border-gray-100">
                      <td className="py-2 pr-3">
                        <Link to={`/pipelines/${run.pipelineId}`} className="text-primary-600 hover:text-primary-800">
                          {run.pipelineName}
                        </Link>
                      </td>
                      <td className="py-2 pr-3">{run.status}</td>
                      <td className="py-2 pr-3">{run.startedAt ? new Date(run.startedAt).toLocaleTimeString() : '-'}</td>
                      <td className="py-2 pr-3">{run.durationMs != null ? `${run.durationMs} ms` : '-'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <p className="text-sm text-gray-500">No runs found in this bucket.</p>
          )}
        </div>
      )}

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
      ) : displayedPipelines.length > 0 ? (
        <div className="space-y-4">
          {displayedPipelines.map((pipeline) => {
            const runs = runsMap[pipeline.id] ?? [];
            const isTriggering = triggeringId === pipeline.id;
            const isDeleting = deletingId === pipeline.id;
            const confirmingDelete = confirmDeleteId === pipeline.id;
            const latestStatus = getLatestStatus(pipeline);

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
                          onClick={(e) => {
                            e.preventDefault();
                            setExpandedPipelineId((prev) => (prev === pipeline.id ? null : pipeline.id));
                            if (!selectedRunIdByPipeline[pipeline.id] && runs[0]?.id) {
                              setSelectedRunIdByPipeline((prev) => ({ ...prev, [pipeline.id]: runs[0].id }));
                            }
                          }}
                          className="text-sm text-gray-600 hover:text-gray-800"
                        >
                          {expandedPipelineId === pipeline.id ? 'Hide Details' : 'Details'}
                        </button>
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
                  {latestStatus && <span>Health: {isProblematic(latestStatus) ? 'Needs attention' : latestStatus}</span>}
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
                    {getRunAlertsFired(runs[0]) != null && (
                      <span className="text-xs text-amber-700">Alerts: {getRunAlertsFired(runs[0])}</span>
                    )}
                    {getRunMetricNumber(runs[0], 'executionMs') != null && (
                      <span className="text-xs text-gray-500">{getRunMetricNumber(runs[0], 'executionMs')} ms</span>
                    )}
                  </div>
                )}

                {expandedPipelineId === pipeline.id && (
                  <div className="mt-4 rounded border border-gray-200 bg-gray-50 p-3">
                    <div className="text-xs font-semibold text-gray-700 mb-2">Run Details</div>
                    {runs.length === 0 ? (
                      <p className="text-sm text-gray-500">No runs available yet.</p>
                    ) : (
                      <>
                        <div className="flex flex-wrap gap-2 mb-3">
                          {runs.map((run) => (
                            <button
                              key={run.id}
                              onClick={(e) => {
                                e.preventDefault();
                                setSelectedRunIdByPipeline((prev) => ({ ...prev, [pipeline.id]: run.id }));
                              }}
                              className={`px-2 py-1 text-xs rounded border ${
                                (selectedRunIdByPipeline[pipeline.id] ?? runs[0].id) === run.id
                                  ? 'bg-white border-primary-500 text-primary-700'
                                  : 'bg-gray-100 border-gray-300 text-gray-700'
                              }`}
                            >
                              {run.status} - {new Date(run.startedAt).toLocaleTimeString()}
                            </button>
                          ))}
                        </div>

                        {(() => {
                          const selectedRunId = selectedRunIdByPipeline[pipeline.id] ?? runs[0].id;
                          const selectedRun = runs.find((r) => r.id === selectedRunId) ?? runs[0];
                          const resultPayload = (selectedRun.metrics?.result as Record<string, unknown> | undefined) ?? selectedRun.metrics;
                          const alertEvaluation = (selectedRun.alertEvaluation as Record<string, unknown> | undefined)
                            ?? (resultPayload?.alertEvaluation as Record<string, unknown> | undefined);
                          return (
                            <div className="text-xs text-gray-700 space-y-2">
                              <div className="flex flex-wrap gap-4">
                                <span>Status: <strong>{selectedRun.status}</strong></span>
                                <span>Trigger: <strong>{selectedRun.triggerType}</strong></span>
                                <span>Started: <strong>{formatRunTime(selectedRun.startedAt)}</strong></span>
                                <span>Duration: <strong>{selectedRun.durationMs != null ? `${selectedRun.durationMs} ms` : '-'}</strong></span>
                              </div>
                              {alertEvaluation && (
                                <div className="flex flex-wrap gap-4 text-amber-800">
                                  <span>Rules: <strong>{String(alertEvaluation.evaluatedRules ?? 0)}</strong></span>
                                  <span>Matched: <strong>{String(alertEvaluation.matchedRules ?? 0)}</strong></span>
                                  <span>Alerts: <strong>{String(alertEvaluation.firedAlerts ?? selectedRun.alertsFired ?? 0)}</strong></span>
                                  <span>Cooldown: <strong>{String(alertEvaluation.cooldownSkipped ?? 0)}</strong></span>
                                </div>
                              )}
                              {selectedRun.errorMessage && (
                                <div className="text-red-700">Error: {selectedRun.errorMessage}</div>
                              )}
                              <pre className="mt-2 max-h-40 overflow-auto rounded bg-white border border-gray-200 p-2 text-[11px] text-gray-700">
                                {JSON.stringify(resultPayload, null, 2)}
                              </pre>
                            </div>
                          );
                        })()}
                      </>
                    )}
                  </div>
                )}
              </div>
            );
          })}
        </div>
      ) : (
        <div className="card text-center py-12">
          <p className="text-gray-500 mb-4">
            {pipelines.length > 0 ? 'No pipelines match this filter' : 'No pipelines yet'}
          </p>
          {pipelines.length === 0 && (
            <button onClick={() => setShowCreateModal(true)} className="btn-primary">
              Create Your First Pipeline
            </button>
          )}
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
