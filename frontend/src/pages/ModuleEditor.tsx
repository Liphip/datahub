import { useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router';
import Editor from '@monaco-editor/react';
import { useTranslation } from 'react-i18next';
import { useAuthStore } from '../stores/auth';
import api from '../services/api';
import type { CodeModule } from '../types';

const TEMPLATES = {
  IMPORT: `class ImportModule:
    """
    IMPORT node — parse and transform data the platform has already fetched.

    The platform connector handles all network I/O before this module runs.
    You receive the raw payload as \`input_data\` — do NOT make network calls here.

    connector_meta contains non-sensitive metadata (type, name, topic, etc.)
    for informational use only. Credentials are never passed to modules.
    """

    def parse(self, input_data: dict, connector_meta: dict = None) -> dict:
        """Transform/validate the raw fetched payload. Return the parsed record."""
        # Example: extract and rename fields
        # return {
        #     "temperature": input_data.get("temp_c"),
        #     "sensor_id": input_data.get("id"),
        # }
        return input_data
`,
  DB_LOAD: `class DbLoadModule:
    """
    DB_LOAD module.

    Use metadata() to describe accepted query parameters and load() to fetch
    rows from the configured schema/table in the platform-managed data DB.
    """

    def metadata(self) -> dict:
        return {
            "defaults": {"limit": 100, "order_by": "created_at desc"},
            "supported_filters": ["start", "end", "sensor_id"],
        }

    def load(self, query: dict, context: dict | None = None):
        # The platform will provide query + table config to the DB runtime.
        # Return a list of rows or a DataFrame.
        return []
`,
  PROCESSING: `class ProcessingModule:
    """
    PROCESSING node — transform, filter, or enrich data from one upstream node.

    Return the modified dict to pass it downstream.
    Return None to drop the message.
    Raise an exception to fail the pipeline run.
    """

    def process(self, data: dict, query_fn=None) -> dict | None:
        # Example: add a derived field
        # data["celsius"] = (data["fahrenheit"] - 32) * 5 / 9
        return data
`,
  MERGE: `class MergeModule:
    """
    MERGE node — fan-in from two or more upstream nodes.

    \`inputs\` is a list of dicts, one per upstream node, in topological order.
    Return a single dict to pass downstream.
    """

    def merge(self, inputs: list) -> dict:
        # Example: merge all upstream dicts, later keys win
        result = {}
        for item in inputs:
            if isinstance(item, dict):
                result.update(item)
        return result
`,
  ALERT: `class AlertModule:
    """
    ALERT node — evaluate conditions and fire alerts via the platform.
    Terminal node — does NOT forward data downstream.

    alert_fn(severity, message, payload=None, title=None, channel=None, cooldown_seconds=None)
      severity          'info' | 'warning' | 'error' | 'critical'
      channel           alert channel id or name (default: the node's default channel)
      cooldown_seconds  suppress repeats of the same alert (same pipeline, node and title)
                        within this window; defaults to the platform setting
    Return True if an alert was fired, False otherwise.
    """

    def __init__(self, config: dict = None):
        cfg = config or {}
        self.threshold = cfg.get('threshold', 80)

    def evaluate(self, data: dict, alert_fn=None, query_fn=None) -> bool:
        value = data.get('value')
        if isinstance(value, (int, float)) and value > self.threshold:
            alert_fn(severity='warning',
                     title='Threshold exceeded',
                     message=f"Value {value} exceeds threshold {self.threshold}",
                     payload=data)
            return True
        return False
`,
  EXPORT: `class ExportModule:
    """
    EXPORT node — shape the outbound payload the platform will deliver.

    The platform connector handles all network I/O after this module returns.
    Do NOT make network calls here — return the dict you want delivered.

    connector_meta contains non-sensitive metadata (type, name, topic, etc.)
    for informational use only.
    """

    def shape(self, data: dict, connector_meta: dict = None) -> dict:
        """Return the final payload to be delivered by the platform connector."""
        # Example: rename/filter fields before sending
        # return {
        #     "sensor": data.get("sensor_id"),
        #     "value": data.get("temperature"),
        # }
        return data
`,
  DB_SAVE: `class DbSaveModule:
    """
    DB_SAVE module.

    Use metadata() to describe write behaviour and prepare() to normalize rows
    before the platform persists them into the configured schema/table.
    """

    def metadata(self) -> dict:
        return {
            "mode": "append",
            "timestamp_column": "created_at",
            "batch_size": 500,
        }

    def prepare(self, data: dict | list[dict], context: dict | None = None):
        if isinstance(data, dict):
            return [data]
        return data
`,
};

/**
 * Implements `defaultModuleConfig` for this source unit.
 */
function defaultModuleConfig(moduleType: CodeModule['moduleType']) {
  if (moduleType === 'DB_LOAD') {
    return JSON.stringify(
      {
        table: 'measurements',
        limit: 100,
        orderBy: 'created_at',
        orderDirection: 'DESC',
        columns: ['device_id', 'value', 'created_at'],
      },
      null,
      2
    );
  }
  if (moduleType === 'DB_SAVE') {
    return JSON.stringify(
      {
        table: 'measurements',
        mode: 'append',
        keyColumns: ['device_id', 'created_at'],
        columnModel: {
          device_id: 'TEXT',
          value: 'DOUBLE PRECISION',
          created_at: 'TIMESTAMPTZ',
        },
      },
      null,
      2
    );
  }
  return '{}';
}

export default function ModuleEditor() {
  const { t } = useTranslation();
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { user, selectedTenantId, tenantContexts } = useAuthStore();
  const canWriteModules = Boolean(user?.superadmin || user?.abilities?.includes('*') || user?.abilities?.includes('module:write'));
  
  const [module, setModule] = useState<CodeModule | null>(null);
  const [code, setCode] = useState('');
  const [requirements, setRequirements] = useState('pandas\nnumpy');
  const [configText, setConfigText] = useState('{}');
  const [versionTag, setVersionTag] = useState('');
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [toast, setToast] = useState<{ message: string; type: 'success' | 'error' } | null>(null);
  const [loadedVersionId, setLoadedVersionId] = useState<string | null>(null);
  const [loadedCodeSnapshot, setLoadedCodeSnapshot] = useState('');
  const [loadedRequirementsSnapshot, setLoadedRequirementsSnapshot] = useState('');
  const [loadedConfigSnapshot, setLoadedConfigSnapshot] = useState('{}');
  const [pendingLoadVersionId, setPendingLoadVersionId] = useState<string | null>(null);

  const tenantId = selectedTenantId || user?.tenantId || tenantContexts[0]?.tenantId;
  const hasUnsavedChanges =
    code !== loadedCodeSnapshot ||
    requirements !== loadedRequirementsSnapshot ||
    configText !== loadedConfigSnapshot;

  /**
   * Implements `useEffect` for this source unit.
   */
  useEffect(() => {
    /**
     * Implements `fetchModule` for this source unit.
     */
    const fetchModule = async () => {
      if (!id) return;
      
      try {
        const data = await api.getModule(id);
        setModule(data);
        
        if (data.versions && data.versions.length > 0) {
          const latestCode = data.versions[0].code || TEMPLATES[data.moduleType as keyof typeof TEMPLATES] || TEMPLATES.PROCESSING;
          const latestRequirements = data.versions[0].requirements || 'pandas\nnumpy';
          const latestConfig = JSON.stringify(data.versions[0].config || {}, null, 2);
          setCode(latestCode);
          setRequirements(latestRequirements);
          setConfigText(latestConfig);
          setLoadedVersionId(data.versions[0].id);
          setLoadedCodeSnapshot(latestCode);
          setLoadedRequirementsSnapshot(latestRequirements);
          setLoadedConfigSnapshot(latestConfig);
        } else {
          const templateCode = TEMPLATES[data.moduleType as keyof typeof TEMPLATES] || TEMPLATES.PROCESSING;
          const defaultRequirements = 'pandas\nnumpy';
          const defaultConfig = defaultModuleConfig(data.moduleType);
          setCode(templateCode);
          setRequirements(defaultRequirements);
          setConfigText(defaultConfig);
          setLoadedVersionId(null);
          setLoadedCodeSnapshot(templateCode);
          setLoadedRequirementsSnapshot(defaultRequirements);
          setLoadedConfigSnapshot(defaultConfig);
        }
      } catch (error) {
        console.error('Error fetching module:', error);
        setCode(TEMPLATES.PROCESSING);
      } finally {
        setLoading(false);
      }
    };
    
    fetchModule();
  }, [id]);

  /**
   * Implements `showToast` for this source unit.
   */
  const showToast = (message: string, type: 'success' | 'error') => {
    setToast({ message, type });
    setTimeout(() => setToast(null), 3500);
  };

  /**
   * Processes the named request, event, or operation.
   */
  const handleSave = async () => {
    if (!id || !tenantId || !canWriteModules) return;
    
    setSaving(true);
    try {
      const parsedConfig = parseConfigText();
      if (!parsedConfig.ok) {
        showToast(parsedConfig.error, 'error');
        setSaving(false);
        return;
      }
      await api.createModuleVersion(id, code, requirements, versionTag || undefined, parsedConfig.value);
      const versions = await api.getModuleVersions(id);
      setModule((prev) => prev ? { ...prev, versions } : prev);
      if (versions.length > 0) {
        setLoadedVersionId(versions[0].id);
      }
      setLoadedCodeSnapshot(code);
      setLoadedRequirementsSnapshot(requirements);
      setLoadedConfigSnapshot(configText);
      setPendingLoadVersionId(null);
      setVersionTag('');
      showToast(t('modules.versionSaved'), 'success');
    } catch (error) {
      console.error('Error saving version:', error);
      showToast(t('modules.versionLoadFailed'), 'error');
    } finally {
      setSaving(false);
    }
  };

  if (loading) {
    return (
      <div className="flex items-center justify-center h-screen">
        <div className="animate-spin rounded-full h-12 w-12 border-b-2 border-primary-600"></div>
      </div>
    );
  }

  if (!module) {
    return (
      <div className="text-center py-12">
        <p className="text-gray-500">{t('modules.moduleNotFound')}</p>
        <button onClick={() => navigate('/modules')} className="btn-primary mt-4">
          {t('modules.backToModules')}
        </button>
      </div>
    );
  }

  /**
   * Parses external or persisted input into the required representation.
   */
  function parseConfigText():
    | { ok: true; value: Record<string, unknown> }
    | { ok: false; error: string } {
    try {
      const parsed = JSON.parse(configText || '{}');
      if (!parsed || Array.isArray(parsed) || typeof parsed !== 'object') {
        return { ok: false, error: 'Module config must be a JSON object.' };
      }
      return { ok: true, value: parsed as Record<string, unknown> };
    } catch {
      return { ok: false, error: 'Module config must be valid JSON.' };
    }
  }

  /**
   * Implements `refreshModule` for this source unit.
   */
  const refreshModule = async () => {
    if (!id) return;
    const data = await api.getModule(id);
    setModule(data);
  };

  /**
   * Processes the named request, event, or operation.
   */
  const handleSetDefaultVersion = async (versionId: string) => {
    if (!id || !canWriteModules) return;
    try {
      const updated = await api.setDefaultModuleVersion(id, versionId);
      setModule(updated);
      showToast('Default version updated.', 'success');
    } catch (error) {
      console.error('Error setting default version:', error);
      showToast('Failed to update default version.', 'error');
    }
  };

  /**
   * Processes the named request, event, or operation.
   */
  const handleToggleVersionActive = async (versionId: string, isActive: boolean) => {
    if (!id || !canWriteModules) return;
    try {
      await api.updateModuleVersion(id, versionId, { isActive: !isActive });
      await refreshModule();
      showToast(`Version ${!isActive ? 'activated' : 'deactivated'}.`, 'success');
    } catch (error) {
      console.error('Error updating version:', error);
      showToast('Failed to update version state.', 'error');
    }
  };

  /**
   * Retrieves or enumerates the requested state.
   */
  const loadVersionIntoEditor = (versionId: string, skipDirtyGuard = false) => {
    const selectedVersion = module.versions.find((version) => version.id === versionId);
    if (!selectedVersion) return;
    if (hasUnsavedChanges && !skipDirtyGuard) {
      setPendingLoadVersionId(versionId);
      return;
    }

    const nextCode = selectedVersion.code || TEMPLATES[module.moduleType as keyof typeof TEMPLATES] || TEMPLATES.PROCESSING;
    const nextRequirements = selectedVersion.requirements || 'pandas\nnumpy';
    const nextConfig = JSON.stringify(selectedVersion.config || {}, null, 2);
    setCode(nextCode);
    setRequirements(nextRequirements);
    setConfigText(nextConfig);
    setLoadedVersionId(selectedVersion.id);
    setLoadedCodeSnapshot(nextCode);
    setLoadedRequirementsSnapshot(nextRequirements);
    setLoadedConfigSnapshot(nextConfig);
    setPendingLoadVersionId(null);
    showToast(t('modules.versionLoaded', { version: selectedVersion.version }), 'success');
  };

  return (
    <div className="h-screen flex flex-col">
      {toast && (
        <div className={`fixed top-4 right-4 z-50 px-4 py-3 rounded shadow-lg text-white text-sm ${
          toast.type === 'success' ? 'bg-green-600' : 'bg-red-600'
        }`}>
          {toast.message}
        </div>
      )}

      <div className="flex justify-between items-center p-4 bg-white border-b">
        <div>
          <h1 className="text-xl font-bold">{module.name}</h1>
          <p className="text-sm text-gray-500">{t(`modules.${module.moduleType.toLowerCase()}`)} {t('modules.title')}</p>
          {!canWriteModules && (
            <p className="text-xs text-amber-700 mt-1">{t('modules.readOnlyNotice')}</p>
          )}
          {hasUnsavedChanges && (
            <p className="text-xs text-amber-700 mt-1">{t('common.unsavedChanges')}</p>
          )}
        </div>
        <div className="flex space-x-3">
          <button
            onClick={() => navigate('/modules')}
            className="btn-secondary"
          >
            {t('common.cancel')}
          </button>
          <button
            onClick={handleSave}
            disabled={saving || !canWriteModules}
            className="btn-primary"
            title={!canWriteModules ? t('modules.readOnlyNotice') : undefined}
          >
            {saving ? t('common.saving') : t('common.save')}
          </button>
        </div>
      </div>

      <div className="flex-1 flex">
        <div className="w-3/4 border-r">
          <Editor
            height="100%"
            defaultLanguage="python"
            value={code}
            onChange={(value) => setCode(value || '')}
            theme="vs-dark"
            options={{
              minimap: { enabled: false },
              fontSize: 14,
              lineNumbers: 'on',
              scrollBeyondLastLine: false,
              automaticLayout: true,
            }}
          />
        </div>
        
        <div className="w-1/4 p-4 bg-gray-50 overflow-y-auto">
          <h3 className="font-semibold mb-4">{t('modules.configuration')}</h3>
          
          <div className="mb-4">
            <label className="block text-sm font-medium text-gray-700 mb-1">
              {t('modules.requirements')}
            </label>
            <textarea
              value={requirements}
              onChange={(e) => setRequirements(e.target.value)}
              className="input h-32 font-mono text-sm"
              placeholder="pandas&#10;numpy&#10;scikit-learn"
            />
          </div>

          <div className="mb-4">
            <label className="block text-sm font-medium text-gray-700 mb-1">
              Module config (JSON)
            </label>
            <textarea
              value={configText}
              onChange={(e) => setConfigText(e.target.value)}
              className="input h-40 font-mono text-sm"
              placeholder="{&#10;  &quot;table&quot;: &quot;measurements&quot;&#10;}"
            />
          </div>
          
          <div className="mb-4">
            <label className="block text-sm font-medium text-gray-700 mb-1">
              {t('modules.versionTag')}
            </label>
            <input
              type="text"
              value={versionTag}
              onChange={(e) => setVersionTag(e.target.value)}
              className="input"
              placeholder={t('modules.versionTagPlaceholder')}
            />
          </div>

          <div className="border-t pt-4 mt-4">
            <h4 className="font-medium text-sm mb-2">{t('modules.versionHistory')}</h4>
            {pendingLoadVersionId && (
              <div className="mb-3 rounded-sm border border-amber-200 bg-amber-50 px-3 py-2 text-xs text-amber-900">
                <p className="mb-2">{t('modules.unsavedChangesPrompt')}</p>
                <div className="flex items-center gap-2">
                  <button
                    onClick={() => loadVersionIntoEditor(pendingLoadVersionId, true)}
                    className="px-2 py-0.5 rounded-sm border border-amber-300 bg-white hover:bg-amber-100"
                  >
                    {t('modules.loadAnyway')}
                  </button>
                  <button
                    onClick={() => setPendingLoadVersionId(null)}
                    className="px-2 py-0.5 rounded-sm border border-amber-300 bg-white hover:bg-amber-100"
                  >
                    {t('modules.keepEditing')}
                  </button>
                </div>
              </div>
            )}
            {module.versions.length > 0 ? (
              <div className="space-y-2">
                {module.versions.map((v) => (
                  <div key={v.id} className="p-2 bg-white rounded-sm border text-sm">
                    <div className="flex justify-between">
                      <span className="font-medium">v{v.version}</span>
                      <div className="flex items-center gap-1">
                        {module.defaultVersionId === v.id && (
                          <span className="text-xs bg-blue-100 px-2 py-0.5 rounded-sm text-blue-800">
                            Default
                          </span>
                        )}
                        {!v.isActive && (
                          <span className="text-xs bg-amber-100 px-2 py-0.5 rounded-sm text-amber-800">
                            Inactive
                          </span>
                        )}
                        {v.versionTag && (
                          <span className="text-xs bg-gray-100 px-2 py-0.5 rounded-sm">
                            {v.versionTag}
                          </span>
                        )}
                      </div>
                    </div>
                    <div className="text-xs text-gray-500">
                      {new Date(v.createdAt).toLocaleString()}
                    </div>
                    <div className="mt-2 flex items-center gap-2">
                      <button
                        onClick={() => loadVersionIntoEditor(v.id)}
                        className="px-2 py-0.5 text-xs rounded-sm border border-gray-300 text-gray-700 hover:bg-gray-50"
                      >
                        {t('common.load')}
                      </button>
                      {module.defaultVersionId !== v.id && v.isActive && (
                        <button
                          onClick={() => handleSetDefaultVersion(v.id)}
                          className="px-2 py-0.5 text-xs rounded-sm border border-blue-300 text-blue-700 hover:bg-blue-50"
                        >
                          Set default
                        </button>
                      )}
                      <button
                        onClick={() => handleToggleVersionActive(v.id, v.isActive)}
                        className="px-2 py-0.5 text-xs rounded-sm border border-gray-300 text-gray-700 hover:bg-gray-50"
                      >
                        {v.isActive ? 'Deactivate' : 'Activate'}
                      </button>
                      {loadedVersionId === v.id && (
                        <span className="text-[11px] text-green-700">{t('modules.loaded')}</span>
                      )}
                    </div>
                  </div>
                ))}
              </div>
            ) : (
              <p className="text-sm text-gray-500">{t('modules.noVersionsYet')}</p>
            )}
          </div>

          <div className="border-t pt-4 mt-4">
            <h4 className="font-medium text-sm mb-2">{t('modules.moduleTemplate')}</h4>
            <p className="text-xs text-gray-600 mb-2">
              {t(`modules.${module.moduleType.toLowerCase()}Description`)}
            </p>
            <div className="flex space-x-2 mb-2">
              {Object.keys(TEMPLATES).map((type) => (
                <button
                  key={type}
                  onClick={() => setCode(TEMPLATES[type as keyof typeof TEMPLATES])}
                  className={`text-xs px-2 py-1 rounded ${
                    code === TEMPLATES[type as keyof typeof TEMPLATES]
                      ? 'bg-primary-100 text-primary-800'
                      : 'bg-gray-100 text-gray-600 hover:bg-gray-200'
                  }`}
                >
                  {type}
                </button>
              ))}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
