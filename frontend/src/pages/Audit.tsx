import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useAuthStore } from '../stores/auth';
import api from '../services/api';
import type { AuditChainReport, AuditEvent, AuditFilter, AuditNote, DeadLetter } from '../types';

const PAGE_SIZE = 50;

const errorMessage = (err: unknown, fallback: string) =>
  (err as { response?: { data?: { error?: string; message?: string } } })?.response?.data?.message
  ?? (err as { response?: { data?: { error?: string } } })?.response?.data?.error
  ?? fallback;

/** A datetime-local value is local time without a zone; the API wants an ISO instant. */
const toInstant = (local: string) => (local ? new Date(local).toISOString() : undefined);

/** Audit trail (AUD-07/08): filter, page and export; platform administrators also see dead letters. */
export default function Audit() {
  const { t } = useTranslation();
  const { user, selectedTenantId, tenantContexts } = useAuthStore();
  const isPlatformAdmin = Boolean(user?.superadmin);
  const canRead = Boolean(isPlatformAdmin || user?.abilities?.includes('*') || user?.abilities?.includes('audit:read'));
  const tenantId = selectedTenantId || user?.tenantId || tenantContexts[0]?.tenantId;

  const [tab, setTab] = useState<'events' | 'deadLetters'>('events');
  const [wholeInstallation, setWholeInstallation] = useState(false);
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [action, setAction] = useState('');
  const [resourceType, setResourceType] = useState('');
  const [outcome, setOutcome] = useState('');
  const [text, setText] = useState('');
  const [offset, setOffset] = useState(0);
  const [items, setItems] = useState<AuditEvent[]>([]);
  const [total, setTotal] = useState(0);
  const [expanded, setExpanded] = useState<string | null>(null);
  const [deadLetters, setDeadLetters] = useState<DeadLetter[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [chain, setChain] = useState<AuditChainReport | null>(null);
  const canNote = Boolean(isPlatformAdmin || user?.abilities?.includes('*') || user?.abilities?.includes('audit.note:write'));

  const verify = async () => {
    try {
      setChain(await api.verifyAuditChain());
    } catch (err) {
      setError(errorMessage(err, t('common.loadingFailed')));
    }
  };

  const filter = useCallback((): AuditFilter => ({
    tenantId: isPlatformAdmin && wholeInstallation ? undefined : tenantId,
    from: toInstant(from),
    to: toInstant(to),
    action: action.trim() || undefined,
    resourceType: resourceType.trim() || undefined,
    outcome: outcome || undefined,
    q: text.trim() || undefined,
  }), [isPlatformAdmin, wholeInstallation, tenantId, from, to, action, resourceType, outcome, text]);

  const load = useCallback(async (start: number) => {
    if (!canRead || (!tenantId && !isPlatformAdmin)) return;
    setLoading(true);
    try {
      const page = await api.searchAudit(filter(), PAGE_SIZE, start);
      setItems(page.items);
      setTotal(page.total);
      setOffset(start);
      setError('');
    } catch (err) {
      setError(errorMessage(err, t('common.loadingFailed')));
    } finally {
      setLoading(false);
    }
  }, [canRead, tenantId, isPlatformAdmin, filter, t]);

  // Reload from the first page when the scope changes; the other filters apply on submit.
  useEffect(() => {
    if (tab === 'events') void load(0);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tab, tenantId, wholeInstallation]);

  useEffect(() => {
    if (tab !== 'deadLetters' || !isPlatformAdmin) return;
    setLoading(true);
    api.getDeadLetters()
      .then((rows) => { setDeadLetters(rows); setError(''); })
      .catch((err) => setError(errorMessage(err, t('common.loadingFailed'))))
      .finally(() => setLoading(false));
  }, [tab, isPlatformAdmin, t]);

  const download = async (format: 'csv' | 'json') => {
    try {
      const blob = await api.exportAudit(filter(), format);
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = `audit.${format}`;
      link.click();
      URL.revokeObjectURL(url);
    } catch (err) {
      setError(errorMessage(err, t('common.loadingFailed')));
    }
  };

  if (!canRead) return <div className="card text-center py-12"><p className="text-gray-500">{t('audit.noPermission')}</p></div>;
  if (!tenantId && !isPlatformAdmin) return <div className="card text-center py-12"><p className="text-gray-500">{t('common.noTenantId')}</p></div>;

  const outcomeClass = (value: string) =>
    value.toUpperCase().startsWith('SUCC') ? 'bg-green-100 text-green-800' : 'bg-red-100 text-red-800';

  return (
    <div className="px-4 py-6 sm:px-0">
      <div className="flex flex-wrap justify-between items-center gap-3 mb-2">
        <h1 className="text-2xl font-bold text-gray-900">{t('audit.title')}</h1>
        {tab === 'events' && (
          <div className="flex gap-2">
            {isPlatformAdmin && <button className="btn-secondary text-sm" onClick={verify}>{t('audit.verify')}</button>}
            <button className="btn-secondary text-sm" onClick={() => download('csv')}>{t('audit.exportCsv')}</button>
            <button className="btn-secondary text-sm" onClick={() => download('json')}>{t('audit.exportJson')}</button>
          </div>
        )}
      </div>
      <p className="mb-4 text-sm text-slate-600">{t('audit.intro')}</p>

      {isPlatformAdmin && (
        <div className="mb-4 flex gap-4 border-b border-gray-200" role="tablist">
          {(['events', 'deadLetters'] as const).map((key) => (
            <button
              key={key}
              role="tab"
              aria-selected={tab === key}
              className={`pb-2 text-sm ${tab === key ? 'border-b-2 border-blue-600 font-semibold text-blue-700' : 'text-slate-600'}`}
              onClick={() => setTab(key)}
            >
              {t(key === 'events' ? 'audit.tabEvents' : 'audit.tabDeadLetters')}
            </button>
          ))}
        </div>
      )}

      {chain && (
        <div role="status" className={`mb-4 px-4 py-3 rounded-sm border text-sm ${chain.intact ? 'bg-green-50 border-green-200 text-green-800' : 'bg-red-50 border-red-200 text-red-700'}`}>
          {chain.intact
            ? t('audit.chainIntact', { count: chain.checked })
            : t('audit.chainBroken', { seq: chain.brokenAtSeq, problem: chain.problem })}
          {chain.unchained > 0 && ` ${t('audit.chainUnchained', { count: chain.unchained })}`}
        </div>
      )}

      {error && <div className="mb-4 bg-red-50 border border-red-200 text-red-700 px-4 py-3 rounded-sm">{error}</div>}

      {tab === 'events' ? (
        <>
          <form
            className="card mb-4 grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-4"
            onSubmit={(e) => { e.preventDefault(); void load(0); }}
          >
            {isPlatformAdmin && (
              <label className="text-sm">
                <span className="block text-slate-600">{t('audit.scope')}</span>
                <select className="input mt-1 w-full" value={wholeInstallation ? 'all' : 'tenant'} onChange={(e) => setWholeInstallation(e.target.value === 'all')}>
                  <option value="tenant" disabled={!tenantId}>{t('audit.scopeTenant')}</option>
                  <option value="all">{t('audit.scopeAll')}</option>
                </select>
              </label>
            )}
            <label className="text-sm">
              <span className="block text-slate-600">{t('audit.from')}</span>
              <input type="datetime-local" className="input mt-1 w-full" value={from} onChange={(e) => setFrom(e.target.value)} />
            </label>
            <label className="text-sm">
              <span className="block text-slate-600">{t('audit.to')}</span>
              <input type="datetime-local" className="input mt-1 w-full" value={to} onChange={(e) => setTo(e.target.value)} />
            </label>
            <label className="text-sm">
              <span className="block text-slate-600">{t('audit.action')}</span>
              <input className="input mt-1 w-full" value={action} placeholder={t('audit.actionHint')} onChange={(e) => setAction(e.target.value)} />
            </label>
            <label className="text-sm">
              <span className="block text-slate-600">{t('audit.resourceType')}</span>
              <input className="input mt-1 w-full" value={resourceType} onChange={(e) => setResourceType(e.target.value)} />
            </label>
            <label className="text-sm">
              <span className="block text-slate-600">{t('audit.outcome')}</span>
              <select className="input mt-1 w-full" value={outcome} onChange={(e) => setOutcome(e.target.value)}>
                <option value="">{t('audit.anyOutcome')}</option>
                <option value="SUCCESS">SUCCESS</option>
                <option value="FAILURE">FAILURE</option>
              </select>
            </label>
            <label className="text-sm">
              <span className="block text-slate-600">{t('audit.search')}</span>
              <input className="input mt-1 w-full" value={text} onChange={(e) => setText(e.target.value)} />
            </label>
            <div className="flex items-end">
              <button type="submit" className="btn-primary text-sm">{t('common.apply')}</button>
            </div>
          </form>

          {loading ? (
            <div className="card animate-pulse"><div className="h-4 bg-gray-200 rounded-sm w-1/3" /></div>
          ) : items.length === 0 ? (
            <div className="card text-center py-10 text-slate-500">{t('audit.none')}</div>
          ) : (
            <div className="card overflow-x-auto p-0!">
              <table className="min-w-full text-sm">
                <thead className="bg-gray-50 text-left text-slate-600">
                  <tr>
                    <th className="px-3 py-2">{t('audit.time')}</th>
                    <th className="px-3 py-2">{t('audit.action')}</th>
                    <th className="px-3 py-2">{t('audit.outcome')}</th>
                    <th className="px-3 py-2">{t('audit.target')}</th>
                    <th className="px-3 py-2">{t('audit.actor')}</th>
                    <th className="px-3 py-2" />
                  </tr>
                </thead>
                <tbody>
                  {items.map((event) => (
                    <FragmentRow
                      key={event.id}
                      event={event}
                      open={expanded === event.id}
                      onToggle={() => setExpanded(expanded === event.id ? null : event.id)}
                      outcomeClass={outcomeClass(event.outcome)}
                      canNote={canNote}
                      systemLabel={t('audit.system')}
                      detailsLabel={t('audit.details')}
                    />
                  ))}
                </tbody>
              </table>
            </div>
          )}

          <div className="mt-3 flex items-center justify-between text-sm text-slate-600">
            <span>{t('audit.total', { count: total })}</span>
            <div className="flex gap-2">
              <button className="btn-secondary text-sm" disabled={offset === 0 || loading} onClick={() => load(Math.max(0, offset - PAGE_SIZE))}>{t('common.previous')}</button>
              <button className="btn-secondary text-sm" disabled={offset + PAGE_SIZE >= total || loading} onClick={() => load(offset + PAGE_SIZE)}>{t('common.next')}</button>
            </div>
          </div>
        </>
      ) : (
        <>
          <p className="mb-4 text-sm text-slate-600">{t('audit.deadLettersIntro')}</p>
          {loading ? (
            <div className="card animate-pulse"><div className="h-4 bg-gray-200 rounded-sm w-1/3" /></div>
          ) : deadLetters.length === 0 ? (
            <div className="card text-center py-10 text-slate-500">{t('audit.deadLettersNone')}</div>
          ) : (
            <ul className="space-y-3">
              {deadLetters.map((letter) => (
                <li key={letter.id} className="card p-4!">
                  <div className="text-xs text-slate-500">{new Date(letter.createdAt).toLocaleString()} · {t('audit.channel')}: {letter.channel}</div>
                  <div className="mt-1 text-sm text-red-700 break-words">{t('audit.error')}: {letter.error}</div>
                  <pre className="mt-2 max-h-40 overflow-auto rounded-sm bg-gray-50 p-2 text-xs whitespace-pre-wrap break-all">{letter.payload}</pre>
                </li>
              ))}
            </ul>
          )}
        </>
      )}
    </div>
  );
}

/** Notes on one record: listed oldest first; adding is the only change there is (AUD-06). */
function Notes({ eventId, canNote }: { eventId: string; canNote: boolean }) {
  const { t } = useTranslation();
  const [notes, setNotes] = useState<AuditNote[]>([]);
  const [draft, setDraft] = useState('');
  const [failed, setFailed] = useState('');

  useEffect(() => {
    api.getAuditNotes(eventId).then(setNotes).catch((err) => setFailed(errorMessage(err, t('common.loadingFailed'))));
  }, [eventId, t]);

  const add = async () => {
    if (!draft.trim()) return;
    try {
      const saved = await api.addAuditNote(eventId, draft.trim());
      setNotes((current) => [...current, saved]);
      setDraft('');
      setFailed('');
    } catch (err) {
      setFailed(errorMessage(err, t('common.saveFailed')));
    }
  };

  return (
    <div className="mt-2 border-t border-gray-200 pt-2">
      <div className="text-xs font-semibold text-slate-600">{t('audit.notes')}</div>
      {notes.length === 0 && <div className="text-xs text-slate-500">{t('audit.notesNone')}</div>}
      <ul className="space-y-1">
        {notes.map((note) => (
          <li key={note.id} className="text-xs">
            <span className="text-slate-500">{new Date(note.createdAt).toLocaleString()}</span> — {note.note}
          </li>
        ))}
      </ul>
      {failed && <div className="text-xs text-red-700">{failed}</div>}
      {canNote && (
        <div className="mt-2 flex gap-2">
          <input className="input flex-1 text-sm" aria-label={t('audit.noteAdd')} placeholder={t('audit.noteAdd')} value={draft} maxLength={4000} onChange={(e) => setDraft(e.target.value)} />
          <button className="btn-secondary text-sm" disabled={!draft.trim()} onClick={add}>{t('common.add')}</button>
        </div>
      )}
    </div>
  );
}

function FragmentRow({ event, open, onToggle, outcomeClass, systemLabel, detailsLabel, canNote }: {
  event: AuditEvent; open: boolean; onToggle: () => void; outcomeClass: string; systemLabel: string; detailsLabel: string; canNote: boolean;
}) {
  return (
    <>
      <tr className="border-t border-gray-100">
        <td className="px-3 py-2 whitespace-nowrap">{new Date(event.occurredAt).toLocaleString()}</td>
        <td className="px-3 py-2 font-mono text-xs">{event.action}</td>
        <td className="px-3 py-2"><span className={`px-2 py-0.5 text-xs rounded-sm ${outcomeClass}`}>{event.outcome}</span></td>
        <td className="px-3 py-2 break-all">{event.resourceType}{event.resourceId ? ` · ${event.resourceId}` : ''}</td>
        <td className="px-3 py-2 break-all text-xs">{event.actorId ?? systemLabel}</td>
        <td className="px-3 py-2 text-right">
          <button className="text-blue-700 hover:underline text-xs" aria-expanded={open} onClick={onToggle}>{detailsLabel}</button>
        </td>
      </tr>
      {open && (
        <tr className="bg-gray-50">
          <td colSpan={6} className="px-3 py-2">
            <pre className="max-h-60 overflow-auto text-xs whitespace-pre-wrap break-all">{JSON.stringify({ seq: event.seq, hash: event.hash, requestId: event.requestId, tenantId: event.tenantId, metadata: event.metadata }, null, 2)}</pre>
            <Notes eventId={event.id} canNote={canNote} />
          </td>
        </tr>
      )}
    </>
  );
}
