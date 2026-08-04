import { useEffect, useState } from 'react';
import { useParams, useNavigate, Link } from 'react-router-dom';
import api from '../services/api';
import type { PipelineRun, Pipeline } from '../types';

// Module-level cache so navigating away and back doesn't lose log data.
// Capped at 50 entries to prevent unbounded memory growth in long sessions.
const MAX_CACHE = 50;

function cappedSet<V>(map: Map<string, V>, key: string, value: V) {
  if (map.size >= MAX_CACHE && !map.has(key)) {
    // Evict oldest entry (first key in insertion order)
    const oldest = map.keys().next().value;
    if (oldest !== undefined) map.delete(oldest);
  }
  map.set(key, value);
}

const runCache = new Map<string, PipelineRun>();
const pipelineCache = new Map<string, Pipeline>();

const STATUS_COLOR: Record<string, string> = {
  SUCCESS:   'bg-emerald-100 text-emerald-800 border-emerald-300',
  FAILED:    'bg-red-100 text-red-800 border-red-300',
  RUNNING:   'bg-blue-100 text-blue-800 border-blue-300',
  PENDING:   'bg-amber-100 text-amber-800 border-amber-300',
  CANCELLED: 'bg-gray-100 text-gray-600 border-gray-300',
};

const NODE_TYPE_COLOR: Record<string, string> = {
  IMPORT:     'bg-emerald-100 text-emerald-800 border border-emerald-300',
  PROCESSING: 'bg-blue-100 text-blue-800 border border-blue-300',
  MERGE:      'bg-violet-100 text-violet-800 border border-violet-300',
  ALERT:      'bg-amber-100 text-amber-800 border border-amber-300',
  EXPORT:     'bg-rose-100 text-rose-800 border border-rose-300',
};

const NODE_TYPE_ICON: Record<string, string> = {
  IMPORT:     '↓',
  PROCESSING: '⚙',
  MERGE:      '⊕',
  ALERT:      '⚠',
  EXPORT:     '↑',
};

/**
 * Implements `formatDuration` for this source unit.
 */
function formatDuration(ms?: number) {
  if (ms == null) return '—';
  if (ms < 1000) return `${ms}ms`;
  if (ms < 60000) return `${(ms / 1000).toFixed(1)}s`;
  return `${Math.floor(ms / 60000)}m ${Math.floor((ms % 60000) / 1000)}s`;
}

/**
 * Implements `ResultDisplay` for this source unit.
 */
function ResultDisplay({ result }: { result: unknown }) {
  if (result == null) return <span className="text-gray-400 italic">null</span>;

  if (typeof result === 'object') {
    const json = JSON.stringify(result, null, 2);
    const isLong = json.length > 500;
    return (
      <pre className="text-xs bg-gray-900 text-emerald-300 rounded p-3 overflow-x-auto whitespace-pre-wrap break-all font-mono leading-relaxed max-h-64 overflow-y-auto">
        {isLong ? json.slice(0, 500) + '\n…' : json}
      </pre>
    );
  }

  return (
    <pre className="text-xs bg-gray-900 text-emerald-300 rounded p-3 font-mono">
      {String(result)}
    </pre>
  );
}

/**
 * Implements `ModuleLogCard` for this source unit.
 */
