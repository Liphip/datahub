import { useEffect, useState, useCallback } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useAuthStore } from '../stores/auth';
import api from '../services/api';
import type { Pipeline, PipelineRun, PipelineRunSummary, TenantRunSummary } from '../types';

// ── Helpers ───────────────────────────────────────────────────────────────────

function fmt(ms?: number | null) {
  if (ms == null) return 'n/a';
  if (ms < 1000) return `${ms}ms`;
  return `${(ms / 1000).toFixed(1)}s`;
}

function fmtRate(rate?: number | null) {
  if (rate == null) return 'n/a';
  return `${Math.round(rate * 100)}%`;
}

function rateColor(rate?: number | null) {
  if (rate == null) return 'text-slate-500';
  if (rate >= 0.9) return 'text-emerald-600 dark:text-emerald-400';
  if (rate >= 0.75) return 'text-amber-600 dark:text-amber-400';
  return 'text-red-600 dark:text-red-400';
}

function rateBg(rate?: number | null) {
  if (rate == null) return 'bg-slate-100 text-slate-600 dark:bg-slate-800 dark:text-slate-300';
  if (rate >= 0.9) return 'bg-emerald-100 text-emerald-800 dark:bg-emerald-900/40 dark:text-emerald-300';
  if (rate >= 0.75) return 'bg-amber-100 text-amber-800 dark:bg-amber-900/40 dark:text-amber-300';
  return 'bg-red-100 text-red-800 dark:bg-red-900/40 dark:text-red-300';
}

const STATUS_DOT: Record<string, string> = {
  SUCCESS:   'bg-emerald-500',
  FAILED:    'bg-red-500',
  RUNNING:   'bg-amber-400 animate-pulse',
  PENDING:   'bg-slate-400',
  CANCELLED: 'bg-slate-300',
};

const STATUS_BADGE: Record<string, string> = {
  SUCCESS:   'bg-emerald-100 text-emerald-800 dark:bg-emerald-900/40 dark:text-emerald-300',
  FAILED:    'bg-red-100 text-red-800 dark:bg-red-900/40 dark:text-red-300',
  RUNNING:   'bg-amber-100 text-amber-800 dark:bg-amber-900/40 dark:text-amber-300',
  PENDING:   'bg-slate-100 text-slate-700 dark:bg-slate-700 dark:text-slate-300',
  CANCELLED: 'bg-slate-100 text-slate-500 dark:bg-slate-800 dark:text-slate-400',
};

// ── Mini sparkline (bar chart) ────────────────────────────────────────────────

function MiniSparkline({
  buckets,
  height = 28,
}: {
  buckets: PipelineRunSummary['trendBuckets'];
  height?: number;
}) {
  const max = Math.max(1, ...buckets.map((b) => b.totalRuns));
  if (buckets.length === 0) return <div className="text-xs text-slate-400">No data</div>;
  return (
    <div className="flex items-end gap-px" style={{ height }}>
      {buckets.map((b, i) => {
        const ratio = b.totalRuns / max;
        const failShare = b.totalRuns > 0 ? b.failedRuns / b.totalRuns : 0;
        const bg =
          b.totalRuns === 0
            ? 'bg-slate-200 dark:bg-slate-700'
            : failShare > 0.4
            ? 'bg-red-400 dark:bg-red-500'
            : failShare > 0
            ? 'bg-amber-400 dark:bg-amber-500'
            : 'bg-emerald-400 dark:bg-emerald-500';
        return (
          <div
            key={i}
            className={`flex-1 rounded-t ${bg} min-h-[2px]`}
            style={{ height: `${Math.max(8, Math.round(ratio * 100))}%` }}
            title={`${new Date(b.bucketStart).toLocaleString()} · ${b.totalRuns} runs (${b.failedRuns} failed)`}
          />
        );
      })}
    </div>
  );
}

// ── Per-tenant data (backed by TenantRunSummary from the all-tenants endpoint) ─

interface TenantStats extends TenantRunSummary {
  roleName?: string;
  loading: boolean;
  /** activePipelines mirrors activePipelineCount for consistent local naming */
  activePipelines: number;
  /** summary sub-object for components that expect PipelineRunSummary shape */
  summary: Pick<PipelineRunSummary, 'successRate' | 'totalRuns' | 'trendBuckets'> | null;
}

// ── Stat card ─────────────────────────────────────────────────────────────────

