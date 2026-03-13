import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { useAuthStore } from '../stores/auth';
import api from '../services/api';
import type { Pipeline, PipelineRun } from '../types';

export default function Dashboard() {
  const { user } = useAuthStore();
  const [pipelines, setPipelines] = useState<Pipeline[]>([]);
  const [recentRuns, setRecentRuns] = useState<PipelineRun[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const fetchData = async () => {
      if (!user?.tenantId) return;
      
      try {
        const [pipelinesData] = await Promise.all([
          api.getPipelines(user.tenantId),
        ]);
        setPipelines(pipelinesData);
        
        // Get recent runs from first pipeline
        if (pipelinesData.length > 0) {
          const runs = await api.getPipelineRuns(pipelinesData[0].id);
          setRecentRuns(runs.slice(0, 5));
        }
      } catch (error) {
        console.error('Error fetching dashboard data:', error);
      } finally {
        setLoading(false);
      }
    };
    
    fetchData();
  }, [user?.tenantId]);

  const activePipelines = pipelines.filter(p => p.isActive).length;
  const totalPipelines = pipelines.length;
  const failedRuns = recentRuns.filter(r => r.status === 'FAILED').length;
  const successRuns = recentRuns.filter(r => r.status === 'SUCCESS').length;

  return (
    <div className="px-4 py-6 sm:px-0">
      <div className="mb-6">
        <h1 className="text-2xl font-bold text-gray-900">Dashboard</h1>
        <p className="mt-1 text-sm text-gray-600">
          Welcome back, {user?.firstName || user?.email}
        </p>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-4 gap-6 mb-8">
        <div className="card">
          <div className="text-2xl font-bold text-primary-600">{totalPipelines}</div>
          <div className="text-sm text-gray-500">Total Pipelines</div>
        </div>
        <div className="card">
          <div className="text-2xl font-bold text-green-600">{activePipelines}</div>
          <div className="text-sm text-gray-500">Active Pipelines</div>
        </div>
        <div className="card">
          <div className="text-2xl font-bold text-green-600">{successRuns}</div>
          <div className="text-sm text-gray-500">Successful Runs</div>
        </div>
        <div className="card">
          <div className="text-2xl font-bold text-red-600">{failedRuns}</div>
          <div className="text-sm text-gray-500">Failed Runs</div>
        </div>
      </div>

      <div className="card">
        <div className="flex justify-between items-center mb-4">
          <h2 className="text-lg font-semibold">Recent Pipeline Runs</h2>
          <Link to="/pipelines" className="text-primary-600 hover:text-primary-700">
            View all
          </Link>
        </div>
        
        {loading ? (
          <div className="animate-pulse space-y-4">
            {[1, 2, 3].map(i => (
              <div key={i} className="h-12 bg-gray-200 rounded"></div>
            ))}
          </div>
        ) : recentRuns.length > 0 ? (
          <div className="space-y-3">
            {recentRuns.map((run) => (
              <div key={run.id} className="flex items-center justify-between p-3 bg-gray-50 rounded">
                <div className="flex items-center space-x-3">
                  <span className={`w-3 h-3 rounded-full ${
                    run.status === 'SUCCESS' ? 'bg-green-500' :
                    run.status === 'FAILED' ? 'bg-red-500' :
                    run.status === 'RUNNING' ? 'bg-yellow-500' :
                    'bg-gray-400'
                  }`}></span>
                  <span className="text-sm font-medium">{run.id.slice(0, 8)}...</span>
                  <span className="text-sm text-gray-500">{run.triggerType}</span>
                </div>
                <span className="text-sm text-gray-500">
                  {new Date(run.startedAt).toLocaleString()}
                </span>
              </div>
            ))}
          </div>
        ) : (
          <p className="text-gray-500 text-center py-4">No pipeline runs yet</p>
        )}
      </div>

      <div className="mt-6 grid grid-cols-1 md:grid-cols-2 gap-6">
        <div className="card">
          <h2 className="text-lg font-semibold mb-4">Quick Actions</h2>
          <div className="space-y-3">
            <Link to="/modules" className="btn-primary block text-center">
              Create New Module
            </Link>
            <Link to="/pipelines" className="btn-secondary block text-center">
              Create New Pipeline
            </Link>
          </div>
        </div>

        <div className="card">
          <h2 className="text-lg font-semibold mb-4">System Status</h2>
          <div className="space-y-2">
            <div className="flex justify-between">
              <span className="text-gray-600">API Status</span>
              <span className="text-green-600">● Online</span>
            </div>
            <div className="flex justify-between">
              <span className="text-gray-600">Kafka</span>
              <span className="text-green-600">● Connected</span>
            </div>
            <div className="flex justify-between">
              <span className="text-gray-600">Database</span>
              <span className="text-green-600">● Connected</span>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
