import { useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useAuthStore } from '../stores/auth';
import api from '../services/api';
import type { DataSummaryResponse } from '../types';

type DataTable = {
  rawName: string;
  name: string;
  type: string;
};

function toCsvValue(value: unknown): string {
  const text = typeof value === 'object' && value !== null ? JSON.stringify(value) : String(value ?? '');
  if (!/[",\n]/.test(text)) {
    return text;
  }
  return `"${text.replace(/"/g, '""')}"`;
}

function formatMetric(value?: number | null, digits = 2): string {
  if (value === undefined || value === null || Number.isNaN(value)) return '-';
  return value.toLocaleString(undefined, { maximumFractionDigits: digits });
}

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
          <div className="h-2 overflow-hidden rounded bg-slate-100">
            <div
              className="h-full rounded bg-gradient-to-r from-cyan-500 to-sky-500"
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
  const hasAbility = (ability: string) => Boolean(user?.superadmin || abilities.includes('*') || abilities.includes(ability));
  const canReadData = hasAbility('data:read') || hasAbility('data:write');
  const canWriteData = hasAbility('data:write');

  const [tables, setTables] = useState<DataTable[]>([]);
  const [selectedTable, setSelectedTable] = useState('');
  const [data, setData] = useState<unknown[]>([]);
  const [summary, setSummary] = useState<DataSummaryResponse | null>(null);

  const [loading, setLoading] = useState(true);
  const [querying, setQuerying] = useState(false);
  const [summaryLoading, setSummaryLoading] = useState(false);

  const [showCreateModal, setShowCreateModal] = useState(false);
  const [newTableName, setNewTableName] = useState('');
  const [insertingData, setInsertingData] = useState(false);
  const [deletingTable, setDeletingTable] = useState(false);

  const [rowLimit, setRowLimit] = useState(100);
  const [topTags, setTopTags] = useState(8);
  const [startTime, setStartTime] = useState('');
  const [endTime, setEndTime] = useState('');
  const [numericField, setNumericField] = useState('value');
  const [tagField, setTagField] = useState('location');

  const [error, setError] = useState('');
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [toast, setToast] = useState<{ message: string; type: 'success' | 'error' } | null>(null);

  const tenantId = selectedTenantId || user?.tenantId || tenantContexts[0]?.tenantId;

  const showToast = (message: string, type: 'success' | 'error') => {
    setToast({ message, type });
    setTimeout(() => setToast(null), 3500);
  };

  const getShortTableName = (rawName: string, tenant: string) => {
    const tenantPrefix = `tenant_${tenant.replace(/-/g, '_')}_`;
    return rawName.startsWith(tenantPrefix) ? rawName.slice(tenantPrefix.length) : rawName;
  };

  const fetchTables = async (tenant: string) => {
    const result = await api.listTables(tenant);
    const nextTables = result.tables.map((table) => ({
      rawName: table.name,
      name: getShortTableName(table.name, tenant),
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

  const fetchSummary = async (tenant: string, table: string) => {
    setSummaryLoading(true);
    try {
      const params: Record<string, unknown> = {
        limit: rowLimit,
        topTags,
        numericField,
        tagField,
      };
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

  useEffect(() => {
    const load = async () => {
      if (!tenantId) {
        setLoading(false);
        return;
      }
      if (!canReadData) {
        setTables([]);
        setSelectedTable('');
        setData([]);
        setSummary(null);
        setError('');
        setLoading(false);
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

  const handleQuery = async () => {
    if (!canReadData) {
      showToast(t('data.permissionQuery'), 'error');
      return;
    }
    if (!tenantId || !selectedTable) return;

    setQuerying(true);
    try {
      const params: Record<string, unknown> = { limit: rowLimit };
      if (startTime) params.start = new Date(startTime).toISOString();
      if (endTime) params.end = new Date(endTime).toISOString();

      const result = await api.queryData(tenantId, selectedTable, params);
      setData(result.data || []);
      await fetchSummary(tenantId, selectedTable);
      setError('');
    } catch (queryError) {
      console.error('Error querying data:', queryError);
      setError(t('data.failedQueryData'));
    } finally {
      setQuerying(false);
    }
  };

  const handleCreateTable = async () => {
    if (!canWriteData) {
      showToast(t('data.permissionCreateTable'), 'error');
      return;
    }
    if (!tenantId || !newTableName) return;

    setInsertingData(true);
    try {
      await api.insertData(tenantId, newTableName, [
        {
          time: new Date().toISOString(),
          device_id: 'sample-device-001',
          value: 25.5,
          tags: { location: 'warehouse' },
        },
      ]);

      await fetchTables(tenantId);
      setSelectedTable(newTableName);
      setShowCreateModal(false);
      setNewTableName('');
      setError('');
      showToast(t('data.tableCreated'), 'success');
    } catch (createError) {
      console.error('Error creating table:', createError);
      setError(t('data.failedCreateTable'));
    } finally {
      setInsertingData(false);
    }
  };

  const handleDeleteTable = async () => {
    if (!canWriteData) {
      showToast(t('data.permissionDeleteTable'), 'error');
      return;
    }
    if (!tenantId || !selectedTable) return;

    setDeletingTable(true);
    try {
      await api.deleteTable(tenantId, selectedTable);
      await fetchTables(tenantId);
      setConfirmDelete(false);
      setSummary(null);
      setError('');
      showToast(t('data.tableDeleted'), 'success');
    } catch (deleteError) {
      console.error('Error deleting table:', deleteError);
      setError(t('data.failedDeleteTable'));
    } finally {
      setDeletingTable(false);
    }
  };

  const handleRefreshTables = async () => {
    if (!canReadData) {
      showToast(t('data.permissionRefreshTables'), 'error');
      return;
    }
    if (!tenantId) return;
    setLoading(true);
    try {
      await fetchTables(tenantId);
      setError('');
    } catch (refreshError) {
      console.error('Error refreshing tables:', refreshError);
      setError(t('data.failedRefreshTables'));
    } finally {
      setLoading(false);
    }
  };

  const exportJson = () => {
    const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = `${selectedTable || 'table'}-export.json`;
    anchor.click();
    URL.revokeObjectURL(url);
  };

  const exportCsv = () => {
    if (data.length === 0) return;
    const rows = data as Record<string, unknown>[];
    const headers = Array.from(new Set(rows.flatMap((row) => Object.keys(row))));
    const csvBody = [
      headers.join(','),
      ...rows.map((row) => headers.map((header) => toCsvValue(row[header])).join(',')),
    ].join('\n');

    const blob = new Blob([csvBody], { type: 'text/csv;charset=utf-8' });
    const url = URL.createObjectURL(blob);
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
          <div className="mb-4 h-4 w-1/3 rounded bg-gray-200" />
          <div className="h-32 rounded bg-gray-200" />
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

  return (
    <div className="px-4 py-6 sm:px-0">
      {toast && (
        <div className={`fixed right-4 top-4 z-50 rounded px-4 py-3 text-sm text-white shadow-lg ${toast.type === 'success' ? 'bg-green-600' : 'bg-red-600'}`}>
          {toast.message}
        </div>
      )}

      <div className="mb-6 rounded-2xl border border-slate-200 bg-gradient-to-r from-slate-50 via-cyan-50 to-sky-50 p-5">
        <div className="flex flex-col gap-3 md:flex-row md:items-center md:justify-between">
          <div>
            <h1 className="text-2xl font-bold text-slate-900">{t('data.title')}</h1>
            <p className="mt-1 text-sm text-slate-600">Quick query, trend check, and export tools for tenant datasets.</p>
          </div>
          <div className="flex flex-wrap gap-2">
            <button onClick={handleRefreshTables} className="btn-secondary">
              {t('common.refresh')}
            </button>
            {canWriteData && (
              <button onClick={() => setShowCreateModal(true)} className="btn-primary">
                {t('data.createTable')}
              </button>
            )}
          </div>
        </div>
      </div>

      {error && <div className="mb-4 rounded border border-red-200 bg-red-50 px-4 py-3 text-red-700">{error}</div>}

      <div className="card mb-6">
        <div className="grid grid-cols-1 items-end gap-4 md:grid-cols-2 xl:grid-cols-5">
          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700">{t('data.table')}</label>
            <select value={selectedTable} onChange={(event) => setSelectedTable(event.target.value)} className="input">
              {tables.length === 0 && <option value="">{t('data.noTables')}</option>}
              {tables.map((table) => (
                <option key={table.rawName} value={table.name}>
                  {table.name} ({table.type})
                </option>
              ))}
            </select>
          </div>

          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700">{t('data.start')}</label>
            <input type="datetime-local" value={startTime} onChange={(event) => setStartTime(event.target.value)} className="input" />
          </div>

          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700">{t('data.end')}</label>
            <input type="datetime-local" value={endTime} onChange={(event) => setEndTime(event.target.value)} className="input" />
          </div>

          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700">{t('common.rows')}</label>
            <input
              type="number"
              min={1}
              max={1000}
              value={rowLimit}
              onChange={(event) => setRowLimit(Number(event.target.value) || 100)}
              className="input"
            />
          </div>

          <div className="flex flex-wrap gap-2">
            <button onClick={handleQuery} disabled={querying || !selectedTable} className="btn-primary">
              {querying ? t('common.refreshing') : t('common.search')}
            </button>
            <button
              onClick={() => setConfirmDelete(true)}
              disabled={!canWriteData || !selectedTable || deletingTable}
              className="btn-secondary border-red-300 text-red-700 hover:bg-red-50"
              title={!canWriteData ? t('data.needDataWritePermission') : undefined}
            >
              {deletingTable ? t('common.deleting') : t('data.deleteTable')}
            </button>
          </div>
        </div>

        <div className="mt-4 grid grid-cols-1 gap-4 border-t border-slate-100 pt-4 md:grid-cols-3 xl:grid-cols-5">
          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700">{t('data.metricField', { defaultValue: 'Metric field' })}</label>
            <input
              type="text"
              value={numericField}
              onChange={(event) => setNumericField(event.target.value)}
              className="input"
              placeholder={t('data.metricFieldPlaceholder', { defaultValue: 'value' })}
            />
          </div>
          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700">{t('data.tagField', { defaultValue: 'Tag field' })}</label>
            <input
              type="text"
              value={tagField}
              onChange={(event) => setTagField(event.target.value)}
              className="input"
              placeholder={t('data.tagFieldPlaceholder', { defaultValue: 'location' })}
            />
          </div>
          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700">{t('data.topTags', { defaultValue: 'Top tags' })}</label>
            <input
              type="number"
              min={1}
              max={25}
              value={topTags}
              onChange={(event) => setTopTags(Number(event.target.value) || 8)}
              className="input"
            />
          </div>
          <div className="flex items-end gap-2 md:col-span-2 xl:col-span-2">
            <button onClick={exportCsv} disabled={data.length === 0} className="btn-secondary">
              {t('data.exportCsv', { defaultValue: 'Export CSV' })}
            </button>
            <button onClick={exportJson} disabled={data.length === 0} className="btn-secondary">
              {t('data.exportJson', { defaultValue: 'Export JSON' })}
            </button>
          </div>
        </div>
      </div>

      {summary && (
        <div className="mb-6 space-y-4">
          <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
            <div className="rounded-xl border border-slate-200 bg-white p-3">
              <div className="text-xs uppercase tracking-wide text-gray-500">Rows fetched</div>
              <div className="mt-1 text-xl font-semibold text-slate-900">{summary.summary.rowCount}</div>
            </div>
            <div className="rounded-xl border border-slate-200 bg-white p-3">
              <div className="text-xs uppercase tracking-wide text-gray-500">Avg value</div>
              <div className="mt-1 text-xl font-semibold text-slate-900">{formatMetric(summary.summary.avg)}</div>
            </div>
            <div className="rounded-xl border border-slate-200 bg-white p-3">
              <div className="text-xs uppercase tracking-wide text-gray-500">Min / Max</div>
              <div className="mt-1 text-xl font-semibold text-slate-900">
                {formatMetric(summary.summary.min)} / {formatMetric(summary.summary.max)}
              </div>
            </div>
            <div className="rounded-xl border border-slate-200 bg-white p-3">
              <div className="text-xs uppercase tracking-wide text-gray-500">Latest value</div>
              <div className="mt-1 text-xl font-semibold text-slate-900">{formatMetric(summary.summary.latestValue)}</div>
            </div>
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
        <div className="mb-4 rounded border border-sky-200 bg-sky-50 px-4 py-2 text-sm text-sky-700">
          {t('data.loadingSummary', { defaultValue: 'Updating summary charts...' })}
        </div>
      )}

      {tables.length === 0 ? (
        <div className="card py-12 text-center">
          <p className="mb-4 text-gray-500">{t('data.noTablesYet')}</p>
          {canWriteData ? (
            <button onClick={() => setShowCreateModal(true)} className="btn-primary">
              {t('data.createFirstTable')}
            </button>
          ) : (
            <p className="text-sm text-gray-500">{t('data.needDataWriteAccess')}</p>
          )}
        </div>
      ) : (
        <div className="card overflow-hidden">
          {data.length > 0 ? (
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
          ) : (
            <div className="py-12 text-center">
              <p className="text-gray-500">{t('data.noDataInTable')}</p>
            </div>
          )}
        </div>
      )}

      {showCreateModal && canWriteData && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black bg-opacity-50">
          <div className="w-full max-w-md rounded-lg bg-white p-6">
            <h2 className="mb-4 text-xl font-bold">{t('data.createTable')}</h2>
            <div className="mb-4">
              <label className="mb-1 block text-sm font-medium text-gray-700">{t('data.tableName')}</label>
              <input
                type="text"
                value={newTableName}
                onChange={(event) => setNewTableName(event.target.value)}
                className="input w-full"
                placeholder={t('data.tableNamePlaceholder')}
              />
            </div>
            <p className="mb-4 text-sm text-gray-500">{t('data.createTableDescription')}</p>
            <div className="flex justify-end space-x-2">
              <button onClick={() => setShowCreateModal(false)} className="rounded-md border border-gray-300 px-4 py-2 text-gray-700 hover:bg-gray-50">
                {t('common.cancel')}
              </button>
              <button onClick={handleCreateTable} disabled={insertingData || !newTableName} className="btn-primary">
                {insertingData ? t('common.creating') : t('common.create')}
              </button>
            </div>
          </div>
        </div>
      )}

      {confirmDelete && canWriteData && selectedTable && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black bg-opacity-50">
          <div className="w-full max-w-md rounded-lg bg-white p-6">
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
  );
}
