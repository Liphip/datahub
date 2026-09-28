import { useCallback, useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useAuthStore } from '../stores/auth';
import api from '../services/api';
import type { ApiKey, Connector, Pipeline } from '../types';

const errorMessage = (err: unknown, fallback: string) =>
  (err as { response?: { data?: { error?: string; message?: string } } })?.response?.data?.message
  ?? (err as { response?: { data?: { error?: string } } })?.response?.data?.error
  ?? fallback;

/** Ingest credentials: create (shown once), inspect grants and last use, revoke. */
export default function ApiKeys() {
  const { t } = useTranslation();
  const { user, selectedTenantId, tenantContexts } = useAuthStore();
  const canManage = Boolean(user?.superadmin || user?.abilities?.includes('*') || user?.abilities?.includes('secret:manage'));
  const tenantId = selectedTenantId || user?.tenantId || tenantContexts[0]?.tenantId;

  const [keys, setKeys] = useState<ApiKey[]>([]);
  const [pipelines, setPipelines] = useState<Pipeline[]>([]);
  const [connectors, setConnectors] = useState<Connector[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [creating, setCreating] = useState(false);
  const [created, setCreated] = useState<ApiKey | null>(null);

  const load = useCallback(async () => {
    if (!tenantId || !canManage) return;
    const [k, p, c] = await Promise.all([
      api.getApiKeys(tenantId),
      api.getPipelines(tenantId).catch(() => [] as Pipeline[]),
      api.getConnectors(tenantId, undefined, 'INPUT').catch(() => [] as Connector[]),
    ]);
    setKeys(k);
    setPipelines(p);
    setConnectors(c);
  }, [tenantId, canManage]);

  useEffect(() => {
    load().then(() => setError('')).catch((err) => setError(errorMessage(err, t('common.loadingFailed')))).finally(() => setLoading(false));
  }, [load, t]);

  const names = useMemo(() => new Map<string, string>([
    ...pipelines.map((p) => [p.id, p.name] as [string, string]),
    ...connectors.map((c) => [c.id, c.name] as [string, string]),
  ]), [pipelines, connectors]);

  const revoke = async (key: ApiKey) => {
    if (!window.confirm(t('apiKeys.revokeConfirm', { name: key.name }))) return;
    try {
      await api.revokeApiKey(key.id);
      await load();
    } catch (err) {
      setError(errorMessage(err, t('common.saveFailed')));
    }
  };

  if (!tenantId) return <div className="card text-center py-12"><p className="text-gray-500">{t('common.noTenantId')}</p></div>;
  if (!canManage) return <div className="card text-center py-12"><p className="text-gray-500">{t('apiKeys.noPermission')}</p></div>;

  const status = (key: ApiKey) => {
    if (!key.isActive) return { label: t('apiKeys.revoked'), cls: 'bg-gray-100 text-gray-700' };
    if (key.expiresAt && new Date(key.expiresAt) < new Date()) return { label: t('apiKeys.expired'), cls: 'bg-amber-100 text-amber-800' };
    return { label: t('apiKeys.active'), cls: 'bg-green-100 text-green-800' };
  };

  return (
    <div className="px-4 py-6 sm:px-0">
      <div className="flex flex-wrap justify-between items-center gap-3 mb-2">
        <h1 className="text-2xl font-bold text-gray-900">{t('apiKeys.title')}</h1>
        <button className="btn-primary text-sm" onClick={() => setCreating(true)}>{t('apiKeys.create')}</button>
      </div>
      <p className="mb-4 text-sm text-slate-600">{t('apiKeys.intro')}</p>
      {error && <div className="mb-4 bg-red-50 border border-red-200 text-red-700 px-4 py-3 rounded-sm">{error}</div>}

      {loading ? (
        <div className="card animate-pulse"><div className="h-4 bg-gray-200 rounded-sm w-1/3" /></div>
      ) : keys.length === 0 ? (
        <div className="card text-center py-10 text-slate-500">{t('apiKeys.none')}</div>
      ) : (
        <ul className="space-y-3">
          {keys.map((key) => {
            const s = status(key);
            const grants = [...key.pipelineIds, ...key.connectorIds].map((id) => names.get(id) ?? id);
            return (
              <li key={key.id} className="card p-4!">
                <div className="flex flex-wrap items-start justify-between gap-2">
                  <div className="min-w-0">
                    <div className="flex items-center gap-2">
                      <span className="font-semibold">{key.name}</span>
                      <span className={`px-2 py-0.5 text-xs rounded-sm ${s.cls}`}>{s.label}</span>
                    </div>
                    <div className="mt-1 text-sm text-slate-600 break-words">
                      <span className="text-slate-400">{t('apiKeys.grants')}:</span> {grants.join(', ')}
                    </div>
                    <div className="mt-1 text-xs text-slate-500">
                      {t('apiKeys.lastUsed')}: {key.lastUsedAt ? new Date(key.lastUsedAt).toLocaleString() : t('apiKeys.never')}
                      {' · '}
                      {key.expiresAt ? new Date(key.expiresAt).toLocaleDateString() : t('apiKeys.noExpiry')}
                    </div>
                  </div>
                  {key.isActive && (
                    <button className="text-sm text-red-600 hover:text-red-800" onClick={() => revoke(key)}>{t('apiKeys.revoke')}</button>
                  )}
                </div>
              </li>
            );
          })}
        </ul>
      )}

      {creating && (
        <CreateKey
          tenantId={tenantId}
          pipelines={pipelines}
          connectors={connectors}
          onClose={() => setCreating(false)}
          onCreated={async (key) => { setCreating(false); setCreated(key); await load(); }}
        />
      )}
      {created && <ShowKey apiKey={created} pipelines={pipelines} onClose={() => setCreated(null)} />}
    </div>
  );
}

function CreateKey({ tenantId, pipelines, connectors, onClose, onCreated }: {
  tenantId: string;
  pipelines: Pipeline[];
  connectors: Connector[];
  onClose: () => void;
  onCreated: (key: ApiKey) => void;
}) {
  const { t } = useTranslation();
  const [name, setName] = useState('');
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [expires, setExpires] = useState('');
  const [error, setError] = useState('');
  const [saving, setSaving] = useState(false);

  const toggle = (id: string) => setSelected((prev) => {
    const next = new Set(prev);
    if (next.has(id)) next.delete(id); else next.add(id);
    return next;
  });

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (selected.size === 0) { setError(t('apiKeys.selectOne')); return; }
    setSaving(true);
    try {
      const key = await api.createApiKey(tenantId, {
        name: name.trim(),
        pipelineIds: pipelines.filter((p) => selected.has(p.id)).map((p) => p.id),
        connectorIds: connectors.filter((c) => selected.has(c.id)).map((c) => c.id),
        expiresAt: expires ? new Date(`${expires}T23:59:59`).toISOString() : undefined,
      });
      onCreated(key);
    } catch (err) {
      setError(errorMessage(err, t('common.saveFailed')));
    } finally {
      setSaving(false);
    }
  };

  const checklist = (items: { id: string; name: string }[]) => (
    <div className="max-h-40 overflow-y-auto rounded-sm border border-slate-200 p-2 space-y-1">
      {items.map((item) => (
        <label key={item.id} className="flex items-center gap-2 text-sm">
          <input type="checkbox" checked={selected.has(item.id)} onChange={() => toggle(item.id)} />
          <span className="truncate">{item.name}</span>
        </label>
      ))}
    </div>
  );

  return (
    <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50 p-4">
      <form onSubmit={submit} className="bg-white rounded-lg p-6 max-w-lg w-full max-h-[90vh] overflow-y-auto space-y-4">
        <h2 className="text-xl font-bold">{t('apiKeys.create')}</h2>
        {error && <div className="bg-red-50 border border-red-200 text-red-700 px-3 py-2 rounded-sm text-sm">{error}</div>}
        <label className="block text-sm">{t('apiKeys.name')}
          <input className="input mt-1" required value={name} onChange={(e) => setName(e.target.value)} placeholder={t('apiKeys.namePlaceholder')} />
        </label>
        {pipelines.length > 0 && (
          <div className="text-sm"><span className="block mb-1">{t('apiKeys.pipelines')}</span>{checklist(pipelines)}</div>
        )}
        {connectors.length > 0 && (
          <div className="text-sm"><span className="block mb-1">{t('apiKeys.connectors')}</span>{checklist(connectors.map((c) => ({ id: c.id, name: c.name })))}</div>
        )}
        <label className="block text-sm">{t('apiKeys.expires')}
          <input type="date" className="input mt-1" value={expires} onChange={(e) => setExpires(e.target.value)} />
        </label>
        <div className="flex justify-end gap-2">
          <button type="button" className="btn-secondary" onClick={onClose}>{t('common.cancel')}</button>
          <button type="submit" className="btn-primary" disabled={saving}>{saving ? t('common.saving') : t('common.create')}</button>
        </div>
      </form>
    </div>
  );
}

