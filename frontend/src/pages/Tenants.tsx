import { useEffect, useState } from 'react';
import { useAuthStore } from '../stores/auth';
import api from '../services/api';
import type { Tenant } from '../types';

interface TenantWithChildren extends Tenant {
  children?: TenantWithChildren[];
}

export default function Tenants() {
  const { user } = useAuthStore();
  const [tenants, setTenants] = useState<TenantWithChildren[]>([]);
  const [loading, setLoading] = useState(true);
  const [showCreateModal, setShowCreateModal] = useState(false);
  const [showEditModal, setShowEditModal] = useState(false);
  const [editingTenant, setEditingTenant] = useState<TenantWithChildren | null>(null);
  const [newTenantName, setNewTenantName] = useState('');
  const [newTenantParentId, setNewTenantParentId] = useState('');
  const [creating, setCreating] = useState(false);
  const [deletingId, setDeletingId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const fetchTenants = async () => {
    try {
      const rootTenants = await api.getTenants();
      const tenantsWithChildren: TenantWithChildren[] = await Promise.all(
        rootTenants.map(async (t) => {
          try {
            const children = await api.getTenantChildren(t.id);
            return { ...t, children: children as TenantWithChildren[] };
          } catch {
            return { ...t, children: [] };
          }
        })
      );
      setTenants(tenantsWithChildren);
    } catch (err) {
      console.error('Error fetching tenants:', err);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchTenants();
  }, []);

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
      await fetchTenants();
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
      await fetchTenants();
      setShowEditModal(false);
      setEditingTenant(null);
      setNewTenantName('');
    } catch (err) {
      console.error('Error updating tenant:', err);
    } finally {
      setCreating(false);
    }
  };

  const handleDeleteTenant = async (tenantId: string) => {
    const tenant = tenants.find(t => t.id === tenantId);
    const isRoot = !tenant?.parentId;
    const rootCount = tenants.length;
    
    if (isRoot && rootCount <= 1) {
      alert('Cannot delete the last root tenant');
      return;
    }
    
    if (!confirm('Are you sure you want to delete this tenant and all its children?')) return;
    
    setDeletingId(tenantId);
    try {
      await api.deleteTenant(tenantId);
      await fetchTenants();
    } catch (err) {
      console.error('Error deleting tenant:', err);
      alert('Failed to delete tenant');
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

  const renderTenantTree = (tenant: TenantWithChildren, depth: number = 0) => {
    const hasChildren = tenant.children && tenant.children.length > 0;
    const isRoot = !tenant.parentId;

    return (
      <div key={tenant.id} style={{ marginLeft: depth * 24 }}>
        <div className="card mb-2">
          <div className="flex justify-between items-start">
            <div>
              <h3 className="text-lg font-semibold">{tenant.name}</h3>
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
              <button 
                onClick={() => handleDeleteTenant(tenant.id)}
                disabled={deletingId === tenant.id}
                className="text-red-600 hover:text-red-700 text-sm"
              >
                {deletingId === tenant.id ? 'Deleting...' : 'Delete'}
              </button>
            </div>
          </div>
        </div>
        {hasChildren && (
          <div className="border-l-2 border-gray-200 ml-4 pl-4">
            {tenant.children!.map(child => renderTenantTree(child, depth + 1))}
          </div>
        )}
      </div>
    );
  };

  if (!user?.superadmin) {
    return (
      <div className="px-4 py-6">
        <p className="text-gray-500">Access denied. Admin privileges required.</p>
      </div>
    );
  }

  return (
    <div className="px-4 py-6 sm:px-0">
      <div className="flex justify-between items-center mb-6">
        <h1 className="text-2xl font-bold text-gray-900">Tenants</h1>
        <button 
          onClick={() => openCreateModal()} 
          className="btn-primary"
          disabled={tenants.length > 0}
          title={tenants.length > 0 ? "Root tenant already exists. Add sub-tenants instead." : "Create root tenant"}
        >
          Create Root Tenant
        </button>
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
                  {tenants.flatMap(t => [
                    <option key={t.id} value={t.id}>{t.name}</option>,
                    ...(t.children || []).map(c => (
                      <option key={c.id} value={c.id}>  - {t.name} / {c.name}</option>
                    ))
                  ])}
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