function ModuleLogCard({ log, index }: {
  log: NonNullable<PipelineRun['moduleLogs']>[0];
  index: number;
}) {
  const [expanded, setExpanded] = useState(true);

  const stageLabel = log.stage?.toUpperCase() ?? 'MODULE';
  const colorClass = NODE_TYPE_COLOR[stageLabel] ?? 'bg-gray-100 text-gray-700 border border-gray-300';
  const icon = NODE_TYPE_ICON[stageLabel] ?? '·';

  const hasOutput = log.stdout || log.stderr || log.result != null || log.error;

  return (
    <div className="border rounded-lg overflow-hidden shadow-sm">
      {/* Header */}
      <button
        className={`w-full flex items-center justify-between px-4 py-3 text-left transition-colors ${
          log.success === false ? 'bg-red-50 hover:bg-red-100' : 'bg-white hover:bg-gray-50'
        }`}
        onClick={() => setExpanded((v) => !v)}
      >
        <div className="flex items-center gap-3 min-w-0">
          <span className="text-gray-400 text-xs font-mono w-5 text-center shrink-0">{index + 1}</span>
          <span className={`text-xs font-semibold px-2 py-0.5 rounded shrink-0 ${colorClass}`}>
            {icon} {stageLabel}
          </span>
          <span className="font-medium text-gray-800 truncate">
            {log.moduleName ?? log.moduleVersionId ?? 'Unknown module'}
          </span>
          {log.connector && (
            <span className="text-xs text-gray-400 truncate hidden sm:block">
              {log.connector.connectorType && `[${log.connector.connectorType}]`}
              {log.connector.name && ` ${log.connector.name}`}
            </span>
          )}
        </div>
        <div className="flex items-center gap-3 shrink-0 ml-4">
          {log.durationMs != null && (
            <span className="text-xs text-gray-400 font-mono">{formatDuration(log.durationMs)}</span>
          )}
          <span className={`text-sm font-bold ${log.success ? 'text-emerald-600' : 'text-red-600'}`}>
            {log.success ? '✓' : '✗'}
          </span>
          {hasOutput && (
            <span className="text-gray-400 text-xs">{expanded ? '▲' : '▼'}</span>
          )}
        </div>
      </button>

      {/* Body */}
      {expanded && hasOutput && (
        <div className="border-t bg-gray-50 divide-y">
          {/* Error message */}
          {log.error && (
            <div className="px-4 py-3">
              <p className="text-xs font-semibold text-red-600 mb-1">Error</p>
              <p className="text-sm text-red-700 break-words font-mono">{log.error}</p>
            </div>
          )}

          {/* Result / return value */}
          {log.result != null && (
            <div className="px-4 py-3">
              <p className="text-xs font-semibold text-gray-600 mb-1.5">
                Return value
                <span className="ml-2 font-normal text-gray-400">
                  ({Array.isArray(log.result) ? `array[${(log.result as unknown[]).length}]` : typeof log.result})
                </span>
              </p>
              <ResultDisplay result={log.result} />
            </div>
          )}

          {/* stdout */}
          {log.stdout ? (
            <div className="px-4 py-3">
              <p className="text-xs font-semibold text-gray-600 mb-1.5">stdout</p>
              <pre className="text-xs bg-gray-900 text-gray-100 rounded p-3 overflow-x-auto whitespace-pre-wrap break-all max-h-96 overflow-y-auto font-mono leading-relaxed">
                {log.stdout}
              </pre>
            </div>
          ) : (
            !log.error && log.result == null && (
              <div className="px-4 py-2 text-xs text-gray-400 italic">No output</div>
            )
          )}

          {/* stderr */}
          {log.stderr && (
            <div className="px-4 py-3">
              <p className="text-xs font-semibold text-red-600 mb-1.5">stderr</p>
              <pre className="text-xs bg-red-950 text-red-200 rounded p-3 overflow-x-auto whitespace-pre-wrap break-all max-h-64 overflow-y-auto font-mono leading-relaxed">
                {log.stderr}
              </pre>
            </div>
          )}

          {/* Connector info */}
          {log.connector && (
            <div className="px-4 py-2 flex flex-wrap gap-x-6 gap-y-1 text-xs text-gray-500 bg-white">
              <span className="font-medium text-gray-500">Connector</span>
              {log.connector.connectorType && <span><span className="font-medium text-gray-600">Type:</span> {log.connector.connectorType}</span>}
              {log.connector.name && <span><span className="font-medium text-gray-600">Name:</span> {log.connector.name}</span>}
              {log.connector.topic && <span><span className="font-medium text-gray-600">Topic:</span> {log.connector.topic}</span>}
              {log.connector.broker && <span><span className="font-medium text-gray-600">Broker:</span> {log.connector.broker}</span>}
            </div>
          )}

          {/* exit code */}
          {log.exitCode != null && log.exitCode !== 0 && (
            <div className="px-4 py-2 text-xs text-gray-500">
              Exit code: <span className="font-mono text-red-600">{log.exitCode}</span>
            </div>
          )}
        </div>
      )}
    </div>
  );
}

