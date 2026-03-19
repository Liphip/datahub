import { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useAuthStore } from '../stores/auth';
import api from '../services/api';
import type { Pipeline, PipelineRun, CodeModule, Connector, Tenant, PipelineRunSummary } from '../types';

interface TenantNode extends Tenant {
  children?: TenantNode[];
}

export default function Dashboard() {
  const { t } = useTranslation();
  const { user, selectedTenantId, tenantContexts } = useAuthStore();
  const navigate = useNavigate();
  const [pipelines, setPipelines] = useState<Pipeline[]>([]);
  const [allRuns, setAllRuns] = useState<(PipelineRun & { pipelineName?: string })[]>([]);
  const [modules, setModules] = useState<CodeModule[]>([]);
  const [connectors, setConnectors] = useState<Connector[]>([]);
  const [tenantTree, setTenantTree] = useState<TenantNode[]>([]);
  const [runSummary, setRunSummary] = useState<PipelineRunSummary | null>(null);
  const [reliabilityWindow, setReliabilityWindow] = useState<1 | 24 | 168>(24);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const tenantId = selectedTenantId || user?.tenantId || tenantContexts[0]?.tenantId;

  useEffect(() => {
    const fetchData = async () => {
      if (!tenantId) { setLoading(false); return; }
      try {
        const [pipelinesData, modulesData, connectorsData] = await Promise.all([
          api.getPipelines(tenantId, { includeRunSummary: true }),
          api.getModules(tenantId),
          api.getConnectors(tenantId),
        ]);
        setPipelines(pipelinesData);
        setModules(modulesData);
        setConnectors(connectorsData);

        const runLists = await Promise.all(
          pipelinesData.map(async (p) => {
            try {
              const runs = await api.getPipelineRuns(p.id);
              return runs.slice(0, 3).map((r) => ({ ...r, pipelineName: p.name }));
            } catch {
              return [];
            }
          })
        );
        const combined = runLists
          .flat()
          .sort((a, b) => new Date(b.startedAt).getTime() - new Date(a.startedAt).getTime())
          .slice(0, 10);
        setAllRuns(combined);

        try {
          const summary = await api.getPipelineRunSummary(tenantId, 50, reliabilityWindow);
          setRunSummary(summary);
        } catch {
          setRunSummary(null);
        }

        if (user?.superadmin) {
          try {
            const tenants = await api.getTenantsRecursive();
            setTenantTree(tenants as TenantNode[]);
          } catch {
            setTenantTree([]);
          }
        }

        setError('');
      } catch {
        setError(t('dashboard.failedToLoadDashboard'));
      } finally {
        setLoading(false);
      }
    };
    fetchData();
  }, [tenantId, user?.superadmin, reliabilityWindow, t]);

  const activePipelines = pipelines.filter((p) => p.isActive).length;
  const failedRuns = allRuns.filter((r) => r.status === 'FAILED').length;
  const successRuns = allRuns.filter((r) => r.status === 'SUCCESS').length;
  const problematicPipelines = pipelines
    .filter((p) => p.latestRunStatus === 'FAILED' || p.latestRunStatus === 'RUNNING' || p.latestRunStatus === 'PENDING')
    .sort((a, b) => {
      const weight = (s?: string) => (s === 'FAILED' ? 0 : s === 'RUNNING' || s === 'PENDING' ? 1 : 2);
      const w = weight(a.latestRunStatus) - weight(b.latestRunStatus);
      if (w !== 0) return w;
      const aTime = a.latestRunStartedAt ? new Date(a.latestRunStartedAt).getTime() : 0;
      const bTime = b.latestRunStartedAt ? new Date(b.latestRunStartedAt).getTime() : 0;
      return bTime - aTime;
    });

  const runStatusColor: Record<string, string> = {
    SUCCESS: 'bg-green-500',
    FAILED: 'bg-red-500',
    RUNNING: 'bg-yellow-400 animate-pulse',
    PENDING: 'bg-gray-400',
    CANCELLED: 'bg-gray-300',
  };

  const stats = [
    { label: t('dashboard.totalPipelines'), value: pipelines.length, color: 'text-primary-600' },
    { label: t('dashboard.activePipelines'), value: activePipelines, color: 'text-green-600' },
    { label: t('dashboard.modules'), value: modules.length, color: 'text-purple-600' },
    { label: t('dashboard.connectors'), value: connectors.length, color: 'text-blue-600' },
    { label: t('dashboard.recentSuccesses'), value: successRuns, color: 'text-green-600' },
    { label: t('dashboard.recentFailures'), value: failedRuns, color: failedRuns > 0 ? 'text-red-600' : 'text-gray-500' },
  ];

  const flattenTenants = (nodes: TenantNode[]): TenantNode[] =>
    nodes.flatMap((node) => [node, ...flattenTenants((node.children || []) as TenantNode[])]);

  const maxTenantDepth = (nodes: TenantNode[], depth = 1): number => {
    if (nodes.length === 0) return depth - 1;
    return Math.max(
      ...nodes.map((node) => {
        const children = (node.children || []) as TenantNode[];
        return children.length > 0 ? maxTenantDepth(children, depth + 1) : depth;
      })
    );
  };

  const allTenants = flattenTenants(tenantTree);
  const leafTenants = allTenants.filter((t) => !t.children || t.children.length === 0).length;
  const latestTenantUpdate = allTenants
    .map((t) => t.updatedAt)
    .filter((d): d is string => Boolean(d))
    .sort((a, b) => new Date(b).getTime() - new Date(a).getTime())[0];

  const maxTrendTotal = Math.max(1, ...(runSummary?.trendBuckets?.map((b) => b.totalRuns) ?? [1]));

  return (
    <div className="px-4 py-6 sm:px-0">
      <div className="mb-6">
        <h1 className="text-2xl font-bold text-gray-900">{t('dashboard.title')}</h1>
        <p className="mt-1 text-sm text-gray-600">
          {t('dashboard.welcomeBack', { name: user?.firstName || user?.email })}
        </p>
      </div>

      {error && (
        <div className="mb-6 bg-red-50 border border-red-200 text-red-700 px-4 py-3 rounded">{error}</div>
      )}

      <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-6 gap-4 mb-8">
        {stats.map((s) => (
          <div key={s.label} className="card text-center">
            <div className={`text-2xl font-bold ${s.color}`}>
              {loading ? '–' : s.value}
            </div>
            <div className="text-xs text-gray-500 mt-1">{s.label}</div>
          </div>
        ))}
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        <div className="lg:col-span-2 card">
          <div className="flex justify-between items-center mb-4">
            <h2 className="text-lg font-semibold">{t('dashboard.recentPipelineRuns')}</h2>
            <Link to="/pipelines" className="text-sm text-primary-600 hover:text-primary-700">{t('dashboard.viewAll')}</Link>
          </div>

          {loading ? (
            <div className="animate-pulse space-y-3">
              {[1, 2, 3].map(i => <div key={i} className="h-10 bg-gray-200 rounded"></div>)}
            </div>
          ) : allRuns.length > 0 ? (
            <div className="space-y-2">
              {allRuns.map((run) => (
                <div key={run.id} className="flex items-center justify-between py-2 px-3 bg-gray-50 rounded">
                  <div className="flex items-center space-x-3 min-w-0">
                    <span className={`w-2.5 h-2.5 rounded-full shrink-0 ${runStatusColor[run.status] ?? 'bg-gray-400'}`} />
                    <div className="min-w-0">
                      <p className="text-sm font-medium truncate">{run.pipelineName ?? t('dashboard.pipeline')}</p>
                      <p className="text-xs text-gray-400">{run.triggerType}</p>
                    </div>
                  </div>
                  <div className="shrink-0 text-right ml-4">
                    <span className={`px-2 py-0.5 text-xs rounded font-medium ${
                      run.status === 'SUCCESS' ? 'bg-green-100 text-green-800' :
                      run.status === 'FAILED' ? 'bg-red-100 text-red-800' :
                      run.status === 'RUNNING' ? 'bg-yellow-100 text-yellow-800' :
                      'bg-gray-100 text-gray-700'
                    }`}>
                      {run.status}
                    </span>
                    <p className="text-xs text-gray-400 mt-1">{new Date(run.startedAt).toLocaleString()}</p>
                  </div>
                </div>
              ))}
            </div>
          ) : (
            <p className="text-gray-500 text-center py-8">{t('dashboard.noPipelineRunsYet')}</p>
          )}
        </div>

        <div className="space-y-6">
          {!loading && (
            <div className="card border-l-4 border-l-amber-500">
              <div className="flex items-center justify-between mb-2">
                <h2 className="text-base font-semibold">{t('dashboard.problemPipelines')}</h2>
                <span className={`text-xs px-2 py-0.5 rounded ${problematicPipelines.length > 0 ? 'bg-amber-100 text-amber-800' : 'bg-green-100 text-green-800'}`}>
                  {problematicPipelines.length}
                </span>
              </div>
              {problematicPipelines.length > 0 ? (
                <>
                  <ul className="space-y-2 mb-3">
                    {problematicPipelines.slice(0, 5).map((p) => (
                      <li key={p.id} className="flex items-center justify-between text-sm">
                        <Link to={`/pipelines/${p.id}`} className="text-primary-600 hover:underline truncate">
                          {p.name}
                        </Link>
                        <span className={`ml-2 px-2 py-0.5 rounded text-xs ${
                          p.latestRunStatus === 'FAILED'
                            ? 'bg-red-100 text-red-800'
                            : 'bg-yellow-100 text-yellow-800'
                        }`}>
                          {p.latestRunStatus}
                        </span>
                      </li>
                    ))}
                  </ul>
                  <Link to="/pipelines?health=PROBLEMATIC" className="btn-secondary block text-center text-sm">{t('dashboard.openProblemView')}</Link>
                </>
              ) : (
                <p className="text-sm text-gray-500">{t('dashboard.noProblemPipelines')}</p>
              )}
            </div>
          )}

          <div className="card">
            <div className="flex items-center justify-between mb-3">
              <h2 className="text-base font-semibold">{t('dashboard.executionReliability')}</h2>
              <div className="flex items-center gap-1">
                {([
                  { label: t('dashboard.last1h'), value: 1 },
                  { label: t('dashboard.last24h'), value: 24 },
                  { label: t('dashboard.last7d'), value: 168 },
                ] as const).map((opt) => (
                  <button
                    key={opt.value}
                    onClick={() => setReliabilityWindow(opt.value)}
                    className={`px-2 py-1 text-xs rounded border ${
                      reliabilityWindow === opt.value
                        ? 'bg-primary-600 text-white border-primary-600'
                        : 'bg-white text-gray-700 border-gray-300 hover:bg-gray-50'
                    }`}
                  >
                    {opt.label}
                  </button>
                ))}
              </div>
            </div>
            <div className="grid grid-cols-2 gap-3 text-sm">
              <div>
                <p className="text-gray-500">{t('dashboard.window')}</p>
                <p className="font-semibold text-gray-900">{runSummary?.sinceHours ? `${runSummary.sinceHours}h` : 'n/a'}</p>
              </div>
              <div>
                <p className="text-gray-500">{t('dashboard.successRate')}</p>
                <p className={`font-semibold ${(runSummary?.successRate ?? 0) >= 0.9 ? 'text-green-700' : (runSummary?.successRate ?? 0) >= 0.75 ? 'text-amber-700' : 'text-red-700'}`}>
                  {runSummary ? `${Math.round(runSummary.successRate * 100)}%` : 'n/a'}
                </p>
              </div>
              <div>
                <p className="text-gray-500">{t('dashboard.failures')}</p>
                <p className="font-semibold text-gray-900">{runSummary?.failedRuns ?? 0}</p>
              </div>
              <div>
                <p className="text-gray-500">{t('dashboard.inFlight')}</p>
                <p className="font-semibold text-gray-900">{runSummary?.inFlightRuns ?? 0}</p>
              </div>
            </div>
            <p className="mt-3 text-xs text-gray-500">{t('dashboard.averageDuration')}: {runSummary?.avgDurationMs != null ? `${runSummary.avgDurationMs} ms` : 'n/a'}</p>
            <div className="mt-3">
              <div className="flex items-end gap-1 h-16 rounded border border-gray-200 bg-gray-50 px-2 py-1">
                {(runSummary?.trendBuckets ?? []).map((bucket) => {
                  const ratio = bucket.totalRuns / maxTrendTotal;
                  const failureShare = bucket.totalRuns > 0 ? bucket.failedRuns / bucket.totalRuns : 0;
                  const bg = failureShare > 0.4 ? 'bg-red-400' : failureShare > 0 ? 'bg-amber-400' : 'bg-green-400';
                  const targetHealth = bucket.failedRuns > 0 ? 'FAILED' : bucket.inFlightRuns > 0 ? 'RUNNING' : 'HEALTHY';
                  return (
                    <button
                      type="button"
                      key={bucket.bucketStart}
                      className={`flex-1 rounded-t ${bg}`}
                      title={`${new Date(bucket.bucketStart).toLocaleString()} | total:${bucket.totalRuns} success:${bucket.successRuns} failed:${bucket.failedRuns} running:${bucket.inFlightRuns}`}
                      style={{ height: `${Math.max(8, Math.round(ratio * 100))}%` }}
                      onClick={() => navigate(`/pipelines?health=${targetHealth}&sinceHours=${reliabilityWindow}&bucketStart=${encodeURIComponent(bucket.bucketStart)}&bucketSeconds=${runSummary?.bucketSeconds ?? 0}`)}
                    />
                  );
                })}
              </div>
              <div className="mt-1 flex justify-between text-[10px] text-gray-500">
                <span>{reliabilityWindow === 1 ? t('dashboard.lastHour') : reliabilityWindow === 24 ? t('dashboard.lastDay') : t('dashboard.last7Days')}</span>
                <span>{t('dashboard.trendLegend')}</span>
              </div>
            </div>
          </div>

          <div className="card">
            <h2 className="text-base font-semibold mb-3">{t('dashboard.quickActions')}</h2>
            <div className="space-y-2">
              <Link to="/modules" className="btn-primary block text-center text-sm">{t('dashboard.createModule')}</Link>
              <Link to="/pipelines" className="btn-secondary block text-center text-sm">{t('dashboard.createPipeline')}</Link>
              <Link to="/connectors" className="btn-secondary block text-center text-sm">{t('dashboard.addConnector')}</Link>
            </div>
          </div>

          {user?.superadmin && (
            <div className="card">
              <h2 className="text-base font-semibold mb-3">{t('dashboard.tenantTopology')}</h2>
              <div className="grid grid-cols-2 gap-3 text-sm">
                <div>
                  <p className="text-gray-500">{t('dashboard.total')}</p>
                  <p className="font-semibold text-gray-900">{loading ? '–' : allTenants.length}</p>
                </div>
                <div>
                  <p className="text-gray-500">{t('dashboard.roots')}</p>
                  <p className="font-semibold text-gray-900">{loading ? '–' : tenantTree.length}</p>
                </div>
                <div>
                  <p className="text-gray-500">{t('dashboard.leafTenants')}</p>
                  <p className="font-semibold text-gray-900">{loading ? '–' : leafTenants}</p>
                </div>
                <div>
                  <p className="text-gray-500">{t('dashboard.maxDepth')}</p>
                  <p className="font-semibold text-gray-900">{loading ? '–' : maxTenantDepth(tenantTree)}</p>
                </div>
              </div>
              <p className="mt-3 text-xs text-gray-500">
                Last tenant update: {latestTenantUpdate ? new Date(latestTenantUpdate).toLocaleString() : 'n/a'}
              </p>
              <Link to="/tenants" className="btn-secondary block text-center text-sm mt-3">{t('dashboard.openTenantTree')}</Link>
            </div>
          )}

          {!loading && pipelines.length > 0 && (
            <div className="card">
              <h2 className="text-base font-semibold mb-3">{t('nav.pipelines')}</h2>
              <ul className="space-y-2">
                {pipelines.slice(0, 5).map((p) => (
                  <li key={p.id} className="flex items-center justify-between text-sm">
                    <Link to={`/pipelines/${p.id}`} className="text-primary-600 hover:underline truncate">
                      {p.name}
                    </Link>
                    <span className={`shrink-0 ml-2 w-2 h-2 rounded-full ${p.isActive ? 'bg-green-400' : 'bg-gray-300'}`} />
                  </li>
                ))}
                {pipelines.length > 5 && (
                  <li className="text-xs text-gray-400">{t('dashboard.more', { count: pipelines.length - 5 })}</li>
                )}
              </ul>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
