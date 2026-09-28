import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useAuthStore } from '../stores/auth';
import api from '../services/api';
import { parseCsv } from '../lib/csv';
import type { MetadataObjectDetail, MetadataObjectSummary, MetadataPeriod, MetadataTemplate, MetadataType, SaveMetadataMapping } from '../types';

const PAGE_SIZE = 50;

/** Extracts the backend's `{error}` message from a failed request. */
const errorMessage = (err: unknown, fallback: string) =>
  (err as { response?: { data?: { error?: string } } })?.response?.data?.error || fallback;

/** Formats a validity period; open bounds are shown as "always" / "open". */
function Period({ period }: { period: MetadataPeriod }) {
  const { t } = useTranslation();
  const fmt = (v?: string | null) => (v ? new Date(v).toLocaleString() : null);
  return (
    <span className="text-xs text-slate-500 whitespace-nowrap">
      {fmt(period.validFrom) ?? t('metadata.sinceAlways')} → {fmt(period.validTo) ?? t('metadata.open')}
    </span>
  );
}

const renderValue = (v: unknown) => (v == null ? '—' : typeof v === 'object' ? JSON.stringify(v) : String(v));

/** One line per entry: `scheme [pattern]`, `name [unit]` or `relation -> targetType`. */
const parseLines = (text: string) => text.split('\n').map((l) => l.trim()).filter(Boolean);