export default function RunDetail() {
  const { id: pipelineId, runId } = useParams<{ id: string; runId: string }>();
  const navigate = useNavigate();
  const [run, setRun] = useState<PipelineRun | null>(() => (runId ? runCache.get(runId) ?? null : null));
  const [pipeline, setPipeline] = useState<Pipeline | null>(() => (pipelineId ? pipelineCache.get(pipelineId) ?? null : null));
  const [loading, setLoading] = useState(!runId || !runCache.has(runId));
  const [error, setError] = useState('');

  /**
   * Implements `useEffect` for this source unit.
   */
  useEffect(() => {
    if (!pipelineId || !runId) { setLoading(false); return; }
    /**
     * Retrieves or enumerates the requested state.
     */
    const load = async () => {
      try {
        const [runData, pipelineData] = await Promise.all([
          api.getPipelineRun(pipelineId, runId, true),
          api.getPipeline(pipelineId),
        ]);
        cappedSet(runCache, runId, runData);
        cappedSet(pipelineCache, pipelineId, pipelineData);
        setRun(runData);
        setPipeline(pipelineData);
      } catch (err) {
        if (!runCache.has(runId)) setError('Failed to load run details');
      } finally {
        setLoading(false);
      }
    };
    load();

    // Poll while running/pending
    const timer = setInterval(async () => {
      try {
        const runData = await api.getPipelineRun(pipelineId, runId, true);
        cappedSet(runCache, runId, runData);
        setRun(runData);
        if (runData.status !== 'RUNNING' && runData.status !== 'PENDING') {
          clearInterval(timer);
        }
      } catch { clearInterval(timer); }
    }, 2500);
    return () => clearInterval(timer);
  }, [pipelineId, runId]);

  if (loading) {
    return (
      <div className="flex items-center justify-center h-64">
        <div className="animate-spin rounded-full h-10 w-10 border-b-2 border-primary-600" />
      </div>
    );
  }

  if (error || !run) {
    return (
      <div className="p-6">
        <p className="text-red-600">{error || 'Run not found'}</p>
        <button onClick={() => navigate(-1)} className="mt-3 text-primary-600 hover:underline text-sm">← Back</button>
      </div>
    );
  }

  const statusClass = STATUS_COLOR[run.status] ?? 'bg-gray-100 text-gray-700 border-gray-300';
  const logs = (run.moduleLogs ?? []).slice().sort((a, b) => (a.order ?? 0) - (b.order ?? 0));
  const executionLog = run.executionLog ?? [];

  const successCount = logs.filter((l) => l.success).length;
  const failCount = logs.filter((l) => !l.success).length;

  return (
    <div className="max-w-4xl mx-auto px-4 py-6 space-y-6">
      {/* Breadcrumb */}
      <nav className="text-sm text-gray-500 flex items-center gap-2 flex-wrap">
        <Link to="/pipelines" className="hover:text-primary-600">Pipelines</Link>
        <span>/</span>
        {pipeline && (
          <>
            <Link to={`/pipelines/${pipelineId}`} className="hover:text-primary-600 max-w-xs truncate">
              {pipeline.name}
            </Link>
            <span>/</span>
          </>
        )}
        <span className="text-gray-700 font-medium truncate max-w-xs" title={runId}>
          Run {runId?.slice(0, 8)}…
        </span>
      </nav>

      {/* Run header */}
      <div className="bg-white rounded-lg border shadow-sm p-5">
        <div className="flex items-start justify-between gap-4 flex-wrap">
          <div>
            <h1 className="text-lg font-semibold text-gray-900 mb-1">
              {pipeline?.name ?? 'Pipeline'} — Run
            </h1>
            <p className="text-xs text-gray-400 font-mono">{runId}</p>
          </div>
          <div className="flex items-center gap-2 flex-wrap">
            {run.status === 'RUNNING' && (
              <span className="flex items-center gap-1.5 text-xs text-blue-600">
                <span className="w-2 h-2 rounded-full bg-blue-500 animate-pulse" />
                Running…
              </span>
            )}
            <span className={`text-sm font-bold px-3 py-1 rounded border ${statusClass}`}>
              {run.status}
            </span>
          </div>
        </div>

        <div className="mt-4 grid grid-cols-2 sm:grid-cols-4 gap-4 text-sm">
          <div>
            <p className="text-xs text-gray-400 uppercase tracking-wide mb-0.5">Started</p>
            <p className="text-gray-700">{new Date(run.startedAt).toLocaleString()}</p>
          </div>
          {run.completedAt && (
            <div>
              <p className="text-xs text-gray-400 uppercase tracking-wide mb-0.5">Completed</p>
              <p className="text-gray-700">{new Date(run.completedAt).toLocaleString()}</p>
            </div>
          )}
          <div>
            <p className="text-xs text-gray-400 uppercase tracking-wide mb-0.5">Duration</p>
            <p className="text-gray-700 font-mono">{formatDuration(run.durationMs)}</p>
          </div>
          <div>
            <p className="text-xs text-gray-400 uppercase tracking-wide mb-0.5">Trigger</p>
            <p className="text-gray-700">{run.triggerType}</p>
          </div>
        </div>

        {/* Module summary pills */}
        {logs.length > 0 && (
          <div className="mt-4 flex items-center gap-3 text-xs">
            <span className="text-gray-500">{logs.length} module{logs.length !== 1 ? 's' : ''}:</span>
            {successCount > 0 && (
              <span className="bg-emerald-100 text-emerald-800 px-2 py-0.5 rounded-full font-medium">
                {successCount} passed
              </span>
            )}
            {failCount > 0 && (
              <span className="bg-red-100 text-red-800 px-2 py-0.5 rounded-full font-medium">
                {failCount} failed
              </span>
            )}
          </div>
        )}

        {run.errorMessage && (
          <div className="mt-4 p-3 bg-red-50 border border-red-200 rounded">
            <p className="text-sm font-semibold text-red-700 mb-0.5">Pipeline error</p>
            <p className="text-sm text-red-600 break-words font-mono">{run.errorMessage}</p>
          </div>
        )}

        {run.alertsFired != null && run.alertsFired > 0 && (
          <div className="mt-3 text-sm text-amber-700">
            ⚠ {run.alertsFired} alert{run.alertsFired !== 1 ? 's' : ''} fired
          </div>
        )}
      </div>

      {/* Module logs */}
      <div>
        <h2 className="text-sm font-semibold text-gray-700 uppercase tracking-wide mb-3">
          Module logs ({logs.length})
        </h2>
        {logs.length === 0 ? (
          <p className="text-sm text-gray-400 italic">No module logs recorded for this run.</p>
        ) : (
          <div className="space-y-3">
            {logs.map((log, i) => (
              <ModuleLogCard key={log.nodeId ?? i} log={log} index={i} />
            ))}
          </div>
        )}
      </div>

      {/* Execution log */}
      {executionLog.length > 0 && (
        <div>
          <h2 className="text-sm font-semibold text-gray-700 uppercase tracking-wide mb-3">
            Execution log
          </h2>
          <pre className="text-xs bg-gray-900 text-gray-200 rounded p-4 overflow-x-auto whitespace-pre-wrap break-all max-h-64 overflow-y-auto font-mono leading-relaxed">
            {executionLog.join('\n')}
          </pre>
        </div>
      )}

      {/* Back link */}
      <div className="pt-2">
        <button
          onClick={() => navigate(`/pipelines/${pipelineId}`)}
          className="text-sm text-primary-600 hover:underline"
        >
          ← Back to pipeline editor
        </button>
      </div>
    </div>
  );
}
