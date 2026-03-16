import { useEffect, useState } from 'react';
import { useAuthStore } from '../stores/auth';
import api from '../services/api';
import type { Tenant } from '../types';

export default function Tenants() {
  const { user } = useAuthStore();
  const [tenants, setTenants] = useState<Tenant[]>([]);
  const [loading, setLoading] = useState(true);
  const [showCreateModal, setShowCreateModal] = useState(false);
  const [showEditModal, setShowEditModal] = useState(false);
  const [editingTenant, setEditingTenant] = useState<Tenant | null>(null);
  const [newTenantName, setNewTenantName] = useState('');
  const [newTenantParentId, setNewTenantParentId] = useState('');
  const [creating, setCreating] = useState(false);
  const [deletingId, setDeletingId] = useState<string | null>(null);

  useEffect(() => {
    const fetchTenants = async () => {
      try {
        const data = await api.getTenants();
        setTenants(data);
      } catch (error) {
        console.error('Error fetching tenants:', error);
      } finally {
        setLoading(false);
      }
    };
    
    fetchTenants();
  }, []);

  const handleCreateTenant = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!newTenantName) return;
    
    setCreating(true);
    try {
      const newTenant = await api.createTenant(newTenantName, newTenantParentId || undefined);
      setShowCreateModal(false);
      setNewTenantName('');
      setNewTenantParentId('');
      setTenants([...tenants, newTenant]);
    } catch (error) {
      console.error('Error creating tenant:', error);
    } finally {
      setCreating(false);
    }
  };

  const handleEditTenant = (tenant: Tenant) => {
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
      setTenants(tenants.map(t => t.id === editingTenant.id ? { ...t, name: newTenantName } : t));
      setShowEditModal(false);
      setEditingTenant(null);
      setNewTenantName('');
    } catch (error) {
      console.error('Error updating tenant:', error);
    } finally {
      setCreating(false);
    }
  };

  const handleDeleteTenant = async (tenantId: string) => {
    if (!confirm('Are you sure you want to delete this tenant?')) return;
    
    setDeletingId(tenantId);
    try {
      await api.deleteTenant(tenantId);
      setTenants(tenants.filter(t => t.id !== tenantId));
    } catch (error) {
      console.error('Error deleting tenant:', error);
    } finally {
      setDeletingId(null);
    }
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
        <button onClick={() => setShowCreateModal(true)} className="btn-primary">
          Create Tenant
        </button>
      </div>

      {loading ? (
        <div className="space-y-4">
          {[1, 2, 3].map(i => (
            <div key={i} className="card animate-pulse">
              <div className="h-4 bg-gray-200 rounded w-1/3"></div>
            </div>
          ))}
        </div>
      ) : (
        <div className="space-y-4">
          {tenants.map((tenant) => (
            <div key={tenant.id} className="card">
              <div className="flex justify-between items-start">
                <div>
                  <h3 className="text-lg font-semibold">{tenant.name}</h3>
                  <p className="text-sm text-gray-500">ID: {tenant.id}</p>
                  {tenant.parentId && (
                    <p className="text-sm text-gray-500">Parent: {tenant.parentId}</p>
                  )}
                </div>
                <div className="flex space-x-2">
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
              {tenant.children && tenant.children.length > 0 && (
                <div className="mt-4 pl-4 border-l-2 border-gray-200">
                  <p className="text-sm font-medium text-gray-700 mb-2">Child Tenants:</p>
                  <div className="space-y-2">
                    {tenant.children.map((child) => (
                      <div key={child.id} className="text-sm text-gray-600">
                        {child.name}
                      </div>
                    ))}
                  </div>
                </div>
              )}
            </div>
          ))}
        </div>
      )}

      {showCreateModal && (
        <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
          <div className="bg-white rounded-lg p-6 max-w-md w-full">
            <h2 className="text-xl font-bold mb-4">Create Tenant</h2>
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
                <label className="block text-sm font-medium text-gray-700 mb-1">Parent Tenant (optional)</label>
                <select
                  value={newTenantParentId}
                  onChange={(e) => setNewTenantParentId(e.target.value)}
                  className="input w-full"
                >
                  <option value="">None (Root)</option>
                  {tenants.map((t) => (
                    <option key={t.id} value={t.id}>{t.name}</option>
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
