import { useEffect, useState } from 'react';
import { useAuthStore } from '../stores/auth';
import api from '../services/api';

type DataTable = {
  rawName: string;
  name: string;
  type: string;
};

export default function Data() {
  const { user, selectedTenantId } = useAuthStore();
  const abilities = user?.abilities || [];
  const hasAbility = (ability: string) => Boolean(user?.superadmin || abilities.includes('*') || abilities.includes(ability));
  const canReadData = hasAbility('data:read') || hasAbility('data:write');
  const canWriteData = hasAbility('data:write');
  const [tables, setTables] = useState<DataTable[]>([]);
  const [selectedTable, setSelectedTable] = useState<string>('');
  const [data, setData] = useState<unknown[]>([]);
  const [loading, setLoading] = useState(true);
  const [querying, setQuerying] = useState(false);
  const [showCreateModal, setShowCreateModal] = useState(false);
  const [newTableName, setNewTableName] = useState('');
  const [insertingData, setInsertingData] = useState(false);
  const [deletingTable, setDeletingTable] = useState(false);
  const [rowLimit, setRowLimit] = useState(100);
  const [startTime, setStartTime] = useState('');
  const [endTime, setEndTime] = useState('');
  const [error, setError] = useState('');
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [toast, setToast] = useState<{ message: string; type: 'success' | 'error' } | null>(null);

  const tenantId = selectedTenantId || user?.tenantId;

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
      return;
    }

    if (!nextTables.some((table) => table.name === selectedTable)) {
      setSelectedTable(nextTables[0].name);
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
        setError('');
        setLoading(false);
        return;
      }
      
      try {
        await fetchTables(tenantId);
        setError('');
      } catch (error) {
        console.error('Error fetching tables:', error);
        setError('Failed to load tables');
      } finally {
        setLoading(false);
      }
    };
    
    load();
  }, [tenantId, canReadData]);

  const handleQuery = async () => {
    if (!canReadData) {
      showToast('You do not have permission to query data', 'error');
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
      setError('');
    } catch (error) {
      console.error('Error querying data:', error);
      setError('Failed to query table data');
    } finally {
      setQuerying(false);
    }
  };

  const handleCreateTable = async () => {
    if (!canWriteData) {
      showToast('You do not have permission to create tables', 'error');
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
          tags: { location: 'warehouse' }
        }
      ]);

      await fetchTables(tenantId);
      setSelectedTable(newTableName);
      setShowCreateModal(false);
      setNewTableName('');
      setError('');
      showToast('Table created and initialized', 'success');
    } catch (error) {
      console.error('Error creating table:', error);
      setError('Failed to create table');
    } finally {
      setInsertingData(false);
    }
  };

  const handleDeleteTable = async () => {
    if (!canWriteData) {
      showToast('You do not have permission to delete tables', 'error');
      return;
    }
    if (!tenantId || !selectedTable) return;

    setDeletingTable(true);
    try {
      await api.deleteTable(tenantId, selectedTable);
      await fetchTables(tenantId);
      setConfirmDelete(false);
      setError('');
      showToast('Table deleted', 'success');
    } catch (error) {
      console.error('Error deleting table:', error);
      setError('Failed to delete table');
    } finally {
      setDeletingTable(false);
    }
  };

  const handleRefreshTables = async () => {
    if (!canReadData) {
      showToast('You do not have permission to refresh tables', 'error');
      return;
    }
    if (!tenantId) return;
    setLoading(true);
    try {
      await fetchTables(tenantId);
      setError('');
    } catch (error) {
      console.error('Error refreshing tables:', error);
      setError('Failed to refresh tables');
    } finally {
      setLoading(false);
    }
  };

  if (loading) {
    return (
      <div className="px-4 py-6">
        <div className="card animate-pulse">
          <div className="h-4 bg-gray-200 rounded w-1/3 mb-4"></div>
          <div className="h-32 bg-gray-200 rounded"></div>
        </div>
      </div>
    );
  }

  if (!tenantId) {
    return (
      <div className="px-4 py-6">
        <div className="card text-center py-12">
          <p className="text-gray-500">No tenant ID found. Please contact support.</p>
        </div>
      </div>
    );
  }

  if (!canReadData) {
    return (
      <div className="px-4 py-6">
        <div className="card text-center py-12">
          <p className="text-gray-500">You do not have permission to view data for this tenant.</p>
        </div>
      </div>
    );
  }

  return (
    <div className="px-4 py-6 sm:px-0">
      {toast && (
        <div className={`fixed top-4 right-4 z-50 px-4 py-3 rounded shadow-lg text-white text-sm ${
          toast.type === 'success' ? 'bg-green-600' : 'bg-red-600'
        }`}>
          {toast.message}
        </div>
      )}

      <div className="flex justify-between items-center mb-6">
        <h1 className="text-2xl font-bold text-gray-900">Data Explorer</h1>
        <div className="flex gap-2">
          <button onClick={handleRefreshTables} className="btn-secondary">
            Refresh
          </button>
          {canWriteData && (
            <button onClick={() => setShowCreateModal(true)} className="btn-primary">
              Create Table
            </button>
          )}
        </div>
      </div>

      {error && (
        <div className="mb-4 bg-red-50 border border-red-200 text-red-700 px-4 py-3 rounded">
          {error}
        </div>
      )}

      <div className="card mb-6">
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-5 gap-4 items-end">
          <div className="flex-1">
            <label className="block text-sm font-medium text-gray-700 mb-1">
              Table
            </label>
            <select
              value={selectedTable}
              onChange={(e) => setSelectedTable(e.target.value)}
              className="input"
            >
              {tables.length === 0 && <option value="">No tables</option>}
              {tables.map((table) => (
                <option key={table.rawName} value={table.name}>
                  {table.name} ({table.type})
                </option>
              ))}
            </select>
          </div>
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">Start</label>
            <input
              type="datetime-local"
              value={startTime}
              onChange={(e) => setStartTime(e.target.value)}
              className="input"
            />
          </div>
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">End</label>
            <input
              type="datetime-local"
              value={endTime}
              onChange={(e) => setEndTime(e.target.value)}
              className="input"
            />
          </div>
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">Rows</label>
            <input
              type="number"
              min={1}
              max={1000}
              value={rowLimit}
              onChange={(e) => setRowLimit(Number(e.target.value) || 100)}
              className="input"
            />
          </div>
          <button
            onClick={handleQuery}
            disabled={querying || !selectedTable}
            className="btn-primary"
          >
            {querying ? 'Querying...' : 'Query'}
          </button>
          <button
            onClick={() => setConfirmDelete(true)}
            disabled={!canWriteData || !selectedTable || deletingTable}
            className="btn-secondary border-red-300 text-red-700 hover:bg-red-50"
            title={!canWriteData ? 'You need data write permission to delete tables' : undefined}
          >
            {deletingTable ? 'Deleting...' : 'Delete Table'}
          </button>
        </div>
      </div>

      {tables.length === 0 ? (
        <div className="card text-center py-12">
          <p className="text-gray-500 mb-4">No tables found</p>
          {canWriteData ? (
            <button onClick={() => setShowCreateModal(true)} className="btn-primary">Create Your First Table</button>
          ) : (
            <p className="text-sm text-gray-500">You need data write access to create tables.</p>
          )}
        </div>
      ) : (
        <div className="card overflow-hidden">
          {data.length > 0 ? (
            <div className="overflow-x-auto">
              <div className="px-6 py-3 text-sm text-gray-600 border-b border-gray-200">
                Showing {Math.min(data.length, rowLimit)} row(s)
              </div>
              <table className="min-w-full divide-y divide-gray-200">
                <thead className="bg-gray-50">
                  <tr>
                    {Object.keys(data[0] as object).map((key) => (
                      <th
                        key={key}
                        className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider"
                      >
                        {key}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody className="bg-white divide-y divide-gray-200">
                  {data.slice(0, 100).map((row, i) => (
                    <tr key={i}>
                      {Object.values(row as object).map((value, j) => (
                        <td
                          key={j}
                          className="px-6 py-4 whitespace-nowrap text-sm text-gray-500"
                        >
                          {typeof value === 'object' 
                            ? JSON.stringify(value) 
                            : String(value)}
                        </td>
                      ))}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <div className="text-center py-12">
              <p className="text-gray-500">
                No data in this table. Run a pipeline to populate data.
              </p>
            </div>
          )}
        </div>
      )}

      {showCreateModal && canWriteData && (
        <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
          <div className="bg-white rounded-lg p-6 max-w-md w-full">
            <h2 className="text-xl font-bold mb-4">Create Table</h2>
            <div className="mb-4">
              <label className="block text-sm font-medium text-gray-700 mb-1">Table Name</label>
              <input
                type="text"
                value={newTableName}
                onChange={(e) => setNewTableName(e.target.value)}
                className="input w-full"
                placeholder="e.g., measurements"
              />
            </div>
            <p className="text-sm text-gray-500 mb-4">
              Creating a table will insert a sample row to initialize the table schema.
            </p>
            <div className="flex justify-end space-x-2">
              <button
                onClick={() => setShowCreateModal(false)}
                className="px-4 py-2 border border-gray-300 rounded-md text-gray-700 hover:bg-gray-50"
              >
                Cancel
              </button>
              <button
                onClick={handleCreateTable}
                disabled={insertingData || !newTableName}
                className="btn-primary"
              >
                {insertingData ? 'Creating...' : 'Create'}
              </button>
            </div>
          </div>
        </div>
      )}

      {confirmDelete && canWriteData && selectedTable && (
        <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
          <div className="bg-white rounded-lg p-6 max-w-md w-full">
            <h2 className="text-xl font-bold mb-2">Delete Table</h2>
            <p className="text-sm text-gray-600 mb-4">
              Delete table <span className="font-mono">{selectedTable}</span>? This action cannot be undone.
            </p>
            <div className="flex justify-end space-x-2">
              <button
                onClick={() => setConfirmDelete(false)}
                className="px-4 py-2 border border-gray-300 rounded-md text-gray-700 hover:bg-gray-50"
              >
                Cancel
              </button>
              <button
                onClick={handleDeleteTable}
                disabled={deletingTable}
                className="px-4 py-2 rounded-md bg-red-600 text-white hover:bg-red-700 disabled:opacity-50"
              >
                {deletingTable ? 'Deleting...' : 'Delete'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
