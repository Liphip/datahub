import { useEffect, useState } from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { useAuthStore } from './stores/auth';
import { API_STATUS_EVENT } from './services/api';
import Login from './pages/Login';
import Setup from './pages/Setup';
import Dashboard from './pages/Dashboard';
import Modules from './pages/Modules';
import ModuleEditor from './pages/ModuleEditor';
import Pipelines from './pages/Pipelines';
import PipelineEditor from './pages/PipelineEditor';
import Connectors from './pages/Connectors';
import Tenants from './pages/Tenants';
import Users from './pages/Users';
import Alerts from './pages/Alerts';
import Data from './pages/Data';
import RunDetail from './pages/RunDetail';
import Layout from './components/Layout';

function ProtectedRoute({ children }: { children: React.ReactNode }) {
  const { isAuthenticated, isLoading } = useAuthStore();

  if (isLoading) {
    return (
      <div className="min-h-screen flex items-center justify-center">
        <div className="animate-spin rounded-full h-12 w-12 border-b-2 border-primary-600"></div>
      </div>
    );
  }

  if (!isAuthenticated) {
    return <Navigate to="/login" replace />;
  }

  return <>{children}</>;
}

function App() {
  const [apiAvailable, setApiAvailable] = useState(true);
  const [apiStatusMessage, setApiStatusMessage] = useState('');

  useEffect(() => {
    const onStatus = (event: Event) => {
      const detail = (event as CustomEvent<{ available: boolean; message?: string }>).detail;
      if (!detail) return;
      setApiAvailable(detail.available);
      setApiStatusMessage(detail.message || 'API connection issue detected');
    };

    const onOffline = () => {
      setApiAvailable(false);
      setApiStatusMessage('Network offline. Reconnect to continue syncing data.');
    };

    const onOnline = () => {
      setApiAvailable(true);
      setApiStatusMessage('');
    };

    window.addEventListener(API_STATUS_EVENT, onStatus as EventListener);
    window.addEventListener('offline', onOffline);
    window.addEventListener('online', onOnline);

    return () => {
      window.removeEventListener(API_STATUS_EVENT, onStatus as EventListener);
      window.removeEventListener('offline', onOffline);
      window.removeEventListener('online', onOnline);
    };
  }, []);

  return (
    <BrowserRouter>
      {!apiAvailable && (
        <div className="bg-amber-50 border-b border-amber-200 text-amber-900 px-4 py-2 text-sm flex items-center justify-between">
          <span>{apiStatusMessage || 'API unavailable. Some data may be outdated.'}</span>
          <button
            onClick={() => window.location.reload()}
            className="ml-4 px-2 py-1 rounded border border-amber-400 hover:bg-amber-100"
          >
            Retry
          </button>
        </div>
      )}
      <Routes>
        <Route path="/login" element={<Login />} />
        <Route path="/setup" element={<Setup />} />
        
        <Route
          path="/*"
          element={
            <ProtectedRoute>
              <Layout>
                <Routes>
                  <Route path="/" element={<Dashboard />} />
                  <Route path="/modules" element={<Modules />} />
                  <Route path="/modules/:id" element={<ModuleEditor />} />
                  <Route path="/pipelines" element={<Pipelines />} />
                  <Route path="/pipelines/:id" element={<PipelineEditor />} />
                  <Route path="/pipelines/:id/runs/:runId" element={<RunDetail />} />
                  <Route path="/connectors" element={<Connectors />} />
                  <Route path="/tenants" element={<Tenants />} />
                  <Route path="/users" element={<Users />} />
                  <Route path="/alerts" element={<Alerts />} />
                  <Route path="/data" element={<Data />} />
                </Routes>
              </Layout>
            </ProtectedRoute>
          }
        />
      </Routes>
    </BrowserRouter>
  );
}

export default App;
