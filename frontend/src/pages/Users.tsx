import { useEffect, useState } from 'react';
import { useAuthStore } from '../stores/auth';
import api from '../services/api';
import type { Membership, Role, User } from '../types';

interface EditState {
  membershipId: string;
  roleId: string;
}

export default function Users() {
  const { user } = useAuthStore();
  const [users, setUsers] = useState<User[]>([]);
  const [memberships, setMemberships] = useState<Membership[]>([]);
  const [roles, setRoles] = useState<Role[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [toast, setToast] = useState<{ message: string; type: 'success' | 'error' } | null>(null);
  const [showInviteModal, setShowInviteModal] = useState(false);
  const [newUserEmail, setNewUserEmail] = useState('');
  const [newUserPassword, setNewUserPassword] = useState('');
  const [newUserFirstName, setNewUserFirstName] = useState('');
  const [newUserLastName, setNewUserLastName] = useState('');
  const [newUserRoleId, setNewUserRoleId] = useState('');
  const [inviting, setInviting] = useState(false);
  const [deletingId, setDeletingId] = useState<string | null>(null);
  const [confirmDeleteId, setConfirmDeleteId] = useState<string | null>(null);
  const [editState, setEditState] = useState<EditState | null>(null);
  const [savingEdit, setSavingEdit] = useState(false);

  const tenantId = user?.tenantId;

  const showToast = (message: string, type: 'success' | 'error') => {
    setToast({ message, type });
    setTimeout(() => setToast(null), 3500);
  };

  const loadData = async (tid: string) => {
    const [usersData, membershipsData, rolesData] = await Promise.all([
      api.getUsers(tid),
      api.getTenantMemberships(tid),
      api.getRoles(tid),
    ]);
    setUsers(usersData);
    setMemberships(membershipsData);
    setRoles(rolesData);
    return rolesData;
  };

  useEffect(() => {
    const init = async () => {
      if (!tenantId) { setLoading(false); return; }
      try {
        const loadedRoles = await loadData(tenantId);
        const defaultRole = loadedRoles.find((r) => r.isDefault) ?? loadedRoles[0];
        if (defaultRole) setNewUserRoleId(defaultRole.id);
      } catch {
        setError('Failed to load users and memberships');
      } finally {
        setLoading(false);
      }
    };
    init();
  }, [tenantId]);

  const getMembershipForUser = (userId: string) => memberships.find((m) => m.authUserId === userId);
  const getRoleForMembership = (m?: Membership) => roles.find((r) => r.id === m?.roleId);
  const parseAbilities = (abilities: Membership['abilities']): string[] => {
    if (!abilities) return [];
    if (Array.isArray(abilities)) return abilities as string[];
    try { const p = JSON.parse(abilities as string); return Array.isArray(p) ? p : []; } catch { return []; }
  };

  const handleInviteUser = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!tenantId || !newUserEmail || !newUserPassword) return;
    setInviting(true);
    try {
      const createdUser = await api.createUser(tenantId, newUserEmail, newUserPassword, {
        firstName: newUserFirstName || undefined,
        lastName: newUserLastName || undefined,
      });
      const roleId = newUserRoleId || roles.find((r) => r.isDefault)?.id || roles[0]?.id;
      if (!roleId) { setError('No roles found — create a role first.'); return; }
      await api.createTenantMembership(tenantId, createdUser.id, roleId);
      setShowInviteModal(false);
      setNewUserEmail(''); setNewUserPassword('');
      setNewUserFirstName(''); setNewUserLastName('');
      await loadData(tenantId);
      showToast('User invited successfully', 'success');
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: { error?: string } } })?.response?.data?.error ?? 'Failed to create user or membership';
      setError(msg);
    } finally {
      setInviting(false);
    }
  };

  const handleSaveRoleEdit = async () => {
    if (!tenantId || !editState) return;
    setSavingEdit(true);
    try {
      await api.updateTenantMembership(tenantId, editState.membershipId, editState.roleId);
      await loadData(tenantId);
      setEditState(null);
      showToast('Role updated', 'success');
    } catch {
      showToast('Failed to update role', 'error');
    } finally {
      setSavingEdit(false);
    }
  };

  const handleDeleteMembership = async (membershipId: string) => {
    if (!tenantId) return;
    setDeletingId(membershipId);
    try {
      await api.deleteTenantMembership(tenantId, membershipId);
      await loadData(tenantId);
      setConfirmDeleteId(null);
      showToast('Membership removed', 'success');
    } catch {
      showToast('Failed to remove membership', 'error');
    } finally {
      setDeletingId(null);
    }
  };

  return (
    <div className="px-4 py-6 sm:px-0">
      {/* Toast */}
      {toast && (
        <div className={`fixed top-4 right-4 z-50 px-4 py-3 rounded shadow-lg text-white text-sm ${
          toast.type === 'success' ? 'bg-green-600' : 'bg-red-600'
        }`}>
          {toast.message}
        </div>
      )}

      <div className="flex justify-between items-center mb-6">
        <h1 className="text-2xl font-bold text-gray-900">Users</h1>
        <button onClick={() => setShowInviteModal(true)} className="btn-primary">Invite User</button>
      </div>

      {error && (
        <div className="mb-4 rounded border border-red-200 bg-red-50 px-4 py-3 text-red-700 flex justify-between items-center">
          <span>{error}</span>
          <button onClick={() => setError('')} className="text-red-400 hover:text-red-600 ml-4">✕</button>
        </div>
      )}

      {loading ? (
        <div className="space-y-4">
          {[1, 2, 3].map(i => (
            <div key={i} className="card animate-pulse"><div className="h-4 bg-gray-200 rounded w-1/3"></div></div>
          ))}
        </div>
      ) : !tenantId ? (
        <div className="card text-center py-12">
          <p className="text-gray-500">No tenant ID found.</p>
        </div>
      ) : users.length === 0 ? (
        <div className="card text-center py-12">
          <p className="text-gray-500 mb-4">No users yet</p>
          <button onClick={() => setShowInviteModal(true)} className="btn-primary">Invite First User</button>
        </div>
      ) : (
        <div className="card overflow-hidden">
          <table className="min-w-full divide-y divide-gray-200">
            <thead className="bg-gray-50">
              <tr>
                <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">User</th>
                <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Role</th>
                <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Abilities</th>
                <th className="px-6 py-3 text-right text-xs font-medium text-gray-500 uppercase tracking-wider">Actions</th>
              </tr>
            </thead>
            <tbody className="bg-white divide-y divide-gray-200">
              {users.map((u) => {
                const membership = getMembershipForUser(u.id);
                const role = getRoleForMembership(membership);
                const abilities = parseAbilities(membership?.abilities);
                const isEditingThis = editState?.membershipId === membership?.id;
                const isConfirmingDelete = confirmDeleteId === membership?.id;
                const isDeleting = deletingId === membership?.id;

                return (
                  <tr key={u.id}>
                    <td className="px-6 py-4 whitespace-nowrap">
                      <div className="flex items-center">
                        <div className="h-10 w-10 rounded-full bg-primary-100 flex items-center justify-center">
                          <span className="text-primary-600 font-medium">{u.email[0].toUpperCase()}</span>
                        </div>
                        <div className="ml-4">
                          <div className="text-sm font-medium text-gray-900">{u.email}</div>
                          {(u.firstName || u.lastName) && (
                            <div className="text-sm text-gray-500">{u.firstName} {u.lastName}</div>
                          )}
                        </div>
                      </div>
                    </td>
                    <td className="px-6 py-4 whitespace-nowrap">
                      {isEditingThis ? (
                        <div className="flex items-center space-x-2">
                          <select
                            value={editState!.roleId}
                            onChange={(e) => setEditState({ membershipId: editState!.membershipId, roleId: e.target.value })}
                            className="input text-sm py-1"
                          >
                            {roles.map((r) => (
                              <option key={r.id} value={r.id}>{r.name}</option>
                            ))}
                          </select>
                          <button
                            onClick={handleSaveRoleEdit}
                            disabled={savingEdit}
                            className="text-xs text-green-600 hover:text-green-800 font-medium"
                          >
                            {savingEdit ? '…' : 'Save'}
                          </button>
                          <button
                            onClick={() => setEditState(null)}
                            className="text-xs text-gray-500 hover:text-gray-700"
                          >
                            ✕
                          </button>
                        </div>
                      ) : (
                        <span className="px-2 py-1 text-xs font-medium rounded bg-gray-100 text-gray-800">
                          {role?.name ?? 'No Role'}
                        </span>
                      )}
                    </td>
                    <td className="px-6 py-4">
                      <div className="flex flex-wrap gap-1">
                        {abilities.slice(0, 3).map((a) => (
                          <span key={a} className="px-2 py-0.5 text-xs rounded bg-blue-100 text-blue-800">{a}</span>
                        ))}
                        {abilities.length > 3 && (
                          <span className="px-2 py-0.5 text-xs rounded bg-gray-100 text-gray-600">
                            +{abilities.length - 3}
                          </span>
                        )}
                      </div>
                    </td>
                    <td className="px-6 py-4 whitespace-nowrap text-right text-sm font-medium">
                      {isConfirmingDelete ? (
                        <span className="space-x-2">
                          <span className="text-gray-600 text-xs">Remove?</span>
                          <button
                            onClick={() => handleDeleteMembership(membership!.id)}
                            disabled={isDeleting}
                            className="text-red-600 hover:text-red-900"
                          >
                            {isDeleting ? '…' : 'Yes'}
                          </button>
                          <button onClick={() => setConfirmDeleteId(null)} className="text-gray-500 hover:text-gray-700">No</button>
                        </span>
                      ) : (
                        <span className="space-x-3">
                          {membership && !isEditingThis && (
                            <button
                              onClick={() => setEditState({ membershipId: membership.id, roleId: membership.roleId ?? roles[0]?.id ?? '' })}
                              className="text-primary-600 hover:text-primary-900"
                            >
                              Edit Role
                            </button>
                          )}
                          <button
                            onClick={() => setConfirmDeleteId(membership?.id ?? null)}
                            disabled={!membership?.id}
                            className="text-red-600 hover:text-red-900 disabled:opacity-40"
                          >
                            Remove
                          </button>
                        </span>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}

      {showInviteModal && (
        <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
          <div className="bg-white rounded-lg p-6 max-w-md w-full">
            <h2 className="text-xl font-bold mb-4">Invite User</h2>
            <form onSubmit={handleInviteUser}>
              <div className="mb-4">
                <label className="block text-sm font-medium text-gray-700 mb-1">Email</label>
                <input type="email" value={newUserEmail} onChange={(e) => setNewUserEmail(e.target.value)}
                  className="input w-full" required autoFocus />
              </div>
              <div className="mb-4 grid grid-cols-2 gap-2">
                <div>
                  <label className="block text-sm font-medium text-gray-700 mb-1">First Name</label>
                  <input type="text" value={newUserFirstName} onChange={(e) => setNewUserFirstName(e.target.value)}
                    className="input w-full" />
                </div>
                <div>
                  <label className="block text-sm font-medium text-gray-700 mb-1">Last Name</label>
                  <input type="text" value={newUserLastName} onChange={(e) => setNewUserLastName(e.target.value)}
                    className="input w-full" />
                </div>
              </div>
              <div className="mb-4">
                <label className="block text-sm font-medium text-gray-700 mb-1">Temporary Password</label>
                <input type="password" value={newUserPassword} onChange={(e) => setNewUserPassword(e.target.value)}
                  className="input w-full" required />
              </div>
              <div className="mb-4">
                <label className="block text-sm font-medium text-gray-700 mb-1">Role</label>
                <select value={newUserRoleId} onChange={(e) => setNewUserRoleId(e.target.value)} className="input w-full">
                  {roles.map((r) => (
                    <option key={r.id} value={r.id}>{r.name}{r.isDefault ? ' (default)' : ''}</option>
                  ))}
                  {roles.length === 0 && <option value="">No roles available</option>}
                </select>
              </div>
              <div className="flex justify-end space-x-2">
                <button type="button" onClick={() => setShowInviteModal(false)}
                  className="px-4 py-2 border border-gray-300 rounded-md text-gray-700 hover:bg-gray-50">Cancel</button>
                <button type="submit" disabled={inviting || roles.length === 0} className="btn-primary">
                  {inviting ? 'Inviting…' : 'Invite'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}