export default function Metadata() {
  const { t } = useTranslation();
  const { user, selectedTenantId, tenantContexts } = useAuthStore();
  const abilities = user?.abilities || [];
  const hasAbility = (ability: string) => Boolean(user?.superadmin || abilities.includes('*') || abilities.includes(ability));
  const canWrite = hasAbility('metadata:write');
  const canRead = canWrite || hasAbility('metadata:read');
  const tenantId = selectedTenantId || user?.tenantId || tenantContexts[0]?.tenantId;

  const [tab, setTab] = useState<'objects' | 'types'>('objects');
  const [types, setTypes] = useState<MetadataType[]>([]);
  const [templates, setTemplates] = useState<MetadataTemplate[]>([]);
  const [objects, setObjects] = useState<MetadataObjectSummary[]>([]);
  const [total, setTotal] = useState(0);
  const [offset, setOffset] = useState(0);
  const [typeFilter, setTypeFilter] = useState('');
  const [search, setSearch] = useState('');
  const [query, setQuery] = useState('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [editing, setEditing] = useState<MetadataObjectDetail | 'new' | null>(null);
  const [importing, setImporting] = useState(false);
  const [creatingType, setCreatingType] = useState(false);
  const [toast, setToast] = useState<{ message: string; type: 'success' | 'error' } | null>(null);
  const toastTimer = useRef<ReturnType<typeof setTimeout>>(undefined);

  const showToast = useCallback((message: string, type: 'success' | 'error') => {
    clearTimeout(toastTimer.current);
    setToast({ message, type });
    toastTimer.current = setTimeout(() => setToast(null), 3500);
  }, []);
  useEffect(() => () => clearTimeout(toastTimer.current), []);

  const loadTypes = useCallback(async () => {
    if (!tenantId || !canRead) return;
    const [typeList, templateList] = await Promise.all([api.getMetadataTypes(tenantId), api.getMetadataTemplates()]);
    setTypes(typeList);
    setTemplates(templateList);
  }, [tenantId, canRead]);

  const loadObjects = useCallback(async () => {
    if (!tenantId || !canRead) return;
    const page = await api.getMetadataObjects(tenantId, { type: typeFilter || undefined, q: query || undefined, limit: PAGE_SIZE, offset });
    setObjects(page.items);
    setTotal(page.total);
  }, [tenantId, canRead, typeFilter, query, offset]);

  useEffect(() => {
    if (!tenantId || !canRead) { setLoading(false); return; }
    loadTypes().catch(() => setError(t('common.loadingFailed')));
  }, [tenantId, canRead, loadTypes, t]);

  useEffect(() => {
    if (!tenantId || !canRead) return;
    setLoading(true);
    loadObjects()
      .then(() => setError(''))
      .catch(() => setError(t('common.loadingFailed')))
      .finally(() => setLoading(false));
  }, [tenantId, canRead, loadObjects, t]);

  const refresh = async () => {
    await Promise.all([loadTypes(), loadObjects()]);
  };

  const installTemplate = async (key: string) => {
    if (!tenantId) return;
    try {
      const result = await api.installMetadataTemplate(tenantId, key);
      showToast(t('metadata.templateInstalled', { count: result.installed.length }), 'success');
      await loadTypes();
    } catch (err) {
      showToast(errorMessage(err, t('common.saveFailed')), 'error');
    }
  };

  const deleteType = async (type: MetadataType) => {
    if (!window.confirm(t('metadata.deleteTypeConfirm', { name: type.name }))) return;
    try {
      await api.deleteMetadataType(type.id);
      await loadTypes();
    } catch (err) {
      showToast(errorMessage(err, t('common.deleteFailed')), 'error');
    }
  };

  if (!tenantId) {
    return <div className="card text-center py-12"><p className="text-gray-500">{t('common.noTenantId')}</p></div>;
  }
  if (!canRead) {
    return <div className="card text-center py-12"><p className="text-gray-500">{t('metadata.noPermission')}</p></div>;
  }

  return (
    <div className="px-4 py-6 sm:px-0">
      {toast && (
        <div className={`fixed top-4 right-4 z-50 px-4 py-3 rounded shadow-lg text-white text-sm ${toast.type === 'success' ? 'bg-green-600' : 'bg-red-600'}`}>
          {toast.message}
        </div>
      )}
      <div className="flex flex-wrap justify-between items-center gap-3 mb-2">
        <h1 className="text-2xl font-bold text-gray-900">{t('metadata.title')}</h1>
        {canWrite && tab === 'objects' && types.length > 0 && (
          <div className="flex gap-2">
            <button className="btn-secondary text-sm" onClick={() => setImporting(true)}>{t('metadata.importCsv')}</button>
            <button className="btn-primary text-sm" onClick={() => setEditing('new')}>{t('metadata.addObject')}</button>
          </div>
        )}
        {canWrite && tab === 'types' && (
          <button className="btn-primary text-sm" onClick={() => setCreatingType(true)}>{t('metadata.addType')}</button>
        )}
      </div>
      <p className="mb-4 text-sm text-slate-600">{t('metadata.intro')}</p>

      {error && <div className="mb-4 bg-red-50 border border-red-200 text-red-700 px-4 py-3 rounded-sm">{error}</div>}

      <div className="mb-4 flex space-x-2">
        {(['objects', 'types'] as const).map((key) => (
          <button
            key={key}
            onClick={() => setTab(key)}
            className={`px-4 py-2 rounded-lg text-sm font-medium ${tab === key ? 'bg-primary-600 text-white' : 'bg-white text-slate-700 border border-slate-200'}`}
          >
            {t(`metadata.tab.${key}`)}
          </button>
        ))}
      </div>

      {tab === 'objects' && types.length === 0 && !loading && (
        <div className="card text-center py-10 space-y-3">
          <p className="text-slate-600">{t('metadata.noTypes')}</p>
          <button className="btn-secondary text-sm" onClick={() => setTab('types')}>{t('metadata.goToTypes')}</button>
        </div>
      )}

      {tab === 'objects' && types.length > 0 && (
        <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]">
          <div className="card p-4! space-y-3 min-w-0">
            <form
              className="flex flex-wrap gap-2"
              onSubmit={(e) => { e.preventDefault(); setOffset(0); setQuery(search.trim()); }}
            >
              <select className="input w-auto!" value={typeFilter} onChange={(e) => { setOffset(0); setTypeFilter(e.target.value); }} aria-label={t('metadata.type')}>
                <option value="">{t('metadata.allTypes')}</option>
                {types.map((type) => <option key={type.id} value={type.typeKey}>{type.name}</option>)}
              </select>
              <input className="input flex-1 min-w-40" value={search} onChange={(e) => setSearch(e.target.value)} placeholder={t('metadata.searchPlaceholder')} />
              <button className="btn-secondary text-sm" type="submit">{t('common.search')}</button>
            </form>
            {loading ? (
              <div className="h-4 bg-gray-200 rounded-sm w-1/3 animate-pulse" />
            ) : objects.length === 0 ? (
              <p className="text-sm text-slate-500 py-6 text-center">{t('metadata.noObjects')}</p>
            ) : (
              <ul className="divide-y divide-slate-100 text-sm">
                {objects.map((o) => (
                  <li key={o.id}>
                    <button
                      type="button"
                      onClick={() => setSelectedId(o.id)}
                      className={`w-full text-left px-2 py-2 rounded-sm hover:bg-slate-50 ${selectedId === o.id ? 'bg-primary-50' : ''}`}
                    >
                      <div className="flex flex-wrap items-baseline justify-between gap-x-3">
                        <span className="font-medium break-all">{o.name}</span>
                        <span className="text-xs text-slate-500">{o.typeName}</span>
                      </div>
                      <div className="mt-0.5 flex flex-wrap gap-x-3 gap-y-0.5 font-mono text-xs text-slate-500">
                        {Object.entries(o.identifiers).filter(([, v]) => v !== o.name).map(([k, v]) => (
                          <span key={k} className="break-all"><span className="text-slate-400">{k}</span> {v}</span>
                        ))}
                      </div>
                    </button>
                  </li>
                ))}
              </ul>
            )}
            <div className="flex justify-between items-center text-xs text-slate-500">
              <span>{t('metadata.showing', { from: total === 0 ? 0 : offset + 1, to: Math.min(offset + PAGE_SIZE, total), total })}</span>
              <div className="flex gap-2">
                <button className="btn-secondary text-xs px-2! py-1!" disabled={offset === 0} onClick={() => setOffset(Math.max(0, offset - PAGE_SIZE))}>{t('common.previous')}</button>
                <button className="btn-secondary text-xs px-2! py-1!" disabled={offset + PAGE_SIZE >= total} onClick={() => setOffset(offset + PAGE_SIZE)}>{t('common.next')}</button>
              </div>
            </div>
          </div>
          <div className="min-w-0">
            {selectedId ? (
              <ObjectDetail
                key={selectedId}
                id={selectedId}
                canWrite={canWrite}
                onNavigate={setSelectedId}
                onEdit={setEditing}
                onDeleted={async () => { setSelectedId(null); await refresh(); }}
                showToast={showToast}
              />
            ) : (
              <div className="card text-center py-12 text-sm text-slate-500">{t('metadata.selectObject')}</div>
            )}
          </div>
        </div>
      )}

      {tab === 'types' && (
        <div className="space-y-4">
          {canWrite && templates.length > 0 && (
            <div className="rounded-sm border border-blue-200 bg-blue-50 px-4 py-3 text-sm text-blue-900 space-y-2">
              <p className="font-semibold">{t('metadata.templatesTitle')}</p>
              {templates.map((tpl) => (
                <div key={tpl.key} className="flex flex-wrap items-center justify-between gap-2">
                  <div>
                    <span className="font-medium">{tpl.name}</span>
                    <span className="text-blue-800"> — {tpl.description}</span>
                    <div className="text-xs text-blue-700 font-mono">{tpl.types.join(', ')}</div>
                  </div>
                  <button className="btn-secondary text-xs" onClick={() => installTemplate(tpl.key)}>{t('metadata.install')}</button>
                </div>
              ))}
              <p className="text-xs text-blue-700">{t('metadata.templatesHint')}</p>
            </div>
          )}
          {types.length === 0 && <div className="card text-center py-8 text-slate-500">{t('metadata.noTypes')}</div>}
          {types.map((type) => (
            <div key={type.id} className="card">
              <div className="flex flex-wrap justify-between gap-2">
                <div>
                  <h3 className="text-lg font-semibold">{type.name} <span className="font-mono text-sm text-slate-500">{type.typeKey}</span></h3>
                  {type.description && <p className="text-sm text-slate-500">{type.description}</p>}
                </div>
                <div className="flex items-center gap-3 text-sm">
                  <span className="text-slate-500">{t('metadata.objectCount', { count: type.objectCount })}</span>
                  {type.template && <span className="px-2 py-1 text-xs rounded-sm bg-slate-100">{t('metadata.fromTemplate', { template: type.template })}</span>}
                  {canWrite && type.objectCount === 0 && (
                    <button className="text-red-600 hover:text-red-800" onClick={() => deleteType(type)}>{t('common.delete')}</button>
                  )}
                </div>
              </div>
              <dl className="mt-3 grid gap-3 sm:grid-cols-3 text-sm">
                <div>
                  <dt className="text-xs uppercase text-slate-500">{t('metadata.identifierSchemes')}</dt>
                  {type.identifierSchemes.map((s) => (
                    <dd key={s.scheme} className="font-mono text-xs">{s.scheme}{s.pattern && <span className="text-slate-400"> {s.pattern}</span>}</dd>
                  ))}
                </div>
                <div>
                  <dt className="text-xs uppercase text-slate-500">{t('metadata.attributes')}</dt>
                  {type.attributeSchema.map((a) => (
                    <dd key={a.name} className="font-mono text-xs">{a.name}{a.unit && <span className="text-slate-400"> [{a.unit}]</span>}</dd>
                  ))}
                </div>
                <div>
                  <dt className="text-xs uppercase text-slate-500">{t('metadata.relations')}</dt>
                  {type.relationSchema.map((r) => (
                    <dd key={r.relation} className="font-mono text-xs">{r.relation} → {r.targetType ?? '*'}</dd>
                  ))}
                </div>
              </dl>
            </div>
          ))}
        </div>
      )}

      {editing && (
        <ObjectEditor
          tenantId={tenantId}
          types={types}
          existing={editing === 'new' ? null : editing}
          onClose={() => setEditing(null)}
          onSaved={async (id) => { setEditing(null); setSelectedId(id); await refresh(); showToast(t('common.saved'), 'success'); }}
        />
      )}
      {importing && (
        <CsvImport
          tenantId={tenantId}
          types={types}
          onClose={() => setImporting(false)}
          onImported={async (message) => { setImporting(false); await refresh(); showToast(message, 'success'); }}
        />
      )}
      {creatingType && (
        <TypeEditor
          tenantId={tenantId}
          types={types}
          onClose={() => setCreatingType(false)}
          onSaved={async () => { setCreatingType(false); await loadTypes(); }}
        />
      )}
    </div>
  );
}

