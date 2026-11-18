import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useAuthStore } from '../stores/auth';
import api from '../services/api';
import ComponentTabs from '../components/ComponentTabs';
import type { Tenant } from '../types';

/**
 * Defines the interface `TenantWithChildren`, the contract implemented by this source unit.
 */
interface TenantWithChildren extends Tenant {
  children?: TenantWithChildren[];
}

export default function Tenants() {
  const { t } = useTranslation();
  const { user, selectedTenantId, tenantContexts, switchTenant } = useAuthStore();
  const canManageTenants = Boolean(user?.superadmin || user?.abilities?.includes('*') || user?.abilities?.includes('tenant:manage'));

  const [tenants, setTenants] = useState<TenantWithChildren[]>([]);
  const [loading, setLoading] = useState(true);
  const [showCreateModal, setShowCreateModal] = useState(false);
  const [showEditModal, setShowEditModal] = useState(false);
  const [editingTenant, setEditingTenant] = useState<TenantWithChildren | null>(null);
  const [newTenantName, setNewTenantName] = useState('');
  const [newTenantParentId, setNewTenantParentId] = useState('');
  const [creating, setCreating] = useState(false);
  const [deletingId, setDeletingId] = useState<string | null>(null);
  const [confirmDeleteId, setConfirmDeleteId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [toast, setToast] = useState<{ message: string; type: 'success' | 'error' } | null>(null);
  const quotaFields = ['ingestRecordsPerMinute', 'maxConcurrentExecutions', 'retentionPipelineRunsDays', 'retentionAlertHistoryDays'] as const;
  const [quotaValues, setQuotaValues] = useState<Record<string, string>>({});
  const [searchQuery, setSearchQuery] = useState('');
  const [expandedTenantIds, setExpandedTenantIds] = useState<Record<string, boolean>>({});
  const [switchingTenantId, setSwitchingTenantId] = useState<string | null>(null);

  /**
   * Implements `showToast` for this source unit.
   */
  const showToast = (message: string, type: 'success' | 'error') => {
    setToast({ message, type });
    setTimeout(() => setToast(null), 3500);
  };

  const collectTenantIds = (nodes: TenantWithChildren[]): string[] =>
    nodes.flatMap((node) => [node.id, ...collectTenantIds((node.children || []) as TenantWithChildren[])]);

  const flattenTenants = (nodes: TenantWithChildren[]): TenantWithChildren[] =>
    nodes.flatMap((node) => [node, ...flattenTenants((node.children || []) as TenantWithChildren[])]);

  /**
   * Implements `fetchTenants` for this source unit.
   */
  const fetchTenants = async (query?: string) => {
    try {
      const tree = await api.getTenantsRecursive(query);
      const tenantTree = tree as TenantWithChildren[];
      setTenants(tenantTree);
      const allIds = collectTenantIds(tenantTree);
      setExpandedTenantIds((prev) => {
        const next = { ...prev };
        allIds.forEach((id) => {
          if (next[id] === undefined) next[id] = true;
        });
        return next;
      });
    } catch (err) {
      console.error('Error fetching tenants:', err);
    } finally {
      setLoading(false);
    }
  };

  /**
   * Implements `useEffect` for this source unit.
   */
  useEffect(() => {
    fetchTenants();
  }, []);

  /**
   * Implements `useEffect` for this source unit.
   */
  useEffect(() => {
    const timer = window.setTimeout(() => {
      fetchTenants(searchQuery.trim() || undefined);
    }, 250);
    return () => window.clearTimeout(timer);
  }, [searchQuery]);

  const flattenTenantOptions = (nodes: TenantWithChildren[], depth = 0): { id: string; name: string }[] => {
    return nodes.flatMap((node) => [
      { id: node.id, name: `${'  '.repeat(depth)}${depth > 0 ? '- ' : ''}${node.name}` },
      ...(node.children ? flattenTenantOptions(node.children, depth + 1) : []),
    ]);
  };

  /**
   * Processes the named request, event, or operation.
   */
  const handleCreateTenant = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!newTenantName) return;

    setCreating(true);
    setError(null);
    try {
      await api.createTenant(newTenantName, newTenantParentId || undefined);
      setShowCreateModal(false);
      setNewTenantName('');
      setNewTenantParentId('');
      await fetchTenants(searchQuery.trim() || undefined);
      showToast('Tenant created successfully', 'success');
    } catch (err: unknown) {
      const errorMsg = (err as { response?: { data?: { error?: string } } })?.response?.data?.error || 'Failed to create tenant';
      setError(errorMsg);
    } finally {
      setCreating(false);
    }
  };

  /**
   * Processes the named request, event, or operation.
   */
  const handleEditTenant = (tenant: TenantWithChildren) => {
    setEditingTenant(tenant);
    setError(null);
    setQuotaValues(Object.fromEntries(quotaFields.map(key => [key, tenant.quota?.[key] === undefined ? '' : String(tenant.quota[key])])));
    setNewTenantName(tenant.name);
    setShowEditModal(true);
  };

  /**
   * Processes the named request, event, or operation.
   */
  const handleUpdateTenant = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!editingTenant || !newTenantName) return;

    setCreating(true);
    try {
      const quota = { ...editingTenant.quota };
      for (const key of quotaFields) {
        const raw = quotaValues[key]?.trim() || '';
        if (!raw) { delete quota[key]; continue; }
        const value = Number(raw);
        const minimum = key.startsWith('retention') ? -1 : key === 'maxConcurrentExecutions' ? 1 : 0;
        if (!Number.isInteger(value) || value < minimum || value > 2147483647) {
          setError(t('tenants.quotaInvalid'));
          return;
        }
        quota[key] = value;
      }
      await api.updateTenant(editingTenant.id, { name: newTenantName, quota });
      await fetchTenants(searchQuery.trim() || undefined);
      setShowEditModal(false);
      setEditingTenant(null);
      setNewTenantName('');
      showToast('Tenant updated', 'success');
    } catch (err) {
      const message = (err as { response?: { data?: { error?: string } } })?.response?.data?.error;
      setError(message || t('tenants.quotaSaveFailed'));
    } finally {
      setCreating(false);
    }
  };

  /**
   * Processes the named request, event, or operation.
   */
  const handleDeleteTenant = async (tenantId: string) => {
    const flatten = (nodes: TenantWithChildren[]): TenantWithChildren[] =>
      nodes.flatMap((n) => [n, ...(n.children ? flatten(n.children) : [])]);

    const allTenants = flatten(tenants);
    const tenant = allTenants.find(t => t.id === tenantId);
    const isRoot = !tenant?.parentId;
    const rootCount = tenants.length;

    if (isRoot && rootCount <= 1) {
      showToast('Cannot delete the last root tenant', 'error');
      return;
    }

    setDeletingId(tenantId);
    try {
      await api.deleteTenant(tenantId);
      await fetchTenants(searchQuery.trim() || undefined);
      setConfirmDeleteId(null);
      showToast('Tenant deleted', 'success');
    } catch (err) {
      console.error('Error deleting tenant:', err);
      showToast('Failed to delete tenant', 'error');
    } finally {
      setDeletingId(null);
    }
  };

  /**
   * Processes the named request, event, or operation.
   */
  const handleSwitchTenant = async (tenantId: string) => {
    if (tenantId === selectedTenantId) return;
    setSwitchingTenantId(tenantId);
    try {
      await switchTenant(tenantId);
      showToast('Switched tenant', 'success');
    } catch {
      showToast('Failed to switch tenant', 'error');
    } finally {
      setSwitchingTenantId(null);
    }
  };

  /**
   * Implements `openCreateModal` for this source unit.
   */
  const openCreateModal = (parentId?: string) => {
    setNewTenantParentId(parentId || '');
    setNewTenantName('');
    setError(null);
    setShowCreateModal(true);
  };

  const allFlatTenants = flattenTenants(tenants);
  const tenantOptions = flattenTenantOptions(tenants);
  const currentTenantName = tenantContexts.find((tc) => tc.tenantId === selectedTenantId)?.tenantName
    || allFlatTenants.find((t) => t.id === selectedTenantId)?.name
    || selectedTenantId
    || 'None';

  /**
   * Implements `renderTenantTree` for this source unit.
   */
  const renderTenantTree = (tenant: TenantWithChildren, depth: number = 0) => {
    const hasChildren = tenant.children && tenant.children.length > 0;
    const isExpanded = expandedTenantIds[tenant.id] ?? true;
    const isCurrentTenant = tenant.id === selectedTenantId;

    return (
      <div key={tenant.id} style={{ marginLeft: depth * 24 }}>
        <div className={`card mb-2 ${isCurrentTenant ? 'ring-2 ring-primary-500 ring-offset-1' : ''}`}>
          <div className="flex justify-between items-start">
            <div>
              <div className="flex items-center space-x-2">
                {hasChildren && (
                  <button
                    onClick={() => setExpandedTenantIds((prev) => ({ ...prev, [tenant.id]: !isExpanded }))}
                    className="text-xs px-1.5 py-0.5 rounded-sm border border-gray-300 text-gray-600 hover:bg-gray-50"
                    title={isExpanded ? 'Collapse subtree' : 'Expand subtree'}
                  >
                    {isExpanded ? '−' : '+'}
                  </button>
                )}
                <h3 className="text-lg font-semibold">{tenant.name}</h3>
                {isCurrentTenant && (
                  <span className="px-2 py-0.5 text-xs rounded-sm bg-primary-100 text-primary-700">Current</span>
                )}
              </div>
              <p className="text-sm text-gray-500">ID: {tenant.id}</p>
            </div>
            <div className="flex items-center space-x-2">
              {tenantContexts.some((tc) => tc.tenantId === tenant.id) && !isCurrentTenant && (
                <button
                  onClick={() => handleSwitchTenant(tenant.id)}
                  disabled={switchingTenantId === tenant.id}
                  className="btn-secondary text-sm"
                >
                  {switchingTenantId === tenant.id ? 'Switching…' : 'Switch'}
                </button>
              )}
              <button
                onClick={() => openCreateModal(tenant.id)}
                className="text-green-600 hover:text-green-700 text-sm"
                title="Add sub-tenant"
              >
                + Add
              </button>
              <button onClick={() => handleEditTenant(tenant)} className="btn-secondary text-sm">Edit</button>
              {confirmDeleteId === tenant.id ? (
                <div className="flex items-center space-x-2">
                  <span className="text-xs text-gray-500">Delete?</span>
                  <button
                    onClick={() => handleDeleteTenant(tenant.id)}
                    disabled={deletingId === tenant.id}
                    className="text-red-600 hover:text-red-700 text-sm"
                  >
                    {deletingId === tenant.id ? 'Deleting…' : 'Yes'}
                  </button>
                  <button
                    onClick={() => setConfirmDeleteId(null)}
                    className="text-gray-500 hover:text-gray-700 text-sm"
                  >
                    No
                  </button>
                </div>
              ) : (
                <button
                  onClick={() => setConfirmDeleteId(tenant.id)}
                  className="text-red-600 hover:text-red-700 text-sm"
                >
                  Delete
                </button>
              )}
            </div>
          </div>
        </div>
        {hasChildren && isExpanded && (
          <div className="border-l-2 border-gray-200 ml-4 pl-4">
            {tenant.children!.map(child => renderTenantTree(child, depth + 1))}
          </div>
        )}
      </div>
    );
  };

  if (!canManageTenants) {
    return (
      <div className="px-4 py-6">
        <p className="text-gray-500">Access denied. Tenant management permissions are required.</p>
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

      {/* Tenant switcher */}
      {tenantContexts.length > 0 && (
        <div className="mb-4 rounded-sm border border-slate-200 bg-slate-50 px-4 py-3 flex flex-wrap items-center gap-3">
          <span className="text-sm font-medium text-slate-700">
            Current tenant: <strong>{currentTenantName}</strong>
          </span>
          <select
            value={selectedTenantId || ''}
            onChange={(e) => { if (e.target.value) handleSwitchTenant(e.target.value); }}
            className="input text-sm py-1 max-w-xs"
          >
            {tenantContexts.map((tc) => (
              <option key={tc.tenantId} value={tc.tenantId}>{tc.tenantName}</option>
            ))}
          </select>
          {switchingTenantId && (
            <span className="text-xs text-slate-500">Switching…</span>
          )}
        </div>
      )}

      <div className="flex justify-between items-center mb-6">
        <h1 className="text-2xl font-bold text-gray-900">{t('tenants.title')}</h1>
        <div className="flex items-center space-x-2">
          <button
            onClick={() => {
              const ids = collectTenantIds(tenants);
              const next: Record<string, boolean> = {};
              ids.forEach((id) => { next[id] = true; });
              setExpandedTenantIds(next);
            }}
            className="btn-secondary text-sm"
          >
            {t('common.expandAll')}
          </button>
          <button
            onClick={() => {
              const ids = collectTenantIds(tenants);
              const next: Record<string, boolean> = {};
              ids.forEach((id) => { next[id] = false; });
              setExpandedTenantIds(next);
            }}
            className="btn-secondary text-sm"
          >
            {t('common.collapseAll')}
          </button>
          <button
            onClick={() => openCreateModal()}
            className="btn-primary"
          >
            {t('tenants.createRootTenant')}
          </button>
        </div>
      </div>

      <div className="mb-4">
        <input
          type="text"
          value={searchQuery}
          onChange={(e) => setSearchQuery(e.target.value)}
          className="input w-full md:max-w-md"
          placeholder={t('tenants.searchPlaceholder')}
        />
      </div>

      {loading ? (
        <div className="space-y-3">
          {[1, 2, 3].map(i => (
            <div key={i} className="card animate-pulse"><div className="h-4 bg-gray-200 rounded-sm w-1/3"></div></div>
          ))}
        </div>
      ) : tenants.length === 0 ? (
        <div className="card text-center py-12">
          <p className="text-gray-500 mb-4">No tenants yet.</p>
          <button onClick={() => openCreateModal()} className="btn-primary">Create First Tenant</button>
        </div>
      ) : (
        <div>
          {tenants.map((tenant) => renderTenantTree(tenant, 0))}
        </div>
      )}

      {/* Create tenant modal */}
      {showCreateModal && (
        <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50">
          <div className="bg-white rounded-lg p-6 max-w-md w-full max-h-[90vh] overflow-y-auto">
            <h2 className="text-xl font-bold mb-4">Create Tenant</h2>
            {error && (
              <div className="mb-3 rounded-sm border border-red-200 bg-red-50 px-3 py-2 text-red-700 text-sm">{error}</div>
            )}
            <form onSubmit={handleCreateTenant}>
              <div className="mb-4">
                <label className="block text-sm font-medium text-gray-700 mb-1">Tenant Name <span className="text-red-500">*</span></label>
                <input
                  type="text"
                  value={newTenantName}
                  onChange={(e) => setNewTenantName(e.target.value)}
                  className="input w-full"
                  required
                  autoFocus
                />
              </div>
              <div className="mb-4">
                <label className="block text-sm font-medium text-gray-700 mb-1">Parent Tenant</label>
                <select
                  value={newTenantParentId}
                  onChange={(e) => setNewTenantParentId(e.target.value)}
                  className="input w-full"
                >
                  <option value="">— no parent (root tenant) —</option>
                  {tenantOptions.map((opt) => (
                    <option key={opt.id} value={opt.id}>{opt.name}</option>
                  ))}
                </select>
              </div>
              <div className="flex justify-end space-x-2">
                <button
                  type="button"
                  onClick={() => { setShowCreateModal(false); setNewTenantName(''); setNewTenantParentId(''); setError(null); }}
                  className="px-4 py-2 border border-gray-300 rounded-md text-gray-700 hover:bg-gray-50"
                >
                  {t('common.cancel')}
                </button>
                <button type="submit" disabled={creating || !newTenantName.trim()} className="btn-primary">
                  {creating ? t('common.creating') : t('common.create')}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* Edit tenant modal */}
      {showEditModal && editingTenant && (
        <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50">
          <div className="bg-white rounded-lg p-6 max-w-md w-full max-h-[90vh] overflow-y-auto">
            <h2 className="text-xl font-bold mb-4">Edit Tenant</h2>
            {error && <p role="alert" className="mb-3 text-red-700">{error}</p>}
            <ComponentTabs tenantId={editingTenant.id} resourceType="tenant" resourceId={editingTenant.id} onClose={() => setShowEditModal(false)}>
            <form onSubmit={handleUpdateTenant}>
              <div className="mb-4">
                <label className="block text-sm font-medium text-gray-700 mb-1">Tenant Name <span className="text-red-500">*</span></label>
                <input
                  type="text"
                  value={newTenantName}
                  onChange={(e) => setNewTenantName(e.target.value)}
                  className="input w-full"
                  required
                  autoFocus
                />
              </div>
              <fieldset className="mb-4 space-y-3">
                <legend className="font-medium">{t('tenants.quotaTitle')}</legend>
                <p className="text-sm text-gray-600">{t('tenants.quotaHelp')}</p>
                {quotaFields.map(key => (
                  <label key={key} className="block text-sm">
                    {t(`tenants.quota.${key}`)}
                    <input type="number" step="1" min={key.startsWith('retention') ? -1 : key === 'maxConcurrentExecutions' ? 1 : 0}
                      max="2147483647" value={quotaValues[key] || ''}
                      onChange={e => setQuotaValues(prev => ({ ...prev, [key]: e.target.value }))}
                      placeholder={t('tenants.quotaDefault')} className="input w-full" />
                  </label>
                ))}
              </fieldset>
              <div className="flex justify-end space-x-2">
                <button
                  type="button"
                  onClick={() => { setShowEditModal(false); setEditingTenant(null); setNewTenantName(''); }}
                  className="px-4 py-2 border border-gray-300 rounded-md text-gray-700 hover:bg-gray-50"
                >
                  {t('common.cancel')}
                </button>
                <button type="submit" disabled={creating || !newTenantName.trim()} className="btn-primary">
                  {creating ? t('common.saving') : t('common.save')}
                </button>
              </div>
            </form>
            </ComponentTabs>
          </div>
        </div>
      )}
    </div>
  );
}
