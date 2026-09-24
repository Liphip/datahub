import { Link, useLocation, useNavigate } from 'react-router';
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useAuthStore } from '../stores/auth';
import { languages } from '../i18n';

/**
 * Defines the interface `LayoutProps`, the contract implemented by this source unit.
 */
interface LayoutProps {
  children: React.ReactNode;
}

// ── Nav item definitions ───────────────────────────────────────────────────────

/**
 * Defines the interface `NavItem`, the contract implemented by this source unit.
 */
interface NavItem {
  key: string;
  href: string;
  exact?: boolean;
  adminOnly?: boolean;
  icon: React.ReactNode;
}

const navItems: NavItem[] = [
  {
    key: 'nav.dashboard',
    href: '/',
    exact: true,
    icon: (
      <svg className="w-5 h-5 shrink-0" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.75} strokeLinecap="round" strokeLinejoin="round">
        <path d="M3 9l9-7 9 7v11a2 2 0 01-2 2H5a2 2 0 01-2-2z" />
        <path d="M9 22V12h6v10" />
      </svg>
    ),
  },
  {
    key: 'nav.pipelines',
    href: '/pipelines',
    icon: (
      <svg className="w-5 h-5 shrink-0" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.75} strokeLinecap="round" strokeLinejoin="round">
        <path d="M22 12h-4l-3 9L9 3l-3 9H2" />
      </svg>
    ),
  },
  {
    key: 'nav.modules',
    href: '/modules',
    icon: (
      <svg className="w-5 h-5 shrink-0" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.75} strokeLinecap="round" strokeLinejoin="round">
        <path d="M21 16V8a2 2 0 00-1-1.73l-7-4a2 2 0 00-2 0l-7 4A2 2 0 002 8v8a2 2 0 001 1.73l7 4a2 2 0 002 0l7-4A2 2 0 0021 16z" />
        <path d="M3.27 6.96L12 12.01l8.73-5.05M12 22.08V12" />
      </svg>
    ),
  },
  {
    key: 'nav.connectors',
    href: '/connectors',
    icon: (
      <svg className="w-5 h-5 shrink-0" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.75} strokeLinecap="round" strokeLinejoin="round">
        <path d="M18 13v6a2 2 0 01-2 2H5a2 2 0 01-2-2V8a2 2 0 012-2h6" />
        <path d="M15 3h6v6" />
        <path d="M10 14L21 3" />
      </svg>
    ),
  },
  {
    key: 'nav.data',
    href: '/data',
    icon: (
      <svg className="w-5 h-5 shrink-0" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.75} strokeLinecap="round" strokeLinejoin="round">
        <ellipse cx="12" cy="5" rx="9" ry="3" />
        <path d="M21 12c0 1.66-4 3-9 3s-9-1.34-9-3" />
        <path d="M3 5v14c0 1.66 4 3 9 3s9-1.34 9-3V5" />
      </svg>
    ),
  },
  {
    key: 'nav.alerts',
    href: '/alerts',
    icon: (
      <svg className="w-5 h-5 shrink-0" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.75} strokeLinecap="round" strokeLinejoin="round">
        <path d="M18 8A6 6 0 006 8c0 7-3 9-3 9h18s-3-2-3-9" />
        <path d="M13.73 21a2 2 0 01-3.46 0" />
      </svg>
    ),
  },
  {
    key: 'nav.users',
    href: '/users',
    icon: (
      <svg className="w-5 h-5 shrink-0" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.75} strokeLinecap="round" strokeLinejoin="round">
        <path d="M17 21v-2a4 4 0 00-4-4H5a4 4 0 00-4 4v2" />
        <circle cx="9" cy="7" r="4" />
        <path d="M23 21v-2a4 4 0 00-3-3.87" />
        <path d="M16 3.13a4 4 0 010 7.75" />
      </svg>
    ),
  },
  {
    key: 'nav.tenants',
    href: '/tenants',
    adminOnly: true,
    icon: (
      <svg className="w-5 h-5 shrink-0" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.75} strokeLinecap="round" strokeLinejoin="round">
        <path d="M3 21h18M9 8h1M9 12h1M9 16h1M14 8h1M14 12h1M14 16h1M5 21V5a2 2 0 012-2h10a2 2 0 012 2v16" />
      </svg>
    ),
  },
];

// ── Sidebar inner content (extracted to avoid React remount issue) ─────────────

/**
 * Defines the interface `SidebarProps`, the contract implemented by this source unit.
 */