/** Object as valid at a chosen time, with the full history of identifiers, attributes and relations. */
function ObjectDetail({ id, canWrite, onNavigate, onEdit, onDeleted, showToast }: {
  id: string;
  canWrite: boolean;
  onNavigate: (id: string) => void;
  onEdit: (detail: MetadataObjectDetail) => void;
  onDeleted: () => void;
  showToast: (message: string, type: 'success' | 'error') => void;
}) {
  const { t } = useTranslation();
  const [asOf, setAsOf] = useState('');
  const [detail, setDetail] = useState<MetadataObjectDetail | null>(null);
  const [error, setError] = useState('');
  const [confirmDelete, setConfirmDelete] = useState(false);

  useEffect(() => {
    let cancelled = false;
    api.getMetadataObject(id, asOf ? new Date(asOf).toISOString() : undefined)
      .then((d) => { if (!cancelled) { setDetail(d); setError(''); } })
      .catch((err) => { if (!cancelled) setError(errorMessage(err, t('common.loadingFailed'))); });
    return () => { cancelled = true; };
  }, [id, asOf, t]);

  if (error) return <div className="card text-red-700 text-sm">{error}</div>;
  if (!detail) return <div className="card animate-pulse"><div className="h-4 bg-gray-200 rounded-sm w-1/3" /></div>;

  const remove = async () => {
    try {
      await api.deleteMetadataObject(detail.id);
      onDeleted();
    } catch (err) {
      showToast(errorMessage(err, t('common.deleteFailed')), 'error');
    }
  };

  return (
    <div className="card space-y-4">
      <div className="flex flex-wrap justify-between gap-2">
        <div>
          <h2 className="text-lg font-semibold">{detail.name}</h2>
          <p className="text-xs text-slate-500">{detail.typeName} · <span className="font-mono">{detail.id}</span></p>
        </div>
        {canWrite && (
          <div className="flex items-center gap-3 text-sm">
            <button className="text-primary-600 hover:text-primary-800" onClick={() => onEdit(detail)}>{t('common.edit')}</button>
            {confirmDelete ? (
              <>
                <span className="text-xs text-slate-500">{t('metadata.deleteObjectConfirm')}</span>
                <button className="text-red-600" onClick={remove}>{t('common.delete')}</button>
                <button className="text-slate-500" onClick={() => setConfirmDelete(false)}>{t('common.cancel')}</button>
              </>
            ) : (
              <button className="text-red-600 hover:text-red-800" onClick={() => setConfirmDelete(true)}>{t('common.delete')}</button>
            )}
          </div>
        )}
      </div>

      <label className="flex flex-wrap items-center gap-2 text-sm">
        <span className="text-slate-600">{t('metadata.asOf')}</span>
        <input type="datetime-local" className="input w-auto!" value={asOf} onChange={(e) => setAsOf(e.target.value)} />
        {asOf && <button className="text-xs text-primary-600" onClick={() => setAsOf('')}>{t('metadata.now')}</button>}
      </label>

      <section>
        <h3 className="text-xs uppercase text-slate-500 mb-1">{t('metadata.identifiers')}</h3>
        <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm">
          {Object.entries(detail.identifiers).map(([k, v]) => (
            <div key={k} className="contents"><dt className="text-slate-600">{k}</dt><dd className="font-mono break-all">{v}</dd></div>
          ))}
        </dl>
      </section>

      <section>
        <h3 className="text-xs uppercase text-slate-500 mb-1">{t('metadata.attributes')}</h3>
        {Object.keys(detail.attributes).length === 0 ? <p className="text-sm text-slate-400">—</p> : (
          <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm">
            {Object.entries(detail.attributes).map(([k, v]) => (
              <div key={k} className="contents"><dt className="text-slate-600">{k}</dt><dd className="break-all">{renderValue(v)}</dd></div>
            ))}
          </dl>
        )}
      </section>

      <section>
        <h3 className="text-xs uppercase text-slate-500 mb-1">{t('metadata.relations')}</h3>
        {detail.relations.length + detail.referencedBy.length === 0 ? <p className="text-sm text-slate-400">—</p> : (
          <ul className="space-y-1 text-sm">
            {detail.relations.map((r, i) => (
              <li key={`o${i}`} className="flex flex-wrap items-center gap-2">
                <span className="text-slate-600">{r.relation} →</span>
                <button className="text-primary-600 hover:underline break-all text-left" onClick={() => onNavigate(r.target.id)}>{r.target.name}</button>
                <Period period={r} />
              </li>
            ))}
            {detail.referencedBy.map((r, i) => (
              <li key={`i${i}`} className="flex flex-wrap items-center gap-2">
                <button className="text-primary-600 hover:underline break-all text-left" onClick={() => onNavigate(r.source.id)}>{r.source.name}</button>
                <span className="text-slate-600">→ {r.relation}</span>
                <Period period={r} />
              </li>
            ))}
          </ul>
        )}
      </section>

      <details>
        <summary className="cursor-pointer text-sm font-medium text-slate-700">{t('metadata.history')}</summary>
        <div className="mt-2 space-y-3 text-sm">
          {detail.identifierHistory.map((h, i) => (
            <div key={`id${i}`} className="flex flex-wrap gap-2 items-center">
              <span className="text-slate-600">{h.scheme}</span><span className="font-mono">{h.value}</span><Period period={h} />
            </div>
          ))}
          {detail.attributeHistory.map((h, i) => (
            <div key={`a${i}`} className="border-t border-slate-100 pt-2">
              <div className="flex flex-wrap gap-2 items-center"><Period period={h} />{h.source && <span className="text-xs text-slate-400">{h.source}</span>}</div>
              <pre className="text-xs bg-slate-50 rounded-sm p-2 overflow-x-auto">{JSON.stringify(h.attributes, null, 2)}</pre>
            </div>
          ))}
        </div>
      </details>
    </div>
  );
}

