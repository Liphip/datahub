import { type ChangeEvent, useEffect, useRef, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { useAuthStore } from '../stores/auth';
import api from '../services/api';
import type { Pipeline, PipelineRun, PipelineRunLineage, PipelineRunSummary, TenantPipelineRun } from '../types';

type RunMap = Record<string, PipelineRun[]>;
type PipelineRunDetailFilter = {
  startedFrom?: string;
  startedTo?: string;
  runStatus?: 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED';
  label?: string;
};
type HealthFilter = 'ALL' | 'PROBLEMATIC' | 'RUNNING' | 'FAILED' | 'HEALTHY';
type SortMode = 'RECENT_RUN' | 'NAME';
type SinceHoursFilter = 1 | 24 | 168 | null;
type BucketSortMode = 'NEWEST' | 'FAILED_FIRST';
type BucketRunStatusFilter = 'ALL' | 'RUNNING' | 'PENDING' | 'SUCCESS' | 'FAILED' | 'CANCELLED';
type LineageDirection = 'both' | 'ancestors' | 'descendants';
type LineageStatusFilter = 'ALL' | 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'CANCELLED';

type TriagePreset = {
  name: string;
  query: string;
  tag?: string;
  favorite?: boolean;
  useCount?: number;
  lastUsedAt?: string;
};

const TRIAGE_PRESETS_KEY = 'pipelines.triage.presets.v1';
const LINEAGE_STATUS_OPTIONS: LineageStatusFilter[] = ['ALL', 'FAILED', 'RUNNING', 'PENDING', 'SUCCESS', 'CANCELLED'];

export default function Pipelines() {
  const { user, selectedTenantId, tenantContexts } = useAuthStore();
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const [pipelines, setPipelines] = useState<Pipeline[]>([]);
  const [runsMap, setRunsMap] = useState<RunMap>({});
  const [detailedRunsMap, setDetailedRunsMap] = useState<RunMap>({});
  const [pipelineSummaries, setPipelineSummaries] = useState<Record<string, PipelineRunSummary>>({});
  const [summaryWindowByPipeline, setSummaryWindowByPipeline] = useState<Record<string, 1 | 24 | 168>>({});
  const [detailFiltersByPipeline, setDetailFiltersByPipeline] = useState<Record<string, PipelineRunDetailFilter>>({});
  const [loading, setLoading] = useState(true);
  const [showCreateModal, setShowCreateModal] = useState(false);
  const [newPipelineName, setNewPipelineName] = useState('');
  const [newPipelineDescription, setNewPipelineDescription] = useState('');
  const [creating, setCreating] = useState(false);
  const [triggeringId, setTriggeringId] = useState<string | null>(null);
  const [cancellingRunId, setCancellingRunId] = useState<string | null>(null);
  const [retryingRunId, setRetryingRunId] = useState<string | null>(null);
  const [refreshingRunId, setRefreshingRunId] = useState<string | null>(null);
  const [lineageByRunId, setLineageByRunId] = useState<Record<string, PipelineRunLineage>>({});
  const [loadingLineageRunId, setLoadingLineageRunId] = useState<string | null>(null);
  const [lineageDepthByRunId, setLineageDepthByRunId] = useState<Record<string, number>>({});
  const [lineageDirectionByRunId, setLineageDirectionByRunId] = useState<Record<string, LineageDirection>>({});
  const [lineageStatusFilterByRunId, setLineageStatusFilterByRunId] = useState<Record<string, LineageStatusFilter>>({});
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
  const initialBucketSort = (() => {
    const raw = (searchParams.get('bucketSort') || '').toUpperCase();
    return raw === 'FAILED_FIRST' ? 'FAILED_FIRST' : 'NEWEST';
  })();
  const initialBucketStatus = (() => {
    const raw = (searchParams.get('bucketStatus') || '').toUpperCase();
    return (['ALL', 'RUNNING', 'PENDING', 'SUCCESS', 'FAILED', 'CANCELLED'] as const).includes(raw as BucketRunStatusFilter)
      ? (raw as BucketRunStatusFilter)
      : 'ALL';
  })();
  const [bucketSortMode, setBucketSortMode] = useState<BucketSortMode>(initialBucketSort);
  const [bucketStatusFilter, setBucketStatusFilter] = useState<BucketRunStatusFilter>(initialBucketStatus);
  const [bucketPipelineQuery, setBucketPipelineQuery] = useState(searchParams.get('bucketQ') || '');
  const [bucketPipelineIdFilter, setBucketPipelineIdFilter] = useState<string | null>(searchParams.get('bucketPipelineId'));
  const bucketQueryInputRef = useRef<HTMLInputElement | null>(null);
  const triagePresetNameInputRef = useRef<HTMLInputElement | null>(null);
  const [triagePresets, setTriagePresets] = useState<TriagePreset[]>([]);
  const [triagePresetName, setTriagePresetName] = useState('');
  const [triagePresetTag, setTriagePresetTag] = useState('');
  const [triagePresetTagFilter, setTriagePresetTagFilter] = useState('ALL');
  const [triagePresetSortMode, setTriagePresetSortMode] = useState<'MANUAL' | 'RECENT_USED' | 'MOST_USED'>('MANUAL');
  const [editingPresetName, setEditingPresetName] = useState<string | null>(null);
  const [editingPresetNextName, setEditingPresetNextName] = useState('');
  const [confirmPresetActions, setConfirmPresetActions] = useState(() => localStorage.getItem('pipelines.presets.confirm') !== 'false');
  const [pendingPresetAction, setPendingPresetAction] = useState<null | { type: 'delete' | 'resetOne' | 'resetAll'; name?: string }>(null);
  const importPresetInputRef = useRef<HTMLInputElement | null>(null);
  const handledFocusRef = useRef<string | null>(null);
  const [showShortcutHints, setShowShortcutHints] = useState(() => {
    const stored = localStorage.getItem('pipelines.bucket.shortcuts.hidden');
    return stored !== 'true';
  });
  const [toast, setToast] = useState<{ message: string; type: 'success' | 'error' } | null>(null);
  const [error, setError] = useState('');
  const [bucketRuns, setBucketRuns] = useState<TenantPipelineRun[]>([]);
  const [loadingBucketRuns, setLoadingBucketRuns] = useState(false);
  const [autoRefreshBucket, setAutoRefreshBucket] = useState(false);
  const [bucketLastRefreshedAt, setBucketLastRefreshedAt] = useState<string | null>(null);
  const [refreshNowTick, setRefreshNowTick] = useState(() => Date.now());

  const bucketStartParam = searchParams.get('bucketStart');
  const focusPipelineIdParam = searchParams.get('focusPipelineId');
  const focusRunIdParam = searchParams.get('focusRunId');
  const bucketSecondsParam = Number(searchParams.get('bucketSeconds') || '');
  const bucketStart = bucketStartParam ? new Date(bucketStartParam) : null;
  const bucketSeconds = Number.isFinite(bucketSecondsParam) && bucketSecondsParam > 0 ? bucketSecondsParam : null;
  const bucketEnd = bucketStart && bucketSeconds ? new Date(bucketStart.getTime() + bucketSeconds * 1000) : null;

  const tenantId = selectedTenantId || user?.tenantId || tenantContexts[0]?.tenantId;
  const abilities = user?.abilities || [];
  const hasAbility = (ability: string) => Boolean(user?.superadmin || abilities.includes('*') || abilities.includes(ability));
  const canReadPipelines = hasAbility('pipeline:read') || hasAbility('pipeline:write') || hasAbility('pipeline:execute') || hasAbility('manage:tenant');
  const canWritePipelines = hasAbility('pipeline:write') || hasAbility('manage:tenant');
  const canExecutePipelines = hasAbility('pipeline:execute') || canWritePipelines;

  const showToast = (message: string, type: 'success' | 'error') => {
    setToast({ message, type });
    setTimeout(() => setToast(null), 3500);
  };

  const persistTriagePresets = (presets: TriagePreset[]) => {
    localStorage.setItem(TRIAGE_PRESETS_KEY, JSON.stringify(presets));
    setTriagePresets(presets);
  };

  const refreshBucketRuns = async (tid: string, fromDate: Date, toDate: Date, silent = false) => {
    if (!silent) setLoadingBucketRuns(true);
    try {
      const runs = await api.getTenantPipelineRuns(tid, {
        from: fromDate.toISOString(),
        to: toDate.toISOString(),
        limit: 40,
        runStatus: bucketStatusFilter === 'ALL' ? undefined : bucketStatusFilter,
        pipelineId: bucketPipelineIdFilter || undefined,
      });
      setBucketRuns(runs);
      setBucketLastRefreshedAt(new Date().toISOString());
    } catch {
      if (!silent) setBucketRuns([]);
    } finally {
      if (!silent) setLoadingBucketRuns(false);
    }
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

  const loadPipelineSummary = async (pipelineId: string, sinceHours?: 1 | 24 | 168) => {
    const window = sinceHours ?? summaryWindowByPipeline[pipelineId] ?? 24;
    const summary = await api.getPipelineRunSummaryByPipeline(pipelineId, window);
    setPipelineSummaries((prev) => ({ ...prev, [pipelineId]: summary }));
    setSummaryWindowByPipeline((prev) => ({ ...prev, [pipelineId]: window }));
  };

  const loadDetailedPipelineRuns = async (pipelineId: string, filter?: PipelineRunDetailFilter) => {
    const runs = await api.getPipelineRuns(pipelineId, {
      limit: 24,
      includeDetails: true,
      startedFrom: filter?.startedFrom,
      startedTo: filter?.startedTo,
      runStatus: filter?.runStatus,
    });
    setDetailedRunsMap((prev) => ({ ...prev, [pipelineId]: runs }));
  };

  const bucketRangeForSummary = (summary: PipelineRunSummary, index: number) => {
    const bucket = summary.trendBuckets[index];
    const start = new Date(bucket.bucketStart);
    const end = new Date(start.getTime() + summary.bucketSeconds * 1000);
    return { start, end, bucket };
  };

  useEffect(() => {
    const fetchAll = async () => {
      if (!tenantId) { setLoading(false); return; }
      if (!canReadPipelines) {
        setPipelines([]);
        setRunsMap({});
        setDetailedRunsMap({});
        setError('');
        setLoading(false);
        return;
      }
      try {
        const data = await loadPipelines(tenantId, healthFilter);
        // Fetch latest 3 runs for each pipeline concurrently
        const runsEntries = await Promise.all(
          data.map(async (p) => {
            try {
              const runs = await api.getPipelineRuns(p.id, { limit: 3, includeDetails: false });
              return [p.id, runs] as [string, PipelineRun[]];
            } catch {
              return [p.id, []] as [string, PipelineRun[]];
            }
          })
        );
        setRunsMap(Object.fromEntries(runsEntries));

        if (bucketStart && bucketEnd) {
          await refreshBucketRuns(tenantId, bucketStart, bucketEnd);
        } else {
          setBucketRuns([]);
          setBucketLastRefreshedAt(null);
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
  }, [tenantId, canReadPipelines, healthFilter, sinceHoursFilter, bucketStartParam, bucketSeconds, bucketStatusFilter, bucketPipelineIdFilter]);

  useEffect(() => {
    if (!focusPipelineIdParam || !canReadPipelines) return;
    const signature = `${focusPipelineIdParam}:${focusRunIdParam || ''}`;
    if (handledFocusRef.current === signature) return;
    const pipelineExists = pipelines.some((pipeline) => pipeline.id === focusPipelineIdParam);
    if (!pipelineExists) return;

    handledFocusRef.current = signature;
    const applyFocus = async () => {
      setExpandedPipelineId(focusPipelineIdParam);
      try {
        await loadDetailedPipelineRuns(focusPipelineIdParam);
      } catch {
        // keep current runs rendering
      }
      try {
        await loadPipelineSummary(focusPipelineIdParam);
      } catch {
        // summary is optional for deep-link focus
      }

      const fallbackRunId = runsMap[focusPipelineIdParam]?.[0]?.id;
      const runIdToSelect = focusRunIdParam || fallbackRunId;
      if (runIdToSelect) {
        setSelectedRunIdByPipeline((prev) => ({ ...prev, [focusPipelineIdParam]: runIdToSelect }));
      }
    };

    void applyFocus();
  }, [focusPipelineIdParam, focusRunIdParam, canReadPipelines, pipelines, runsMap]);

  useEffect(() => {
    try {
      const parsed = JSON.parse(localStorage.getItem(TRIAGE_PRESETS_KEY) || '[]') as TriagePreset[];
      setTriagePresets(Array.isArray(parsed) ? parsed : []);
    } catch {
      setTriagePresets([]);
    }
  }, []);

  useEffect(() => {
    if (!autoRefreshBucket || !tenantId || !bucketStart || !bucketEnd) return;

    const intervalId = window.setInterval(async () => {
      if (document.hidden) return;
      try {
        await refreshBucketRuns(tenantId, bucketStart, bucketEnd, true);
      } catch {
        // keep last rendered bucket data
      }
    }, 10000);

    return () => window.clearInterval(intervalId);
  }, [autoRefreshBucket, tenantId, bucketStartParam, bucketSeconds, bucketStatusFilter, bucketPipelineIdFilter]);

  useEffect(() => {
    if (!bucketStart || !bucketEnd || !bucketLastRefreshedAt) return;

    const intervalId = window.setInterval(() => {
      if (document.hidden) return;
      setRefreshNowTick(Date.now());
    }, 1000);

    return () => window.clearInterval(intervalId);
  }, [bucketStartParam, bucketSeconds, bucketLastRefreshedAt]);

  useEffect(() => {
    if (!bucketStart || !bucketEnd) return;

    const onKeyDown = (event: KeyboardEvent) => {
      const target = event.target as HTMLElement | null;
      const tagName = target?.tagName?.toLowerCase();
      const isTypingTarget = tagName === 'input' || tagName === 'textarea' || (target?.isContentEditable ?? false);

      if (event.key === '/' && !isTypingTarget) {
        event.preventDefault();
        bucketQueryInputRef.current?.focus();
        return;
      }

      if (isTypingTarget) return;

      if (event.key.toLowerCase() === 'f') {
        event.preventDefault();
        applyBucketStatusFilter('FAILED');
      } else if (event.key.toLowerCase() === 'r') {
        event.preventDefault();
        applyBucketStatusFilter('RUNNING');
      } else if (event.key.toLowerCase() === 'a') {
        event.preventDefault();
        applyBucketStatusFilter('ALL');
      } else if (event.key.toLowerCase() === 'n') {
        event.preventDefault();
        triagePresetNameInputRef.current?.focus();
      } else if (event.key.toLowerCase() === 'p') {
        event.preventDefault();
        if (visibleTriagePresets.length > 0) {
          applyTriagePreset(visibleTriagePresets[0]);
        }
      }
    };

    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [bucketStartParam, bucketSeconds, searchParams.toString(), triagePresets, triagePresetTagFilter, triagePresetSortMode]);

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
            const runs = await api.getPipelineRuns(p.id, { limit: 3, includeDetails: false });
            return [p.id, runs] as [string, PipelineRun[]];
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
    if (!tenantId || !newPipelineName || !canWritePipelines) return;
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
    if (!canExecutePipelines) {
      showToast('You need pipeline execute permission to run pipelines', 'error');
      return;
    }
    setTriggeringId(pipelineId);
    try {
      await api.triggerPipeline(pipelineId);
      showToast('Pipeline triggered successfully', 'success');
      // Refresh runs for this pipeline
      const runs = await api.getPipelineRuns(pipelineId, { limit: 3, includeDetails: false });
      setRunsMap((prev) => ({ ...prev, [pipelineId]: runs }));
      if (expandedPipelineId === pipelineId) {
        await loadDetailedPipelineRuns(pipelineId, detailFiltersByPipeline[pipelineId]);
        try {
          await loadPipelineSummary(pipelineId);
        } catch {
          // keep run details available even if summary refresh fails
        }
      }
    } catch {
      showToast('Failed to trigger pipeline', 'error');
    } finally {
      setTriggeringId(null);
    }
  };

  const handleDeletePipeline = async (pipelineId: string, e: React.MouseEvent) => {
    e.preventDefault();
    e.stopPropagation();
    if (!canWritePipelines) {
      showToast('You need pipeline write permission to delete pipelines', 'error');
      return;
    }
    setDeletingId(pipelineId);
    try {
      await api.deletePipeline(pipelineId);
      if (tenantId) await loadPipelines(tenantId, healthFilter);
      setRunsMap((prev) => { const next = { ...prev }; delete next[pipelineId]; return next; });
      setDetailedRunsMap((prev) => { const next = { ...prev }; delete next[pipelineId]; return next; });
      setPipelineSummaries((prev) => { const next = { ...prev }; delete next[pipelineId]; return next; });
      setSummaryWindowByPipeline((prev) => { const next = { ...prev }; delete next[pipelineId]; return next; });
      setDetailFiltersByPipeline((prev) => { const next = { ...prev }; delete next[pipelineId]; return next; });
      setConfirmDeleteId(null);
      showToast('Pipeline deleted', 'success');
    } catch {
      showToast('Failed to delete pipeline', 'error');
    } finally {
      setDeletingId(null);
    }
  };

  const handleCancelRun = async (pipelineId: string, runId: string) => {
    if (!canExecutePipelines) {
      showToast('You need pipeline execute permission to cancel runs', 'error');
      return;
    }
    setCancellingRunId(runId);
    try {
      await api.cancelPipelineRun(pipelineId, runId);
      const runs = await api.getPipelineRuns(pipelineId, { limit: 3, includeDetails: false });
      setRunsMap((prev) => ({ ...prev, [pipelineId]: runs }));
      await loadDetailedPipelineRuns(pipelineId, detailFiltersByPipeline[pipelineId]);
      await loadPipelineSummary(pipelineId);
      showToast('Run cancelled', 'success');
    } catch (error: any) {
      showToast(error?.response?.data?.error || 'Failed to cancel run', 'error');
    } finally {
      setCancellingRunId(null);
    }
  };

  const handleRetryRun = async (pipelineId: string, runId: string) => {
    if (!canExecutePipelines) {
      showToast('You need pipeline execute permission to retry runs', 'error');
      return;
    }
    setRetryingRunId(runId);
    try {
      await api.retryPipelineRun(pipelineId, runId);
      const runs = await api.getPipelineRuns(pipelineId, { limit: 3, includeDetails: false });
      setRunsMap((prev) => ({ ...prev, [pipelineId]: runs }));
      await loadDetailedPipelineRuns(pipelineId, detailFiltersByPipeline[pipelineId]);
      await loadPipelineSummary(pipelineId);
      showToast('Run retried', 'success');
    } catch (error: any) {
      showToast(error?.response?.data?.error || 'Failed to retry run', 'error');
    } finally {
      setRetryingRunId(null);
    }
  };

  const handleRefreshRun = async (pipelineId: string, runId: string) => {
    if (!canReadPipelines) {
      showToast('You need pipeline read permission to refresh runs', 'error');
      return;
    }
    setRefreshingRunId(runId);
    try {
      const run = await api.getPipelineRun(pipelineId, runId, true);
      setDetailedRunsMap((prev) => {
        const existing = prev[pipelineId] ?? [];
        const without = existing.filter((item) => item.id !== run.id);
        return { ...prev, [pipelineId]: [run, ...without] };
      });
      showToast('Run details refreshed', 'success');
    } catch {
      showToast('Failed to refresh run details', 'error');
    } finally {
      setRefreshingRunId(null);
    }
  };

  const handleLoadRunLineage = async (
    pipelineId: string,
    runId: string,
    maxDepth?: number,
    direction?: LineageDirection,
    statusFilter?: LineageStatusFilter
  ) => {
    if (!canReadPipelines) {
      showToast('You need pipeline read permission to inspect run lineage', 'error');
      return;
    }
    setLoadingLineageRunId(runId);
    try {
      const depth = maxDepth ?? lineageDepthByRunId[runId] ?? 20;
      const requestedDirection = direction ?? lineageDirectionByRunId[runId] ?? 'both';
      const requestedStatus = statusFilter ?? lineageStatusFilterByRunId[runId] ?? 'ALL';
      const statuses = requestedStatus === 'ALL' ? undefined : [requestedStatus];
      const lineage = await api.getPipelineRunLineage(pipelineId, runId, depth, requestedDirection, statuses);
      setLineageDepthByRunId((prev) => ({ ...prev, [runId]: depth }));
      setLineageDirectionByRunId((prev) => ({ ...prev, [runId]: requestedDirection }));
      setLineageStatusFilterByRunId((prev) => ({ ...prev, [runId]: requestedStatus }));
      setLineageByRunId((prev) => ({ ...prev, [runId]: lineage }));
    } catch {
      showToast('Failed to load run lineage', 'error');
    } finally {
      setLoadingLineageRunId(null);
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

  const bucketStatusCounts = bucketRuns.reduce(
    (acc, run) => {
      if (run.status === 'FAILED') acc.failed += 1;
      if (run.status === 'RUNNING' || run.status === 'PENDING') acc.running += 1;
      if (run.status === 'SUCCESS') acc.success += 1;
      return acc;
    },
    { failed: 0, running: 0, success: 0 }
  );

  const bucketRefreshAgeSeconds = bucketLastRefreshedAt
    ? Math.max(0, Math.floor((refreshNowTick - new Date(bucketLastRefreshedAt).getTime()) / 1000))
    : null;

  const bucketFreshness = bucketRefreshAgeSeconds == null
    ? 'unknown'
    : bucketRefreshAgeSeconds <= 15
      ? 'fresh'
      : bucketRefreshAgeSeconds <= 45
        ? 'aging'
        : 'stale';

  const statusPriority = (status: TenantPipelineRun['status']) => {
    if (status === 'FAILED') return 0;
    if (status === 'RUNNING' || status === 'PENDING') return 1;
    if (status === 'SUCCESS') return 2;
    return 3;
  };

  const sortedBucketRuns = [...bucketRuns].sort((a, b) => {
    if (bucketSortMode === 'FAILED_FIRST') {
      const p = statusPriority(a.status) - statusPriority(b.status);
      if (p !== 0) return p;
    }
    const aTime = a.startedAt ? new Date(a.startedAt).getTime() : 0;
    const bTime = b.startedAt ? new Date(b.startedAt).getTime() : 0;
    return bTime - aTime;
  });

  const visibleBucketRuns = sortedBucketRuns.filter((run) => {
    if (!bucketPipelineQuery.trim()) return true;
    return run.pipelineName?.toLowerCase().includes(bucketPipelineQuery.trim().toLowerCase());
  });

  const pinnedPipelineName = bucketPipelineIdFilter
    ? bucketRuns.find((run) => run.pipelineId === bucketPipelineIdFilter)?.pipelineName
    : null;

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
    next.delete('bucketSort');
    next.delete('bucketStatus');
    next.delete('bucketQ');
    next.delete('bucketPipelineId');
    setSearchParams(next, { replace: true });
  };

  const clearAllTriageFilters = () => {
    setHealthFilter('ALL');
    setSinceHoursFilter(null);
    setBucketSortMode('NEWEST');
    setBucketStatusFilter('ALL');
    setBucketPipelineQuery('');
    setBucketPipelineIdFilter(null);

    const next = new URLSearchParams(searchParams);
    next.delete('health');
    next.delete('sinceHours');
    next.delete('bucketStart');
    next.delete('bucketSeconds');
    next.delete('bucketSort');
    next.delete('bucketStatus');
    next.delete('bucketQ');
    next.delete('bucketPipelineId');
    setSearchParams(next, { replace: true });
  };

  const copyTriageLink = async () => {
    try {
      const url = `${window.location.origin}${window.location.pathname}?${searchParams.toString()}`;
      await navigator.clipboard.writeText(url);
      showToast('Triage link copied to clipboard', 'success');
    } catch {
      showToast('Failed to copy triage link', 'error');
    }
  };

  const handleManualBucketRefresh = async () => {
    if (!tenantId || !bucketStart || !bucketEnd) return;
    await refreshBucketRuns(tenantId, bucketStart, bucketEnd);
    showToast('Bucket runs refreshed', 'success');
  };

  const applyBucketSortMode = (mode: BucketSortMode) => {
    setBucketSortMode(mode);
    const next = new URLSearchParams(searchParams);
    if (mode === 'NEWEST') {
      next.delete('bucketSort');
    } else {
      next.set('bucketSort', mode);
    }
    setSearchParams(next, { replace: true });
  };

  const applyBucketStatusFilter = (status: BucketRunStatusFilter) => {
    setBucketStatusFilter(status);
    const next = new URLSearchParams(searchParams);
    if (status === 'ALL') {
      next.delete('bucketStatus');
    } else {
      next.set('bucketStatus', status);
    }
    setSearchParams(next, { replace: true });
  };

  const applyBucketPipelineQuery = (query: string) => {
    setBucketPipelineQuery(query);
    const next = new URLSearchParams(searchParams);
    if (!query.trim()) {
      next.delete('bucketQ');
    } else {
      next.set('bucketQ', query.trim());
    }
    setSearchParams(next, { replace: true });
  };

  const applyBucketPipelineIdFilter = (pipelineId: string | null) => {
    setBucketPipelineIdFilter(pipelineId);
    const next = new URLSearchParams(searchParams);
    if (!pipelineId) {
      next.delete('bucketPipelineId');
    } else {
      next.set('bucketPipelineId', pipelineId);
    }
    setSearchParams(next, { replace: true });
  };

  const clearBucketStatusFilter = () => applyBucketStatusFilter('ALL');
  const clearBucketPipelineQuery = () => applyBucketPipelineQuery('');
  const clearBucketPipelineIdFilter = () => applyBucketPipelineIdFilter(null);
  const clearBucketSort = () => applyBucketSortMode('NEWEST');

  const saveCurrentTriagePreset = () => {
    const name = triagePresetName.trim();
    if (!name) {
      showToast('Enter a preset name first', 'error');
      return;
    }
    const query = searchParams.toString();
    const existing = triagePresets.find((p) => p.name.toLowerCase() === name.toLowerCase());
    const deduped = triagePresets.filter((p) => p.name.toLowerCase() !== name.toLowerCase());
    const tag = triagePresetTag.trim();
    const next = [{
      name,
      query,
      tag: tag || undefined,
      favorite: existing?.favorite === true,
      useCount: existing?.useCount,
      lastUsedAt: existing?.lastUsedAt,
    }, ...deduped].slice(0, 20);
    persistTriagePresets(next);
    setTriagePresetName('');
    setTriagePresetTag('');
    showToast('Triage preset saved', 'success');
  };

  const applyTriagePreset = (preset: TriagePreset) => {
    const params = new URLSearchParams(preset.query);
    const health = ((params.get('health') || 'ALL').toUpperCase() as HealthFilter);
    const sinceRaw = Number(params.get('sinceHours'));
    const since = sinceRaw === 1 || sinceRaw === 24 || sinceRaw === 168 ? (sinceRaw as 1 | 24 | 168) : null;
    const bSort = (params.get('bucketSort') || 'NEWEST').toUpperCase() === 'FAILED_FIRST' ? 'FAILED_FIRST' : 'NEWEST';
    const bStatusRaw = (params.get('bucketStatus') || 'ALL').toUpperCase();
    const bStatus = (['ALL', 'RUNNING', 'PENDING', 'SUCCESS', 'FAILED', 'CANCELLED'] as const).includes(bStatusRaw as BucketRunStatusFilter)
      ? (bStatusRaw as BucketRunStatusFilter)
      : 'ALL';

    setHealthFilter((['ALL', 'PROBLEMATIC', 'RUNNING', 'FAILED', 'HEALTHY'] as const).includes(health) ? health : 'ALL');
    setSinceHoursFilter(since);
    setBucketSortMode(bSort);
    setBucketStatusFilter(bStatus);
    setBucketPipelineQuery(params.get('bucketQ') || '');
    setBucketPipelineIdFilter(params.get('bucketPipelineId'));
    setSearchParams(params, { replace: true });

    const now = new Date().toISOString();
    const next = triagePresets.map((p) =>
      p.name === preset.name
        ? { ...p, useCount: (p.useCount || 0) + 1, lastUsedAt: now }
        : p
    );
    persistTriagePresets(next);
    showToast(`Applied preset: ${preset.name}`, 'success');
  };

  const executeDeleteTriagePreset = (name: string) => {
    const next = triagePresets.filter((p) => p.name !== name);
    persistTriagePresets(next);
    showToast('Preset removed', 'success');
  };

  const deleteTriagePreset = (name: string) => {
    if (confirmPresetActions) {
      setPendingPresetAction({ type: 'delete', name });
      return;
    }
    executeDeleteTriagePreset(name);
  };

  const executeResetPresetUsage = (name: string) => {
    const next = triagePresets.map((preset) =>
      preset.name === name
        ? { ...preset, useCount: 0, lastUsedAt: undefined }
        : preset
    );
    persistTriagePresets(next);
    showToast('Preset usage reset', 'success');
  };

  const resetPresetUsage = (name: string) => {
    if (confirmPresetActions) {
      setPendingPresetAction({ type: 'resetOne', name });
      return;
    }
    executeResetPresetUsage(name);
  };

  const executeResetAllPresetUsage = () => {
    const next = triagePresets.map((preset) => ({
      ...preset,
      useCount: 0,
      lastUsedAt: undefined,
    }));
    persistTriagePresets(next);
    showToast('All preset usage stats reset', 'success');
  };

  const resetAllPresetUsage = () => {
    if (confirmPresetActions) {
      setPendingPresetAction({ type: 'resetAll' });
      return;
    }
    executeResetAllPresetUsage();
  };

  const setPresetConfirmPreference = (enabled: boolean) => {
    setConfirmPresetActions(enabled);
    localStorage.setItem('pipelines.presets.confirm', enabled ? 'true' : 'false');
  };

  const confirmPendingPresetAction = () => {
    if (!pendingPresetAction) return;
    if (pendingPresetAction.type === 'delete' && pendingPresetAction.name) {
      executeDeleteTriagePreset(pendingPresetAction.name);
    } else if (pendingPresetAction.type === 'resetOne' && pendingPresetAction.name) {
      executeResetPresetUsage(pendingPresetAction.name);
    } else if (pendingPresetAction.type === 'resetAll') {
      executeResetAllPresetUsage();
    }
    setPendingPresetAction(null);
  };

  const pendingPresetActionLabel = pendingPresetAction == null
    ? ''
    : pendingPresetAction.type === 'delete'
      ? `Delete preset "${pendingPresetAction.name}"?`
      : pendingPresetAction.type === 'resetOne'
        ? `Reset usage stats for "${pendingPresetAction.name}"?`
        : 'Reset usage stats for all presets?';

  const duplicateTriagePreset = (name: string) => {
    const original = triagePresets.find((p) => p.name === name);
    if (!original) return;

    const baseName = `${original.name} Copy`;
    let copyName = baseName;
    let counter = 2;
    while (triagePresets.some((p) => p.name.toLowerCase() === copyName.toLowerCase())) {
      copyName = `${baseName} ${counter}`;
      counter += 1;
    }

    const next = [{ ...original, name: copyName }, ...triagePresets].slice(0, 20);
    persistTriagePresets(next);
    showToast('Preset duplicated', 'success');
  };

  const startRenameTriagePreset = (name: string) => {
    setEditingPresetName(name);
    setEditingPresetNextName(name);
  };

  const confirmRenameTriagePreset = () => {
    if (!editingPresetName) return;
    const nextName = editingPresetNextName.trim();
    if (!nextName) {
      showToast('Preset name cannot be empty', 'error');
      return;
    }

    const duplicateExists = triagePresets.some(
      (p) => p.name.toLowerCase() === nextName.toLowerCase() && p.name !== editingPresetName
    );
    if (duplicateExists) {
      showToast('A preset with that name already exists', 'error');
      return;
    }

    const next = triagePresets.map((preset) =>
      preset.name === editingPresetName ? { ...preset, name: nextName } : preset
    );
    persistTriagePresets(next);
    setEditingPresetName(null);
    setEditingPresetNextName('');
    showToast('Preset renamed', 'success');
  };

  const cancelRenameTriagePreset = () => {
    setEditingPresetName(null);
    setEditingPresetNextName('');
  };

  const toggleFavoritePreset = (name: string) => {
    const next = triagePresets.map((preset) =>
      preset.name === name
        ? { ...preset, favorite: !preset.favorite }
        : preset
    );
    persistTriagePresets(next);
  };

  const moveFavoritePreset = (name: string, direction: 'up' | 'down') => {
    const favorites = triagePresets.filter((p) => p.favorite);
    const others = triagePresets.filter((p) => !p.favorite);
    const index = favorites.findIndex((p) => p.name === name);
    if (index < 0) return;

    const targetIndex = direction === 'up' ? index - 1 : index + 1;
    if (targetIndex < 0 || targetIndex >= favorites.length) return;

    const reordered = [...favorites];
    const [item] = reordered.splice(index, 1);
    reordered.splice(targetIndex, 0, item);
    persistTriagePresets([...reordered, ...others]);
  };

  const availablePresetTags = Array.from(
    new Set(triagePresets.map((p) => p.tag?.trim()).filter((v): v is string => Boolean(v)))
  ).sort((a, b) => a.localeCompare(b));

  const visibleTriagePresets = triagePresets.filter((preset) => {
    if (triagePresetTagFilter === 'ALL') return true;
    return (preset.tag || '').toLowerCase() === triagePresetTagFilter.toLowerCase();
  }).sort((a, b) => {
    if ((a.favorite ? 1 : 0) !== (b.favorite ? 1 : 0)) {
      return (b.favorite ? 1 : 0) - (a.favorite ? 1 : 0);
    }
    if (triagePresetSortMode === 'MOST_USED') {
      return (b.useCount || 0) - (a.useCount || 0);
    }
    if (triagePresetSortMode === 'RECENT_USED') {
      const aTime = a.lastUsedAt ? new Date(a.lastUsedAt).getTime() : 0;
      const bTime = b.lastUsedAt ? new Date(b.lastUsedAt).getTime() : 0;
      return bTime - aTime;
    }
    return 0;
  });

  const formatPresetLastUsed = (value?: string) => {
    if (!value) return 'never';
    const ms = Date.now() - new Date(value).getTime();
    const seconds = Math.max(0, Math.floor(ms / 1000));
    if (seconds < 60) return `${seconds}s ago`;
    const minutes = Math.floor(seconds / 60);
    if (minutes < 60) return `${minutes}m ago`;
    const hours = Math.floor(minutes / 60);
    if (hours < 24) return `${hours}h ago`;
    const days = Math.floor(hours / 24);
    return `${days}d ago`;
  };

  const exportTriagePresets = () => {
    if (triagePresets.length === 0) {
      showToast('No presets to export', 'error');
      return;
    }

    try {
      const payload = JSON.stringify({
        version: 1,
        exportedAt: new Date().toISOString(),
        presets: triagePresets,
      }, null, 2);
      const blob = new Blob([payload], { type: 'application/json' });
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement('a');
      anchor.href = url;
      anchor.download = `triage-presets-${new Date().toISOString().replace(/[:.]/g, '-')}.json`;
      anchor.click();
      URL.revokeObjectURL(url);
      showToast('Presets exported', 'success');
    } catch {
      showToast('Failed to export presets', 'error');
    }
  };

  const triggerImportTriagePresets = () => {
    importPresetInputRef.current?.click();
  };

  const importTriagePresets = async (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    if (!file) return;

    try {
      const text = await file.text();
      const parsed = JSON.parse(text) as { presets?: TriagePreset[] };
      const imported = Array.isArray(parsed?.presets)
        ? parsed.presets
            .filter((p) => typeof p?.name === 'string' && typeof p?.query === 'string')
            .map((p) => ({
              name: p.name,
              query: p.query,
              tag: typeof p.tag === 'string' ? p.tag : undefined,
              favorite: p.favorite === true,
              useCount: typeof p.useCount === 'number' ? p.useCount : undefined,
              lastUsedAt: typeof p.lastUsedAt === 'string' ? p.lastUsedAt : undefined,
            }))
        : [];

      if (imported.length === 0) {
        showToast('No valid presets found in file', 'error');
        return;
      }

      const merged = [...imported, ...triagePresets]
        .reduce<TriagePreset[]>((acc, preset) => {
          if (acc.some((p) => p.name.toLowerCase() === preset.name.toLowerCase())) return acc;
          acc.push(preset);
          return acc;
        }, [])
        .slice(0, 20);

      persistTriagePresets(merged);
      showToast(`Imported ${imported.length} preset(s)`, 'success');
    } catch {
      showToast('Failed to import presets', 'error');
    } finally {
      event.target.value = '';
    }
  };

  const toggleShortcutHints = () => {
    setShowShortcutHints((prev) => {
      const next = !prev;
      localStorage.setItem('pipelines.bucket.shortcuts.hidden', next ? 'false' : 'true');
      return next;
    });
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
        <button
          onClick={() => setShowCreateModal(true)}
          className="btn-primary"
          disabled={!canWritePipelines}
          title={!canWritePipelines ? 'You need pipeline write permission to create pipelines' : undefined}
        >
          Create Pipeline
        </button>
      </div>

      {!canReadPipelines && (
        <div className="card mb-4 border-amber-200 bg-amber-50 text-amber-900">
          You do not have permission to view pipelines in this tenant.
        </div>
      )}

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
          <div className="mb-3 flex flex-wrap items-center gap-2">
            <input
              ref={importPresetInputRef}
              type="file"
              accept="application/json"
              onChange={importTriagePresets}
              className="hidden"
            />
            <input
              ref={triagePresetNameInputRef}
              value={triagePresetName}
              onChange={(e) => setTriagePresetName(e.target.value)}
              placeholder="Save triage preset"
              className="input text-xs max-w-[190px]"
            />
            <input
              value={triagePresetTag}
              onChange={(e) => setTriagePresetTag(e.target.value)}
              placeholder="Tag (optional)"
              className="input text-xs max-w-[150px]"
            />
            <button
              onClick={saveCurrentTriagePreset}
              className="px-2 py-1 rounded border border-gray-300 text-gray-700 hover:bg-gray-50 text-xs"
            >
              Save preset
            </button>
            <button
              onClick={exportTriagePresets}
              className="px-2 py-1 rounded border border-gray-300 text-gray-700 hover:bg-gray-50 text-xs"
            >
              Export
            </button>
            <button
              onClick={triggerImportTriagePresets}
              className="px-2 py-1 rounded border border-gray-300 text-gray-700 hover:bg-gray-50 text-xs"
            >
              Import
            </button>
            {availablePresetTags.length > 0 && (
              <select
                value={triagePresetTagFilter}
                onChange={(e) => setTriagePresetTagFilter(e.target.value)}
                className="input text-xs max-w-[150px]"
              >
                <option value="ALL">All tags</option>
                {availablePresetTags.map((tag) => (
                  <option key={tag} value={tag}>{tag}</option>
                ))}
              </select>
            )}
            {triagePresets.length > 0 && (
              <select
                value={triagePresetSortMode}
                onChange={(e) => setTriagePresetSortMode(e.target.value as 'MANUAL' | 'RECENT_USED' | 'MOST_USED')}
                className="input text-xs max-w-[165px]"
              >
                <option value="MANUAL">Sort: favorites</option>
                <option value="RECENT_USED">Sort: recently used</option>
                <option value="MOST_USED">Sort: most used</option>
              </select>
            )}
            {triagePresets.length > 0 && (
              <button
                onClick={resetAllPresetUsage}
                className="px-2 py-1 rounded border border-gray-300 text-gray-700 hover:bg-gray-50 text-xs"
                title="Reset usage stats for all presets"
              >
                Reset usage
              </button>
            )}
            <label className="inline-flex items-center gap-1 text-[11px] text-gray-600">
              <input
                type="checkbox"
                checked={confirmPresetActions}
                onChange={(e) => setPresetConfirmPreference(e.target.checked)}
              />
              Confirm destructive actions
            </label>
            {triagePresets.length > 0 && (
              <div className="flex flex-wrap gap-2">
                {visibleTriagePresets.slice(0, 8).map((preset) => (
                  <span
                    key={preset.name}
                    className="inline-flex items-center gap-1 px-2 py-1 rounded border border-gray-200 bg-gray-50 text-[11px]"
                    title={`Used ${preset.useCount || 0} times, last used ${formatPresetLastUsed(preset.lastUsedAt)}`}
                  >
                    <button
                      onClick={() => toggleFavoritePreset(preset.name)}
                      className={`hover:opacity-80 ${preset.favorite ? 'text-amber-600' : 'text-gray-400'}`}
                      title={preset.favorite ? 'Unfavorite preset' : 'Favorite preset'}
                    >
                      {preset.favorite ? '★' : '☆'}
                    </button>
                    {preset.favorite && (
                      <>
                        <button
                          onClick={() => moveFavoritePreset(preset.name, 'up')}
                          className="text-gray-500 hover:text-gray-800"
                          title="Move favorite up"
                        >
                          ↑
                        </button>
                        <button
                          onClick={() => moveFavoritePreset(preset.name, 'down')}
                          className="text-gray-500 hover:text-gray-800"
                          title="Move favorite down"
                        >
                          ↓
                        </button>
                      </>
                    )}
                    {editingPresetName === preset.name ? (
                      <>
                        <input
                          value={editingPresetNextName}
                          onChange={(e) => setEditingPresetNextName(e.target.value)}
                          className="input text-[11px] px-1 py-0.5 max-w-[140px]"
                        />
                        <button onClick={confirmRenameTriagePreset} className="text-green-700 hover:text-green-900" title="Save name">✓</button>
                        <button onClick={cancelRenameTriagePreset} className="text-gray-500 hover:text-gray-700" title="Cancel rename">✕</button>
                      </>
                    ) : (
                      <button onClick={() => applyTriagePreset(preset)} className="text-primary-700 hover:text-primary-900">
                        {preset.name}
                      </button>
                    )}
                    {preset.tag && <span className="text-gray-500">[{preset.tag}]</span>}
                    <span className="text-gray-400" title="Usage count">#{preset.useCount || 0}</span>
                    <button onClick={() => resetPresetUsage(preset.name)} className="text-gray-500 hover:text-gray-700" title="Reset usage stats">↺</button>
                    <button onClick={() => duplicateTriagePreset(preset.name)} className="text-gray-500 hover:text-gray-700" title="Duplicate preset">⧉</button>
                    <button onClick={() => startRenameTriagePreset(preset.name)} className="text-gray-500 hover:text-gray-700" title="Rename preset">✎</button>
                    <button onClick={() => deleteTriagePreset(preset.name)} className="text-gray-500 hover:text-red-600">x</button>
                  </span>
                ))}
              </div>
            )}
          </div>

          {pendingPresetAction && (
            <div className="mb-3 flex items-center gap-2 rounded border border-amber-200 bg-amber-50 px-3 py-2 text-xs text-amber-900">
              <span>{pendingPresetActionLabel}</span>
              <button
                onClick={confirmPendingPresetAction}
                className="px-2 py-0.5 rounded border border-amber-300 bg-white hover:bg-amber-100"
              >
                Confirm
              </button>
              <button
                onClick={() => setPendingPresetAction(null)}
                className="px-2 py-0.5 rounded border border-amber-300 bg-white hover:bg-amber-100"
              >
                Cancel
              </button>
            </div>
          )}

          <div className="flex items-center justify-between mb-3">
            <h2 className="text-sm font-semibold text-gray-900">Runs In Selected Bucket</h2>
            <div className="flex items-center gap-2">
              <button
                onClick={clearAllTriageFilters}
                className="px-2 py-1 rounded border border-red-200 bg-red-50 text-red-700 hover:bg-red-100 text-xs"
              >
                Clear all triage
              </button>
              <label className="flex items-center gap-1 text-xs text-gray-600">
                <input
                  type="checkbox"
                  checked={autoRefreshBucket}
                  onChange={(e) => setAutoRefreshBucket(e.target.checked)}
                />
                Auto-refresh
              </label>
              <button
                onClick={handleManualBucketRefresh}
                disabled={loadingBucketRuns}
                className="px-2 py-1 rounded border border-gray-300 text-gray-700 hover:bg-gray-50 text-xs"
              >
                {loadingBucketRuns ? 'Refreshing...' : 'Refresh now'}
              </button>
              <button
                onClick={copyTriageLink}
                className="px-2 py-1 rounded border border-gray-300 text-gray-700 hover:bg-gray-50 text-xs"
              >
                Copy link
              </button>
              <span className="text-xs text-gray-500">{bucketRuns.length} runs</span>
              <select
                value={bucketSortMode}
                onChange={(e) => applyBucketSortMode(e.target.value as BucketSortMode)}
                className="input text-xs max-w-[150px]"
              >
                <option value="NEWEST">Newest first</option>
                <option value="FAILED_FIRST">Failed first</option>
              </select>
              <button
                onClick={toggleShortcutHints}
                className="px-2 py-1 rounded border border-gray-300 text-gray-700 hover:bg-gray-50 text-xs"
              >
                {showShortcutHints ? 'Hide shortcuts' : 'Show shortcuts'}
              </button>
              {bucketPipelineIdFilter && (
                <button
                  onClick={() => navigate(`/pipelines/${bucketPipelineIdFilter}`)}
                  className="px-2 py-1 rounded border border-indigo-300 bg-indigo-50 text-indigo-700 hover:bg-indigo-100 text-xs"
                  title={pinnedPipelineName ? `Open ${pinnedPipelineName}` : 'Open pinned pipeline'}
                >
                  Open pinned
                </button>
              )}
            </div>
          </div>
          {showShortcutHints && (
            <div className="mb-2 flex flex-wrap gap-2 text-[11px] text-gray-600">
              <span className="px-1.5 py-0.5 rounded border border-gray-300 bg-gray-50">`/` focus search</span>
              <span className="px-1.5 py-0.5 rounded border border-gray-300 bg-gray-50">`f` failed</span>
              <span className="px-1.5 py-0.5 rounded border border-gray-300 bg-gray-50">`r` running</span>
              <span className="px-1.5 py-0.5 rounded border border-gray-300 bg-gray-50">`a` all</span>
              <span className="px-1.5 py-0.5 rounded border border-gray-300 bg-gray-50">`n` new preset</span>
              <span className="px-1.5 py-0.5 rounded border border-gray-300 bg-gray-50">`p` apply top preset</span>
            </div>
          )}
          {bucketLastRefreshedAt && (
            <div className="mb-2 flex items-center gap-2 text-[11px]">
              <span className="text-gray-500">
                Last refreshed: {new Date(bucketLastRefreshedAt).toLocaleTimeString()}
              </span>
              <span className={`px-1.5 py-0.5 rounded border ${
                bucketFreshness === 'fresh'
                  ? 'border-green-200 bg-green-50 text-green-700'
                  : bucketFreshness === 'aging'
                    ? 'border-amber-200 bg-amber-50 text-amber-800'
                    : 'border-red-200 bg-red-50 text-red-700'
              }`}>
                {bucketFreshness === 'fresh' ? 'Fresh' : bucketFreshness === 'aging' ? 'Aging' : 'Stale'}
                {bucketRefreshAgeSeconds != null ? ` (${bucketRefreshAgeSeconds}s)` : ''}
              </span>
            </div>
          )}
          {bucketRuns.length > 0 && (
            <div className="mb-3 flex flex-wrap gap-2 text-xs">
              <button
                onClick={() => applyBucketStatusFilter('FAILED')}
                className="px-2 py-1 rounded border border-red-200 bg-red-50 text-red-700 hover:bg-red-100"
              >
                Failed: {bucketStatusCounts.failed}
              </button>
              <button
                onClick={() => applyBucketStatusFilter('RUNNING')}
                className="px-2 py-1 rounded border border-amber-200 bg-amber-50 text-amber-800 hover:bg-amber-100"
              >
                Running/Pending: {bucketStatusCounts.running}
              </button>
              <button
                onClick={() => applyBucketStatusFilter('SUCCESS')}
                className="px-2 py-1 rounded border border-green-200 bg-green-50 text-green-700 hover:bg-green-100"
              >
                Success: {bucketStatusCounts.success}
              </button>
              <button
                onClick={() => applyBucketStatusFilter('ALL')}
                className="px-2 py-1 rounded border border-gray-300 bg-white text-gray-700 hover:bg-gray-50"
              >
                All statuses
              </button>
              <input
                ref={bucketQueryInputRef}
                value={bucketPipelineQuery}
                onChange={(e) => applyBucketPipelineQuery(e.target.value)}
                placeholder="Filter pipeline name"
                className="input text-xs max-w-[190px]"
              />
            </div>
          )}

          {(bucketStatusFilter !== 'ALL' || bucketPipelineQuery.trim() || bucketSortMode !== 'NEWEST' || bucketPipelineIdFilter) && (
            <div className="mb-3 flex flex-wrap items-center gap-2 text-[11px]">
              <span className="text-gray-500">Active filters:</span>
              {bucketStatusFilter !== 'ALL' && (
                <button
                  onClick={clearBucketStatusFilter}
                  className="px-2 py-0.5 rounded-full border border-blue-200 bg-blue-50 text-blue-700 hover:bg-blue-100"
                >
                  status: {bucketStatusFilter} x
                </button>
              )}
              {bucketPipelineQuery.trim() && (
                <button
                  onClick={clearBucketPipelineQuery}
                  className="px-2 py-0.5 rounded-full border border-purple-200 bg-purple-50 text-purple-700 hover:bg-purple-100"
                >
                  query: {bucketPipelineQuery.trim()} x
                </button>
              )}
              {bucketPipelineIdFilter && (
                <button
                  onClick={clearBucketPipelineIdFilter}
                  className="px-2 py-0.5 rounded-full border border-indigo-200 bg-indigo-50 text-indigo-700 hover:bg-indigo-100"
                >
                  pipeline: pinned x
                </button>
              )}
              {bucketSortMode !== 'NEWEST' && (
                <button
                  onClick={clearBucketSort}
                  className="px-2 py-0.5 rounded-full border border-gray-300 bg-gray-50 text-gray-700 hover:bg-gray-100"
                >
                  sort: failed-first x
                </button>
              )}
            </div>
          )}
          {loadingBucketRuns ? (
            <p className="text-sm text-gray-500">Loading bucket runs...</p>
          ) : visibleBucketRuns.length > 0 ? (
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
                  {visibleBucketRuns.slice(0, 12).map((run) => (
                    <tr key={run.id} className="border-b border-gray-100">
                      <td className="py-2 pr-3">
                        <div className="flex items-center gap-2">
                          <Link to={`/pipelines/${run.pipelineId}`} className="text-primary-600 hover:text-primary-800 hover:underline">
                            {run.pipelineName}
                          </Link>
                          <button
                            onClick={() => applyBucketPipelineIdFilter(run.pipelineId)}
                            className="px-1.5 py-0.5 rounded border border-indigo-200 bg-indigo-50 text-indigo-700 hover:bg-indigo-100 text-[10px]"
                          >
                            Only
                          </button>
                        </div>
                      </td>
                      <td className="py-2 pr-3">
                        <button
                          onClick={() => applyBucketStatusFilter(run.status)}
                          className={`px-2 py-0.5 rounded text-[11px] border ${
                            run.status === 'FAILED'
                              ? 'border-red-200 bg-red-50 text-red-700'
                              : run.status === 'RUNNING' || run.status === 'PENDING'
                                ? 'border-amber-200 bg-amber-50 text-amber-800'
                                : 'border-green-200 bg-green-50 text-green-700'
                          }`}
                        >
                          {run.status}
                        </button>
                      </td>
                      <td className="py-2 pr-3">{run.startedAt ? new Date(run.startedAt).toLocaleTimeString() : '-'}</td>
                      <td className="py-2 pr-3">{run.durationMs != null ? `${run.durationMs} ms` : '-'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <p className="text-sm text-gray-500">
              {bucketRuns.length > 0 ? 'No runs match current bucket filters.' : 'No runs found in this bucket.'}
            </p>
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
                          onClick={async (e) => {
                            e.preventDefault();
                            const nextExpandedId = expandedPipelineId === pipeline.id ? null : pipeline.id;
                            setExpandedPipelineId(nextExpandedId);
                            if (nextExpandedId === pipeline.id && !detailedRunsMap[pipeline.id]) {
                              try {
                                await loadDetailedPipelineRuns(pipeline.id, detailFiltersByPipeline[pipeline.id]);
                              } catch {
                                // keep lightweight runs if detailed fetch fails
                              }
                            }
                            if (nextExpandedId === pipeline.id && !pipelineSummaries[pipeline.id]) {
                              try {
                                await loadPipelineSummary(pipeline.id);
                              } catch {
                                // keep UI usable when summary fetch fails
                              }
                            }
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
                          disabled={isTriggering || !canExecutePipelines}
                          className="btn-primary text-sm"
                          title={!canExecutePipelines ? 'You need pipeline execute permission to run pipelines' : undefined}
                        >
                          {isTriggering ? 'Triggering…' : 'Run'}
                        </button>
                        <button
                          onClick={(e) => { e.preventDefault(); setConfirmDeleteId(pipeline.id); }}
                          disabled={!canWritePipelines}
                          title={!canWritePipelines ? 'You need pipeline write permission to delete pipelines' : undefined}
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
                        title={`${run.status} — ${new Date(run.startedAt).toLocaleString()}${run.retryOfRunId ? ` — retry of ${run.retryOfRunId}` : ''}`}
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
                    <div className="mb-3 rounded border border-indigo-100 bg-indigo-50 p-2">
                      <div className="flex flex-wrap items-center justify-between gap-2 mb-2">
                        <div className="text-[11px] font-semibold text-indigo-900">Reliability Snapshot</div>
                        <div className="flex items-center gap-2">
                          {([1, 24, 168] as const).map((hours) => (
                            <button
                              key={hours}
                              onClick={async (e) => {
                                e.preventDefault();
                                try {
                                  await loadPipelineSummary(pipeline.id, hours);
                                } catch {
                                  showToast('Failed to load run summary', 'error');
                                }
                              }}
                              className={`px-2 py-0.5 rounded text-[11px] border ${
                                (summaryWindowByPipeline[pipeline.id] ?? 24) === hours
                                  ? 'border-indigo-500 text-indigo-700 bg-white'
                                  : 'border-indigo-200 text-indigo-800 bg-indigo-100'
                              }`}
                            >
                              {hours === 1 ? '1h' : hours === 24 ? '24h' : '7d'}
                            </button>
                          ))}
                        </div>
                      </div>
                      {pipelineSummaries[pipeline.id] ? (
                        <>
                          <div className="grid grid-cols-2 md:grid-cols-4 gap-2 text-[11px] text-indigo-900">
                            <div>Total: <strong>{pipelineSummaries[pipeline.id].totalRuns}</strong></div>
                            <div>Success: <strong>{pipelineSummaries[pipeline.id].successRuns}</strong></div>
                            <div>Failed: <strong>{pipelineSummaries[pipeline.id].failedRuns}</strong></div>
                            <div>In-flight: <strong>{pipelineSummaries[pipeline.id].inFlightRuns}</strong></div>
                          </div>
                          <div className="mt-2 flex flex-wrap gap-3 text-[11px] text-indigo-900">
                            <span>Success rate: <strong>{Math.round((pipelineSummaries[pipeline.id].successRate || 0) * 100)}%</strong></span>
                            <span>Avg duration: <strong>{pipelineSummaries[pipeline.id].avgDurationMs != null ? `${pipelineSummaries[pipeline.id].avgDurationMs} ms` : '-'}</strong></span>
                          </div>
                          <div className="mt-2">
                            <div className="text-[11px] text-indigo-800 mb-1">Trend buckets (click to filter run list)</div>
                            <div className="flex items-end gap-1">
                              {pipelineSummaries[pipeline.id].trendBuckets.map((bucket, index) => {
                                const peak = Math.max(...pipelineSummaries[pipeline.id].trendBuckets.map((b) => b.totalRuns || 0), 1);
                                const height = Math.max(6, Math.round((bucket.totalRuns / peak) * 28));
                                return (
                                  <button
                                    key={`${pipeline.id}-bucket-${bucket.bucketStart}`}
                                    title={`${new Date(bucket.bucketStart).toLocaleString()} - total ${bucket.totalRuns}, failed ${bucket.failedRuns}`}
                                    onClick={async (e) => {
                                      e.preventDefault();
                                      const range = bucketRangeForSummary(pipelineSummaries[pipeline.id], index);
                                      const filter = {
                                        startedFrom: range.start.toISOString(),
                                        startedTo: range.end.toISOString(),
                                        label: `${range.start.toLocaleTimeString()}-${range.end.toLocaleTimeString()}`,
                                      };
                                      setDetailFiltersByPipeline((prev) => ({ ...prev, [pipeline.id]: filter }));
                                      try {
                                        await loadDetailedPipelineRuns(pipeline.id, filter);
                                      } catch {
                                        showToast('Failed to load bucket runs', 'error');
                                      }
                                    }}
                                    className={`w-2 rounded-sm ${bucket.failedRuns > 0 ? 'bg-red-500' : bucket.inFlightRuns > 0 ? 'bg-amber-400' : bucket.totalRuns > 0 ? 'bg-emerald-500' : 'bg-indigo-200'}`}
                                    style={{ height: `${height}px` }}
                                  />
                                );
                              })}
                            </div>
                          </div>
                          <div className="mt-2 flex flex-wrap gap-2 text-[11px]">
                            <button
                              onClick={async (e) => {
                                e.preventDefault();
                                const next: PipelineRunDetailFilter = {
                                  ...(detailFiltersByPipeline[pipeline.id] || {}),
                                  runStatus: 'FAILED',
                                  label: 'Failed runs',
                                };
                                setDetailFiltersByPipeline((prev) => ({ ...prev, [pipeline.id]: next }));
                                try {
                                  await loadDetailedPipelineRuns(pipeline.id, next);
                                } catch {
                                  showToast('Failed to load failed runs', 'error');
                                }
                              }}
                              className="px-2 py-0.5 rounded border border-red-300 text-red-700 bg-white"
                            >
                              Failed only
                            </button>
                            <button
                              onClick={async (e) => {
                                e.preventDefault();
                                const next: PipelineRunDetailFilter = {
                                  ...(detailFiltersByPipeline[pipeline.id] || {}),
                                  runStatus: 'RUNNING',
                                  label: 'Running runs',
                                };
                                setDetailFiltersByPipeline((prev) => ({ ...prev, [pipeline.id]: next }));
                                try {
                                  await loadDetailedPipelineRuns(pipeline.id, next);
                                } catch {
                                  showToast('Failed to load running runs', 'error');
                                }
                              }}
                              className="px-2 py-0.5 rounded border border-amber-300 text-amber-800 bg-white"
                            >
                              Running only
                            </button>
                            <button
                              onClick={async (e) => {
                                e.preventDefault();
                                setDetailFiltersByPipeline((prev) => {
                                  const next = { ...prev };
                                  delete next[pipeline.id];
                                  return next;
                                });
                                try {
                                  await loadDetailedPipelineRuns(pipeline.id);
                                } catch {
                                  showToast('Failed to clear run filters', 'error');
                                }
                              }}
                              className="px-2 py-0.5 rounded border border-indigo-300 text-indigo-800 bg-white"
                            >
                              Clear filters
                            </button>
                          </div>
                          {detailFiltersByPipeline[pipeline.id]?.label && (
                            <div className="mt-1 text-[11px] text-indigo-900">
                              Active run filter: <strong>{detailFiltersByPipeline[pipeline.id].label}</strong>
                            </div>
                          )}
                        </>
                      ) : (
                        <div className="text-[11px] text-indigo-800">No summary loaded yet.</div>
                      )}
                    </div>
                    {(() => {
                      const detailRuns = detailedRunsMap[pipeline.id] ?? runs;
                      return detailRuns.length === 0;
                    })() ? (
                      <p className="text-sm text-gray-500">No runs available yet.</p>
                    ) : (
                      <>
                        <div className="flex flex-wrap gap-2 mb-3">
                          {(detailedRunsMap[pipeline.id] ?? runs).map((run) => (
                            <button
                              key={run.id}
                              onClick={(e) => {
                                e.preventDefault();
                                setSelectedRunIdByPipeline((prev) => ({ ...prev, [pipeline.id]: run.id }));
                              }}
                              className={`px-2 py-1 text-xs rounded border ${
                                (selectedRunIdByPipeline[pipeline.id] ?? (detailedRunsMap[pipeline.id] ?? runs)[0].id) === run.id
                                  ? 'bg-white border-primary-500 text-primary-700'
                                  : 'bg-gray-100 border-gray-300 text-gray-700'
                              }`}
                            >
                              {run.status} - {new Date(run.startedAt).toLocaleTimeString()}
                            </button>
                          ))}
                        </div>

                        {(() => {
                          const detailRuns = detailedRunsMap[pipeline.id] ?? runs;
                          const selectedRunId = selectedRunIdByPipeline[pipeline.id] ?? detailRuns[0].id;
                          const selectedRun = detailRuns.find((r) => r.id === selectedRunId) ?? detailRuns[0];
                          const resultPayload = (selectedRun.metrics?.result as Record<string, unknown> | undefined) ?? selectedRun.metrics;
                          const alertEvaluation = (selectedRun.alertEvaluation as Record<string, unknown> | undefined)
                            ?? (resultPayload?.alertEvaluation as Record<string, unknown> | undefined);
                          const moduleLogs = selectedRun.moduleLogs ?? (selectedRun.metrics?.moduleLogs as PipelineRun['moduleLogs']);
                          const executionLog = selectedRun.executionLog ?? (selectedRun.metrics?.executionLog as string[] | undefined);
                          return (
                            <div className="text-xs text-gray-700 space-y-2">
                              <div className="flex flex-wrap gap-4">
                                <span>Status: <strong>{selectedRun.status}</strong></span>
                                <span>Trigger: <strong>{selectedRun.triggerType}</strong></span>
                                {selectedRun.retryOfRunId && (
                                  <span>
                                    Retry of: <strong>{selectedRun.retryOfRunId.slice(0, 8)}</strong>
                                  </span>
                                )}
                                <span>Started: <strong>{formatRunTime(selectedRun.startedAt)}</strong></span>
                                <span>Duration: <strong>{selectedRun.durationMs != null ? `${selectedRun.durationMs} ms` : '-'}</strong></span>
                                <button
                                  onClick={(e) => {
                                    e.preventDefault();
                                    void handleRefreshRun(pipeline.id, selectedRun.id);
                                  }}
                                  disabled={refreshingRunId === selectedRun.id}
                                  className="px-2 py-0.5 rounded border border-slate-300 text-slate-700 bg-white"
                                >
                                  {refreshingRunId === selectedRun.id ? 'Refreshing...' : 'Refresh run'}
                                </button>
                                <button
                                  onClick={(e) => {
                                    e.preventDefault();
                                    void handleLoadRunLineage(pipeline.id, selectedRun.id);
                                  }}
                                  disabled={loadingLineageRunId === selectedRun.id}
                                  className="px-2 py-0.5 rounded border border-indigo-200 text-indigo-700 bg-white"
                                >
                                  {loadingLineageRunId === selectedRun.id
                                    ? 'Loading lineage...'
                                    : `Load lineage (${lineageDirectionByRunId[selectedRun.id] ?? 'both'} ${lineageStatusFilterByRunId[selectedRun.id] ?? 'ALL'} d${lineageDepthByRunId[selectedRun.id] ?? 20})`}
                                </button>
                                {(selectedRun.status === 'RUNNING' || selectedRun.status === 'PENDING') && (
                                  <button
                                    onClick={(e) => {
                                      e.preventDefault();
                                      void handleCancelRun(pipeline.id, selectedRun.id);
                                    }}
                                    disabled={cancellingRunId === selectedRun.id || !canExecutePipelines}
                                    title={!canExecutePipelines ? 'You need pipeline execute permission to cancel runs' : undefined}
                                    className="px-2 py-0.5 rounded border border-red-300 text-red-700 bg-white"
                                  >
                                    {cancellingRunId === selectedRun.id ? 'Cancelling...' : 'Cancel run'}
                                  </button>
                                )}
                                {(selectedRun.status === 'FAILED' || selectedRun.status === 'CANCELLED') && (
                                  <button
                                    onClick={(e) => {
                                      e.preventDefault();
                                      void handleRetryRun(pipeline.id, selectedRun.id);
                                    }}
                                    disabled={retryingRunId === selectedRun.id || !canExecutePipelines}
                                    title={!canExecutePipelines ? 'You need pipeline execute permission to retry runs' : undefined}
                                    className="px-2 py-0.5 rounded border border-indigo-300 text-indigo-700 bg-white"
                                  >
                                    {retryingRunId === selectedRun.id ? 'Retrying...' : 'Retry run'}
                                  </button>
                                )}
                              </div>
                              {lineageByRunId[selectedRun.id] && (
                                <div className="rounded border border-indigo-100 bg-indigo-50 p-2 text-[11px] text-indigo-900">
                                  <div className="font-semibold mb-1">Retry Lineage</div>
                                  <div className="mb-1">
                                    Ancestors: {lineageByRunId[selectedRun.id].ancestors.length}/{lineageByRunId[selectedRun.id].ancestorsTotal ?? lineageByRunId[selectedRun.id].ancestors.length} · Descendants: {lineageByRunId[selectedRun.id].descendants.length}/{lineageByRunId[selectedRun.id].descendantsTotal ?? lineageByRunId[selectedRun.id].descendants.length}
                                  </div>
                                  <div className="mb-2 flex flex-wrap items-center gap-2">
                                    <span className="text-[11px] text-indigo-800">Direction:</span>
                                    {(['both', 'ancestors', 'descendants'] as const).map((option) => {
                                      const active = (lineageByRunId[selectedRun.id].direction ?? lineageDirectionByRunId[selectedRun.id] ?? 'both') === option;
                                      return (
                                        <button
                                          key={`${selectedRun.id}-lineage-direction-${option}`}
                                          className={`px-2 py-0.5 rounded border ${active ? 'border-indigo-500 bg-indigo-100 text-indigo-900' : 'border-indigo-300 bg-white text-indigo-800'}`}
                                          onClick={(e) => {
                                            e.preventDefault();
                                            const currentDepth = lineageDepthByRunId[selectedRun.id] ?? 20;
                                            void handleLoadRunLineage(
                                              pipeline.id,
                                              selectedRun.id,
                                              currentDepth,
                                              option,
                                              lineageStatusFilterByRunId[selectedRun.id] ?? 'ALL'
                                            );
                                          }}
                                        >
                                          {option}
                                        </button>
                                      );
                                    })}
                                  </div>
                                  <div className="mb-2 flex flex-wrap items-center gap-2">
                                    <span className="text-[11px] text-indigo-800">Status:</span>
                                    {LINEAGE_STATUS_OPTIONS.map((option) => {
                                      const active = (lineageStatusFilterByRunId[selectedRun.id] ?? 'ALL') === option;
                                      const statusCount = option === 'ALL'
                                        ? (lineageByRunId[selectedRun.id].ancestorsTotal ?? lineageByRunId[selectedRun.id].ancestors.length)
                                          + (lineageByRunId[selectedRun.id].descendantsTotal ?? lineageByRunId[selectedRun.id].descendants.length)
                                        : (lineageByRunId[selectedRun.id].ancestorsStatusCounts?.[option] ?? 0)
                                          + (lineageByRunId[selectedRun.id].descendantsStatusCounts?.[option] ?? 0);
                                      const disabled = option !== 'ALL' && statusCount === 0;
                                      return (
                                        <button
                                          key={`${selectedRun.id}-lineage-status-${option}`}
                                          disabled={disabled}
                                          className={`px-2 py-0.5 rounded border ${active ? 'border-indigo-500 bg-indigo-100 text-indigo-900' : 'border-indigo-300 bg-white text-indigo-800'} ${disabled ? 'opacity-50 cursor-not-allowed' : ''}`}
                                          onClick={(e) => {
                                            e.preventDefault();
                                            if (disabled) return;
                                            const currentDepth = lineageDepthByRunId[selectedRun.id] ?? 20;
                                            void handleLoadRunLineage(
                                              pipeline.id,
                                              selectedRun.id,
                                              currentDepth,
                                              lineageDirectionByRunId[selectedRun.id] ?? 'both',
                                              option
                                            );
                                          }}
                                        >
                                          {`${option} (${statusCount})`}
                                        </button>
                                      );
                                    })}
                                  </div>
                                  <div className="mb-2 flex flex-wrap items-center gap-2 text-[11px]">
                                    <span className="text-indigo-800">Quick presets:</span>
                                    <button
                                      className="px-2 py-0.5 rounded border border-indigo-300 bg-white"
                                      onClick={(e) => {
                                        e.preventDefault();
                                        const currentDepth = lineageDepthByRunId[selectedRun.id] ?? 20;
                                        void handleLoadRunLineage(
                                          pipeline.id,
                                          selectedRun.id,
                                          currentDepth,
                                          lineageDirectionByRunId[selectedRun.id] ?? 'both',
                                          'FAILED'
                                        );
                                      }}
                                    >
                                      Failures only
                                    </button>
                                    <button
                                      className="px-2 py-0.5 rounded border border-indigo-300 bg-white"
                                      onClick={(e) => {
                                        e.preventDefault();
                                        const currentDepth = lineageDepthByRunId[selectedRun.id] ?? 20;
                                        void handleLoadRunLineage(
                                          pipeline.id,
                                          selectedRun.id,
                                          currentDepth,
                                          lineageDirectionByRunId[selectedRun.id] ?? 'both',
                                          'RUNNING'
                                        );
                                      }}
                                    >
                                      In-flight only
                                    </button>
                                    <button
                                      className="px-2 py-0.5 rounded border border-indigo-300 bg-white"
                                      onClick={(e) => {
                                        e.preventDefault();
                                        const currentDepth = lineageDepthByRunId[selectedRun.id] ?? 20;
                                        void handleLoadRunLineage(
                                          pipeline.id,
                                          selectedRun.id,
                                          currentDepth,
                                          lineageDirectionByRunId[selectedRun.id] ?? 'both',
                                          'ALL'
                                        );
                                      }}
                                    >
                                      Clear status filter
                                    </button>
                                  </div>
                                  <div className="mb-2 flex flex-wrap gap-2">
                                    {lineageByRunId[selectedRun.id].rootRunId && (
                                      <button
                                        className="px-2 py-0.5 rounded border border-indigo-300 bg-white"
                                        onClick={(e) => {
                                          e.preventDefault();
                                          const rootId = lineageByRunId[selectedRun.id].rootRunId;
                                          if (!rootId) return;
                                          const rootRun = lineageByRunId[selectedRun.id].ancestors.find((item) => item.id === rootId);
                                          if (rootRun) {
                                            setDetailedRunsMap((prev) => {
                                              const existing = prev[pipeline.id] ?? [];
                                              if (existing.some((item) => item.id === rootRun.id)) return prev;
                                              return { ...prev, [pipeline.id]: [{ ...rootRun, metrics: rootRun.metrics ?? {} }, ...existing] };
                                            });
                                          }
                                          setSelectedRunIdByPipeline((prev) => ({ ...prev, [pipeline.id]: rootId }));
                                        }}
                                      >
                                        Go to root
                                      </button>
                                    )}
                                    {lineageByRunId[selectedRun.id].latestDescendantRunId && (
                                      <button
                                        className="px-2 py-0.5 rounded border border-indigo-300 bg-white"
                                        onClick={(e) => {
                                          e.preventDefault();
                                          const latestId = lineageByRunId[selectedRun.id].latestDescendantRunId;
                                          if (!latestId) return;
                                          const latestRun = lineageByRunId[selectedRun.id].descendants.find((item) => item.id === latestId);
                                          if (latestRun) {
                                            setDetailedRunsMap((prev) => {
                                              const existing = prev[pipeline.id] ?? [];
                                              if (existing.some((item) => item.id === latestRun.id)) return prev;
                                              return { ...prev, [pipeline.id]: [{ ...latestRun, metrics: latestRun.metrics ?? {} }, ...existing] };
                                            });
                                          }
                                          setSelectedRunIdByPipeline((prev) => ({ ...prev, [pipeline.id]: latestId }));
                                        }}
                                      >
                                        Go to latest retry
                                      </button>
                                    )}
                                  </div>
                                  {(lineageByRunId[selectedRun.id].ancestorsTruncated || lineageByRunId[selectedRun.id].descendantsTruncated) && (
                                    <div className="mb-2 flex items-center gap-2">
                                      <span className="text-xs text-indigo-800">Lineage truncated at depth {lineageByRunId[selectedRun.id].maxDepth ?? lineageDepthByRunId[selectedRun.id] ?? 20}</span>
                                      <button
                                        className="px-2 py-0.5 rounded border border-indigo-300 bg-white"
                                        onClick={(e) => {
                                          e.preventDefault();
                                          const currentDepth = lineageDepthByRunId[selectedRun.id] ?? 20;
                                          const nextDepth = lineageByRunId[selectedRun.id].nextRecommendedDepth ?? Math.min(200, currentDepth + 20);
                                          void handleLoadRunLineage(
                                            pipeline.id,
                                            selectedRun.id,
                                            nextDepth,
                                            lineageDirectionByRunId[selectedRun.id] ?? 'both',
                                            lineageStatusFilterByRunId[selectedRun.id] ?? 'ALL'
                                          );
                                        }}
                                      >
                                        {`Load to d${lineageByRunId[selectedRun.id].nextRecommendedDepth ?? Math.min(200, (lineageDepthByRunId[selectedRun.id] ?? 20) + 20)}`}
                                      </button>
                                    </div>
                                  )}
                                  <div className="flex flex-wrap gap-2">
                                    {lineageByRunId[selectedRun.id].ancestors.map((run) => (
                                      <button
                                        key={`ancestor-${run.id}`}
                                        onClick={(e) => {
                                          e.preventDefault();
                                          setDetailedRunsMap((prev) => {
                                            const existing = prev[pipeline.id] ?? [];
                                            if (existing.some((item) => item.id === run.id)) return prev;
                                            return { ...prev, [pipeline.id]: [{ ...run, metrics: run.metrics ?? {} }, ...existing] };
                                          });
                                          setSelectedRunIdByPipeline((prev) => ({ ...prev, [pipeline.id]: run.id }));
                                        }}
                                        className="px-2 py-0.5 rounded border border-indigo-300 bg-white"
                                      >
                                        Ancestor {run.id.slice(0, 6)} (d{run.lineageDepth ?? -1})
                                      </button>
                                    ))}
                                    {lineageByRunId[selectedRun.id].descendants.map((run) => (
                                      <button
                                        key={`descendant-${run.id}`}
                                        onClick={(e) => {
                                          e.preventDefault();
                                          setDetailedRunsMap((prev) => {
                                            const existing = prev[pipeline.id] ?? [];
                                            if (existing.some((item) => item.id === run.id)) return prev;
                                            return { ...prev, [pipeline.id]: [{ ...run, metrics: run.metrics ?? {} }, ...existing] };
                                          });
                                          setSelectedRunIdByPipeline((prev) => ({ ...prev, [pipeline.id]: run.id }));
                                        }}
                                        className="px-2 py-0.5 rounded border border-indigo-300 bg-white"
                                      >
                                        Descendant {run.id.slice(0, 6)} (+{run.lineageDepth ?? 1})
                                      </button>
                                    ))}
                                  </div>
                                </div>
                              )}
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
                              {Array.isArray(executionLog) && executionLog.length > 0 && (
                                <div>
                                  <div className="font-semibold text-gray-800 mb-1">Execution Timeline</div>
                                  <pre className="mt-1 max-h-28 overflow-auto rounded bg-white border border-gray-200 p-2 text-[11px] text-gray-700">{executionLog.join('\n')}</pre>
                                </div>
                              )}
                              {Array.isArray(moduleLogs) && moduleLogs.length > 0 && (
                                <div className="space-y-2">
                                  <div className="font-semibold text-gray-800">Module Logs</div>
                                  {moduleLogs.map((moduleLog, index) => (
                                    <div key={`${selectedRun.id}-log-${index}`} className="rounded border border-gray-200 bg-white p-2">
                                      <div className="flex flex-wrap gap-3 mb-1 text-[11px]">
                                        <span>Stage: <strong>{moduleLog.stage}</strong></span>
                                        {moduleLog.order != null && <span>Order: <strong>{moduleLog.order}</strong></span>}
                                        <span>Module: <strong>{moduleLog.moduleName || moduleLog.moduleVersionId || 'unknown'}</strong></span>
                                        <span>Status: <strong>{moduleLog.success === false ? 'FAILED' : 'SUCCESS'}</strong></span>
                                        {moduleLog.durationMs != null && <span>Duration: <strong>{moduleLog.durationMs} ms</strong></span>}
                                        {moduleLog.exitCode != null && <span>Exit: <strong>{moduleLog.exitCode}</strong></span>}
                                      </div>
                                      {moduleLog.connector && (
                                        <div className="mb-2 rounded border border-blue-100 bg-blue-50 px-2 py-1 text-[11px] text-blue-900">
                                          <span className="mr-3">Connector: <strong>{moduleLog.connector.name || moduleLog.connector.id || 'unknown'}</strong></span>
                                          {moduleLog.connector.connectorType && <span className="mr-3">Type: <strong>{moduleLog.connector.connectorType}</strong></span>}
                                          {moduleLog.connector.direction && <span className="mr-3">Direction: <strong>{moduleLog.connector.direction}</strong></span>}
                                          {moduleLog.connector.topic && <span className="mr-3">Topic: <strong>{moduleLog.connector.topic}</strong></span>}
                                          {moduleLog.connector.username && <span>User: <strong>{moduleLog.connector.username}</strong></span>}
                                        </div>
                                      )}
                                      {moduleLog.error && <div className="text-red-700 mb-1">Error: {moduleLog.error}</div>}
                                      <div className="grid grid-cols-1 md:grid-cols-2 gap-2">
                                        <div>
                                          <div className="font-medium text-gray-700 mb-1">stdout</div>
                                          <pre className="max-h-32 overflow-auto rounded bg-gray-50 border border-gray-200 p-2 text-[11px] text-gray-700 whitespace-pre-wrap">{moduleLog.stdout || '(empty)'}</pre>
                                        </div>
                                        <div>
                                          <div className="font-medium text-gray-700 mb-1">stderr</div>
                                          <pre className="max-h-32 overflow-auto rounded bg-gray-50 border border-gray-200 p-2 text-[11px] text-gray-700 whitespace-pre-wrap">{moduleLog.stderr || '(empty)'}</pre>
                                        </div>
                                      </div>
                                    </div>
                                  ))}
                                </div>
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
            <button
              onClick={() => setShowCreateModal(true)}
              className="btn-primary"
              disabled={!canWritePipelines}
              title={!canWritePipelines ? 'You need pipeline write permission to create pipelines' : undefined}
            >
              Create Your First Pipeline
            </button>
          )}
        </div>
      )}

      {showCreateModal && canWritePipelines && (
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
