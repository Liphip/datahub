import { Link, useLocation, useNavigate } from 'react-router-dom';
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useAuthStore } from '../stores/auth';
import { languages } from '../i18n';

interface LayoutProps {
  children: React.ReactNode;
}

const navigation = [
  { name: 'nav.dashboard', href: '/' },
  { name: 'nav.modules', href: '/modules' },
  { name: 'nav.pipelines', href: '/pipelines' },
  { name: 'nav.connectors', href: '/connectors' },
  { name: 'nav.data', href: '/data' },
  { name: 'nav.alerts', href: '/alerts' },
  { name: 'nav.users', href: '/users' },
  { name: 'nav.tenants', href: '/tenants' },
];

export default function Layout({ children }: LayoutProps) {
  const { t, i18n } = useTranslation();
  const location = useLocation();
  const navigate = useNavigate();
  const { user, logout, tenantContexts, selectedTenantId, switchTenant } = useAuthStore();
  const [switchError, setSwitchError] = useState('');
  const [switchingTenant, setSwitchingTenant] = useState(false);
  const [theme, setTheme] = useState<'light' | 'dark'>(() => {
    const saved = localStorage.getItem('theme');
    if (saved === 'light' || saved === 'dark') return saved;
    return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  });
  const canManageTenants = Boolean(user?.superadmin || user?.abilities?.includes('*') || user?.abilities?.includes('manage:tenant'));
  const visibleNavigation = navigation.filter((item) => item.href !== '/tenants' || canManageTenants);

  useEffect(() => {
    const isDark = theme === 'dark';
    document.documentElement.classList.toggle('dark', isDark);
    document.documentElement.style.colorScheme = isDark ? 'dark' : 'light';
    localStorage.setItem('theme', theme);
  }, [theme]);

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
      setSwitchError(t('errors.generic'));
    } finally {
      setSwitchingTenant(false);
    }
  };

  return (
    <div className="min-h-screen bg-slate-100 text-slate-900 dark:bg-slate-950 dark:text-slate-100 transition-colors">
      <nav className="bg-white dark:bg-slate-900 shadow-sm border-b border-slate-200 dark:border-slate-800">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
          <div className="flex justify-between h-16">
            <div className="flex">
              <div className="flex-shrink-0 flex items-center">
                  <Link to="/" className="text-xl font-bold text-primary-700 dark:text-primary-300">
                    {t('common.appName')}
                  </Link>
                </div>
              <div className="hidden sm:ml-6 sm:flex sm:space-x-8">
                {visibleNavigation.map((item) => (
                  <Link
                    key={item.name}
                    to={item.href}
                    className={`inline-flex items-center px-1 pt-1 border-b-2 text-sm font-medium ${
                      location.pathname === item.href
                        ? 'border-primary-700 text-slate-900 dark:border-primary-300 dark:text-slate-100'
                        : 'border-transparent text-slate-600 hover:border-slate-300 hover:text-slate-900 dark:text-slate-300 dark:hover:border-slate-600 dark:hover:text-slate-100'
                    }`}
                  >
                    {t(item.name)}
                  </Link>
                ))}
              </div>
            </div>
            <div className="flex items-center">
              <div className="flex items-center space-x-4">
                {tenantContexts.length > 1 && (
                  <div className="flex items-center space-x-2">
                    <label htmlFor="tenant-switch" className="text-xs text-slate-500 dark:text-slate-400">{t('auth.tenant')}</label>
                    <select
                      id="tenant-switch"
                      value={selectedTenantId || ''}
                      onChange={(e) => handleTenantSwitch(e.target.value)}
                      disabled={switchingTenant}
                      className="text-sm border border-slate-300 dark:border-slate-600 bg-white dark:bg-slate-900 rounded px-2 py-1"
                    >
                      {tenantContexts.map((ctx) => (
                        <option key={ctx.tenantId} value={ctx.tenantId}>
                          {ctx.tenantName}{ctx.roleName ? ` (${ctx.roleName})` : ''}
                        </option>
                      ))}
                    </select>
                  </div>
                )}
                <select
                  value={i18n.language}
                  onChange={(e) => i18n.changeLanguage(e.target.value)}
                  className="text-xs rounded border border-slate-300 dark:border-slate-600 px-2 py-1 text-slate-600 dark:text-slate-200 bg-white dark:bg-slate-900"
                  title={t('language.select')}
                >
                  {languages.map((lang) => (
                    <option key={lang.code} value={lang.code}>
                      {lang.nativeName}
                    </option>
                  ))}
                </select>
                <button
                  onClick={() => setTheme((prev) => (prev === 'dark' ? 'light' : 'dark'))}
                  className="text-xs rounded border border-slate-300 dark:border-slate-600 px-2 py-1 text-slate-600 dark:text-slate-200 hover:bg-slate-100 dark:hover:bg-slate-800"
                  title={t('theme.toggle')}
                >
                  {theme === 'dark' ? t('theme.light') : t('theme.dark')}
                </button>
                <span className="text-sm text-slate-700 dark:text-slate-200">
                  {user?.email}
                </span>
                <button
                  onClick={handleLogout}
                  className="text-sm text-slate-500 hover:text-slate-700 dark:text-slate-400 dark:hover:text-slate-200"
                >
                  {t('auth.logout')}
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
                      ? 'border-primary-700 bg-primary-100 text-primary-800 dark:border-primary-300 dark:bg-primary-900/30 dark:text-primary-200'
                      : 'border-slate-300 text-slate-600 dark:border-slate-700 dark:text-slate-300'
                  }`}
                >
                  {t(item.name)}
                </Link>
              ))}
            </div>
          </div>
        </div>
      </nav>

      <main className="max-w-7xl mx-auto py-6 sm:px-6 lg:px-8">
        {switchError && (
          <div className="mb-4 mx-4 sm:mx-0 rounded border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700 dark:border-red-900 dark:bg-red-950/40 dark:text-red-200">
            {switchError}
          </div>
        )}
        {children}
      </main>
    </div>
  );
}