/** Creates or changes one object; changes take effect at "valid from" (default now). */
function ObjectEditor({ tenantId, types, existing, onClose, onSaved }: {
  tenantId: string;
  types: MetadataType[];
  existing: MetadataObjectDetail | null;
  onClose: () => void;
  onSaved: (id: string) => void;
}) {
  const { t } = useTranslation();
  const [typeKey, setTypeKey] = useState(existing?.typeKey ?? types[0]?.typeKey ?? '');
  const type = types.find((x) => x.typeKey === typeKey);
  const firstScheme = existing ? Object.keys(existing.identifiers)[0] : type?.identifierSchemes[0]?.scheme;
  const [scheme, setScheme] = useState(firstScheme ?? '');
  const [value, setValue] = useState(existing && firstScheme ? existing.identifiers[firstScheme] : '');
  const [name, setName] = useState(existing?.name ?? '');
  const [identifiers, setIdentifiers] = useState<Record<string, string>>(existing?.identifiers ?? {});
  const [attributes, setAttributes] = useState<Record<string, string>>(
    Object.fromEntries(Object.entries(existing?.attributes ?? {}).map(([k, v]) => [k, v == null ? '' : typeof v === 'object' ? JSON.stringify(v) : String(v)])),
  );
  const [relations, setRelations] = useState<Record<string, string>>({});
  const [validFrom, setValidFrom] = useState('');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');

  const attributeNames = useMemo(
    () => Array.from(new Set([...(type?.attributeSchema.map((a) => a.name) ?? []), ...Object.keys(attributes)])),
    [type, attributes],
  );

  /** Numbers and booleans typed into the form are stored as such. */
  const typed = (raw: string): unknown => {
    const s = raw.trim();
    if (s === '') return null;
    if (s === 'true' || s === 'false') return s === 'true';
    if (/^-?\d+(\.\d+)?$/.test(s)) return Number(s);
    return s;
  };

  const save = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!type || !scheme || !value.trim()) return;
    setSaving(true);
    setError('');
    try {
      const relationTargets = type.relationSchema
        .map((r) => ({ r, target: relations[r.relation]?.trim() }))
        .filter(({ target }) => target);
      const result = await api.upsertMetadataObject(tenantId, {
        type: type.typeKey,
        match: { scheme, value: value.trim() },
        name: name.trim() || undefined,
        identifiers: Object.fromEntries(Object.entries(identifiers).filter(([k, v]) => k !== scheme && v.trim()).map(([k, v]) => [k, v.trim()])),
        attributes: Object.fromEntries(attributeNames.filter((k) => k in attributes).map((k) => [k, typed(attributes[k])])),
        relations: relationTargets.map(({ r, target }) => {
          const targetType = types.find((x) => x.typeKey === r.targetType);
          return { relation: r.relation, type: r.targetType, scheme: targetType?.identifierSchemes[0]?.scheme, value: target, create: true };
        }),
        validFrom: validFrom ? new Date(validFrom).toISOString() : undefined,
      });
      onSaved(result.id);
    } catch (err) {
      setError(errorMessage(err, t('common.saveFailed')));
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50 p-4">
      <form onSubmit={save} className="bg-white rounded-lg p-6 max-w-2xl w-full max-h-[90vh] overflow-y-auto space-y-4">
        <h2 className="text-xl font-bold">{existing ? t('metadata.editObject') : t('metadata.addObject')}</h2>
        {error && <div className="bg-red-50 border border-red-200 text-red-700 px-3 py-2 rounded-sm text-sm">{error}</div>}
        <div className="grid gap-3 sm:grid-cols-2">
          <label className="text-sm">{t('metadata.type')}
            <select
              className="input mt-1"
              value={typeKey}
              disabled={!!existing}
              onChange={(e) => {
                setTypeKey(e.target.value);
                setScheme(types.find((x) => x.typeKey === e.target.value)?.identifierSchemes[0]?.scheme ?? '');
              }}
            >
              {types.map((x) => <option key={x.id} value={x.typeKey}>{x.name}</option>)}
            </select>
          </label>
          <label className="text-sm">{t('common.name')}
            <input className="input mt-1" value={name} onChange={(e) => setName(e.target.value)} placeholder={t('metadata.nameOptional')} />
          </label>
          <label className="text-sm">{t('metadata.matchScheme')}
            <select className="input mt-1" value={scheme} disabled={!!existing} onChange={(e) => setScheme(e.target.value)}>
              {type?.identifierSchemes.map((s) => <option key={s.scheme} value={s.scheme}>{s.name ?? s.scheme}</option>)}
            </select>
          </label>
          <label className="text-sm">{t('metadata.matchValue')}
            <input className="input mt-1 font-mono" required value={value} disabled={!!existing} onChange={(e) => setValue(e.target.value)} />
          </label>
        </div>

        {type && type.identifierSchemes.length > 1 && (
          <fieldset className="space-y-2">
            <legend className="text-xs uppercase text-slate-500">{t('metadata.otherIdentifiers')}</legend>
            {type.identifierSchemes.filter((s) => s.scheme !== scheme).map((s) => (
              <label key={s.scheme} className="grid grid-cols-[10rem_1fr] items-center gap-2 text-sm">
                <span className="text-slate-600 truncate">{s.name ?? s.scheme}</span>
                <input className="input font-mono" value={identifiers[s.scheme] ?? ''} placeholder={s.pattern} onChange={(e) => setIdentifiers({ ...identifiers, [s.scheme]: e.target.value })} />
              </label>
            ))}
          </fieldset>
        )}

        <fieldset className="space-y-2">
          <legend className="text-xs uppercase text-slate-500">{t('metadata.attributes')}</legend>
          {attributeNames.map((attr) => {
            const schema = type?.attributeSchema.find((a) => a.name === attr);
            return (
              <label key={attr} className="grid grid-cols-[10rem_1fr] items-center gap-2 text-sm">
                <span className="text-slate-600 truncate" title={schema?.description}>{attr}{schema?.unit && ` [${schema.unit}]`}</span>
                <input className="input" value={attributes[attr] ?? ''} onChange={(e) => setAttributes({ ...attributes, [attr]: e.target.value })} />
              </label>
            );
          })}
        </fieldset>

        {type && type.relationSchema.length > 0 && (
          <fieldset className="space-y-2">
            <legend className="text-xs uppercase text-slate-500">{t('metadata.relations')}</legend>
            {type.relationSchema.map((r) => {
              const targetType = types.find((x) => x.typeKey === r.targetType);
              return (
                <label key={r.relation} className="grid grid-cols-[10rem_1fr] items-center gap-2 text-sm">
                  <span className="text-slate-600 truncate">{r.relation}</span>
                  <input
                    className="input font-mono"
                    value={relations[r.relation] ?? ''}
                    placeholder={targetType ? `${targetType.name}: ${targetType.identifierSchemes[0]?.scheme ?? ''}` : ''}
                    disabled={!targetType}
                    onChange={(e) => setRelations({ ...relations, [r.relation]: e.target.value })}
                  />
                </label>
              );
            })}
            <p className="text-xs text-slate-500">{t('metadata.relationHint')}</p>
          </fieldset>
        )}

        <label className="block text-sm">{t('metadata.validFrom')}
          <input type="datetime-local" className="input mt-1" value={validFrom} onChange={(e) => setValidFrom(e.target.value)} />
          <span className="text-xs text-slate-500">{t('metadata.validFromHint')}</span>
        </label>

        <div className="flex justify-end gap-2">
          <button type="button" className="btn-secondary" onClick={onClose}>{t('common.cancel')}</button>
          <button type="submit" className="btn-primary" disabled={saving || !type}>{saving ? t('common.saving') : t('common.save')}</button>
        </div>
      </form>
    </div>
  );
}

