import { Link, useLocation, useNavigate } from 'react-router-dom';
import { useState } from 'react';
import { useAuthStore } from '../stores/auth';

interface LayoutProps {
  children: React.ReactNode;
}

const navigation = [
  { name: 'Dashboard', href: '/' },
  { name: 'Modules', href: '/modules' },
  { name: 'Pipelines', href: '/pipelines' },
  { name: 'Connectors', href: '/connectors' },
  { name: 'Data', href: '/data' },
  { name: 'Alerts', href: '/alerts' },
  { name: 'Users', href: '/users' },
  { name: 'Tenants', href: '/tenants' },
];

export default function Layout({ children }: LayoutProps) {
  const location = useLocation();
  const navigate = useNavigate();
  const { user, logout, tenantContexts, selectedTenantId, switchTenant } = useAuthStore();
  const [switchError, setSwitchError] = useState('');
  const [switchingTenant, setSwitchingTenant] = useState(false);
  const canManageTenants = Boolean(user?.superadmin || user?.abilities?.includes('*') || user?.abilities?.includes('manage:tenant'));
  const visibleNavigation = navigation.filter((item) => item.href !== '/tenants' || canManageTenants);

  const handleLogout = () => {
    logout();
    navigate('/login');
  };

  const handleTenantSwitch = async (tenantId: string) => {
    if (!tenantId || tenantId === selectedTenantId) return;
    try {
      setSwitchingTenant(true);
      setSwitchError('');
      await switchTenant(tenantId);
      navigate('/');
    } catch {
      setSwitchError('Failed to switch tenant context');
    } finally {
      setSwitchingTenant(false);
    }
  };

  return (
    <div className="min-h-screen bg-gray-50">
      <nav className="bg-white shadow-sm">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
          <div className="flex justify-between h-16">
            <div className="flex">
              <div className="flex-shrink-0 flex items-center">
                <Link to="/" className="text-xl font-bold text-primary-600">
                  DataTurntable
                </Link>
              </div>
              <div className="hidden sm:ml-6 sm:flex sm:space-x-8">
                {visibleNavigation.map((item) => (
                  <Link
                    key={item.name}
                    to={item.href}
                    className={`inline-flex items-center px-1 pt-1 border-b-2 text-sm font-medium ${
                      location.pathname === item.href
                        ? 'border-primary-600 text-gray-900'
                        : 'border-transparent text-gray-500 hover:border-gray-300 hover:text-gray-700'
                    }`}
                  >
                    {item.name}
                  </Link>
                ))}
              </div>
            </div>
            <div className="flex items-center">
              <div className="flex items-center space-x-4">
                {tenantContexts.length > 1 && (
                  <div className="flex items-center space-x-2">
                    <label htmlFor="tenant-switch" className="text-xs text-gray-500">Tenant</label>
                    <select
                      id="tenant-switch"
                      value={selectedTenantId || ''}
                      onChange={(e) => handleTenantSwitch(e.target.value)}
                      disabled={switchingTenant}
                      className="text-sm border border-gray-300 rounded px-2 py-1"
                    >
                      {tenantContexts.map((ctx) => (
                        <option key={ctx.tenantId} value={ctx.tenantId}>
                          {ctx.tenantName}{ctx.roleName ? ` (${ctx.roleName})` : ''}
                        </option>
                      ))}
                    </select>
                  </div>
                )}
                <span className="text-sm text-gray-700">
                  {user?.email}
                </span>
                <button
                  onClick={handleLogout}
                  className="text-sm text-gray-500 hover:text-gray-700"
                >
                  Logout
                </button>
              </div>
            </div>
          </div>
          <div className="sm:hidden pb-3">
            <div className="flex gap-2 overflow-x-auto">
              {visibleNavigation.map((item) => (
                <Link
                  key={`mobile-${item.name}`}
                  to={item.href}
                  className={`shrink-0 rounded-full px-3 py-1.5 text-xs font-medium border ${
                    location.pathname === item.href
                      ? 'border-primary-600 bg-primary-50 text-primary-700'
                      : 'border-gray-200 text-gray-600'
                  }`}
                >
                  {item.name}
                </Link>
              ))}
            </div>
          </div>
        </div>
      </nav>

      <main className="max-w-7xl mx-auto py-6 sm:px-6 lg:px-8">
        {switchError && (
          <div className="mb-4 mx-4 sm:mx-0 rounded border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
            {switchError}
          </div>
        )}
        {children}
      </main>
    </div>
  );
}
