import { useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useAuthStore } from '../stores/auth';
import api from '../services/api';
import type { DataSummaryResponse } from '../types';

/**
 * Defines the type `DataTable`, the contract implemented by this source unit.
 */
type DataTable = {
  rawName: string;
  name: string;
  type: string;
};

/**
 * Defines the type `ColumnDef`, the contract implemented by this source unit.
 */
type ColumnDef = { name: string; colType: string };

const RELATIONAL_COLUMN_TYPES = ['TEXT', 'INTEGER', 'FLOAT', 'BOOLEAN', 'JSONB', 'TIMESTAMPTZ'];

/**
 * Converts a value into the representation required by its caller.
 */
function toCsvValue(value: unknown): string {
  const text = typeof value === 'object' && value !== null ? JSON.stringify(value) : String(value ?? '');
  if (!/[",\n]/.test(text)) {
    return text;
  }
  return `"${text.replace(/"/g, '""')}"`;
}

/**
 * Implements `formatMetric` for this source unit.
 */
function formatMetric(value?: number | null, digits = 2): string {
  if (value === undefined || value === null || Number.isNaN(value)) return '-';
  return value.toLocaleString(undefined, { maximumFractionDigits: digits });
}

/**
 * Implements `DataLineChart` for this source unit.
 */
function DataLineChart({ series }: { series: Array<{ time: string; value: number }> }) {
  const points = useMemo(() => {
    if (series.length === 0) return '';
    const min = Math.min(...series.map((entry) => entry.value));
    const max = Math.max(...series.map((entry) => entry.value));
    const range = max - min || 1;
    return series
      .map((entry, index) => {
        const x = series.length <= 1 ? 0 : (index / (series.length - 1)) * 100;
        const y = 100 - ((entry.value - min) / range) * 100;
        return `${x},${y}`;
      })
      .join(' ');
  }, [series]);

  if (series.length === 0) {
    return <p className="text-sm text-gray-500">No numeric points in current query window.</p>;
  }

  return (
    <div className="rounded-lg border border-slate-200 bg-white p-3">
      <svg viewBox="0 0 100 100" className="h-44 w-full" preserveAspectRatio="none" aria-label="Time series chart">
        <defs>
          <linearGradient id="dataSeriesGradient" x1="0" x2="0" y1="0" y2="1">
            <stop offset="0%" stopColor="#0ea5e9" stopOpacity="0.35" />
            <stop offset="100%" stopColor="#0ea5e9" stopOpacity="0.02" />
          </linearGradient>
        </defs>
        <polyline fill="none" stroke="#0284c7" strokeWidth="2" points={points} />
        <polygon fill="url(#dataSeriesGradient)" points={`0,100 ${points} 100,100`} />
      </svg>
      <div className="mt-2 flex items-center justify-between text-xs text-gray-500">
        <span>{new Date(series[0].time).toLocaleString()}</span>
        <span>{new Date(series[series.length - 1].time).toLocaleString()}</span>
      </div>
    </div>
  );
}

/**
 * Implements `DataBarChart` for this source unit.
 */
function DataBarChart({ bars }: { bars: Array<{ label: string; count: number; avgValue: number }> }) {
  const maxCount = Math.max(...bars.map((entry) => entry.count), 1);

  if (bars.length === 0) {
    return <p className="text-sm text-gray-500">No tag distribution available for current filters.</p>;
  }

  return (
    <div className="space-y-2">
      {bars.map((entry) => (
        <div key={entry.label}>
          <div className="mb-1 flex items-center justify-between text-xs text-gray-600">
            <span className="font-medium">{entry.label}</span>
            <span>
              {entry.count} rows • avg {formatMetric(entry.avgValue)}
            </span>
          </div>
          <div className="h-2 overflow-hidden rounded-sm bg-slate-100">
            <div
              className="h-full rounded-sm bg-linear-to-r from-cyan-500 to-sky-500"
              style={{ width: `${(entry.count / maxCount) * 100}%` }}
            />
          </div>
        </div>
      ))}
    </div>
  );
}

export default function Data() {
  const { t } = useTranslation();
  const { user, selectedTenantId, tenantContexts } = useAuthStore();
  const abilities = user?.abilities || [];
  /**
   * Evaluates the named capability or state predicate.
   */
  const hasAbility = (ability: string) => Boolean(user?.superadmin || abilities.includes('*') || abilities.includes(ability));
  const canReadData = hasAbility('data:read') || hasAbility('data:write');
  const canWriteData = hasAbility('data:write');

  const tenantId = selectedTenantId || user?.tenantId || tenantContexts[0]?.tenantId;

  const [activeTab, setActiveTab] = useState<'tables' | 'grafana'>('tables');
  const [toast, setToast] = useState<{ message: string; type: 'success' | 'error' } | null>(null);
  const [error, setError] = useState('');

  // Tables tab state
  const [tables, setTables] = useState<DataTable[]>([]);
  const [selectedTable, setSelectedTable] = useState('');
  const [data, setData] = useState<unknown[]>([]);
  const [summary, setSummary] = useState<DataSummaryResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [querying, setQuerying] = useState(false);
  const [summaryLoading, setSummaryLoading] = useState(false);
  const [showCreateModal, setShowCreateModal] = useState(false);
  const [newTableName, setNewTableName] = useState('');
  const [newTableType, setNewTableType] = useState<'timeseries' | 'relational'>('timeseries');
  const [relationalColumns, setRelationalColumns] = useState<ColumnDef[]>([{ name: '', colType: 'TEXT' }]);
  const [creatingTable, setCreatingTable] = useState(false);
  const [deletingTable, setDeletingTable] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [rowLimit, setRowLimit] = useState(100);
  const [startTime, setStartTime] = useState('');
  const [endTime, setEndTime] = useState('');
  const [numericField, setNumericField] = useState('value');
  const [tagField, setTagField] = useState('location');
  const [topTags, setTopTags] = useState(8);
  const [sdkCalloutOpen, setSdkCalloutOpen] = useState(false);

  /**
   * Implements `showToast` for this source unit.
   */
  const showToast = (message: string, type: 'success' | 'error') => {
    setToast({ message, type });
    setTimeout(() => setToast(null), 3500);
  };

  /**
   * Retrieves or enumerates the requested state.
   */
  const getShortTableName = (rawName: string) => rawName;

  /**
   * Implements `fetchTables` for this source unit.
   */
  const fetchTables = async (tenant: string) => {
    const result = await api.listTables(tenant);
    const nextTables = result.tables.map((table) => ({
      rawName: table.name,
      name: getShortTableName(table.name),
      type: table.type,
    }));
    setTables(nextTables);

    if (nextTables.length === 0) {
      setSelectedTable('');
      setData([]);
      setSummary(null);
      return;
    }

    if (!nextTables.some((table) => table.name === selectedTable)) {
      setSelectedTable(nextTables[0].name);
    }
  };

  /**
   * Implements `fetchSummary` for this source unit.
   */
  const fetchSummary = async (tenant: string, table: string) => {
    setSummaryLoading(true);
    try {
      const params: Record<string, unknown> = { limit: rowLimit, topTags, numericField, tagField };
      if (startTime) params.start = new Date(startTime).toISOString();
      if (endTime) params.end = new Date(endTime).toISOString();
      const result = await api.getDataSummary(tenant, table, params);
      setSummary(result);
    } catch (summaryError) {
      console.error('Error loading summary:', summaryError);
      setSummary(null);
    } finally {
      setSummaryLoading(false);
    }
  };

  /**
   * Implements `useEffect` for this source unit.
   */
  useEffect(() => {
    /**
     * Retrieves or enumerates the requested state.
     */
    const load = async () => {
      if (!tenantId) { setLoading(false); return; }
      if (!canReadData) {
        setTables([]); setSelectedTable(''); setData([]); setSummary(null);
        setError(''); setLoading(false);
        return;
      }
      try {
        await fetchTables(tenantId);
        setError('');
      } catch (loadError) {
        console.error('Error fetching tables:', loadError);
        setError(t('data.failedLoadTables'));
      } finally {
        setLoading(false);
      }
    };
    load();
  }, [tenantId, canReadData, t]);

  const selectedTableInfo = tables.find((t) => t.name === selectedTable);
  const isTimeSeries = !selectedTableInfo || selectedTableInfo.type === 'timeseries';

  /**
   * Processes the named request, event, or operation.
   */
  const handleQuery = async () => {
    if (!canReadData) { showToast(t('data.permissionQuery'), 'error'); return; }
    if (!tenantId || !selectedTable) return;
    setQuerying(true);
    try {
      const params: Record<string, unknown> = { limit: rowLimit };
      if (startTime) params.start = new Date(startTime).toISOString();
      if (endTime) params.end = new Date(endTime).toISOString();
      const result = await api.queryData(tenantId, selectedTable, params);
      setData(result.data || []);
      if (isTimeSeries) {
        await fetchSummary(tenantId, selectedTable);
      } else {
        setSummary(null);
      }
      setError('');
    } catch (queryError) {
      console.error('Error querying data:', queryError);
      setError(t('data.failedQueryData'));
    } finally {
      setQuerying(false);
    }
  };

  /**
   * Processes the named request, event, or operation.
   */
  const handleCreateTable = async () => {
    if (!canWriteData) { showToast(t('data.permissionCreateTable'), 'error'); return; }
    if (!tenantId || !newTableName.trim()) return;
    if (!/^[a-zA-Z0-9_]+$/.test(newTableName.trim())) {
      setError('Table name must contain only letters, numbers, and underscores.');
      return;
    }

    setCreatingTable(true);
    try {
      if (newTableType === 'relational') {
        const validCols = relationalColumns
          .map((column) => ({ name: column.name.trim(), type: column.colType }))
          .filter((column) => column.name);
        if (validCols.length === 0) {
          setError('Add at least one column for a relational table.');
          return;
        }
        await api.registerTable(tenantId, {
          tableName: newTableName.trim(),
          tableType: 'relational',
          columns: validCols,
        });
      } else {
        await api.registerTable(tenantId, {
          tableName: newTableName.trim(),
          tableType: 'timeseries',
        });
      }
      await fetchTables(tenantId);
      setSelectedTable(newTableName.trim());
      setShowCreateModal(false);
      setNewTableName('');
      setNewTableType('timeseries');
      setRelationalColumns([{ name: '', colType: 'TEXT' }]);
      setError('');
      showToast(t('data.tableCreated'), 'success');
    } catch (createError) {
      console.error('Error creating table:', createError);
      setError(t('data.failedCreateTable'));
    } finally {
      setCreatingTable(false);
    }
  };

  /**
   * Processes the named request, event, or operation.
   */
  const handleDeleteTable = async () => {
    if (!canWriteData) { showToast(t('data.permissionDeleteTable'), 'error'); return; }
    if (!tenantId || !selectedTable) return;
    setDeletingTable(true);
    try {
      await api.deleteTable(tenantId, selectedTable);
      await fetchTables(tenantId);
      setConfirmDelete(false);
      setSummary(null);
      setData([]);
      setError('');
      showToast(t('data.tableDeleted'), 'success');
    } catch (deleteError) {
      console.error('Error deleting table:', deleteError);
      setError(t('data.failedDeleteTable'));
    } finally {
      setDeletingTable(false);
    }
  };

  /**
   * Implements `exportJson` for this source unit.
   */
  const exportJson = () => {
    const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = `${selectedTable || 'table'}-export.json`;
    anchor.click();
    URL.revokeObjectURL(url);
  };

  /**
   * Implements `exportCsv` for this source unit.
   */
  const exportCsv = () => {
    if (data.length === 0) return;
    const rows = data as Record<string, unknown>[];
    const headers = Array.from(new Set(rows.flatMap((row) => Object.keys(row))));
    const csvBody = [
      headers.join(','),
      ...rows.map((row) => headers.map((header) => toCsvValue(row[header])).join(',')),
    ].join('\n');
    const csvBlob = new Blob([csvBody], { type: 'text/csv;charset=utf-8' });
    const url = URL.createObjectURL(csvBlob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = `${selectedTable || 'table'}-export.csv`;
    anchor.click();
    URL.revokeObjectURL(url);
  };

  if (loading) {
    return (
      <div className="px-4 py-6">
        <div className="card animate-pulse">
          <div className="mb-4 h-4 w-1/3 rounded-sm bg-gray-200" />
          <div className="h-32 rounded-sm bg-gray-200" />
        </div>
      </div>
    );
  }

  if (!tenantId) {
    return (
      <div className="px-4 py-6">
        <div className="card py-12 text-center">
          <p className="text-gray-500">{t('common.noTenantId')}</p>
        </div>
      </div>
    );
  }

  if (!canReadData) {
    return (
      <div className="px-4 py-6">
        <div className="card py-12 text-center">
          <p className="text-gray-500">{t('data.permissionViewData')}</p>
        </div>
      </div>
    );
  }

  const grafanaUrl = (window as { GRAFANA_URL?: string }).GRAFANA_URL || '/dashboard';
  const tableSchema = `tenant_${tenantId.replace(/-/g, '_')}`;

  return (
    <div className="px-4 py-6 sm:px-0">
      {toast && (
        <div className={`fixed right-4 top-4 z-50 rounded-sm px-4 py-3 text-sm text-white shadow-lg ${toast.type === 'success' ? 'bg-green-600' : 'bg-red-600'}`}>
          {toast.message}
        </div>
      )}

      <div className="mb-6 flex items-center justify-between">
        <h1 className="text-2xl font-bold text-slate-900">{t('data.title')}</h1>
      </div>

      {/* Tab switcher */}
      <div className="mb-6 flex space-x-2">
        {(['tables', 'grafana'] as const).map((tab) => (
          <button
            key={tab}
            onClick={() => setActiveTab(tab)}
            className={`px-4 py-2 rounded-lg text-sm font-medium capitalize ${
              activeTab === tab ? 'bg-primary-600 text-white' : 'bg-gray-100 text-gray-700 hover:bg-gray-200'
            }`}
          >
            {tab.charAt(0).toUpperCase() + tab.slice(1)}
          </button>
        ))}
      </div>

      {error && <div className="mb-4 rounded-sm border border-red-200 bg-red-50 px-4 py-3 text-red-700">{error}</div>}

      {/* Tables tab */}
      {activeTab === 'tables' && (
        <div>
          {/* SDK snippet callout */}
          <div className="mb-4 rounded-sm border border-slate-200 bg-slate-50">
            <button
              onClick={() => setSdkCalloutOpen(!sdkCalloutOpen)}
              className="w-full flex items-center justify-between px-4 py-3 text-sm font-medium text-slate-700 hover:bg-slate-100"
            >
              <span>Python SDK — reading and writing data from modules</span>
              <span>{sdkCalloutOpen ? '▲' : '▼'}</span>
            </button>
            {sdkCalloutOpen && (
              <div className="px-4 pb-4">
                <pre className="rounded-sm bg-slate-900 text-slate-100 text-xs font-mono px-4 py-3 overflow-x-auto whitespace-pre">{`# In your module code, use the dt helpers:

# Write to a time-series table:
dt.write_table('my_table', [
    {'time': '2024-01-01T00:00:00Z', 'device_id': 'sensor-1', 'value': 42.0, 'tags': {'location': 'A'}}
])

# Read happens via INPUT_DATA from upstream pipeline nodes:
rows = dt.input_data  # list of dicts from the upstream node`}</pre>
              </div>
            )}
          </div>

          <div className="mb-4 flex items-center justify-between flex-wrap gap-2">
            <div className="flex items-center gap-2">
              <select
                value={selectedTable}
                onChange={(e) => { setSelectedTable(e.target.value); setData([]); setSummary(null); }}
                className="input"
              >
                {tables.length === 0 && <option value="">{t('data.noTables')}</option>}
                {tables.map((table) => (
                  <option key={table.rawName} value={table.name}>
                    {table.name} ({table.type})
                  </option>
                ))}
              </select>
            </div>
            <div className="flex gap-2 flex-wrap">
              {canWriteData && (
                <button onClick={() => setShowCreateModal(true)} className="btn-primary text-sm">
                  {t('data.createTable')}
                </button>
              )}
              <button
                onClick={() => setConfirmDelete(true)}
                disabled={!canWriteData || !selectedTable || deletingTable}
                className="btn-secondary text-sm border-red-300 text-red-700 hover:bg-red-50"
              >
                {deletingTable ? t('common.deleting') : t('data.deleteTable')}
              </button>
            </div>
          </div>

          {selectedTable && (
            <div className="card mb-4">
              <div className="flex items-center justify-between mb-3">
                <h2 className="text-sm font-semibold text-slate-800">
                  Query: <span className="font-mono">{selectedTable}</span>
                  <span className="ml-2 px-2 py-0.5 text-xs rounded-sm bg-slate-100 text-slate-600">
                    {selectedTableInfo?.type || 'timeseries'}
                  </span>
                </h2>
              </div>

              <div className="grid grid-cols-1 gap-3 md:grid-cols-3 xl:grid-cols-4 mb-3">
                <div>
                  <label className="mb-1 block text-xs font-medium text-gray-600">{t('data.start')}</label>
                  <input type="datetime-local" value={startTime} onChange={(e) => setStartTime(e.target.value)} className="input text-sm" />
                </div>
                <div>
                  <label className="mb-1 block text-xs font-medium text-gray-600">{t('data.end')}</label>
                  <input type="datetime-local" value={endTime} onChange={(e) => setEndTime(e.target.value)} className="input text-sm" />
                </div>
                <div>
                  <label className="mb-1 block text-xs font-medium text-gray-600">{t('common.rows')}</label>
                  <input
                    type="number" min={1} max={1000} value={rowLimit}
                    onChange={(e) => setRowLimit(Number(e.target.value) || 100)}
                    className="input text-sm"
                  />
                </div>
                {isTimeSeries ? (
                  <>
                    <div>
                      <label className="mb-1 block text-xs font-medium text-gray-600">Metric field</label>
                      <input type="text" value={numericField} onChange={(e) => setNumericField(e.target.value)} className="input text-sm" placeholder="value" />
                    </div>
                    <div>
                      <label className="mb-1 block text-xs font-medium text-gray-600">Tag field</label>
                      <input type="text" value={tagField} onChange={(e) => setTagField(e.target.value)} className="input text-sm" placeholder="location" />
                    </div>
                    <div>
                      <label className="mb-1 block text-xs font-medium text-gray-600">Top tags</label>
                      <input type="number" min={1} max={25} value={topTags} onChange={(e) => setTopTags(Number(e.target.value) || 8)} className="input text-sm" />
                    </div>
                  </>
                ) : (
                  <div className="md:col-span-2">
                    <label className="mb-1 block text-xs font-medium text-gray-600">Relational querying</label>
                    <div className="rounded-sm border border-slate-200 bg-slate-50 px-3 py-2 text-xs text-slate-600">
                      Ad hoc SQL filters are intentionally disabled here. Use Grafana or a DB_LOAD module for scoped relational querying.
                    </div>
                  </div>
                )}
              </div>

              <div className="flex flex-wrap gap-2">
                <button onClick={handleQuery} disabled={querying || !selectedTable} className="btn-primary text-sm">
                  {querying ? t('common.refreshing') : t('common.search')}
                </button>
                <button onClick={exportCsv} disabled={data.length === 0} className="btn-secondary text-sm">
                  Export CSV
                </button>
                <button onClick={exportJson} disabled={data.length === 0} className="btn-secondary text-sm">
                  Export JSON
                </button>
              </div>
            </div>
          )}

          {/* Summary charts for time series */}
          {isTimeSeries && summary && (
            <div className="mb-6 space-y-4">
              <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
                {[
                  { label: 'Rows fetched', value: summary.summary.rowCount },
                  { label: 'Avg value', value: formatMetric(summary.summary.avg) },
                  { label: 'Min / Max', value: `${formatMetric(summary.summary.min)} / ${formatMetric(summary.summary.max)}` },
                  { label: 'Latest value', value: formatMetric(summary.summary.latestValue) },
                ].map((stat) => (
                  <div key={stat.label} className="rounded-xl border border-slate-200 bg-white p-3">
                    <div className="text-xs uppercase tracking-wide text-gray-500">{stat.label}</div>
                    <div className="mt-1 text-xl font-semibold text-slate-900">{stat.value}</div>
                  </div>
                ))}
              </div>
              <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
                <div className="card">
                  <div className="mb-3 flex items-center justify-between">
                    <h2 className="text-sm font-semibold text-slate-800">Time trend</h2>
                    <span className="text-xs text-gray-500">{summary.applied.numericField}</span>
                  </div>
                  <DataLineChart series={summary.series} />
                </div>
                <div className="card">
                  <div className="mb-3 flex items-center justify-between">
                    <h2 className="text-sm font-semibold text-slate-800">Tag distribution</h2>
                    <span className="text-xs text-gray-500">{summary.applied.tagField}</span>
                  </div>
                  <DataBarChart bars={summary.bars} />
                </div>
              </div>
            </div>
          )}

          {summaryLoading && (
            <div className="mb-4 rounded-sm border border-sky-200 bg-sky-50 px-4 py-2 text-sm text-sky-700">
              Updating summary charts…
            </div>
          )}

          {/* Data table */}
          {tables.length === 0 ? (
            <div className="card py-12 text-center">
              <p className="mb-4 text-gray-500">{t('data.noTablesYet')}</p>
              {canWriteData ? (
                <button onClick={() => setShowCreateModal(true)} className="btn-primary">{t('data.createFirstTable')}</button>
              ) : (
                <p className="text-sm text-gray-500">{t('data.needDataWriteAccess')}</p>
              )}
            </div>
          ) : data.length > 0 ? (
            <div className="card overflow-hidden">
              <div className="overflow-x-auto">
                <div className="border-b border-gray-200 px-6 py-3 text-sm text-gray-600">
                  {t('data.showingRows', { count: Math.min(data.length, rowLimit) })}
                </div>
                <table className="min-w-full divide-y divide-gray-200">
                  <thead className="bg-gray-50">
                    <tr>
                      {Object.keys(data[0] as object).map((key) => (
                        <th key={key} className="px-6 py-3 text-left text-xs font-medium uppercase tracking-wider text-gray-500">
                          {key}
                        </th>
                      ))}
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-gray-200 bg-white">
                    {data.slice(0, 100).map((row, index) => (
                      <tr key={index}>
                        {Object.values(row as object).map((value, colIndex) => (
                          <td key={colIndex} className="whitespace-nowrap px-6 py-4 text-sm text-gray-500">
                            {typeof value === 'object' ? JSON.stringify(value) : String(value)}
                          </td>
                        ))}
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          ) : selectedTable ? (
            <div className="card py-12 text-center">
              <p className="text-gray-500">{t('data.noDataInTable')}</p>
            </div>
          ) : null}

          {/* Create table modal */}
          {showCreateModal && canWriteData && (
            <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50">
              <div className="w-full max-w-lg rounded-lg bg-white p-6 max-h-[90vh] overflow-y-auto">
                <h2 className="mb-4 text-xl font-bold">{t('data.createTable')}</h2>
                <div className="mb-4">
                  <label className="mb-1 block text-sm font-medium text-gray-700">{t('data.tableName')}</label>
                  <input
                    type="text"
                    value={newTableName}
                    onChange={(e) => setNewTableName(e.target.value)}
                    className="input w-full"
                    placeholder="my_table_name"
                  />
                  <p className="mt-1 text-xs text-gray-400">Letters, numbers, and underscores only.</p>
                </div>
                <div className="mb-4">
                  <label className="mb-1 block text-sm font-medium text-gray-700">Table Type</label>
                  <div className="flex gap-3">
                    <label className="flex items-center gap-2 cursor-pointer">
                      <input type="radio" name="tableType" value="timeseries" checked={newTableType === 'timeseries'} onChange={() => setNewTableType('timeseries')} />
                      <span className="text-sm">Time Series</span>
                    </label>
                    <label className="flex items-center gap-2 cursor-pointer">
                      <input type="radio" name="tableType" value="relational" checked={newTableType === 'relational'} onChange={() => setNewTableType('relational')} />
                      <span className="text-sm">Relational</span>
                    </label>
                  </div>
                </div>

                {newTableType === 'timeseries' ? (
                  <div className="mb-4 rounded-sm border border-slate-200 bg-slate-50 px-3 py-2 text-sm text-slate-700">
                    <p className="font-medium mb-1">Fixed schema:</p>
                    <p className="font-mono text-xs">time (TIMESTAMPTZ), device_id (TEXT), value (FLOAT), tags (JSONB)</p>
                    <p className="mt-2 text-xs text-slate-500">Time-series tables always use this schema. Data is partitioned by time.</p>
                  </div>
                ) : (
                  <div className="mb-4">
                    <p className="text-sm font-medium text-gray-700 mb-2">Columns</p>
                    <div className="space-y-2">
                      {relationalColumns.map((col, idx) => (
                        <div key={idx} className="flex items-center gap-2">
                          <input
                            type="text"
                            value={col.name}
                            onChange={(e) => {
                              const next = [...relationalColumns];
                              next[idx] = { ...next[idx], name: e.target.value };
                              setRelationalColumns(next);
                            }}
                            className="input text-sm flex-1"
                            placeholder="column_name"
                          />
                          <select
                            value={col.colType}
                            onChange={(e) => {
                              const next = [...relationalColumns];
                              next[idx] = { ...next[idx], colType: e.target.value };
                              setRelationalColumns(next);
                            }}
                            className="input text-sm w-36"
                          >
                            {RELATIONAL_COLUMN_TYPES.map((ct) => (
                              <option key={ct} value={ct}>{ct}</option>
                            ))}
                          </select>
                          <button
                            type="button"
                            onClick={() => setRelationalColumns((prev) => prev.filter((_, i) => i !== idx))}
                            className="text-red-500 hover:text-red-700 text-xs"
                            disabled={relationalColumns.length <= 1}
                          >
                            ✕
                          </button>
                        </div>
                      ))}
                    </div>
                    <button
                      type="button"
                      onClick={() => setRelationalColumns((prev) => [...prev, { name: '', colType: 'TEXT' }])}
                      className="mt-2 text-sm text-blue-600 hover:text-blue-800"
                    >
                      + Add column
                    </button>
                  </div>
                )}

                <div className="flex justify-end space-x-2">
                  <button onClick={() => { setShowCreateModal(false); setNewTableName(''); setNewTableType('timeseries'); setRelationalColumns([{ name: '', colType: 'TEXT' }]); setError(''); }}
                    className="rounded-md border border-gray-300 px-4 py-2 text-gray-700 hover:bg-gray-50">
                    {t('common.cancel')}
                  </button>
                  <button onClick={handleCreateTable} disabled={creatingTable || !newTableName.trim()} className="btn-primary">
                    {creatingTable ? t('common.creating') : t('common.create')}
                  </button>
                </div>
              </div>
            </div>
          )}

          {/* Delete table confirm */}
          {confirmDelete && canWriteData && selectedTable && (
            <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50">
              <div className="w-full max-w-md rounded-lg bg-white p-6 max-h-[90vh] overflow-y-auto">
                <h2 className="mb-2 text-xl font-bold">{t('data.deleteTable')}</h2>
                <p className="mb-4 text-sm text-gray-600">{t('data.deleteTableConfirm', { name: selectedTable })}</p>
                <div className="flex justify-end space-x-2">
                  <button onClick={() => setConfirmDelete(false)} className="rounded-md border border-gray-300 px-4 py-2 text-gray-700 hover:bg-gray-50">
                    {t('common.cancel')}
                  </button>
                  <button onClick={handleDeleteTable} disabled={deletingTable} className="rounded-md bg-red-600 px-4 py-2 text-white hover:bg-red-700 disabled:opacity-50">
                    {deletingTable ? t('common.deleting') : t('common.delete')}
                  </button>
                </div>
              </div>
            </div>
          )}
        </div>
      )}

      {/* Grafana tab */}
      {activeTab === 'grafana' && (
        <div className="space-y-4">
          <div className="rounded-sm border border-blue-200 bg-blue-50 px-4 py-4">
            <h2 className="text-base font-semibold text-blue-900 mb-2">Grafana Data Explorer</h2>
            <p className="text-sm text-blue-800 mb-3">
              Use the PostgreSQL datasource in Grafana to query your data tables directly. Tenant data lives in a dedicated schema and tables are addressed as:
            </p>
            <div className="rounded-sm bg-white border border-blue-200 px-3 py-2 flex items-center justify-between gap-2 mb-3">
              <code className="text-sm font-mono text-slate-800">{tableSchema}.{'<table_name>'}</code>
              <button
                onClick={() => navigator.clipboard.writeText(tableSchema).then(() => showToast('Copied schema', 'success'))}
                className="text-xs text-blue-700 hover:text-blue-900"
              >
                Copy schema
              </button>
            </div>
            <p className="text-xs text-blue-700">
              Example: a table named <strong>sensor_readings</strong> is accessible as <code className="font-mono">{tableSchema}.sensor_readings</code>.
            </p>
          </div>

          <div className="rounded-sm border border-slate-200 bg-slate-50 px-4 py-3">
            <p className="text-sm text-slate-700 mb-2">
              Grafana is available at: <a href={grafanaUrl} target="_blank" rel="noopener noreferrer" className="text-blue-600 hover:underline font-mono">{grafanaUrl}</a>
            </p>
            <p className="text-xs text-slate-500">
              If Grafana requires authentication, use the credentials provided by your platform administrator.
            </p>
          </div>

          <div className="rounded-sm border border-slate-200 overflow-hidden" style={{ height: 600 }}>
            <iframe
              src={grafanaUrl}
              className="w-full h-full"
              title="Grafana"
              sandbox="allow-scripts allow-same-origin allow-forms allow-popups"
            />
          </div>
        </div>
      )}
    </div>
  );
}