/** Maps CSV columns to a type's identifiers, attributes and relations and imports in batches. */
function CsvImport({ tenantId, types, onClose, onImported }: {
  tenantId: string;
  types: MetadataType[];
  onClose: () => void;
  onImported: (message: string) => void;
}) {
  const { t } = useTranslation();
  const [csv, setCsv] = useState<{ columns: string[]; rows: Record<string, string>[] } | null>(null);
  const [typeKey, setTypeKey] = useState(types[0]?.typeKey ?? '');
  const type = types.find((x) => x.typeKey === typeKey);
  const [matchScheme, setMatchScheme] = useState(type?.identifierSchemes[0]?.scheme ?? '');
  const [columnFor, setColumnFor] = useState<Record<string, string>>({});
  const [nameField, setNameField] = useState('');
  const [validFromField, setValidFromField] = useState('');
  const [progress, setProgress] = useState<string>('');
  const [error, setError] = useState('');

  /** Preselects columns whose header equals a scheme, attribute or relation name. */
  const guessColumns = (data: typeof csv, target: MetadataType | undefined) => {
    if (!data || !target) return;
    const type = target;
    const lower = new Map(data.columns.map((c) => [c.toLowerCase(), c]));
    const guess: Record<string, string> = {};
    const keys = [
      ...type.identifierSchemes.map((s) => `id:${s.scheme}`),
      ...type.attributeSchema.map((a) => `attr:${a.name}`),
      ...type.relationSchema.map((r) => `rel:${r.relation}`),
    ];
    keys.forEach((k) => {
      const col = lower.get(k.split(':')[1].toLowerCase()) ?? lower.get(k.split(':')[1].split('.').pop()!.toLowerCase());
      if (col) guess[k] = col;
    });
    setColumnFor(guess);
  };

  const changeType = (key: string) => {
    const next = types.find((x) => x.typeKey === key);
    setTypeKey(key);
    setMatchScheme(next?.identifierSchemes[0]?.scheme ?? '');
    guessColumns(csv, next);
  };

  const readFile = async (file: File) => {
    try {
      const parsed = parseCsv(await file.text());
      if (parsed.rows.length === 0) throw new Error(t('metadata.csvEmpty'));
      setCsv(parsed);
      guessColumns(parsed, type);
      setError('');
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    }
  };

  const mapping = (): SaveMetadataMapping | null => {
    if (!type || !matchScheme || !columnFor[`id:${matchScheme}`]) return null;
    const pick = (prefix: string) => Object.entries(columnFor)
      .filter(([k, col]) => k.startsWith(prefix) && col)
      .map(([k, col]) => [k.slice(prefix.length), col] as const);
    return {
      type: type.typeKey,
      match: { scheme: matchScheme, field: columnFor[`id:${matchScheme}`] },
      nameField: nameField || undefined,
      identifiers: Object.fromEntries(pick('id:').filter(([scheme]) => scheme !== matchScheme)),
      attributes: Object.fromEntries(pick('attr:')),
      relations: pick('rel:').map(([relation, field]) => {
        const r = type.relationSchema.find((x) => x.relation === relation)!;
        const target = types.find((x) => x.typeKey === r.targetType);
        return { relation, type: r.targetType, scheme: target?.identifierSchemes[0]?.scheme ?? '', field, create: true };
      }).filter((r) => r.scheme),
      validFromField: validFromField || undefined,
    };
  };

  const run = async () => {
    const m = mapping();
    if (!m || !csv) return;
    setError('');
    const totals = { created: 0, changed: 0, unchanged: 0 };
    const batch = 5000;
    try {
      for (let i = 0; i < csv.rows.length; i += batch) {
        setProgress(t('metadata.importProgress', { done: i, total: csv.rows.length }));
        const rows = csv.rows.slice(i, i + batch).filter((row) => row[m.match.field]?.trim());
        const r = await api.importMetadata(tenantId, m, rows);
        totals.created += r.created; totals.changed += r.changed; totals.unchanged += r.unchanged;
      }
      onImported(t('metadata.importDone', totals));
    } catch (err) {
      setError(errorMessage(err, t('common.saveFailed')));
      setProgress('');
    }
  };

  const columnSelect = (key: string) => (
    <select className="input" value={columnFor[key] ?? ''} onChange={(e) => setColumnFor({ ...columnFor, [key]: e.target.value })}>
      <option value="">{t('metadata.notMapped')}</option>
      {csv?.columns.map((c) => <option key={c} value={c}>{c}</option>)}
    </select>
  );

  return (
    <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50 p-4">
      <div className="bg-white rounded-lg p-6 max-w-3xl w-full max-h-[90vh] overflow-y-auto space-y-4">
        <h2 className="text-xl font-bold">{t('metadata.importCsv')}</h2>
        <p className="text-sm text-slate-600">{t('metadata.importHint')}</p>
        {error && <div className="bg-red-50 border border-red-200 text-red-700 px-3 py-2 rounded-sm text-sm">{error}</div>}
        <div className="grid gap-3 sm:grid-cols-2">
          <label className="text-sm">{t('metadata.csvFile')}
            <input type="file" accept=".csv,text/csv,text/plain" className="input mt-1" onChange={(e) => e.target.files?.[0] && readFile(e.target.files[0])} />
          </label>
          <label className="text-sm">{t('metadata.type')}
            <select className="input mt-1" value={typeKey} onChange={(e) => changeType(e.target.value)}>
              {types.map((x) => <option key={x.id} value={x.typeKey}>{x.name}</option>)}
            </select>
          </label>
        </div>

        {csv && type && (
          <>
            <p className="text-xs text-slate-500">{t('metadata.csvRows', { rows: csv.rows.length, columns: csv.columns.length })}</p>
            <div className="grid gap-2 text-sm">
              <label className="grid grid-cols-[12rem_1fr] items-center gap-2">
                <span className="font-medium">{t('metadata.matchScheme')}</span>
                <select className="input" value={matchScheme} onChange={(e) => setMatchScheme(e.target.value)}>
                  {type.identifierSchemes.map((s) => <option key={s.scheme} value={s.scheme}>{s.name ?? s.scheme}</option>)}
                </select>
              </label>
              <p className="text-xs uppercase text-slate-500 mt-2">{t('metadata.identifiers')}</p>
              {type.identifierSchemes.map((s) => (
                <label key={s.scheme} className="grid grid-cols-[12rem_1fr] items-center gap-2">
                  <span className="truncate">{s.name ?? s.scheme}{s.scheme === matchScheme && ' *'}</span>{columnSelect(`id:${s.scheme}`)}
                </label>
              ))}
              <label className="grid grid-cols-[12rem_1fr] items-center gap-2">
                <span>{t('common.name')}</span>
                <select className="input" value={nameField} onChange={(e) => setNameField(e.target.value)}>
                  <option value="">{t('metadata.notMapped')}</option>
                  {csv.columns.map((c) => <option key={c} value={c}>{c}</option>)}
                </select>
              </label>
              <p className="text-xs uppercase text-slate-500 mt-2">{t('metadata.attributes')}</p>
              {type.attributeSchema.map((a) => (
                <label key={a.name} className="grid grid-cols-[12rem_1fr] items-center gap-2">
                  <span className="truncate">{a.name}</span>{columnSelect(`attr:${a.name}`)}
                </label>
              ))}
              {type.relationSchema.length > 0 && <p className="text-xs uppercase text-slate-500 mt-2">{t('metadata.relations')}</p>}
              {type.relationSchema.map((r) => (
                <label key={r.relation} className="grid grid-cols-[12rem_1fr] items-center gap-2">
                  <span className="truncate">{r.relation} → {r.targetType}</span>{columnSelect(`rel:${r.relation}`)}
                </label>
              ))}
              <label className="grid grid-cols-[12rem_1fr] items-center gap-2 mt-2">
                <span>{t('metadata.validFrom')}</span>
                <select className="input" value={validFromField} onChange={(e) => setValidFromField(e.target.value)}>
                  <option value="">{t('metadata.notMapped')}</option>
                  {csv.columns.map((c) => <option key={c} value={c}>{c}</option>)}
                </select>
              </label>
            </div>
            <div className="overflow-x-auto">
              <table className="min-w-full text-xs">
                <thead><tr>{csv.columns.map((c) => <th key={c} className="text-left pr-3 py-1 text-slate-500">{c}</th>)}</tr></thead>
                <tbody>
                  {csv.rows.slice(0, 5).map((row, i) => (
                    <tr key={i} className="border-t border-slate-100">{csv.columns.map((c) => <td key={c} className="pr-3 py-1 font-mono">{row[c]}</td>)}</tr>
                  ))}
                </tbody>
              </table>
            </div>
          </>
        )}

        {progress && <p className="text-sm text-slate-600">{progress}</p>}
        <div className="flex justify-end gap-2">
          <button type="button" className="btn-secondary" onClick={onClose}>{t('common.cancel')}</button>
          <button type="button" className="btn-primary" disabled={!mapping() || !!progress} onClick={run}>{t('metadata.import')}</button>
        </div>
      </div>
    </div>
  );
}

