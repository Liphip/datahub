import { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { useTranslation } from 'react-i18next';
import { useAuthStore } from '../stores/auth';
import api, { type ApiError } from '../services/api';
import type { CodeModule } from '../types';

const MODULE_TYPES = ['IMPORT', 'DB_LOAD', 'PROCESSING', 'MERGE', 'EXPORT', 'DB_SAVE', 'ALERT'] as const;

const moduleTypeColors: Record<string, string> = {
  IMPORT: 'bg-blue-100 text-blue-800',
  DB_LOAD: 'bg-sky-100 text-sky-800',
  PROCESSING: 'bg-purple-100 text-purple-800',
  MERGE: 'bg-violet-100 text-violet-800',
  EXPORT: 'bg-green-100 text-green-800',
  DB_SAVE: 'bg-emerald-100 text-emerald-800',
  ALERT: 'bg-red-100 text-red-800',
};

export default function Modules() {
  const { t } = useTranslation();
  const { user, selectedTenantId, tenantContexts } = useAuthStore();
  const navigate = useNavigate();
  const canReadModules = Boolean(user?.superadmin || user?.abilities?.includes('*') || user?.abilities?.includes('module:read'));
  const canWriteModules = Boolean(user?.superadmin || user?.abilities?.includes('*') || user?.abilities?.includes('module:write'));
  const canDeleteModules = Boolean(user?.superadmin || user?.abilities?.includes('*') || user?.abilities?.includes('module:delete'));
  const [modules, setModules] = useState<CodeModule[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [toast, setToast] = useState<{ message: string; type: 'success' | 'error' } | null>(null);
  const [filter, setFilter] = useState<string>('all');
  const [showCreateModal, setShowCreateModal] = useState(false);
  const [newModuleName, setNewModuleName] = useState('');
  const [newModuleType, setNewModuleType] = useState('PROCESSING');
  const [newModuleDescription, setNewModuleDescription] = useState('');
  const [creating, setCreating] = useState(false);
  const [deletingId, setDeletingId] = useState<string | null>(null);
  const [confirmDeleteId, setConfirmDeleteId] = useState<string | null>(null);

  const tenantId = selectedTenantId || user?.tenantId || tenantContexts[0]?.tenantId;

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
  const loadModules = async (tid: string, type?: string) => {
    const data = await api.getModules(tid, type === 'all' || !type ? undefined : type);
    setModules(data);
  };

  /**
   * Implements `useEffect` for this source unit.
   */
  useEffect(() => {
    /**
     * Implements `fetch` for this source unit.
     */
    const fetch = async () => {
      if (!tenantId) { setLoading(false); return; }
      try {
        await loadModules(tenantId, filter);
        setError('');
      } catch (caught) {
        const err = caught as ApiError;
        const status = err?.response?.status;
        if (status === 403) {
          setError(t('modules.permissionViewModules'));
        } else {
          setError(t('common.loadingFailed'));
        }
      } finally {
        setLoading(false);
      }
    };
    fetch();
  }, [tenantId, filter, t]);

  /**
   * Processes the named request, event, or operation.
   */
  const handleCreateModule = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!tenantId || !newModuleName) return;
    setCreating(true);
    try {
      const newModule = await api.createModule(tenantId, newModuleName, newModuleType, newModuleDescription);
      setShowCreateModal(false);
      setNewModuleName(''); setNewModuleType('PROCESSING'); setNewModuleDescription('');
      navigate(`/modules/${newModule.id}`);
    } catch {
      showToast(t('modules.failedToCreateModule'), 'error');
    } finally {
      setCreating(false);
    }
  };

  /**
   * Processes the named request, event, or operation.
   */
  const handleDeleteModule = async (id: string, e: React.MouseEvent) => {
    e.preventDefault();
    setDeletingId(id);
    try {
      await api.deleteModule(id);
      if (tenantId) await loadModules(tenantId, filter);
      setConfirmDeleteId(null);
      showToast(t('modules.moduleDeleted'), 'success');
    } catch {
      showToast(t('modules.failedToDeleteModule'), 'error');
    } finally {
      setDeletingId(null);
    }
  };

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
        <h1 className="text-2xl font-bold text-gray-900">{t('modules.title')}</h1>
        {canWriteModules && (
          <button onClick={() => setShowCreateModal(true)} className="btn-primary">{t('modules.createModule')}</button>
        )}
      </div>

      {!canReadModules && (
        <div className="mb-4 rounded-lg border border-amber-200 bg-amber-50 p-4 text-sm text-amber-900">
          {t('modules.permissionReadModule')}
        </div>
      )}

      {error && (
        <div className="mb-4 bg-red-50 border border-red-200 text-red-700 px-4 py-3 rounded-sm flex justify-between">
          <span>{error}</span>
          <button onClick={() => setError('')} className="ml-4 text-red-400 hover:text-red-600">✕</button>
        </div>
      )}

      <div className="mb-4 flex flex-wrap gap-2">
        {['all', ...MODULE_TYPES].map((type) => (
          <button
            key={type}
            onClick={() => setFilter(type)}
            className={`px-4 py-2 rounded-lg text-sm font-medium ${
              filter === type ? 'bg-primary-600 text-white' : 'bg-gray-100 text-gray-700 hover:bg-gray-200'
            }`}
          >
            {type === 'all' ? t('common.all') : t(`modules.${type.toLowerCase()}`)}
          </button>
        ))}
      </div>

      {loading ? (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
          {[1, 2, 3].map(i => (
            <div key={i} className="card animate-pulse">
              <div className="h-4 bg-gray-200 rounded-sm w-1/2 mb-4"></div>
              <div className="h-3 bg-gray-200 rounded-sm w-3/4"></div>
            </div>
          ))}
        </div>
      ) : !tenantId ? (
        <div className="card text-center py-12"><p className="text-gray-500">{t('common.noTenantId')}</p></div>
      ) : !canReadModules ? (
        <div className="card text-center py-12"><p className="text-gray-500">{t('modules.permissionDenied')}</p></div>
      ) : modules.length > 0 ? (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
          {modules.map((module) => {
            const isConfirming = confirmDeleteId === module.id;
            const isDeleting = deletingId === module.id;

            return (
              <div key={module.id} className="card hover:shadow-lg transition-shadow flex flex-col">
                <Link to={`/modules/${module.id}`} className="flex-1 block">
                  <div className="flex justify-between items-start mb-2">
                    <h3 className="text-lg font-semibold text-gray-900 group-hover:text-primary-600 truncate">
                      {module.name}
                    </h3>
                    <span className={`shrink-0 ml-2 px-2 py-1 text-xs font-medium rounded-sm ${moduleTypeColors[module.moduleType]}`}>
                      {t(`modules.${module.moduleType.toLowerCase()}`)}
                    </span>
                  </div>
                  <p className="text-sm text-gray-500 mb-4 line-clamp-2">
                    {module.description || t('common.noDescription')}
                  </p>
                  <div className="flex justify-between items-center text-sm text-gray-500">
                    <span>{module.versionCount ?? module.versions?.length ?? 0} {t('common.versions')}</span>
                    <span className={`w-2 h-2 rounded-full ${module.isActive ? 'bg-green-400' : 'bg-gray-300'}`} />
                  </div>
                </Link>

                <div className="mt-4 pt-3 border-t border-gray-100 flex justify-end items-center space-x-3 text-sm">
                  <Link to={`/modules/${module.id}`} className="text-primary-600 hover:text-primary-800">{t('common.open')}</Link>
                  {canDeleteModules && isConfirming ? (
                    <>
                      <span className="text-gray-500 text-xs">{t('common.deleteConfirm')}</span>
                      <button
                        onClick={(e) => handleDeleteModule(module.id, e)}
                        disabled={isDeleting}
                        className="text-red-600 hover:text-red-800 font-medium"
                      >
                        {isDeleting ? '…' : t('common.yes')}
                      </button>
                      <button onClick={() => setConfirmDeleteId(null)} className="text-gray-500 hover:text-gray-700">{t('common.no')}</button>
                    </>
                  ) : canDeleteModules ? (
                    <button
                      onClick={(e) => { e.preventDefault(); setConfirmDeleteId(module.id); }}
                      className="text-red-600 hover:text-red-800"
                    >
                      {t('common.delete')}
                    </button>
                  ) : null}
                </div>
              </div>
            );
          })}
        </div>
      ) : (
        <div className="card text-center py-12">
          <p className="text-gray-500 mb-4">{t('modules.noModulesYet')}</p>
          {canWriteModules ? (
            <button onClick={() => setShowCreateModal(true)} className="btn-primary">{t('modules.createYourFirstModule')}</button>
          ) : (
            <p className="text-sm text-gray-500">{t('modules.permissionCreateModule')}</p>
          )}
        </div>
      )}

      {showCreateModal && (
        <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
          <div className="bg-white rounded-lg p-6 max-w-md w-full">
            <h2 className="text-xl font-bold mb-4">{t('modules.createModule')}</h2>
            <form onSubmit={handleCreateModule}>
              <div className="mb-4">
                <label className="block text-sm font-medium text-gray-700 mb-1">{t('common.name')}</label>
                <input type="text" value={newModuleName} onChange={(e) => setNewModuleName(e.target.value)}
                  className="input w-full" required autoFocus />
              </div>
              <div className="mb-4">
                <label className="block text-sm font-medium text-gray-700 mb-1">{t('common.type')}</label>
                <select value={newModuleType} onChange={(e) => setNewModuleType(e.target.value)} className="input w-full">
                  {MODULE_TYPES.map((type) => (
                    <option key={type} value={type}>{t(`modules.${type.toLowerCase()}`)}</option>
                  ))}
                </select>
              </div>
              <div className="mb-4">
                <label className="block text-sm font-medium text-gray-700 mb-1">{t('common.description')}</label>
                <textarea value={newModuleDescription} onChange={(e) => setNewModuleDescription(e.target.value)}
                  className="input w-full" rows={3} />
              </div>
              <div className="flex justify-end space-x-2">
                <button type="button" onClick={() => setShowCreateModal(false)}
                  className="px-4 py-2 border border-gray-300 rounded-md text-gray-700 hover:bg-gray-50">{t('common.cancel')}</button>
                <button type="submit" disabled={creating} className="btn-primary">
                  {creating ? t('common.creating') : t('common.create')}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}
