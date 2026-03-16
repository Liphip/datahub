import { useEffect, useState } from 'react';
import { useAuthStore } from '../stores/auth';
import api from '../services/api';

export default function Data() {
  const { user } = useAuthStore();
  const [tables, setTables] = useState<{ name: string; type: string }[]>([]);
  const [selectedTable, setSelectedTable] = useState<string>('');
  const [data, setData] = useState<unknown[]>([]);
  const [loading, setLoading] = useState(true);
  const [querying, setQuerying] = useState(false);
  const [showCreateModal, setShowCreateModal] = useState(false);
  const [newTableName, setNewTableName] = useState('');
  const [insertingData, setInsertingData] = useState(false);

  const tenantId = user?.tenantId;

  useEffect(() => {
    const fetchTables = async () => {
      if (!tenantId) {
        setLoading(false);
        return;
      }
      
      try {
        const result = await api.listTables(tenantId);
        setTables(result.tables);
        if (result.tables.length > 0) {
          setSelectedTable(result.tables[0].name);
        }
      } catch (error) {
        console.error('Error fetching tables:', error);
      } finally {
        setLoading(false);
      }
    };
    
    fetchTables();
  }, [tenantId]);

  const handleQuery = async () => {
    if (!tenantId || !selectedTable) return;
    
    setQuerying(true);
    try {
      const result = await api.queryData(tenantId, selectedTable, { limit: 100 });
      setData((result as { data: unknown[] }).data || []);
    } catch (error) {
      console.error('Error querying data:', error);
    } finally {
      setQuerying(false);
    }
  };

  const handleCreateTable = async () => {
    if (!tenantId || !newTableName) return;
    
    setInsertingData(true);
    try {
      // Create table by inserting sample data
      await api.insertData(tenantId, newTableName, [
        {
          time: new Date().toISOString(),
          device_id: 'sample-device-001',
          value: 25.5,
          tags: { location: 'warehouse' }
        }
      ]);
      
      // Refresh tables list
      const result = await api.listTables(tenantId);
      setTables(result.tables);
      setShowCreateModal(false);
      setNewTableName('');
    } catch (error) {
      console.error('Error creating table:', error);
    } finally {
      setInsertingData(false);
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

  return (
    <div className="px-4 py-6 sm:px-0">
      <div className="flex justify-between items-center mb-6">
        <h1 className="text-2xl font-bold text-gray-900">Data Explorer</h1>
        <button onClick={() => setShowCreateModal(true)} className="btn-primary">
          Create Table
        </button>
      </div>

      <div className="card mb-6">
        <div className="flex space-x-4 items-end">
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
                <option key={table.name} value={table.name}>
                  {table.name} ({table.type})
                </option>
              ))}
            </select>
          </div>
          <button
            onClick={handleQuery}
            disabled={querying || !selectedTable}
            className="btn-primary"
          >
            {querying ? 'Querying...' : 'Query'}
          </button>
        </div>
      </div>

      {tables.length === 0 ? (
        <div className="card text-center py-12">
          <p className="text-gray-500 mb-4">No tables found</p>
          <button onClick={() => setShowCreateModal(true)} className="btn-primary">Create Your First Table</button>
        </div>
      ) : (
        <div className="card overflow-hidden">
          {data.length > 0 ? (
            <div className="overflow-x-auto">
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

      {showCreateModal && (
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
    </div>
  );
}
