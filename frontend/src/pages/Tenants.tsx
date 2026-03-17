import { useEffect, useState } from 'react';
import { useAuthStore } from '../stores/auth';
import api from '../services/api';
import type { Tenant, TenantLineageDefaults, TenantLineageDefaultsConfig } from '../types';

interface TenantWithChildren extends Tenant {
  children?: TenantWithChildren[];
}

type LineageDefaultsField = 'safeScopePolicy' | 'defaultFetchMode' | 'defaultDepth' | 'maxDepthCap';
type LineageMergeChoice = Record<LineageDefaultsField, 'MINE' | 'LATEST'>;

interface LineageDefaultsConflictState {
  pending: TenantLineageDefaultsConfig;
  latest: TenantLineageDefaults;
  mergeChoice: LineageMergeChoice;
}

type TenantDefaultsConflictTelemetry = Record<string, { count: number; lastConflictAt?: string }>;
type LineagePollStatus = 'IDLE' | 'FRESH' | 'STALE' | 'OFFLINE';

const DEFAULT_TENANT_LINEAGE_DEFAULTS: TenantLineageDefaults = {
  safeScopePolicy: 'AUTO',
  defaultFetchMode: 'SUMMARY',
  defaultDepth: 20,
  maxDepthCap: 200,
};
const TENANT_DEFAULTS_CONFLICT_TELEMETRY_KEY = 'tenant.lineageDefaults.conflicts.v1';

const LINEAGE_DEFAULT_FIELDS: LineageDefaultsField[] = ['safeScopePolicy', 'defaultFetchMode', 'defaultDepth', 'maxDepthCap'];

