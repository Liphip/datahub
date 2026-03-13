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

  useEffect(() => {
    const fetchTables = async () => {
      if (!user?.tenantId) return;
      
      try {
        const result = await api.listTables(user.tenantId);
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
  }, [user?.tenantId]);

  const handleQuery = async () => {
    if (!user?.tenantId || !selectedTable) return;
    
    setQuerying(true);
    try {
      const result = await api.queryData(user.tenantId, selectedTable, { limit: 100 });
      setData((result as { data: unknown[] }).data);
    } catch (error) {
      console.error('Error querying data:', error);
    } finally {
      setQuerying(false);
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

  return (
    <div className="px-4 py-6 sm:px-0">
      <div className="flex justify-between items-center mb-6">
        <h1 className="text-2xl font-bold text-gray-900">Data Explorer</h1>
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
          <button className="btn-primary">Create Table</button>
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
    </div>
  );
}