function StatCard({
  label,
  value,
  sub,
  colorClass = 'text-slate-800 dark:text-slate-100',
}: {
  label: string;
  value: string | number;
  sub?: string;
  colorClass?: string;
}) {
  return (
    <div className="bg-white dark:bg-slate-900 rounded-xl border border-slate-200 dark:border-slate-800 p-4">
      <p className="text-xs font-medium text-slate-500 dark:text-slate-400 uppercase tracking-wide">{label}</p>
      <p className={`mt-1 text-2xl font-bold ${colorClass}`}>{value}</p>
      {sub && <p className="mt-0.5 text-xs text-slate-400">{sub}</p>}
    </div>
  );
}

// ── Section heading ───────────────────────────────────────────────────────────

function SectionHeading({
  children,
  action,
}: {
  children: React.ReactNode;
  action?: React.ReactNode;
}) {
  return (
    <div className="flex items-center justify-between mb-4">
      <h2 className="text-base font-semibold text-slate-800 dark:text-slate-100">{children}</h2>
      {action}
    </div>
  );
}

// ── Main Dashboard ────────────────────────────────────────────────────────────

export default function Dashboard() {
  const { t } = useTranslation();
  const { user, selectedTenantId, tenantContexts, switchTenant } = useAuthStore();
  const navigate = useNavigate();

  const tenantId = selectedTenantId || user?.tenantId || tenantContexts[0]?.tenantId;

  // Current-tenant data
  const [pipelines, setPipelines] = useState<Pipeline[]>([]);
  const [recentRuns, setRecentRuns] = useState<(PipelineRun & { pipelineName?: string })[]>([]);
  const [summary, setSummary] = useState<PipelineRunSummary | null>(null);
  const [reliabilityWindow, setReliabilityWindow] = useState<1 | 24 | 168>(24);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  // Cross-tenant stats
  const [tenantStats, setTenantStats] = useState<TenantStats[]>([]);

  // ── Fetch current-tenant data ──────────────────────────────────────────────
  useEffect(() => {
    if (!tenantId) { setLoading(false); return; }
    let cancelled = false;
    setLoading(true);
    setError('');

    const run = async () => {
      try {
        const [pipelinesData, summaryData] = await Promise.all([
          api.getPipelines(tenantId, { includeRunSummary: true }),
          api.getPipelineRunSummary(tenantId, 50, reliabilityWindow).catch(() => null),
        ]);
        if (cancelled) return;
        setPipelines(pipelinesData);
        setSummary(summaryData);

        // Collect recent runs across all pipelines (top 10)
        const runLists = await Promise.all(
          pipelinesData.map(async (p) => {
            try {
              const runs = await api.getPipelineRuns(p.id);
              return runs.slice(0, 3).map((r) => ({ ...r, pipelineName: p.name }));
            } catch { return []; }
          })
        );
        if (cancelled) return;
        setRecentRuns(
          runLists
            .flat()
            .sort((a, b) => new Date(b.startedAt).getTime() - new Date(a.startedAt).getTime())
            .slice(0, 12)
        );
      } catch {
        if (!cancelled) setError(t('dashboard.failedToLoadDashboard'));
      } finally {
        if (!cancelled) setLoading(false);
      }
    };
    run();
    return () => { cancelled = true; };
  }, [tenantId, reliabilityWindow, t]);

  // ── Fetch all-tenants stats via single endpoint ────────────────────────────
  useEffect(() => {
    if (tenantContexts.length < 2) { setTenantStats([]); return; }
    let cancelled = false;

    // Show loading skeletons immediately
    setTenantStats(
      tenantContexts.map((ctx) => ({
        tenantId: ctx.tenantId,
        tenantName: ctx.tenantName,
        roleName: ctx.roleName,
        pipelineCount: 0,
        activePipelineCount: 0,
        activePipelines: 0,
        sinceHours: 24,
        bucketSeconds: 3600,
        totalRuns: 0,
        successRuns: 0,
        failedRuns: 0,
        inFlightRuns: 0,
        successRate: 0,
        trendBuckets: [],
        summary: null,
        loading: true,
      }))
    );

    api.getAllTenantRunSummaries(24).then((summaries) => {
      if (cancelled) return;
      // Merge API results with tenant contexts (to preserve roleName)
      const roleByTenant = Object.fromEntries(tenantContexts.map((c) => [c.tenantId, c.roleName]));
      setTenantStats(
        summaries.map((s) => ({
          ...s,
          roleName: roleByTenant[s.tenantId],
          activePipelines: s.activePipelineCount,
          summary: {
            successRate: s.successRate,
            totalRuns: s.totalRuns,
            trendBuckets: s.trendBuckets,
          },
          loading: false,
        }))
      );
    }).catch(() => {
      if (!cancelled) setTenantStats([]);
    });

    return () => { cancelled = true; };
  }, [tenantContexts]);

  const handleSwitchTenant = useCallback(
    async (tId: string) => {
      await switchTenant(tId);
      navigate('/');
    },
    [switchTenant, navigate]
  );

  // ── Derived values ─────────────────────────────────────────────────────────
  const activePipelines = pipelines.filter((p) => p.isActive).length;
  const problematicPipelines = pipelines.filter(
    (p) => p.latestRunStatus === 'FAILED'
  );
  const runningPipelines = pipelines.filter(
    (p) => p.latestRunStatus === 'RUNNING' || p.latestRunStatus === 'PENDING'
  );
  const maxTrend = Math.max(1, ...(summary?.trendBuckets?.map((b) => b.totalRuns) ?? [1]));

  const currentTenantName =
    tenantContexts.find((c) => c.tenantId === tenantId)?.tenantName ?? 'Tenant';
  const userDisplayName = user?.firstName
    ? `${user.firstName}${user.lastName ? ' ' + user.lastName : ''}`
    : user?.email ?? '';

  return (
    <div className="space-y-8">
      {/* ── Welcome banner ───────────────────────────────────────────────────── */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-slate-900 dark:text-slate-50">
            {t('dashboard.welcomeBack', { name: userDisplayName })}
          </h1>
          <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">
            {currentTenantName} · {new Date().toLocaleDateString(undefined, { weekday: 'long', year: 'numeric', month: 'long', day: 'numeric' })}
          </p>
        </div>
        <div className="flex items-center gap-2">
          <Link to="/pipelines" className="btn-primary text-sm">
            + New Pipeline
          </Link>
          <Link to="/modules" className="btn-secondary text-sm">
            + New Module
          </Link>
        </div>
      </div>

      {error && (
        <div className="rounded-lg border border-red-200 dark:border-red-900 bg-red-50 dark:bg-red-950/40 px-4 py-3 text-sm text-red-700 dark:text-red-300">
          {error}
        </div>
      )}

      {/* ── Multi-tenant overview (only when user has multiple tenants) ──────── */}
      {tenantContexts.length > 1 && (
        <section>
          <SectionHeading>All Workspaces</SectionHeading>
          <div className="grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-3 gap-4">
            {tenantStats.map((ts) => {
              const isSelected = ts.tenantId === tenantId;
              return (
                <div
                  key={ts.tenantId}
                  className={`bg-white dark:bg-slate-900 rounded-xl border-2 p-5 transition-shadow hover:shadow-md ${
                    isSelected
                      ? 'border-primary-500 dark:border-primary-500'
                      : 'border-slate-200 dark:border-slate-800'
                  }`}
                >
                  <div className="flex items-start justify-between mb-4">
                    <div>
                      <div className="flex items-center gap-2">
                        <div className="w-8 h-8 rounded-lg bg-primary-100 dark:bg-primary-900/40 flex items-center justify-center">
                          <span className="text-sm font-bold text-primary-700 dark:text-primary-300">
                            {ts.tenantName[0]?.toUpperCase()}
                          </span>
                        </div>
                        <div>
                          <h3 className="text-sm font-semibold text-slate-800 dark:text-slate-100">{ts.tenantName}</h3>
                          {ts.roleName && (
                            <p className="text-xs text-slate-400">{ts.roleName}</p>
                          )}
                        </div>
                      </div>
                    </div>
                    {!isSelected && (
                      <button
                        onClick={() => handleSwitchTenant(ts.tenantId)}
                        className="text-xs text-primary-600 dark:text-primary-400 hover:underline"
                      >
                        Switch →
                      </button>
                    )}
                    {isSelected && (
                      <span className="text-xs bg-primary-100 dark:bg-primary-900/40 text-primary-700 dark:text-primary-300 px-2 py-0.5 rounded-full font-medium">
                        Active
                      </span>
                    )}
                  </div>

                  {ts.loading ? (
                    <div className="animate-pulse space-y-2">
                      <div className="h-3 bg-slate-200 dark:bg-slate-700 rounded w-3/4" />
                      <div className="h-8 bg-slate-100 dark:bg-slate-800 rounded mt-3" />
                    </div>
                  ) : (
                    <>
                      <div className="grid grid-cols-3 gap-3 mb-3">
                        <div className="text-center">
                          <p className="text-lg font-bold text-slate-800 dark:text-slate-100">{ts.pipelineCount}</p>
                          <p className="text-[10px] text-slate-400 uppercase tracking-wide">Pipelines</p>
                        </div>
                        <div className="text-center">
                          <p className="text-lg font-bold text-emerald-600 dark:text-emerald-400">{ts.activePipelineCount}</p>
                          <p className="text-[10px] text-slate-400 uppercase tracking-wide">Active</p>
                        </div>
                        <div className="text-center">
                          <p className={`text-lg font-bold ${rateColor(ts.successRate)}`}>
                            {fmtRate(ts.successRate)}
                          </p>
                          <p className="text-[10px] text-slate-400 uppercase tracking-wide">Success</p>
                        </div>
                      </div>

                      {ts.trendBuckets.length > 0 && (
                        <div className="border-t border-slate-100 dark:border-slate-800 pt-3">
                          <MiniSparkline buckets={ts.trendBuckets} height={24} />
                          <p className="text-[10px] text-slate-400 mt-1">24h trend · {ts.totalRuns} runs</p>
                        </div>
                      )}
                    </>
                  )}
                </div>
              );
            })}
          </div>
        </section>
      )}

      {/* ── Current tenant KPI row ────────────────────────────────────────────── */}
      <section>
        {tenantContexts.length > 1 && (
          <SectionHeading>
            {currentTenantName} — Overview
          </SectionHeading>
        )}
        <div className="grid grid-cols-2 sm:grid-cols-3 xl:grid-cols-6 gap-4">
          <StatCard
            label="Total Pipelines"
            value={loading ? '–' : pipelines.length}
            colorClass="text-slate-800 dark:text-slate-100"
          />
          <StatCard
            label="Active"
            value={loading ? '–' : activePipelines}
            colorClass="text-emerald-600 dark:text-emerald-400"
          />
          <StatCard
            label="Running Now"
            value={loading ? '–' : runningPipelines.length}
            colorClass={runningPipelines.length > 0 ? 'text-amber-600 dark:text-amber-400' : 'text-slate-500'}
          />
          <StatCard
            label="Failed (latest)"
            value={loading ? '–' : problematicPipelines.length}
            colorClass={problematicPipelines.length > 0 ? 'text-red-600 dark:text-red-400' : 'text-slate-500'}
          />
          <StatCard
            label="Success Rate"
            value={loading ? '–' : fmtRate(summary?.successRate)}
            sub={`${reliabilityWindow === 1 ? 'last hour' : reliabilityWindow === 24 ? 'last 24h' : 'last 7 days'}`}
            colorClass={rateColor(summary?.successRate)}
          />
          <StatCard
            label="Avg Duration"
            value={loading ? '–' : fmt(summary?.avgDurationMs)}
            colorClass="text-slate-700 dark:text-slate-200"
          />
        </div>
      </section>

      {/* ── Main content grid ─────────────────────────────────────────────────── */}
      <div className="grid grid-cols-1 xl:grid-cols-3 gap-6">
        {/* ── Recent runs ───────────────────────────────────────────────────── */}
        <div className="xl:col-span-2 bg-white dark:bg-slate-900 rounded-xl border border-slate-200 dark:border-slate-800 p-5">
          <SectionHeading
            action={
              <Link to="/pipelines" className="text-xs text-primary-600 dark:text-primary-400 hover:underline">
                View all →
              </Link>
            }
          >
            Recent Pipeline Runs
          </SectionHeading>

          {loading ? (
            <div className="animate-pulse space-y-3">
              {Array.from({ length: 5 }, (_, i) => (
                <div key={i} className="h-12 bg-slate-100 dark:bg-slate-800 rounded-lg" />
              ))}
            </div>
          ) : recentRuns.length === 0 ? (
            <div className="text-center py-12">
              <div className="w-12 h-12 rounded-full bg-slate-100 dark:bg-slate-800 flex items-center justify-center mx-auto mb-3">
                <svg className="w-6 h-6 text-slate-400" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.5}>
                  <path d="M22 12h-4l-3 9L9 3l-3 9H2" />
                </svg>
              </div>
              <p className="text-sm text-slate-500">{t('dashboard.noPipelineRunsYet')}</p>
              <Link to="/pipelines" className="mt-3 inline-block text-sm text-primary-600 dark:text-primary-400 hover:underline">
                Create your first pipeline →
              </Link>
            </div>
          ) : (
            <div className="divide-y divide-slate-100 dark:divide-slate-800">
              {recentRuns.map((run) => (
                <div key={run.id} className="flex items-center gap-3 py-3 first:pt-0 last:pb-0">
                  <span
                    className={`w-2 h-2 rounded-full shrink-0 ${STATUS_DOT[run.status] ?? 'bg-slate-400'}`}
                  />
                  <div className="flex-1 min-w-0">
                    <p className="text-sm font-medium text-slate-800 dark:text-slate-100 truncate">
                      {run.pipelineName ?? t('dashboard.pipeline')}
                    </p>
                    <p className="text-xs text-slate-400">
                      {run.triggerType} · {new Date(run.startedAt).toLocaleString()}
                      {run.durationMs != null && ` · ${fmt(run.durationMs)}`}
                    </p>
                  </div>
                  <span
                    className={`shrink-0 px-2 py-0.5 text-xs font-medium rounded-full ${STATUS_BADGE[run.status] ?? 'bg-slate-100 text-slate-700'}`}
                  >
                    {run.status}
                  </span>
                </div>
              ))}
            </div>
          )}
        </div>

        {/* ── Right column ──────────────────────────────────────────────────── */}
        <div className="space-y-5">
          {/* Reliability trend */}
          <div className="bg-white dark:bg-slate-900 rounded-xl border border-slate-200 dark:border-slate-800 p-5">
            <div className="flex items-center justify-between mb-3">
              <h2 className="text-base font-semibold text-slate-800 dark:text-slate-100">
                {t('dashboard.executionReliability')}
              </h2>
              <div className="flex gap-1">
                {([
                  { label: '1h', value: 1 as const },
                  { label: '24h', value: 24 as const },
                  { label: '7d', value: 168 as const },
                ]).map((opt) => (
                  <button
                    key={opt.value}
                    onClick={() => setReliabilityWindow(opt.value)}
                    className={`px-2 py-0.5 text-xs rounded border transition-colors ${
                      reliabilityWindow === opt.value
                        ? 'bg-primary-600 text-white border-primary-600 dark:bg-primary-500 dark:border-primary-500'
                        : 'bg-white dark:bg-slate-900 text-slate-600 dark:text-slate-400 border-slate-200 dark:border-slate-700 hover:bg-slate-50 dark:hover:bg-slate-800'
                    }`}
                  >
                    {opt.label}
                  </button>
                ))}
              </div>
            </div>

            <div className="grid grid-cols-2 gap-3 text-sm mb-4">
              <div>
                <p className="text-xs text-slate-500 dark:text-slate-400">Total Runs</p>
                <p className="font-semibold text-slate-800 dark:text-slate-100">{summary?.totalRuns ?? 0}</p>
              </div>
              <div>
                <p className="text-xs text-slate-500 dark:text-slate-400">Success Rate</p>
                <p className={`font-semibold ${rateColor(summary?.successRate)}`}>
                  {fmtRate(summary?.successRate)}
                </p>
              </div>
              <div>
                <p className="text-xs text-slate-500 dark:text-slate-400">Failures</p>
                <p className="font-semibold text-slate-800 dark:text-slate-100">{summary?.failedRuns ?? 0}</p>
              </div>
              <div>
                <p className="text-xs text-slate-500 dark:text-slate-400">In-flight</p>
                <p className={`font-semibold ${(summary?.inFlightRuns ?? 0) > 0 ? 'text-amber-600 dark:text-amber-400' : 'text-slate-800 dark:text-slate-100'}`}>
                  {summary?.inFlightRuns ?? 0}
                </p>
              </div>
            </div>

            {/* Trend chart */}
            <div className="rounded-lg border border-slate-100 dark:border-slate-800 bg-slate-50 dark:bg-slate-800/50 p-2">
              <MiniSparkline buckets={summary?.trendBuckets ?? []} height={48} />
              <div className="flex justify-between text-[10px] text-slate-400 mt-1">
                <span>{reliabilityWindow === 1 ? 'Last hour' : reliabilityWindow === 24 ? 'Last 24 hours' : 'Last 7 days'}</span>
                <span>🟢 ok · 🟡 some failures · 🔴 many failures</span>
              </div>
            </div>
          </div>

          {/* Problem pipelines */}
          {!loading && (problematicPipelines.length > 0 || runningPipelines.length > 0) && (
            <div className="bg-white dark:bg-slate-900 rounded-xl border border-amber-200 dark:border-amber-900/50 p-5">
              <div className="flex items-center justify-between mb-3">
                <h2 className="text-base font-semibold text-slate-800 dark:text-slate-100">
                  {t('dashboard.problemPipelines')}
                </h2>
                <span className={`text-xs px-2 py-0.5 rounded-full font-medium ${rateBg(problematicPipelines.length === 0 ? 1 : 0)}`}>
                  {problematicPipelines.length} failed
                </span>
              </div>
              <ul className="space-y-2">
                {[...problematicPipelines, ...runningPipelines].slice(0, 6).map((p) => (
                  <li key={p.id} className="flex items-center justify-between text-sm">
                    <Link
                      to={`/pipelines/${p.id}`}
                      className="text-primary-600 dark:text-primary-400 hover:underline truncate flex-1 min-w-0"
                    >
                      {p.name}
                    </Link>
                    <span
                      className={`ml-2 shrink-0 px-2 py-0.5 text-xs rounded-full font-medium ${STATUS_BADGE[p.latestRunStatus ?? ''] ?? 'bg-slate-100 text-slate-700'}`}
                    >
                      {p.latestRunStatus}
                    </span>
                  </li>
                ))}
              </ul>
              {problematicPipelines.length > 0 && (
                <Link
                  to="/pipelines?health=PROBLEMATIC"
                  className="btn-secondary block text-center text-sm mt-3"
                >
                  View problem view
                </Link>
              )}
            </div>
          )}

          {/* Quick actions */}
          <div className="bg-white dark:bg-slate-900 rounded-xl border border-slate-200 dark:border-slate-800 p-5">
            <h2 className="text-base font-semibold text-slate-800 dark:text-slate-100 mb-3">
              {t('dashboard.quickActions')}
            </h2>
            <div className="grid grid-cols-2 gap-2">
              {[
                { label: 'New Module', href: '/modules', primary: true },
                { label: 'New Pipeline', href: '/pipelines', primary: false },
                { label: 'Add Connector', href: '/connectors', primary: false },
                { label: 'View Data', href: '/data', primary: false },
                { label: 'Alerts', href: '/alerts', primary: false },
                { label: 'Users', href: '/users', primary: false },
              ].map((item) => (
                <Link
                  key={item.href}
                  to={item.href}
                  className={`text-center text-xs py-2 px-3 rounded-lg border font-medium transition-colors ${
                    item.primary
                      ? 'bg-primary-600 dark:bg-primary-500 text-white border-primary-600 dark:border-primary-500 hover:bg-primary-700 dark:hover:bg-primary-600'
                      : 'bg-white dark:bg-slate-900 text-slate-600 dark:text-slate-300 border-slate-200 dark:border-slate-700 hover:bg-slate-50 dark:hover:bg-slate-800'
                  }`}
                >
                  {item.label}
                </Link>
              ))}
            </div>
          </div>

          {/* Pipeline list (compact) */}
          {!loading && pipelines.length > 0 && (
            <div className="bg-white dark:bg-slate-900 rounded-xl border border-slate-200 dark:border-slate-800 p-5">
              <SectionHeading
                action={
                  <Link to="/pipelines" className="text-xs text-primary-600 dark:text-primary-400 hover:underline">
                    All →
                  </Link>
                }
              >
                Pipelines
              </SectionHeading>
              <ul className="space-y-2">
                {pipelines.slice(0, 7).map((p) => (
                  <li key={p.id} className="flex items-center gap-2 text-sm">
                    <span
                      className={`w-2 h-2 rounded-full shrink-0 ${
                        p.isActive ? 'bg-emerald-400' : 'bg-slate-300 dark:bg-slate-600'
                      }`}
                    />
                    <Link
                      to={`/pipelines/${p.id}`}
                      className="flex-1 truncate text-slate-700 dark:text-slate-200 hover:text-primary-600 dark:hover:text-primary-400 hover:underline"
                    >
                      {p.name}
                    </Link>
                    {p.latestRunStatus && (
                      <span
                        className={`shrink-0 px-1.5 py-0.5 text-[10px] rounded font-medium ${STATUS_BADGE[p.latestRunStatus] ?? ''}`}
                      >
                        {p.latestRunStatus}
                      </span>
                    )}
                  </li>
                ))}
                {pipelines.length > 7 && (
                  <li className="text-xs text-slate-400 pt-1">
                    +{pipelines.length - 7} more
                  </li>
                )}
              </ul>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