export default function Tenants() {
  const { user } = useAuthStore();
  const canManageTenants = Boolean(user?.superadmin || user?.abilities?.includes('*') || user?.abilities?.includes('manage:tenant'));
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
  const [searchQuery, setSearchQuery] = useState('');
  const [expandedTenantIds, setExpandedTenantIds] = useState<Record<string, boolean>>({});
  const [lineageDefaultsTenantId, setLineageDefaultsTenantId] = useState('');
  const [lineageDefaults, setLineageDefaults] = useState<TenantLineageDefaults>(DEFAULT_TENANT_LINEAGE_DEFAULTS);
  const [lineageDefaultsEtag, setLineageDefaultsEtag] = useState<string | undefined>(undefined);
  const [lineageDefaultsBase, setLineageDefaultsBase] = useState<TenantLineageDefaultsConfig>(DEFAULT_TENANT_LINEAGE_DEFAULTS);
  const [loadingLineageDefaults, setLoadingLineageDefaults] = useState(false);
  const [savingLineageDefaults, setSavingLineageDefaults] = useState(false);
  const [lineageDefaultsConflict, setLineageDefaultsConflict] = useState<LineageDefaultsConflictState | null>(null);
  const [lineageDefaultsStaleLatest, setLineageDefaultsStaleLatest] = useState<TenantLineageDefaults | null>(null);
  const [lineagePollStatus, setLineagePollStatus] = useState<LineagePollStatus>('IDLE');
  const [lineagePollCheckedAt, setLineagePollCheckedAt] = useState<string | null>(null);
  const [checkingLineagePollNow, setCheckingLineagePollNow] = useState(false);
  const [lineagePollCooldownUntil, setLineagePollCooldownUntil] = useState<number>(0);
  const [conflictTelemetryByTenant, setConflictTelemetryByTenant] = useState<TenantDefaultsConflictTelemetry>(() => {
    try {
      const raw = localStorage.getItem(TENANT_DEFAULTS_CONFLICT_TELEMETRY_KEY);
      if (!raw) return {};
      const parsed = JSON.parse(raw) as TenantDefaultsConflictTelemetry;
      return parsed && typeof parsed === 'object' ? parsed : {};
    } catch {
      return {};
    }
  });

  const showToast = (message: string, type: 'success' | 'error') => {
    setToast({ message, type });
    setTimeout(() => setToast(null), 3500);
  };

  const toLineageDefaultsConfig = (defaults: TenantLineageDefaults): TenantLineageDefaultsConfig => ({
    safeScopePolicy: defaults.safeScopePolicy,
    defaultFetchMode: defaults.defaultFetchMode,
    defaultDepth: defaults.defaultDepth,
    maxDepthCap: defaults.maxDepthCap,
  });

  const isSameLineageConfig = (a: TenantLineageDefaultsConfig, b: TenantLineageDefaultsConfig) =>
    a.safeScopePolicy === b.safeScopePolicy
    && a.defaultFetchMode === b.defaultFetchMode
    && a.defaultDepth === b.defaultDepth
    && a.maxDepthCap === b.maxDepthCap;

  const buildConflictState = (pending: TenantLineageDefaultsConfig, latest: TenantLineageDefaults): LineageDefaultsConflictState => {
    const mergeChoice = LINEAGE_DEFAULT_FIELDS.reduce((acc, field) => {
      acc[field] = pending[field] === latest[field] ? 'LATEST' : 'MINE';
      return acc;
    }, {} as LineageMergeChoice);
    return { pending, latest, mergeChoice };
  };

  const runLineageDefaultsPollCheck = async (tid: string) => {
    try {
      const latestResponse = await api.getTenantLineageDefaultsIfChanged(tid, lineageDefaultsEtag);
      if (!latestResponse.notModified && latestResponse.data) {
        setLineageDefaultsEtag(latestResponse.etag);
        const latest = latestResponse.data;
        if (latest.version && lineageDefaults.version && latest.version !== lineageDefaults.version) {
          if (isLineageDefaultsDirty) {
            setLineageDefaultsStaleLatest(latest);
            setLineagePollStatus('STALE');
          } else {
            setLineageDefaults(latest);
            setLineageDefaultsBase(toLineageDefaultsConfig(latest));
            setLineageDefaultsStaleLatest(null);
            setLineagePollStatus('FRESH');
          }
        } else {
          setLineageDefaultsStaleLatest(null);
          setLineagePollStatus('FRESH');
        }
      } else if (latestResponse.notModified) {
        setLineagePollStatus((prev) => (prev === 'STALE' ? 'STALE' : 'FRESH'));
      }
      setLineagePollCheckedAt(new Date().toISOString());
    } catch {
      setLineagePollStatus('OFFLINE');
      setLineagePollCheckedAt(new Date().toISOString());
    }
  };

  const trackDefaultsConflict = (tenantId: string) => {
    setConflictTelemetryByTenant((prev) => {
      const current = prev[tenantId] ?? { count: 0 };
      return {
        ...prev,
        [tenantId]: {
          count: current.count + 1,
          lastConflictAt: new Date().toISOString(),
        },
      };
    });
  };

  const clearDefaultsConflictTelemetry = (tenantId: string) => {
    setConflictTelemetryByTenant((prev) => {
      const next = { ...prev };
      delete next[tenantId];
      return next;
    });
  };

  const collectTenantIds = (nodes: TenantWithChildren[]): string[] =>
    nodes.flatMap((node) => [node.id, ...collectTenantIds((node.children || []) as TenantWithChildren[])]);

  const flattenTenants = (nodes: TenantWithChildren[]): TenantWithChildren[] =>
    nodes.flatMap((node) => [node, ...flattenTenants((node.children || []) as TenantWithChildren[])]);

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

  useEffect(() => {
    fetchTenants();
  }, []);

  useEffect(() => {
    const timer = window.setTimeout(() => {
      fetchTenants(searchQuery.trim() || undefined);
    }, 250);
    return () => window.clearTimeout(timer);
  }, [searchQuery]);

  useEffect(() => {
    const all = flattenTenants(tenants);
    if (all.length === 0) {
      setLineageDefaultsTenantId('');
      return;
    }
    const exists = all.some((t) => t.id === lineageDefaultsTenantId);
    if (!exists) {
      setLineageDefaultsTenantId(all[0].id);
    }
  }, [tenants, lineageDefaultsTenantId]);

  useEffect(() => {
    const loadLineageDefaults = async () => {
      if (!lineageDefaultsTenantId) {
        setLineageDefaults(DEFAULT_TENANT_LINEAGE_DEFAULTS);
        setLineagePollStatus('IDLE');
        setLineagePollCheckedAt(null);
        return;
      }
      setLoadingLineageDefaults(true);
      try {
        const response = await api.getTenantLineageDefaultsIfChanged(lineageDefaultsTenantId);
        if (!response.notModified && response.data) {
          setLineageDefaults(response.data);
          setLineageDefaultsBase(toLineageDefaultsConfig(response.data));
          setLineageDefaultsEtag(response.etag);
        }
        setLineagePollStatus('FRESH');
        setLineagePollCheckedAt(new Date().toISOString());
        setLineageDefaultsStaleLatest(null);
      } catch {
        setLineageDefaults(DEFAULT_TENANT_LINEAGE_DEFAULTS);
        setLineageDefaultsBase(DEFAULT_TENANT_LINEAGE_DEFAULTS);
        setLineageDefaultsEtag(undefined);
        setLineagePollStatus('OFFLINE');
        setLineagePollCheckedAt(new Date().toISOString());
        setLineageDefaultsStaleLatest(null);
      } finally {
        setLoadingLineageDefaults(false);
      }
    };
    void loadLineageDefaults();
  }, [lineageDefaultsTenantId]);

  useEffect(() => {
    localStorage.setItem(TENANT_DEFAULTS_CONFLICT_TELEMETRY_KEY, JSON.stringify(conflictTelemetryByTenant));
  }, [conflictTelemetryByTenant]);

  useEffect(() => {
    setLineageDefaultsConflict(null);
    setLineageDefaultsStaleLatest(null);
    setLineageDefaultsEtag(undefined);
    setLineagePollStatus('IDLE');
    setLineagePollCheckedAt(null);
  }, [lineageDefaultsTenantId]);

  const isLineageDefaultsDirty = !isSameLineageConfig(toLineageDefaultsConfig(lineageDefaults), lineageDefaultsBase);

  useEffect(() => {
    if (!lineageDefaultsTenantId || !isLineageDefaultsDirty || savingLineageDefaults) return;
    const timer = window.setInterval(async () => {
      await runLineageDefaultsPollCheck(lineageDefaultsTenantId);
    }, 30000);
    return () => window.clearInterval(timer);
  }, [lineageDefaultsTenantId, isLineageDefaultsDirty, savingLineageDefaults, lineageDefaults.version, lineageDefaultsEtag]);

  const checkLineageDefaultsNow = async () => {
    if (!lineageDefaultsTenantId || checkingLineagePollNow) return;
    if (Date.now() < lineagePollCooldownUntil) return;
    setCheckingLineagePollNow(true);
    setLineagePollCooldownUntil(Date.now() + 5000);
    await runLineageDefaultsPollCheck(lineageDefaultsTenantId);
    setCheckingLineagePollNow(false);
  };

  const saveLineageDefaults = async () => {
    if (!lineageDefaultsTenantId) return;
    setSavingLineageDefaults(true);
    try {
      const defaultDepth = Math.min(200, Math.max(1, lineageDefaults.defaultDepth));
      const maxDepthCap = Math.min(200, Math.max(20, Math.max(defaultDepth, lineageDefaults.maxDepthCap)));
      const pending: TenantLineageDefaultsConfig = {
        safeScopePolicy: lineageDefaults.safeScopePolicy,
        defaultFetchMode: lineageDefaults.defaultFetchMode,
        defaultDepth,
        maxDepthCap,
      };
      const updated = await api.updateTenantLineageDefaults(lineageDefaultsTenantId, pending, lineageDefaults.version);
      setLineageDefaults(updated);
      setLineageDefaultsBase(toLineageDefaultsConfig(updated));
      setLineageDefaultsEtag(undefined);
      setLineageDefaultsStaleLatest(null);
      setLineagePollStatus('FRESH');
      setLineagePollCheckedAt(new Date().toISOString());
      showToast('Tenant lineage defaults saved', 'success');
    } catch (err: any) {
      if (err?.response?.status === 409) {
        showToast('Defaults changed by another user. Resolve conflict before saving.', 'error');
        trackDefaultsConflict(lineageDefaultsTenantId);
        setLineagePollStatus('STALE');
        setLineagePollCheckedAt(new Date().toISOString());
        try {
          const latest = await api.getTenantLineageDefaults(lineageDefaultsTenantId);
          const defaultDepth = Math.min(200, Math.max(1, lineageDefaults.defaultDepth));
          const maxDepthCap = Math.min(200, Math.max(20, Math.max(defaultDepth, lineageDefaults.maxDepthCap)));
          const pending: TenantLineageDefaultsConfig = {
            safeScopePolicy: lineageDefaults.safeScopePolicy,
            defaultFetchMode: lineageDefaults.defaultFetchMode,
            defaultDepth,
            maxDepthCap,
          };
          setLineageDefaultsEtag(undefined);
          setLineageDefaultsStaleLatest(latest);
          setLineageDefaultsConflict(buildConflictState(pending, latest));
        } catch {
          // ignore secondary reload errors
        }
      } else {
        setLineagePollStatus('OFFLINE');
        setLineagePollCheckedAt(new Date().toISOString());
        showToast('Failed to save tenant lineage defaults', 'error');
      }
    } finally {
      setSavingLineageDefaults(false);
    }
  };

  const restoreAndSaveLineageDefaults = async (defaults: TenantLineageDefaultsConfig) => {
    if (!lineageDefaultsTenantId) return;
    setSavingLineageDefaults(true);
    try {
      const pending: TenantLineageDefaultsConfig = {
        safeScopePolicy: defaults.safeScopePolicy,
        defaultFetchMode: defaults.defaultFetchMode,
        defaultDepth: defaults.defaultDepth,
        maxDepthCap: defaults.maxDepthCap,
      };
      const updated = await api.updateTenantLineageDefaults(lineageDefaultsTenantId, pending, lineageDefaults.version);
      setLineageDefaults(updated);
      setLineageDefaultsBase(toLineageDefaultsConfig(updated));
      setLineageDefaultsEtag(undefined);
      setLineageDefaultsStaleLatest(null);
      setLineagePollStatus('FRESH');
      setLineagePollCheckedAt(new Date().toISOString());
      showToast('Lineage defaults restored from history', 'success');
    } catch (err: any) {
      if (err?.response?.status === 409) {
        showToast('Defaults changed by another user. Resolve conflict before restoring.', 'error');
        trackDefaultsConflict(lineageDefaultsTenantId);
        setLineagePollStatus('STALE');
        setLineagePollCheckedAt(new Date().toISOString());
        try {
          const latest = await api.getTenantLineageDefaults(lineageDefaultsTenantId);
          const pending: TenantLineageDefaultsConfig = {
            safeScopePolicy: defaults.safeScopePolicy,
            defaultFetchMode: defaults.defaultFetchMode,
            defaultDepth: defaults.defaultDepth,
            maxDepthCap: defaults.maxDepthCap,
          };
          setLineageDefaultsEtag(undefined);
          setLineageDefaultsStaleLatest(latest);
          setLineageDefaultsConflict(buildConflictState(pending, latest));
        } catch {
          // ignore secondary reload errors
        }
      } else {
        setLineagePollStatus('OFFLINE');
        setLineagePollCheckedAt(new Date().toISOString());
        showToast('Failed to restore lineage defaults from history', 'error');
      }
    } finally {
      setSavingLineageDefaults(false);
    }
  };

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
    } catch (err: any) {
      const errorMsg = err.response?.data?.error || 'Failed to create tenant';
      setError(errorMsg);
    } finally {
      setCreating(false);
    }
  };

  const handleEditTenant = (tenant: TenantWithChildren) => {
    setEditingTenant(tenant);
    setNewTenantName(tenant.name);
    setShowEditModal(true);
  };

  const handleUpdateTenant = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!editingTenant || !newTenantName) return;
    
    setCreating(true);
    try {
      await api.updateTenant(editingTenant.id, { name: newTenantName });
      await fetchTenants(searchQuery.trim() || undefined);
      setShowEditModal(false);
      setEditingTenant(null);
      setNewTenantName('');
      showToast('Tenant updated', 'success');
    } catch (err) {
      console.error('Error updating tenant:', err);
      showToast('Failed to update tenant', 'error');
    } finally {
      setCreating(false);
    }
  };

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

  const openCreateModal = (parentId?: string) => {
    setNewTenantParentId(parentId || '');
    setNewTenantName('');
    setError(null);
    setShowCreateModal(true);
  };

  const flattenTenantOptions = (nodes: TenantWithChildren[], depth = 0): { id: string; name: string }[] => {
    return nodes.flatMap((node) => [
      { id: node.id, name: `${'  '.repeat(depth)}${depth > 0 ? '- ' : ''}${node.name}` },
      ...(node.children ? flattenTenantOptions(node.children, depth + 1) : []),
    ]);
  };

  const allTenants = flattenTenants(tenants);
  const selectedLineageDefaultsTenant = allTenants.find((t) => t.id === lineageDefaultsTenantId);
  const selectedTenantConflictTelemetry = lineageDefaultsTenantId ? conflictTelemetryByTenant[lineageDefaultsTenantId] : undefined;
  const pollStatusClass = lineagePollStatus === 'OFFLINE'
    ? 'border-red-300 bg-red-50 text-red-800'
    : lineagePollStatus === 'STALE'
      ? 'border-amber-300 bg-amber-50 text-amber-900'
      : lineagePollStatus === 'FRESH'
        ? 'border-emerald-300 bg-emerald-50 text-emerald-900'
        : 'border-slate-300 bg-slate-50 text-slate-700';

  const resolveConflictUseLatest = () => {
    if (!lineageDefaultsConflict) return;
    setLineageDefaults(lineageDefaultsConflict.latest);
    setLineageDefaultsBase(toLineageDefaultsConfig(lineageDefaultsConflict.latest));
    setLineageDefaultsEtag(undefined);
    setLineageDefaultsStaleLatest(null);
    setLineagePollStatus('FRESH');
    setLineagePollCheckedAt(new Date().toISOString());
    setLineageDefaultsConflict(null);
    showToast('Loaded latest server values', 'success');
  };

  const resolveConflictReapplyMine = async () => {
    if (!lineageDefaultsTenantId || !lineageDefaultsConflict) return;
    setSavingLineageDefaults(true);
    try {
      const updated = await api.updateTenantLineageDefaults(
        lineageDefaultsTenantId,
        lineageDefaultsConflict.pending,
        lineageDefaultsConflict.latest.version
      );
      setLineageDefaults(updated);
      setLineageDefaultsBase(toLineageDefaultsConfig(updated));
      setLineageDefaultsEtag(undefined);
      setLineageDefaultsStaleLatest(null);
      setLineagePollStatus('FRESH');
      setLineagePollCheckedAt(new Date().toISOString());
      setLineageDefaultsConflict(null);
      showToast('Reapplied your defaults successfully', 'success');
    } catch {
      showToast('Failed to reapply your defaults', 'error');
    } finally {
      setSavingLineageDefaults(false);
    }
  };

  const resolveConflictSaveMerged = async () => {
    if (!lineageDefaultsTenantId || !lineageDefaultsConflict) return;
    const merged: TenantLineageDefaultsConfig = {
      safeScopePolicy: lineageDefaultsConflict.mergeChoice.safeScopePolicy === 'MINE' ? lineageDefaultsConflict.pending.safeScopePolicy : lineageDefaultsConflict.latest.safeScopePolicy,
      defaultFetchMode: lineageDefaultsConflict.mergeChoice.defaultFetchMode === 'MINE' ? lineageDefaultsConflict.pending.defaultFetchMode : lineageDefaultsConflict.latest.defaultFetchMode,
      defaultDepth: lineageDefaultsConflict.mergeChoice.defaultDepth === 'MINE' ? lineageDefaultsConflict.pending.defaultDepth : lineageDefaultsConflict.latest.defaultDepth,
      maxDepthCap: lineageDefaultsConflict.mergeChoice.maxDepthCap === 'MINE' ? lineageDefaultsConflict.pending.maxDepthCap : lineageDefaultsConflict.latest.maxDepthCap,
    };
    setSavingLineageDefaults(true);
    try {
      const updated = await api.updateTenantLineageDefaults(
        lineageDefaultsTenantId,
        merged,
        lineageDefaultsConflict.latest.version
      );
      setLineageDefaults(updated);
      setLineageDefaultsBase(toLineageDefaultsConfig(updated));
      setLineageDefaultsEtag(undefined);
      setLineageDefaultsStaleLatest(null);
      setLineagePollStatus('FRESH');
      setLineagePollCheckedAt(new Date().toISOString());
      setLineageDefaultsConflict(null);
      showToast('Saved merged defaults', 'success');
    } catch {
      showToast('Failed to save merged defaults', 'error');
    } finally {
      setSavingLineageDefaults(false);
    }
  };

  const renderTenantTree = (tenant: TenantWithChildren, depth: number = 0) => {
    const hasChildren = tenant.children && tenant.children.length > 0;
    const isRoot = !tenant.parentId;
    const isExpanded = expandedTenantIds[tenant.id] ?? true;

    return (
      <div key={tenant.id} style={{ marginLeft: depth * 24 }}>
        <div className="card mb-2">
          <div className="flex justify-between items-start">
            <div>
              <div className="flex items-center space-x-2">
                {hasChildren && (
                  <button
                    onClick={() => setExpandedTenantIds((prev) => ({ ...prev, [tenant.id]: !isExpanded }))}
                    className="text-xs px-1.5 py-0.5 rounded border border-gray-300 text-gray-600 hover:bg-gray-50"
                    title={isExpanded ? 'Collapse subtree' : 'Expand subtree'}
                  >
                    {isExpanded ? '−' : '+'}
                  </button>
                )}
                <h3 className="text-lg font-semibold">{tenant.name}</h3>
              </div>
              <p className="text-sm text-gray-500">ID: {tenant.id}</p>
            </div>
            <div className="flex space-x-2">
              {!isRoot && (
                <button 
                  onClick={() => openCreateModal(tenant.id)}
                  className="text-green-600 hover:text-green-700 text-sm"
                  title="Add sub-tenant"
                >
                  + Add
                </button>
              )}
              <button onClick={() => handleEditTenant(tenant)} className="btn-secondary text-sm">Edit</button>
              {confirmDeleteId === tenant.id ? (
                <div className="flex items-center space-x-2">
                  <span className="text-xs text-gray-500">Delete?</span>
                  <button
                    onClick={() => handleDeleteTenant(tenant.id)}
                    disabled={deletingId === tenant.id}
                    className="text-red-600 hover:text-red-700 text-sm"
                  >
                    {deletingId === tenant.id ? 'Deleting...' : 'Yes'}
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

      <div className="flex justify-between items-center mb-6">
        <h1 className="text-2xl font-bold text-gray-900">Tenants</h1>
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
            Expand All
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
            Collapse All
          </button>
          <button 
            onClick={() => openCreateModal()} 
            className="btn-primary"
            disabled={!user?.superadmin || tenants.length > 0}
            title={!user?.superadmin ? 'Only superadmin can create root tenants' : tenants.length > 0 ? 'Root tenant already exists. Add sub-tenants instead.' : 'Create root tenant'}
          >
            Create Root Tenant
          </button>
        </div>
      </div>

      <div className="mb-4">
        <input
          type="text"
          value={searchQuery}
          onChange={(e) => setSearchQuery(e.target.value)}
          className="input w-full md:max-w-md"
          placeholder="Search tenants by name"
        />
      </div>

      <div className="card mb-6 border border-indigo-100 bg-indigo-50">
        <div className="flex flex-col gap-3">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <h2 className="text-lg font-semibold text-indigo-900">Lineage Defaults</h2>
            <div className="flex flex-wrap items-center gap-2 text-xs text-indigo-700">
              {selectedLineageDefaultsTenant?.updatedAt && (
                <span>
                  Tenant updated: {new Date(selectedLineageDefaultsTenant.updatedAt).toLocaleString()}
                </span>
              )}
              <span className={`px-2 py-0.5 rounded border ${pollStatusClass}`}>
                Poll: {lineagePollStatus.toLowerCase()}
              </span>
              {lineagePollCheckedAt && (
                <span>
                  Checked: {new Date(lineagePollCheckedAt).toLocaleTimeString()}
                </span>
              )}
              <button
                className="text-indigo-700 hover:text-indigo-900"
                disabled={checkingLineagePollNow || Date.now() < lineagePollCooldownUntil || !lineageDefaultsTenantId}
                onClick={() => { void checkLineageDefaultsNow(); }}
              >
                {checkingLineagePollNow ? 'Checking...' : 'Check now'}
              </button>
              {selectedTenantConflictTelemetry && (
                <>
                  <span className="px-2 py-0.5 rounded border border-amber-300 bg-amber-50 text-amber-900">
                    Conflicts: {selectedTenantConflictTelemetry.count}
                  </span>
                  {selectedTenantConflictTelemetry.lastConflictAt && (
                    <span>
                      Last: {new Date(selectedTenantConflictTelemetry.lastConflictAt).toLocaleString()}
                    </span>
                  )}
                  <button
                    className="text-indigo-700 hover:text-indigo-900"
                    onClick={() => lineageDefaultsTenantId && clearDefaultsConflictTelemetry(lineageDefaultsTenantId)}
                  >
                    Clear
                  </button>
                </>
              )}
            </div>
          </div>
          <p className="text-sm text-indigo-800">
            Configure tenant-wide defaults for pipeline lineage triage. These act as baseline behavior across pipelines.
          </p>
          {lineageDefaultsStaleLatest && isLineageDefaultsDirty && (
            <div className="rounded border border-amber-300 bg-amber-50 px-3 py-2 text-xs text-amber-900 flex flex-wrap items-center gap-2">
              <span>Defaults may be stale. Another user updated this tenant while you were editing.</span>
              <button
                className="px-2 py-0.5 rounded border border-amber-400 bg-white"
                onClick={() => {
                  setLineageDefaults(lineageDefaultsStaleLatest);
                  setLineageDefaultsBase(toLineageDefaultsConfig(lineageDefaultsStaleLatest));
                  setLineageDefaultsStaleLatest(null);
                  setLineagePollStatus('FRESH');
                  setLineagePollCheckedAt(new Date().toISOString());
                  showToast('Loaded latest tenant defaults', 'success');
                }}
              >
                Refresh latest
              </button>
              <button
                className="px-2 py-0.5 rounded border border-amber-400 bg-white"
                onClick={() => {
                  const pending = toLineageDefaultsConfig(lineageDefaults);
                  setLineageDefaultsConflict(buildConflictState(pending, lineageDefaultsStaleLatest));
                }}
              >
                Resolve now
              </button>
            </div>
          )}
          <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
            <label className="text-sm text-indigo-900">
              Tenant
              <select
                className="input mt-1"
                value={lineageDefaultsTenantId}
                onChange={(e) => setLineageDefaultsTenantId(e.target.value)}
              >
                {flattenTenantOptions(tenants).map((option) => (
                  <option key={option.id} value={option.id}>{option.name}</option>
                ))}
              </select>
            </label>
            <label className="text-sm text-indigo-900">
              Safe Scope Policy
              <select
                className="input mt-1"
                value={lineageDefaults.safeScopePolicy}
                onChange={(e) => setLineageDefaults((prev) => ({ ...prev, safeScopePolicy: e.target.value as TenantLineageDefaults['safeScopePolicy'] }))}
                disabled={loadingLineageDefaults}
              >
                <option value="AUTO">AUTO</option>
                <option value="RUN">RUN</option>
                <option value="PIPELINE">PIPELINE</option>
              </select>
            </label>
            <label className="text-sm text-indigo-900">
              Default Fetch Mode
              <select
                className="input mt-1"
                value={lineageDefaults.defaultFetchMode}
                onChange={(e) => setLineageDefaults((prev) => ({ ...prev, defaultFetchMode: e.target.value as TenantLineageDefaults['defaultFetchMode'] }))}
                disabled={loadingLineageDefaults}
              >
                <option value="SUMMARY">SUMMARY</option>
                <option value="DETAILS">DETAILS</option>
              </select>
            </label>
            <label className="text-sm text-indigo-900">
              Default Depth
              <input
                type="number"
                min={1}
                max={200}
                className="input mt-1"
                value={lineageDefaults.defaultDepth}
                onChange={(e) => setLineageDefaults((prev) => ({ ...prev, defaultDepth: Number(e.target.value || 20) }))}
                disabled={loadingLineageDefaults}
              />
            </label>
            <label className="text-sm text-indigo-900">
              Max Depth Cap
              <input
                type="number"
                min={20}
                max={200}
                className="input mt-1"
                value={lineageDefaults.maxDepthCap}
                onChange={(e) => setLineageDefaults((prev) => ({ ...prev, maxDepthCap: Number(e.target.value || 200) }))}
                disabled={loadingLineageDefaults}
              />
            </label>
          </div>
          <div className="flex flex-wrap gap-2">
            <button
              className="btn-primary"
              disabled={savingLineageDefaults || loadingLineageDefaults || !lineageDefaultsTenantId}
              onClick={() => { void saveLineageDefaults(); }}
            >
              {savingLineageDefaults ? 'Saving...' : 'Save Defaults'}
            </button>
            <button
              className="btn-secondary"
              disabled={loadingLineageDefaults}
              onClick={() => setLineageDefaults(DEFAULT_TENANT_LINEAGE_DEFAULTS)}
            >
              Reset Form
            </button>
          </div>
          <div className="rounded border border-indigo-200 bg-white p-3">
            <div className="flex items-center justify-between mb-2">
              <h3 className="text-sm font-semibold text-indigo-900">Recent Changes</h3>
              <span className="text-xs text-indigo-700">{lineageDefaults.history?.length ?? 0} entries</span>
            </div>
            {(lineageDefaults.history && lineageDefaults.history.length > 0) ? (
              <div className="space-y-2 max-h-48 overflow-auto pr-1">
                {lineageDefaults.history.slice().reverse().map((entry, idx) => (
                  <div key={`${entry.changedAt}-${idx}`} className="flex flex-wrap items-center justify-between gap-2 border border-gray-200 rounded px-2 py-1 text-xs">
                    <div className="text-gray-700">
                      <span className="font-medium">{new Date(entry.changedAt).toLocaleString()}</span>
                      {' · '}
                      <span>{entry.changedByEmail || entry.changedByUserId || 'unknown user'}</span>
                      {' · '}
                      <span>{entry.defaults.defaultFetchMode} d{entry.defaults.defaultDepth} cap d{entry.defaults.maxDepthCap} {entry.defaults.safeScopePolicy.toLowerCase()}</span>
                      {entry.changedFields && entry.changedFields.length > 0 && (
                        <span className="ml-2 inline-flex flex-wrap gap-1">
                          {entry.changedFields.map((field) => (
                            <span key={`${entry.changedAt}-${field}`} className="px-1.5 py-0.5 rounded border border-indigo-200 bg-indigo-50 text-indigo-800">
                              {field}
                            </span>
                          ))}
                        </span>
                      )}
                    </div>
                    <div className="flex items-center gap-2">
                      <button
                        className="text-indigo-700 hover:text-indigo-900"
                        onClick={() => setLineageDefaults((prev) => ({ ...prev, ...entry.defaults }))}
                      >
                        Apply to form
                      </button>
                      <button
                        className="text-indigo-700 hover:text-indigo-900"
                        disabled={savingLineageDefaults}
                        onClick={() => { void restoreAndSaveLineageDefaults(entry.defaults); }}
                      >
                        Restore + Save
                      </button>
                    </div>
                  </div>
                ))}
              </div>
            ) : (
              <p className="text-xs text-gray-500">No lineage defaults changes recorded yet.</p>
            )}
          </div>
        </div>
      </div>

      {tenants.length === 0 && !loading && (
        <div className="text-center py-12 text-gray-500">
          <p className="text-lg mb-2">No tenants yet</p>
          <p>Create your first (root) tenant to get started</p>
        </div>
      )}

      {loading ? (
        <div className="space-y-4">
          {[1, 2, 3].map(i => (
            <div key={i} className="card animate-pulse">
              <div className="h-4 bg-gray-200 rounded w-1/3"></div>
            </div>
          ))}
        </div>
      ) : (
        <div>
          {tenants.map(tenant => renderTenantTree(tenant))}
        </div>
      )}

      {showCreateModal && (
        <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
          <div className="bg-white rounded-lg p-6 max-w-md w-full">
            <h2 className="text-xl font-bold mb-4">
              {newTenantParentId ? 'Create Sub-Tenant' : 'Create Root Tenant'}
            </h2>
            {error && (
              <div className="mb-4 p-3 bg-red-100 text-red-700 rounded text-sm">
                {error}
              </div>
            )}
            <form onSubmit={handleCreateTenant}>
              <div className="mb-4">
                <label className="block text-sm font-medium text-gray-700 mb-1">Name</label>
                <input
                  type="text"
                  value={newTenantName}
                  onChange={(e) => setNewTenantName(e.target.value)}
                  className="input w-full"
                  required
                />
              </div>
              <div className="mb-4">
                <label className="block text-sm font-medium text-gray-700 mb-1">Parent Tenant</label>
                <select
                  value={newTenantParentId}
                  onChange={(e) => setNewTenantParentId(e.target.value)}
                  className="input w-full"
                >
                  <option value="">Root Tenant</option>
                  {flattenTenantOptions(tenants).map((option) => (
                    <option key={option.id} value={option.id}>{option.name}</option>
                  ))}
                </select>
              </div>
              <div className="flex justify-end space-x-2">
                <button
                  type="button"
                  onClick={() => setShowCreateModal(false)}
                  className="px-4 py-2 border border-gray-300 rounded-md text-gray-700 hover:bg-gray-50"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  disabled={creating}
                  className="btn-primary"
                >
                  {creating ? 'Creating...' : 'Create'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {lineageDefaultsConflict && (
        <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
          <div className="bg-white rounded-lg p-6 max-w-2xl w-full">
            <h2 className="text-xl font-bold mb-2">Lineage Defaults Conflict</h2>
            <p className="text-sm text-gray-600 mb-4">
              Another user updated these defaults while you were editing. Choose how to resolve the conflict.
            </p>
            <div className="space-y-2 mb-4">
              {LINEAGE_DEFAULT_FIELDS.map((field) => (
                <div key={`conflict-${field}`} className="border border-gray-200 rounded p-2">
                  <div className="text-xs font-semibold text-gray-700 mb-1">{field}</div>
                  <div className="text-xs text-gray-600 mb-2">
                    <span className="mr-3">Latest: {String(lineageDefaultsConflict.latest[field])}</span>
                    <span>Mine: {String(lineageDefaultsConflict.pending[field])}</span>
                  </div>
                  <div className="flex gap-2">
                    <button
                      className={`px-2 py-0.5 rounded border ${lineageDefaultsConflict.mergeChoice[field] === 'LATEST' ? 'border-indigo-500 bg-indigo-100 text-indigo-900' : 'border-gray-300 bg-white text-gray-700'}`}
                      onClick={() => setLineageDefaultsConflict((prev) => prev ? ({ ...prev, mergeChoice: { ...prev.mergeChoice, [field]: 'LATEST' } }) : prev)}
                    >
                      Use latest
                    </button>
                    <button
                      className={`px-2 py-0.5 rounded border ${lineageDefaultsConflict.mergeChoice[field] === 'MINE' ? 'border-indigo-500 bg-indigo-100 text-indigo-900' : 'border-gray-300 bg-white text-gray-700'}`}
                      onClick={() => setLineageDefaultsConflict((prev) => prev ? ({ ...prev, mergeChoice: { ...prev.mergeChoice, [field]: 'MINE' } }) : prev)}
                    >
                      Use mine
                    </button>
                  </div>
                </div>
              ))}
            </div>
            <div className="flex justify-end gap-2">
              <button
                className="px-4 py-2 border border-gray-300 rounded-md text-gray-700 hover:bg-gray-50"
                onClick={() => setLineageDefaultsConflict(null)}
              >
                Close
              </button>
              <button
                className="px-4 py-2 border border-gray-300 rounded-md text-gray-700 hover:bg-gray-50"
                onClick={resolveConflictUseLatest}
              >
                Use Latest
              </button>
              <button
                className="px-4 py-2 border border-gray-300 rounded-md text-gray-700 hover:bg-gray-50"
                disabled={savingLineageDefaults}
                onClick={() => { void resolveConflictReapplyMine(); }}
              >
                Reapply Mine
              </button>
              <button
                className="btn-primary"
                disabled={savingLineageDefaults}
                onClick={() => { void resolveConflictSaveMerged(); }}
              >
                Merge And Save
              </button>
            </div>
          </div>
        </div>
      )}

      {showEditModal && editingTenant && (
        <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
          <div className="bg-white rounded-lg p-6 max-w-md w-full">
            <h2 className="text-xl font-bold mb-4">Edit Tenant</h2>
            <form onSubmit={handleUpdateTenant}>
              <div className="mb-4">
                <label className="block text-sm font-medium text-gray-700 mb-1">Name</label>
                <input
                  type="text"
                  value={newTenantName}
                  onChange={(e) => setNewTenantName(e.target.value)}
                  className="input w-full"
                  required
                />
              </div>
              <div className="flex justify-end space-x-2">
                <button
                  type="button"
                  onClick={() => { setShowEditModal(false); setEditingTenant(null); }}
                  className="px-4 py-2 border border-gray-300 rounded-md text-gray-700 hover:bg-gray-50"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  disabled={creating}
                  className="btn-primary"
                >
                  {creating ? 'Saving...' : 'Save'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}