/** Defines a custom type; one line per identifier scheme, attribute and relation. */
function TypeEditor({ tenantId, types, onClose, onSaved }: {
  tenantId: string;
  types: MetadataType[];
  onClose: () => void;
  onSaved: () => void;
}) {
  const { t } = useTranslation();
  const [typeKey, setTypeKey] = useState('');
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [schemes, setSchemes] = useState('');
  const [attrs, setAttrs] = useState('');
  const [rels, setRels] = useState('');
  const [error, setError] = useState('');

  const save = async (e: React.FormEvent) => {
    e.preventDefault();
    const identifierSchemes = parseLines(schemes).map((l) => {
      const [scheme, ...pattern] = l.split(/\s+/);
      return { scheme, ...(pattern.length ? { pattern: pattern.join(' ') } : {}) };
    });
    if (identifierSchemes.length === 0) { setError(t('metadata.schemeRequired')); return; }
    try {
      await api.createMetadataType(tenantId, {
        typeKey: typeKey.trim(),
        name: name.trim() || typeKey.trim(),
        description: description.trim() || undefined,
        identifierSchemes,
        attributeSchema: parseLines(attrs).map((l) => {
          const m = l.match(/^(\S+)(?:\s*\[(.+)\])?/);
          return { name: m?.[1] ?? l, ...(m?.[2] ? { unit: m[2] } : {}) };
        }),
        relationSchema: parseLines(rels).map((l) => {
          const [relation, target] = l.split(/\s*->\s*/);
          return { relation: relation.trim(), ...(target ? { targetType: target.trim() } : {}) };
        }),
      });
      onSaved();
    } catch (err) {
      setError(errorMessage(err, t('common.saveFailed')));
    }
  };

  return (
    <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50 p-4">
      <form onSubmit={save} className="bg-white rounded-lg p-6 max-w-xl w-full max-h-[90vh] overflow-y-auto space-y-3">
        <h2 className="text-xl font-bold">{t('metadata.addType')}</h2>
        {error && <div className="bg-red-50 border border-red-200 text-red-700 px-3 py-2 rounded-sm text-sm">{error}</div>}
        <div className="grid gap-3 sm:grid-cols-2">
          <label className="text-sm">{t('metadata.typeKey')}
            <input className="input mt-1 font-mono" required pattern="[a-z][a-z0-9_]*" value={typeKey} onChange={(e) => setTypeKey(e.target.value)} placeholder="heat_pump" />
          </label>
          <label className="text-sm">{t('common.name')}
            <input className="input mt-1" value={name} onChange={(e) => setName(e.target.value)} />
          </label>
        </div>
        <label className="block text-sm">{t('common.description')}
          <input className="input mt-1" value={description} onChange={(e) => setDescription(e.target.value)} />
        </label>
        <label className="block text-sm">{t('metadata.identifierSchemes')}
          <textarea className="input mt-1 font-mono text-xs" rows={3} value={schemes} onChange={(e) => setSchemes(e.target.value)} placeholder={'sap.equipment\nserial ^[A-Z0-9]{8}$'} />
          <span className="text-xs text-slate-500">{t('metadata.schemesHint')}</span>
        </label>
        <label className="block text-sm">{t('metadata.attributes')}
          <textarea className="input mt-1 font-mono text-xs" rows={3} value={attrs} onChange={(e) => setAttrs(e.target.value)} placeholder={'manufacturer\nrated_power [kW]'} />
        </label>
        <label className="block text-sm">{t('metadata.relations')}
          <textarea className="input mt-1 font-mono text-xs" rows={2} value={rels} onChange={(e) => setRels(e.target.value)} placeholder={types[0] ? `site -> ${types[0].typeKey}` : 'site -> location'} />
          <span className="text-xs text-slate-500">{t('metadata.relationsHint')}</span>
        </label>
        <div className="flex justify-end gap-2">
          <button type="button" className="btn-secondary" onClick={onClose}>{t('common.cancel')}</button>
          <button type="submit" className="btn-primary">{t('common.save')}</button>
        </div>
      </form>
    </div>
  );
}
