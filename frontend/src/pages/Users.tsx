import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useAuthStore } from '../stores/auth';
import api from '../services/api';
import type { Membership, Role, User } from '../types';

interface EditState {
  membershipId: string;
  roleId: string;
}

export default function Users() {
  const { t } = useTranslation();
  const { user, selectedTenantId, tenantContexts } = useAuthStore();
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
  const [togglingUserId, setTogglingUserId] = useState<string | null>(null);
  const [searchQuery, setSearchQuery] = useState('');
  const [statusFilter, setStatusFilter] = useState<'ALL' | 'ACTIVE' | 'INACTIVE'>('ALL');
  const [roleFilter, setRoleFilter] = useState<string>('ALL');

  const tenantId = selectedTenantId || user?.tenantId || tenantContexts[0]?.tenantId;
  const abilities = user?.abilities || [];
  const hasAbility = (ability: string) => user?.superadmin || abilities.includes('*') || abilities.includes(ability);
  const canReadUsers = hasAbility('read:user');
  const canWriteUsers = hasAbility('write:user');
  const canManageTenant = hasAbility('manage:tenant');
  const canInviteUsers = canWriteUsers && canManageTenant;

  const showToast = (message: string, type: 'success' | 'error') => {
    setToast({ message, type });
    setTimeout(() => setToast(null), 3500);
  };

  const loadData = async (tid: string) => {
    if (!canReadUsers && !canManageTenant) {
      setUsers([]);
      setMemberships([]);
      setRoles([]);
      return [] as Role[];
    }

    const [usersResult, membershipsResult, rolesResult] = await Promise.allSettled([
      canReadUsers ? api.getUsers(tid) : Promise.resolve([] as User[]),
      canManageTenant ? api.getTenantMemberships(tid) : Promise.resolve([] as Membership[]),
      canManageTenant ? api.getRoles(tid) : Promise.resolve([] as Role[]),
    ]);

    const usersData = usersResult.status === 'fulfilled' ? usersResult.value : [];
    const membershipsData = membershipsResult.status === 'fulfilled' ? membershipsResult.value : [];
    const rolesData = rolesResult.status === 'fulfilled' ? rolesResult.value : [];

    setUsers(usersData);
    setMemberships(membershipsData);
    setRoles(rolesData);

    if (usersResult.status === 'rejected' || membershipsResult.status === 'rejected' || rolesResult.status === 'rejected') {
      setError(t('users.someDataNotLoaded'));
    } else {
      setError('');
    }

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
        setError(t('users.failedLoadUsers'));
      } finally {
        setLoading(false);
      }
    };
    init();
  }, [tenantId, canReadUsers, canManageTenant, t]);

  const getMembershipForUser = (userId: string) => memberships.find((m) => m.authUserId === userId);
  const getRoleForMembership = (m?: Membership) => roles.find((r) => r.id === m?.roleId);
  const parseAbilities = (abilities: Membership['abilities']): string[] => {
    if (!abilities) return [];
    if (Array.isArray(abilities)) return abilities as string[];
    try { const p = JSON.parse(abilities as string); return Array.isArray(p) ? p : []; } catch { return []; }
  };

  const handleInviteUser = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!canInviteUsers) {
      showToast(t('users.permissionInviteUsers'), 'error');
      return;
    }
    if (!tenantId || !newUserEmail || !newUserPassword) return;
    setInviting(true);
    try {
      const createdUser = await api.createUser(tenantId, newUserEmail, newUserPassword, {
        firstName: newUserFirstName || undefined,
        lastName: newUserLastName || undefined,
      });
      const roleId = newUserRoleId || roles.find((r) => r.isDefault)?.id || roles[0]?.id;
      if (!roleId) { setError(t('users.noRolesFoundFirst')); return; }
      await api.createTenantMembership(tenantId, createdUser.id, roleId);
      setShowInviteModal(false);
      setNewUserEmail(''); setNewUserPassword('');
      setNewUserFirstName(''); setNewUserLastName('');
      await loadData(tenantId);
      showToast(t('users.userInvited'), 'success');
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: { error?: string } } })?.response?.data?.error ?? t('users.failedInviteUser');
      setError(msg);
    } finally {
      setInviting(false);
    }
  };

  const handleSaveRoleEdit = async () => {
    if (!canManageTenant) {
      showToast(t('users.permissionUpdateMemberships'), 'error');
      return;
    }
    if (!tenantId || !editState) return;
    setSavingEdit(true);
    try {
      await api.updateTenantMembership(tenantId, editState.membershipId, editState.roleId);
      await loadData(tenantId);
      setEditState(null);
      showToast(t('users.roleUpdated'), 'success');
    } catch {
      showToast(t('users.failedUpdateRole'), 'error');
    } finally {
      setSavingEdit(false);
    }
  };

  const handleDeleteMembership = async (membershipId: string) => {
    if (!canManageTenant) {
      showToast(t('users.permissionRemoveMemberships'), 'error');
      return;
    }
    if (!tenantId) return;
    setDeletingId(membershipId);
    try {
      await api.deleteTenantMembership(tenantId, membershipId);
      await loadData(tenantId);
      setConfirmDeleteId(null);
      showToast(t('users.membershipRemoved'), 'success');
    } catch {
      showToast(t('users.failedRemoveMembership'), 'error');
    } finally {
      setDeletingId(null);
    }
  };

  const handleToggleUserActive = async (target: User) => {
    if (!canWriteUsers) {
      showToast(t('users.permissionUpdateUsers'), 'error');
      return;
    }
    setTogglingUserId(target.id);
    try {
      const nextActive = !(target.isActive ?? true);
      await api.updateUser(target.id, { isActive: nextActive });
      setUsers((prev) => prev.map((u) => (u.id === target.id ? { ...u, isActive: nextActive } : u)));
      showToast(nextActive ? t('users.userActivated') : t('users.userDeactivated'), 'success');
    } catch {
      showToast(t('users.failedUpdateStatus'), 'error');
    } finally {
      setTogglingUserId(null);
    }
  };

  const handleReload = async () => {
    if (!tenantId) return;
    setLoading(true);
    try {
      await loadData(tenantId);
    } catch {
      setError(t('users.failedLoadUsers'));
    } finally {
      setLoading(false);
    }
  };

  const tableUsers = (canReadUsers ? users : memberships.map((m) => ({
    id: m.authUserId,
    email: m.authUserEmail || `user:${m.authUserId.slice(0, 8)}`,
    firstName: m.authUserFirstName,
    lastName: m.authUserLastName,
    abilities: [],
    superadmin: false,
  } as User))).filter((u) => {
    const membership = getMembershipForUser(u.id);
    const role = getRoleForMembership(membership);
    const matchesRole = roleFilter === 'ALL' || role?.id === roleFilter;
    const active = u.isActive ?? true;
    const matchesStatus = statusFilter === 'ALL' || (statusFilter === 'ACTIVE' ? active : !active);
    const query = searchQuery.trim().toLowerCase();
    const fullName = `${u.firstName || ''} ${u.lastName || ''}`.trim().toLowerCase();
    const roleName = (role?.name || membership?.roleName || '').toLowerCase();
    const matchesQuery = !query || u.email.toLowerCase().includes(query) || fullName.includes(query) || roleName.includes(query);
    return matchesRole && matchesStatus && matchesQuery;
  });

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
        <h1 className="text-2xl font-bold text-gray-900">{t('users.title')}</h1>
        {canInviteUsers && (
          <button onClick={() => setShowInviteModal(true)} className="btn-primary">{t('users.inviteUser')}</button>
        )}
      </div>

      {!canReadUsers && !canManageTenant && (
        <div className="mb-4 rounded border border-amber-200 bg-amber-50 px-4 py-3 text-amber-800">
          {t('users.permissionViewUsers')}
        </div>
      )}

      {error && (
        <div className="mb-4 rounded border border-red-200 bg-red-50 px-4 py-3 text-red-700 flex justify-between items-center">
          <span>{error}</span>
          <div className="flex items-center gap-3 ml-4">
            <button onClick={handleReload} className="text-red-700 hover:text-red-900 text-sm underline">{t('common.retry')}</button>
            <button onClick={() => setError('')} className="text-red-400 hover:text-red-600">✕</button>
          </div>
        </div>
      )}

      {(canReadUsers || canManageTenant) && (
        <div className="mb-4 card">
          <div className="grid grid-cols-1 md:grid-cols-3 gap-3">
            <input
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              className="input"
              placeholder={t('users.searchPlaceholder')}
            />
            <select value={statusFilter} onChange={(e) => setStatusFilter(e.target.value as 'ALL' | 'ACTIVE' | 'INACTIVE')} className="input">
              <option value="ALL">{t('users.allStatuses')}</option>
              <option value="ACTIVE">{t('users.active')}</option>
              <option value="INACTIVE">{t('users.inactive')}</option>
            </select>
            <select value={roleFilter} onChange={(e) => setRoleFilter(e.target.value)} className="input">
              <option value="ALL">{t('users.allRoles')}</option>
              {roles.map((role) => (
                <option key={role.id} value={role.id}>{role.name}</option>
              ))}
            </select>
          </div>
          <p className="mt-2 text-xs text-gray-500">{t('users.showingUsers', { count: tableUsers.length })}</p>
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
          <p className="text-gray-500">{t('common.noTenantId')}</p>
        </div>
      ) : users.length === 0 && memberships.length === 0 ? (
        <div className="card text-center py-12">
          <p className="text-gray-500 mb-4">{t('users.noUsersYet')}</p>
          {canInviteUsers && <button onClick={() => setShowInviteModal(true)} className="btn-primary">{t('users.inviteFirstUser')}</button>}
        </div>
      ) : (
        <div className="card overflow-hidden">
          <table className="min-w-full divide-y divide-gray-200">
            <thead className="bg-gray-50">
              <tr>
                <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">{t('users.user')}</th>
                <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">{t('users.role')}</th>
                <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">{t('users.status')}</th>
                <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">{t('users.abilities')}</th>
                {canManageTenant && (
                  <th className="px-6 py-3 text-right text-xs font-medium text-gray-500 uppercase tracking-wider">{t('common.actions')}</th>
                )}
              </tr>
            </thead>
            <tbody className="bg-white divide-y divide-gray-200">
              {tableUsers.length === 0 && (
                <tr>
                  <td colSpan={canManageTenant ? 5 : 4} className="px-6 py-8 text-center text-sm text-gray-500">
                    {t('users.noUsersMatchFilters')}
                  </td>
                </tr>
              )}
              {tableUsers.map((u) => {
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
                            {savingEdit ? '…' : t('common.save')}
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
                          {role?.name ?? membership?.roleName ?? t('users.noRole')}
                        </span>
                      )}
                    </td>
                    <td className="px-6 py-4">
                      <span className={`px-2 py-1 text-xs font-medium rounded ${(u.isActive ?? true) ? 'bg-green-100 text-green-800' : 'bg-gray-100 text-gray-700'}`}>
                        {(u.isActive ?? true) ? t('users.active') : t('users.inactive')}
                      </span>
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
                    {canManageTenant && (
                      <td className="px-6 py-4 whitespace-nowrap text-right text-sm font-medium">
                        {isConfirmingDelete ? (
                          <span className="space-x-2">
                            <span className="text-gray-600 text-xs">{t('common.deleteConfirm')}</span>
                            <button
                              onClick={() => handleDeleteMembership(membership!.id)}
                              disabled={isDeleting}
                              className="text-red-600 hover:text-red-900"
                            >
                              {isDeleting ? '…' : t('common.yes')}
                            </button>
                            <button onClick={() => setConfirmDeleteId(null)} className="text-gray-500 hover:text-gray-700">{t('common.no')}</button>
                          </span>
                        ) : (
                          <span className="space-x-3">
                            {membership && !isEditingThis && (
                              <button
                                onClick={() => setEditState({ membershipId: membership.id, roleId: membership.roleId ?? roles[0]?.id ?? '' })}
                                className="text-primary-600 hover:text-primary-900"
                              >
                                {t('common.edit')}
                              </button>
                            )}
                            {canWriteUsers && (
                              <button
                                onClick={() => handleToggleUserActive(u)}
                                disabled={togglingUserId === u.id}
                                className="text-amber-700 hover:text-amber-900 disabled:opacity-40"
                              >
                                {togglingUserId === u.id ? t('common.saving') : ((u.isActive ?? true) ? t('common.disable') : t('common.enable'))}
                              </button>
                            )}
                            <button
                              onClick={() => setConfirmDeleteId(membership?.id ?? null)}
                              disabled={!membership?.id}
                              className="text-red-600 hover:text-red-900 disabled:opacity-40"
                            >
                              {t('common.remove')}
                            </button>
                          </span>
                        )}
                      </td>
                    )}
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}

      {showInviteModal && canInviteUsers && (
        <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
          <div className="bg-white rounded-lg p-6 max-w-md w-full">
            <h2 className="text-xl font-bold mb-4">{t('users.inviteUser')}</h2>
            <form onSubmit={handleInviteUser}>
              <div className="mb-4">
                <label className="block text-sm font-medium text-gray-700 mb-1">{t('users.email')}</label>
                <input type="email" value={newUserEmail} onChange={(e) => setNewUserEmail(e.target.value)}
                  className="input w-full" required autoFocus />
              </div>
              <div className="mb-4 grid grid-cols-2 gap-2">
                <div>
                  <label className="block text-sm font-medium text-gray-700 mb-1">{t('users.firstName')}</label>
                  <input type="text" value={newUserFirstName} onChange={(e) => setNewUserFirstName(e.target.value)}
                    className="input w-full" />
                </div>
                <div>
                  <label className="block text-sm font-medium text-gray-700 mb-1">{t('users.lastName')}</label>
                  <input type="text" value={newUserLastName} onChange={(e) => setNewUserLastName(e.target.value)}
                    className="input w-full" />
                </div>
              </div>
              <div className="mb-4">
                <label className="block text-sm font-medium text-gray-700 mb-1">{t('users.temporaryPassword')}</label>
                <input type="password" value={newUserPassword} onChange={(e) => setNewUserPassword(e.target.value)}
                  className="input w-full" required />
              </div>
              <div className="mb-4">
                <label className="block text-sm font-medium text-gray-700 mb-1">{t('users.role')}</label>
                <select value={newUserRoleId} onChange={(e) => setNewUserRoleId(e.target.value)} className="input w-full">
                  {roles.map((r) => (
                    <option key={r.id} value={r.id}>{r.name}{r.isDefault ? t('users.defaultRole') : ''}</option>
                  ))}
                  {roles.length === 0 && <option value="">{t('users.noRolesAvailable')}</option>}
                </select>
              </div>
              <div className="flex justify-end space-x-2">
                <button type="button" onClick={() => setShowInviteModal(false)}
                  className="px-4 py-2 border border-gray-300 rounded-md text-gray-700 hover:bg-gray-50">{t('common.cancel')}</button>
                <button type="submit" disabled={inviting || roles.length === 0} className="btn-primary">
                  {inviting ? t('common.loading') : t('common.create')}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}
