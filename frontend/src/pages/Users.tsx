import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useAuthStore } from '../stores/auth';
import api from '../services/api';
import type { Membership, Role, User } from '../types';

/**
 * Defines the interface `EditState`, the contract implemented by this source unit.
 */
interface EditState {
  membershipId: string;
  roleId: string;
}

const PERMISSION_GROUPS = [
  {
    label: 'Pipeline',
    abilities: ['pipeline:read', 'pipeline:write', 'pipeline:execute'],
  },
  {
    label: 'Module',
    abilities: ['module:read', 'module:write', 'module:delete'],
  },
  {
    label: 'Connector',
    abilities: ['connector:read', 'connector:write', 'connector:delete'],
  },
  {
    label: 'Data',
    abilities: ['data:read', 'data:write', 'data:export'],
  },
  {
    label: 'Alerts',
    abilities: ['alert:read', 'alert:configure'],
  },
  {
    label: 'Users / Tenant',
    abilities: ['user:read', 'user:write', 'user:delete', 'tenant:manage'],
  },
];

export default function Users() {
  const { t } = useTranslation();
  const { user, selectedTenantId, tenantContexts } = useAuthStore();
  const [activeTab, setActiveTab] = useState<'members' | 'roles'>('members');
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

  // Role management state
  const [showRoleModal, setShowRoleModal] = useState(false);
  const [editingRole, setEditingRole] = useState<Role | null>(null);
  const [roleModalName, setRoleModalName] = useState('');
  const [roleModalDescription, setRoleModalDescription] = useState('');
  const [roleModalIsDefault, setRoleModalIsDefault] = useState(false);
  const [roleModalAbilities, setRoleModalAbilities] = useState<string[]>([]);
  const [savingRole, setSavingRole] = useState(false);
  const [confirmDeleteRoleId, setConfirmDeleteRoleId] = useState<string | null>(null);
  const [deletingRoleId, setDeletingRoleId] = useState<string | null>(null);

  const tenantId = selectedTenantId || user?.tenantId || tenantContexts[0]?.tenantId;
  const abilities = user?.abilities || [];
  /**
   * Evaluates the named capability or state predicate.
   */
  const hasAbility = (ability: string) => user?.superadmin || abilities.includes('*') || abilities.includes(ability);
  const canReadUsers = hasAbility('user:read');
  const canWriteUsers = hasAbility('user:write');
  const canManageTenant = hasAbility('tenant:manage');
  const canInviteUsers = canWriteUsers && canManageTenant;

  /**
   * Implements `showToast` for this source unit.
   */
  const showToast = (message: string, type: 'success' | 'error') => {
    setToast({ message, type });
    setTimeout(() => setToast(null), 3500);
  };

  /**
   * Retrieves or enumerates the requested state.
   */
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

  /**
   * Implements `useEffect` for this source unit.
   */
  useEffect(() => {
    /**
     * Initializes, connects, or acquires the named runtime resource.
     */
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

  /**
   * Retrieves or enumerates the requested state.
   */
  const getMembershipForUser = (userId: string) => memberships.find((m) => m.authUserId === userId);
  /**
   * Retrieves or enumerates the requested state.
   */
  const getRoleForMembership = (m?: Membership) => roles.find((r) => r.id === m?.roleId);
  const parseAbilities = (memberAbilities: Membership['abilities']): string[] => {
    if (!memberAbilities) return [];
    if (Array.isArray(memberAbilities)) return memberAbilities as string[];
    try { const p = JSON.parse(memberAbilities as string); return Array.isArray(p) ? p : []; } catch { return []; }
  };

  /**
   * Processes the named request, event, or operation.
   */
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

  /**
   * Processes the named request, event, or operation.
   */
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

  /**
   * Processes the named request, event, or operation.
   */
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

  /**
   * Processes the named request, event, or operation.
   */
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

  /**
   * Processes the named request, event, or operation.
   */
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

  // Role CRUD
  /**
   * Implements `openCreateRole` for this source unit.
   */
  const openCreateRole = () => {
    setEditingRole(null);
    setRoleModalName('');
    setRoleModalDescription('');
    setRoleModalIsDefault(false);
    setRoleModalAbilities([]);
    setShowRoleModal(true);
  };

  /**
   * Implements `openEditRole` for this source unit.
   */
  const openEditRole = (role: Role) => {
    setEditingRole(role);
    setRoleModalName(role.name);
    setRoleModalDescription(role.description || '');
    setRoleModalIsDefault(role.isDefault);
    setRoleModalAbilities([...role.abilities]);
    setShowRoleModal(true);
  };

  /**
   * Converts a value into the representation required by its caller.
   */
  const toggleAbility = (ability: string) => {
    setRoleModalAbilities((prev) =>
      prev.includes(ability) ? prev.filter((a) => a !== ability) : [...prev, ability]
    );
  };

  /**
   * Processes the named request, event, or operation.
   */
  const handleSaveRole = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!canManageTenant) {
      showToast(t('users.permissionUpdateMemberships'), 'error');
      return;
    }
    if (!tenantId || !roleModalName.trim()) return;
    setSavingRole(true);
    try {
      if (editingRole) {
        await api.updateRole(editingRole.id, {
          name: roleModalName.trim(),
          description: roleModalDescription.trim() || undefined,
          abilities: roleModalAbilities,
          isDefault: roleModalIsDefault,
        });
        showToast('Role updated', 'success');
      } else {
        await api.createRole(tenantId, roleModalName.trim(), roleModalAbilities, roleModalDescription.trim() || undefined);
        showToast('Role created', 'success');
      }
      setShowRoleModal(false);
      setEditingRole(null);
      await loadData(tenantId);
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: { error?: string } } })?.response?.data?.error ?? 'Failed to save role';
      showToast(msg, 'error');
    } finally {
      setSavingRole(false);
    }
  };

  /**
   * Processes the named request, event, or operation.
   */
  const handleDeleteRole = async (roleId: string) => {
    if (!canManageTenant) {
      showToast(t('users.permissionUpdateMemberships'), 'error');
      return;
    }
    if (!tenantId) return;
    setDeletingRoleId(roleId);
    try {
      await api.deleteRole(roleId);
      await loadData(tenantId);
      setConfirmDeleteRoleId(null);
      showToast('Role deleted', 'success');
    } catch {
      showToast('Failed to delete role', 'error');
    } finally {
      setDeletingRoleId(null);
    }
  };

  /**
   * Implements `tableUsers` for this source unit.
   */
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
        <div className="flex items-center gap-2">
          {activeTab === 'members' && canInviteUsers && (
            <button onClick={() => setShowInviteModal(true)} className="btn-primary">{t('users.inviteUser')}</button>
          )}
          {activeTab === 'roles' && canManageTenant && (
            <button onClick={openCreateRole} className="btn-primary">Create Role</button>
          )}
        </div>
      </div>

      {/* Tab switcher */}
      <div className="mb-6 flex space-x-2">
        <button
          onClick={() => setActiveTab('members')}
          className={`px-4 py-2 rounded-lg text-sm font-medium ${
            activeTab === 'members' ? 'bg-primary-600 text-white' : 'bg-gray-100 text-gray-700 hover:bg-gray-200'
          }`}
        >
          Members
        </button>
        <button
          onClick={() => setActiveTab('roles')}
          className={`px-4 py-2 rounded-lg text-sm font-medium ${
            activeTab === 'roles' ? 'bg-primary-600 text-white' : 'bg-gray-100 text-gray-700 hover:bg-gray-200'
          }`}
        >
          Roles
        </button>
      </div>

      {!canReadUsers && !canManageTenant && (
        <div className="mb-4 rounded-sm border border-amber-200 bg-amber-50 px-4 py-3 text-amber-800">
          {t('users.permissionViewUsers')}
        </div>
      )}

      {error && (
        <div className="mb-4 rounded-sm border border-red-200 bg-red-50 px-4 py-3 text-red-700 flex justify-between items-center">
          <span>{error}</span>
          <div className="flex items-center gap-3 ml-4">
            <button onClick={handleReload} className="text-red-700 hover:text-red-900 text-sm underline">{t('common.retry')}</button>
            <button onClick={() => setError('')} className="text-red-400 hover:text-red-600">✕</button>
          </div>
        </div>
      )}

      {/* Members tab */}
      {activeTab === 'members' && (
        <>
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
                <div key={i} className="card animate-pulse"><div className="h-4 bg-gray-200 rounded-sm w-1/3"></div></div>
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
                    const memberAbilities = parseAbilities(membership?.abilities);
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
                            <span className="px-2 py-1 text-xs font-medium rounded-sm bg-gray-100 text-gray-800">
                              {role?.name ?? membership?.roleName ?? t('users.noRole')}
                            </span>
                          )}
                        </td>
                        <td className="px-6 py-4">
                          <span className={`px-2 py-1 text-xs font-medium rounded-sm ${(u.isActive ?? true) ? 'bg-green-100 text-green-800' : 'bg-gray-100 text-gray-700'}`}>
                            {(u.isActive ?? true) ? t('users.active') : t('users.inactive')}
                          </span>
                        </td>
                        <td className="px-6 py-4">
                          <div className="flex flex-wrap gap-1">
                            {memberAbilities.slice(0, 3).map((a) => (
                              <span key={a} className="px-2 py-0.5 text-xs rounded-sm bg-blue-100 text-blue-800">{a}</span>
                            ))}
                            {memberAbilities.length > 3 && (
                              <span className="px-2 py-0.5 text-xs rounded-sm bg-gray-100 text-gray-600">
                                +{memberAbilities.length - 3}
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
        </>
      )}

      {/* Roles tab */}
      {activeTab === 'roles' && (
        <>
          {loading ? (
            <div className="space-y-4">
              {[1, 2].map(i => (
                <div key={i} className="card animate-pulse"><div className="h-4 bg-gray-200 rounded-sm w-1/3"></div></div>
              ))}
            </div>
          ) : roles.length === 0 ? (
            <div className="card text-center py-12">
              <p className="text-gray-500 mb-4">No roles yet.</p>
              {canManageTenant && (
                <button onClick={openCreateRole} className="btn-primary">Create First Role</button>
              )}
            </div>
          ) : (
            <div className="space-y-3">
              {roles.map((role) => (
                <div key={role.id} className="card">
                  <div className="flex justify-between items-start">
                    <div className="flex-1 min-w-0">
                      <div className="flex items-center gap-2 flex-wrap">
                        <h3 className="text-base font-semibold text-gray-900">{role.name}</h3>
                        {role.isDefault && (
                          <span className="px-2 py-0.5 text-xs rounded-sm bg-blue-100 text-blue-800">Default</span>
                        )}
                        {role.isSystem && (
                          <span className="px-2 py-0.5 text-xs rounded-sm bg-gray-200 text-gray-700">System</span>
                        )}
                      </div>
                      {role.description && (
                        <p className="text-sm text-gray-500 mt-1">{role.description}</p>
                      )}
                      <div className="flex flex-wrap gap-1 mt-2">
                        {role.abilities.map((a) => (
                          <span key={a} className="px-2 py-0.5 text-xs rounded-sm bg-indigo-50 text-indigo-700 border border-indigo-100">{a}</span>
                        ))}
                        {role.abilities.length === 0 && (
                          <span className="text-xs text-gray-400 italic">No abilities</span>
                        )}
                      </div>
                    </div>
                    {canManageTenant && (
                      <div className="flex items-center gap-2 ml-4 shrink-0">
                        <button
                          onClick={() => openEditRole(role)}
                          disabled={role.isSystem}
                          className="text-sm text-blue-600 hover:text-blue-800 disabled:opacity-40 disabled:cursor-not-allowed"
                          title={role.isSystem ? 'System roles cannot be edited' : undefined}
                        >
                          {t('common.edit')}
                        </button>
                        {confirmDeleteRoleId === role.id ? (
                          <div className="flex items-center gap-1">
                            <span className="text-xs text-gray-500">Delete?</span>
                            <button
                              onClick={() => handleDeleteRole(role.id)}
                              disabled={deletingRoleId === role.id}
                              className="text-sm text-red-600 hover:text-red-800"
                            >
                              {deletingRoleId === role.id ? '…' : t('common.yes')}
                            </button>
                            <button
                              onClick={() => setConfirmDeleteRoleId(null)}
                              className="text-sm text-gray-500 hover:text-gray-700"
                            >
                              {t('common.no')}
                            </button>
                          </div>
                        ) : (
                          <button
                            onClick={() => setConfirmDeleteRoleId(role.id)}
                            disabled={role.isSystem}
                            className="text-sm text-red-600 hover:text-red-800 disabled:opacity-40 disabled:cursor-not-allowed"
                            title={role.isSystem ? 'System roles cannot be deleted' : undefined}
                          >
                            {t('common.delete')}
                          </button>
                        )}
                      </div>
                    )}
                  </div>
                </div>
              ))}
            </div>
          )}
        </>
      )}

      {/* Invite user modal */}
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

      {/* Role create/edit modal */}
      {showRoleModal && canManageTenant && (
        <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
          <div className="bg-white rounded-lg p-6 max-w-lg w-full max-h-[90vh] overflow-y-auto">
            <h2 className="text-xl font-bold mb-4">{editingRole ? 'Edit Role' : 'Create Role'}</h2>
            <form onSubmit={handleSaveRole}>
              <div className="mb-4">
                <label className="block text-sm font-medium text-gray-700 mb-1">Name <span className="text-red-500">*</span></label>
                <input
                  type="text"
                  value={roleModalName}
                  onChange={(e) => setRoleModalName(e.target.value)}
                  className="input w-full"
                  required
                  autoFocus
                />
              </div>
              <div className="mb-4">
                <label className="block text-sm font-medium text-gray-700 mb-1">Description</label>
                <textarea
                  value={roleModalDescription}
                  onChange={(e) => setRoleModalDescription(e.target.value)}
                  className="input w-full"
                  rows={2}
                />
              </div>
              <div className="mb-4">
                <label className="flex items-center gap-2 text-sm font-medium text-gray-700 cursor-pointer">
                  <input
                    type="checkbox"
                    checked={roleModalIsDefault}
                    onChange={(e) => setRoleModalIsDefault(e.target.checked)}
                    className="rounded-sm"
                  />
                  Default role for new members
                </label>
              </div>
              <div className="mb-4">
                <p className="text-sm font-medium text-gray-700 mb-2">Permissions</p>
                <div className="space-y-3">
                  {PERMISSION_GROUPS.map((group) => (
                    <div key={group.label}>
                      <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide mb-1">{group.label}</p>
                      <div className="flex flex-wrap gap-2">
                        {group.abilities.map((ability) => (
                          <label key={ability} className="flex items-center gap-1 cursor-pointer">
                            <input
                              type="checkbox"
                              checked={roleModalAbilities.includes(ability)}
                              onChange={() => toggleAbility(ability)}
                              className="rounded-sm"
                            />
                            <span className="text-sm text-gray-700">{ability}</span>
                          </label>
                        ))}
                      </div>
                    </div>
                  ))}
                </div>
              </div>
              <div className="flex justify-end space-x-2">
                <button
                  type="button"
                  onClick={() => { setShowRoleModal(false); setEditingRole(null); }}
                  className="px-4 py-2 border border-gray-300 rounded-md text-gray-700 hover:bg-gray-50"
                >
                  {t('common.cancel')}
                </button>
                <button type="submit" disabled={savingRole || !roleModalName.trim()} className="btn-primary">
                  {savingRole ? t('common.saving') : editingRole ? t('common.save') : t('common.create')}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}