function ShowKey({ apiKey, pipelines, onClose }: { apiKey: ApiKey; pipelines: Pipeline[]; onClose: () => void }) {
  const { t } = useTranslation();
  const [copied, setCopied] = useState(false);
  const pipelineId = apiKey.pipelineIds[0] ?? pipelines[0]?.id ?? '<pipeline-id>';
  const example = apiKey.connectorIds.length > 0 && apiKey.pipelineIds.length === 0
    ? `curl -X POST ${window.location.origin}/api/ingest/v1/ingest/connector/${apiKey.connectorIds[0]} \\\n  -H "X-API-Key: ${apiKey.key}" -H "Content-Type: application/json" \\\n  -d '{"device": "d-1", "value": 21.5}'`
    : `curl -X POST ${window.location.origin}/api/ingest/v1/ingest/${pipelineId} \\\n  -H "X-API-Key: ${apiKey.key}" -H "Content-Type: application/json" \\\n  -H "Idempotency-Key: d-1|2026-01-01T00:00:00Z" \\\n  -d '{"device": "d-1", "time": "2026-01-01T00:00:00Z", "value": 21.5}'`;

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(apiKey.key ?? '');
      setCopied(true);
    } catch {
      setCopied(false);
    }
  };

  return (
    <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50 p-4">
      <div className="bg-white rounded-lg p-6 max-w-2xl w-full max-h-[90vh] overflow-y-auto space-y-4">
        <h2 className="text-xl font-bold">{t('apiKeys.createdTitle')}</h2>
        <p className="text-sm text-amber-800 bg-amber-50 border border-amber-200 rounded-sm px-3 py-2">{t('apiKeys.createdHint')}</p>
        <div className="flex gap-2">
          <code className="flex-1 min-w-0 break-all rounded-sm bg-slate-100 px-3 py-2 text-sm font-mono" data-testid="api-key-value">{apiKey.key}</code>
          <button type="button" className="btn-secondary text-sm" onClick={copy}>{copied ? t('apiKeys.copied') : t('apiKeys.copy')}</button>
        </div>
        <div>
          <p className="text-xs uppercase text-slate-500 mb-1">{t('apiKeys.example')}</p>
          <pre className="text-xs bg-slate-50 rounded-sm p-3 overflow-x-auto whitespace-pre-wrap break-all">{example}</pre>
        </div>
        <div className="flex justify-end"><button className="btn-primary" onClick={onClose}>{t('apiKeys.done')}</button></div>
      </div>
    </div>
  );
}
