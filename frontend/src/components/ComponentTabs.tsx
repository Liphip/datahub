import { useCallback, useEffect, useId, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { useAuthStore } from '../stores/auth';
import api from '../services/api';
import type { AuditNote, AuditPage } from '../types';

/** Configuration and a lazy, tenant-scoped activity view. */
export default function ComponentTabs({ tenantId, resourceType, resourceId, onClose, children }: {
  tenantId?: string; resourceType: string; resourceId?: string; onClose: () => void; children: ReactNode;
}) {
  const { t } = useTranslation();
  const { user } = useAuthStore();
  const [activity, setActivity] = useState(false);
  const id = useId();
  const canRead = user?.superadmin || user?.abilities?.includes('*') || user?.abilities?.includes('audit:read');
  if (!canRead || !tenantId || !resourceId) return children;
  return <>
    <div role="tablist" className="flex gap-3 mb-4">
      <button type="button" role="tab" id={`${id}-config-tab`} aria-controls={`${id}-config`} aria-selected={!activity} onClick={() => setActivity(false)}>{t('activity.configuration')}</button>
      <button type="button" role="tab" id={`${id}-activity-tab`} aria-controls={`${id}-activity`} aria-selected={activity} onClick={() => setActivity(true)}>{t('activity.title')}</button>
    </div>
    {activity ? <div role="tabpanel" id={`${id}-activity`} aria-labelledby={`${id}-activity-tab`}>
      <Activity key={`${tenantId}:${resourceType}:${resourceId}`} tenantId={tenantId} resourceType={resourceType} resourceId={resourceId} />
      <button type="button" className="btn-secondary mt-4" onClick={onClose}>{t('common.close')}</button>
    </div> : <div role="tabpanel" id={`${id}-config`} aria-labelledby={`${id}-config-tab`}>{children}</div>}
  </>;
}

function Activity({ tenantId, resourceType, resourceId }: { tenantId: string; resourceType: string; resourceId: string }) {
  const { t } = useTranslation();
  const { user } = useAuthStore();
  const [page, setPage] = useState<AuditPage | null>(null);
  const [notes, setNotes] = useState<AuditNote[]>([]);
  const [offset, setOffset] = useState(0);
  const [note, setNote] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);
  const [refresh, setRefresh] = useState(0);
  const canWrite = user?.superadmin || user?.abilities?.includes('*') || user?.abilities?.includes('audit.note:write');
  const filter = { tenantId, resourceType, resourceId };
  const load = useCallback(() => Promise.all([
    api.searchAudit({ tenantId, resourceType, resourceId }, 20, offset),
    api.getComponentNotes(tenantId, resourceType, resourceId),
  ]), [tenantId, resourceType, resourceId, offset]);
  useEffect(() => {
    let active = true;
    setLoading(true); setError('');
    load().then(([events, timeline]) => { if (active) { setPage(events); setNotes(timeline); } })
      .catch(() => { if (active) setError(t('activity.failed')); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [load, refresh, t]);
  return <section className="space-y-3">
    {error && <p role="alert">{error}</p>}
    {loading ? <p>{t('common.loading')}</p> : <>
      <h3 className="font-medium">{t('activity.events')}</h3>
      {!page?.items.length && <p>{t('activity.empty')}</p>}
      {page?.items.map(event => <article key={event.id} className="border-b py-2 text-sm break-words">
        <p>{new Date(event.occurredAt).toLocaleString()} — {event.action}</p>
        <p>{event.actorName || event.actorId || t('activity.system')} · {event.outcome}</p>
      </article>)}
      <div className="flex gap-3">
        <button type="button" disabled={!offset} onClick={() => setOffset(Math.max(0, offset - 20))}>{t('activity.previous')}</button>
        <button type="button" disabled={!page || offset + 20 >= page.total} onClick={() => setOffset(offset + 20)}>{t('activity.next')}</button>
      </div>
      <h3 className="font-medium">{t('activity.notes')}</h3>
      {notes.map(item => <article key={item.id} className="border-b py-2 text-sm"><p className="whitespace-pre-wrap break-words">{item.note}</p><p>{new Date(item.createdAt).toLocaleString()} — {item.authorId}</p></article>)}
    </>}
    {canWrite && <form onSubmit={async e => {
      e.preventDefault(); if (!note.trim() || busy) return;
      setBusy(true); setError('');
      try { await api.addComponentNote({ ...filter, note: note.trim() }); setNote(''); setRefresh(value => value + 1); }
      catch { setError(t('activity.failed')); }
      finally { setBusy(false); }
    }}>
      <label className="block">{t('activity.addNote')}<textarea maxLength={4000} required value={note} onChange={e => setNote(e.target.value)} className="input w-full" /></label>
      <p className="text-sm">{t('activity.immutable')}</p>
      <button type="submit" className="btn-primary" disabled={busy || !note.trim()}>{t('common.add')}</button>
    </form>}
  </section>;
}