interface SidebarProps {
  visibleNav: NavItem[];
  currentPath: string;
  tenantContexts: { tenantId: string; tenantName: string; roleName?: string }[];
  selectedTenantId: string | null;
  switchingTenant: boolean;
  userDisplayName: string;
  isSuperadmin: boolean;
  theme: 'light' | 'dark';
  language: string;
  tKeys: (key: string) => string;
  onTenantSwitch: (id: string) => void;
  onThemeToggle: () => void;
  onLangChange: (code: string) => void;
  onLogout: () => void;
  onNavClick?: () => void;
}

/**
 * Implements `SidebarInner` for this source unit.
 */
function SidebarInner({
  visibleNav,
  currentPath,
  tenantContexts,
  selectedTenantId,
  switchingTenant,
  userDisplayName,
  isSuperadmin,
  theme,
  language,
  tKeys,
  onTenantSwitch,
  onThemeToggle,
  onLangChange,
  onLogout,
  onNavClick,
}: SidebarProps) {
  const currentTenant = tenantContexts.find((c) => c.tenantId === selectedTenantId);

  /**
   * Evaluates the named capability or state predicate.
   */
  const isActive = (href: string, exact = false) =>
    exact ? currentPath === href : currentPath.startsWith(href);

  return (
    <div className="flex flex-col h-full">
      {/* Logo */}
      <div className="px-4 py-4 border-b border-slate-200 dark:border-slate-800">
        <Link to="/" className="flex items-center gap-2.5 group" onClick={onNavClick}>
          <div className="w-8 h-8 rounded-lg bg-linear-to-br from-primary-500 to-primary-700 flex items-center justify-center shrink-0 shadow-xs shadow-primary-900/20 group-hover:from-primary-400 group-hover:to-primary-600 transition-all">
            <svg className="w-4 h-4 text-white" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.5}>
              <circle cx="12" cy="12" r="3" />
              <path d="M12 2v4M12 18v4M4.93 4.93l2.83 2.83M16.24 16.24l2.83 2.83M2 12h4M18 12h4M4.93 19.07l2.83-2.83M16.24 7.76l2.83-2.83" />
            </svg>
          </div>
          <div>
            <span className="text-sm font-bold text-slate-900 dark:text-slate-100 leading-none">DataTurntable</span>
            <span className="block text-[10px] text-slate-400 dark:text-slate-500 font-normal leading-tight mt-0.5">Data Platform</span>
          </div>
        </Link>
      </div>

      {/* Tenant selector */}
      {tenantContexts.length > 0 && (
        <div className="px-3 py-3 border-b border-slate-200 dark:border-slate-800">
          <p className="text-[10px] uppercase tracking-wider font-semibold text-slate-400 dark:text-slate-500 mb-1.5 px-1">
            Workspace
          </p>
          {tenantContexts.length === 1 ? (
            <div className="flex items-center gap-2 px-2.5 py-1.5 rounded-lg bg-slate-100 dark:bg-slate-800">
              <div className="w-6 h-6 rounded-md bg-primary-100 dark:bg-primary-900/50 flex items-center justify-center shrink-0">
                <span className="text-[11px] font-bold text-primary-700 dark:text-primary-300">
                  {currentTenant?.tenantName?.[0]?.toUpperCase() ?? 'T'}
                </span>
              </div>
              <span className="text-sm font-medium text-slate-700 dark:text-slate-200 truncate">
                {currentTenant?.tenantName ?? 'Tenant'}
              </span>
            </div>
          ) : (
            <select
              value={selectedTenantId || ''}
              onChange={(e) => onTenantSwitch(e.target.value)}
              disabled={switchingTenant}
              className="w-full text-sm border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 rounded-lg px-2.5 py-1.5 text-slate-700 dark:text-slate-200 focus:outline-hidden focus:ring-2 focus:ring-primary-500 disabled:opacity-50"
            >
              {tenantContexts.map((ctx) => (
                <option key={ctx.tenantId} value={ctx.tenantId}>
                  {ctx.tenantName}
                  {ctx.roleName ? ` — ${ctx.roleName}` : ''}
                </option>
              ))}
            </select>
          )}
        </div>
      )}

      {/* Navigation */}
      <nav className="flex-1 px-2 py-3 space-y-0.5 overflow-y-auto">
        {visibleNav.map((item) => {
          const active = isActive(item.href, item.exact);
          return (
            <Link
              key={item.key}
              to={item.href}
              onClick={onNavClick}
              className={`group relative flex items-center gap-3 px-3 py-2 rounded-lg text-sm font-medium transition-all duration-150 ${
                active
                  ? 'bg-primary-50 dark:bg-primary-900/30 text-primary-700 dark:text-primary-300 shadow-xs'
                  : 'text-slate-600 dark:text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-800 hover:text-slate-900 dark:hover:text-slate-100'
              }`}
            >
              {/* active left-border accent */}
              {active && (
                <span className="absolute left-0 inset-y-1.5 w-0.5 rounded-full bg-primary-600 dark:bg-primary-400" />
              )}
              <span className={`shrink-0 transition-colors ${active ? 'text-primary-600 dark:text-primary-400' : 'group-hover:text-slate-700 dark:group-hover:text-slate-200'}`}>
                {item.icon}
              </span>
              {tKeys(item.key)}
            </Link>
          );
        })}
      </nav>

      {/* Footer */}
      <div className="border-t border-slate-200 dark:border-slate-800 px-3 py-3 space-y-1.5">
        {/* Theme + Language row */}
        <div className="flex items-center gap-2 px-1">
          <button
            onClick={onThemeToggle}
            className="flex items-center gap-1.5 text-xs text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-200 transition-colors py-1"
            title={tKeys('theme.toggle')}
          >
            {theme === 'dark' ? (
              <svg className="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2}>
                <circle cx="12" cy="12" r="5" />
                <path d="M12 1v2M12 21v2M4.22 4.22l1.42 1.42M18.36 18.36l1.42 1.42M1 12h2M21 12h2M4.22 19.78l1.42-1.42M18.36 5.64l1.42-1.42" />
              </svg>
            ) : (
              <svg className="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2}>
                <path d="M21 12.79A9 9 0 1111.21 3 7 7 0 0021 12.79z" />
              </svg>
            )}
            {theme === 'dark' ? tKeys('theme.light') : tKeys('theme.dark')}
          </button>
          <select
            value={language}
            onChange={(e) => onLangChange(e.target.value)}
            className="ml-auto text-xs rounded-sm border border-slate-200 dark:border-slate-700 px-1.5 py-0.5 text-slate-500 dark:text-slate-400 bg-transparent focus:outline-hidden"
            title={tKeys('language.select')}
          >
            {languages.map((lang) => (
              <option key={lang.code} value={lang.code}>{lang.nativeName}</option>
            ))}
          </select>
        </div>

        {/* User row */}
        <div className="flex items-center gap-2 px-1 py-1">
          <div className="w-7 h-7 rounded-full bg-primary-100 dark:bg-primary-900/40 flex items-center justify-center shrink-0">
            <span className="text-xs font-semibold text-primary-700 dark:text-primary-300">
              {userDisplayName[0]?.toUpperCase() ?? '?'}
            </span>
          </div>
          <div className="flex-1 min-w-0">
            <p className="text-xs font-medium text-slate-700 dark:text-slate-200 truncate leading-tight">{userDisplayName}</p>
            {isSuperadmin && (
              <p className="text-[10px] text-amber-600 dark:text-amber-400 font-medium">Superadmin</p>
            )}
          </div>
          <button
            onClick={onLogout}
            className="shrink-0 text-slate-400 hover:text-red-500 dark:hover:text-red-400 transition-colors p-1 rounded-sm"
            title={tKeys('auth.logout')}
          >
            <svg className="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2}>
              <path d="M9 21H5a2 2 0 01-2-2V5a2 2 0 012-2h4M16 17l5-5-5-5M21 12H9" />
            </svg>
          </button>
        </div>
      </div>
    </div>
  );
}

