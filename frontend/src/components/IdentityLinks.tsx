import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import api from '../services/api';

/** Explicit account linking is restricted to platform administrators on both client and server. */
export default function IdentityLinks({ userId, name, onClose }: { userId: string; name: string; onClose: () => void }) {
  const { t } = useTranslation();
  const [links, setLinks] = useState<{ id: string; issuer: string; subject: string }[]>([]);
  const [issuer, setIssuer] = useState('');
  const [subject, setSubject] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const load = useCallback(async () => setLinks(await api.getIdentityLinks(userId)), [userId]);
  useEffect(() => { load().catch(() => setError(t('auth.identityFailed'))); }, [load, t]);
  const act = async (operation: () => Promise<void>) => {
    setBusy(true); setError('');
    try { await operation(); await load(); setSubject(''); }
    catch { setError(t('auth.identityFailed')); }
    finally { setBusy(false); }
  };
  return <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50">
    <section role="dialog" aria-modal="true" aria-labelledby="identity-title" className="bg-white rounded-lg p-6 max-w-lg w-full space-y-3">
      <h2 id="identity-title" className="text-xl font-bold">{t('auth.identityLinks')} — {name}</h2>
      <p className="text-sm text-gray-600">{t('auth.identityHelp')}</p>
      {error && <p role="alert" className="text-red-700">{error}</p>}
      {links.map(link => <div key={link.id} className="border p-2 break-all">
        <p>{link.issuer}</p><p>{link.subject}</p>
        <button disabled={busy} className="btn-secondary" onClick={() => { if (window.confirm(t('auth.unlinkConfirm'))) void act(() => api.unlinkIdentity(userId, link.id)); }}>{t('auth.unlink')}</button>
      </div>)}
      <form className="space-y-3" onSubmit={e => { e.preventDefault(); void act(() => api.linkIdentity(userId, issuer.trim(), subject)); }}>
        <label className="block">{t('auth.issuer')}<input type="url" required maxLength={512} value={issuer} onChange={e => setIssuer(e.target.value)} className="input w-full" /></label>
        <label className="block">{t('auth.subject')}<input required maxLength={255} value={subject} onChange={e => setSubject(e.target.value)} className="input w-full" /></label>
        <button disabled={busy} className="btn-primary">{t('auth.linkIdentity')}</button>
        <button type="button" onClick={onClose} className="btn-secondary ml-2">{t('common.close')}</button>
      </form>
    </section>
  </div>;
}
