import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { useAuthStore } from './stores/auth';
import Login from './pages/Login';
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
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/login" element={<Login />} />
        
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