// ── Layout ─────────────────────────────────────────────────────────────────────

export default function Layout({ children }: LayoutProps) {
  const { t, i18n } = useTranslation();
  const location = useLocation();
  const navigate = useNavigate();
  const { user, logout, tenantContexts, selectedTenantId, switchTenant } = useAuthStore();

  const [switchingTenant, setSwitchingTenant] = useState(false);
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const [theme, setTheme] = useState<'light' | 'dark'>(() => {
    const saved = localStorage.getItem('theme');
    if (saved === 'light' || saved === 'dark') return saved;
    return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  });

  const canManageTenants = Boolean(
    user?.superadmin || user?.abilities?.includes('*') || user?.abilities?.includes('tenant:manage')
  );
  const visibleNav = navItems.filter((item) => !item.adminOnly || canManageTenants);

  /**
   * Implements `useEffect` for this source unit.
   */
  useEffect(() => {
    const isDark = theme === 'dark';
    document.documentElement.classList.toggle('dark', isDark);
    document.documentElement.style.colorScheme = isDark ? 'dark' : 'light';
    localStorage.setItem('theme', theme);
  }, [theme]);

  // Close mobile sidebar on route change
  /**
   * Implements `useEffect` for this source unit.
   */
  useEffect(() => {
    setSidebarOpen(false);
  }, [location.pathname]);

  /**
   * Processes the named request, event, or operation.
   */
  const handleLogout = () => {
    logout();
    navigate('/login');
  };

  /**
   * Processes the named request, event, or operation.
   */
  const handleTenantSwitch = async (tenantId: string) => {
    if (!tenantId || tenantId === selectedTenantId) return;
    try {
      setSwitchingTenant(true);
      await switchTenant(tenantId);
      navigate('/');
    } catch {
      // silent — non-fatal
    } finally {
      setSwitchingTenant(false);
    }
  };

  const userDisplayName = user?.firstName
    ? `${user.firstName}${user.lastName ? ' ' + user.lastName : ''}`
    : user?.email ?? '';

  const sidebarProps: SidebarProps = {
    visibleNav,
    currentPath: location.pathname,
    tenantContexts,
    selectedTenantId,
    switchingTenant,
    userDisplayName,
    isSuperadmin: Boolean(user?.superadmin),
    theme,
    language: i18n.language,
    tKeys: t,
    onTenantSwitch: handleTenantSwitch,
    onThemeToggle: () => setTheme((p) => (p === 'dark' ? 'light' : 'dark')),
    onLangChange: (code) => i18n.changeLanguage(code),
    onLogout: handleLogout,
  };

  return (
    <div className="min-h-screen bg-slate-100 text-slate-900 dark:bg-slate-950 dark:text-slate-100 transition-colors flex">
      {/* ── Desktop sidebar ─────────────────────────────────────────────────── */}
      <aside className="hidden lg:flex flex-col w-64 shrink-0 bg-white dark:bg-slate-900 border-r border-slate-200 dark:border-slate-800 fixed inset-y-0 left-0 z-30">
        <SidebarInner {...sidebarProps} />
      </aside>

      {/* ── Mobile overlay ──────────────────────────────────────────────────── */}
      {sidebarOpen && (
        <div
          className="lg:hidden fixed inset-0 z-40 bg-black/40 backdrop-blur-xs"
          onClick={() => setSidebarOpen(false)}
        />
      )}

      {/* ── Mobile sidebar ──────────────────────────────────────────────────── */}
      <aside
        className={`lg:hidden fixed inset-y-0 left-0 z-50 w-64 bg-white dark:bg-slate-900 border-r border-slate-200 dark:border-slate-800 transform transition-transform duration-200 ease-in-out ${
          sidebarOpen ? 'translate-x-0' : '-translate-x-full'
        }`}
      >
        <SidebarInner {...sidebarProps} onNavClick={() => setSidebarOpen(false)} />
      </aside>

      {/* ── Main area ───────────────────────────────────────────────────────── */}
      <div className="flex-1 flex flex-col min-w-0 lg:ml-64">
        {/* Mobile topbar */}
        <div className="lg:hidden flex items-center gap-3 px-4 py-3 bg-white dark:bg-slate-900 border-b border-slate-200 dark:border-slate-800 sticky top-0 z-20">
          <button
            onClick={() => setSidebarOpen(true)}
            className="text-slate-500 hover:text-slate-700 dark:text-slate-400 dark:hover:text-slate-200 transition-colors"
            aria-label="Open menu"
          >
            <svg className="w-6 h-6" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2}>
              <path d="M3 12h18M3 6h18M3 18h18" />
            </svg>
          </button>
          <Link to="/" className="flex items-center gap-2">
            <div className="w-6 h-6 rounded-sm bg-primary-600 flex items-center justify-center">
              <svg className="w-3.5 h-3.5 text-white" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.5}>
                <circle cx="12" cy="12" r="3" />
                <path d="M12 2v4M12 18v4M4.93 4.93l2.83 2.83M16.24 16.24l2.83 2.83" />
              </svg>
            </div>
            <span className="text-sm font-bold text-slate-900 dark:text-slate-100">DataTurntable</span>
          </Link>
          {tenantContexts.length > 1 && (
            <select
              value={selectedTenantId || ''}
              onChange={(e) => handleTenantSwitch(e.target.value)}
              disabled={switchingTenant}
              className="ml-auto text-xs border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-900 rounded-sm px-2 py-1 max-w-[140px] truncate"
            >
              {tenantContexts.map((ctx) => (
                <option key={ctx.tenantId} value={ctx.tenantId}>{ctx.tenantName}</option>
              ))}
            </select>
          )}
        </div>

        {/* Page content */}
        <main className="flex-1 overflow-y-auto">
          <div className="max-w-7xl mx-auto px-4 sm:px-6 py-6">
            {children}
          </div>
        </main>
      </div>
    </div>
  );
}
