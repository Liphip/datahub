import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import api from '../services/api';
import type { Membership } from '../types';

const localTime = (value?: string | null) => {
  if (!value) return '';
  const date = new Date(value);
  return new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0, 19);
};

export default function MembershipValidityEditor({ membership, tenantId, onSaved, onClose }: {
  membership: Membership; tenantId: string; onSaved: () => void; onClose: () => void;
}) {
  const { t } = useTranslation();
  const [from, setFrom] = useState(localTime(membership.validFrom));
  const [until, setUntil] = useState(localTime(membership.validUntil));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  return <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50">
    <section role="dialog" aria-modal="true" aria-labelledby="validity-title" className="bg-white rounded-lg p-6 max-w-lg w-full space-y-3">
      <h2 id="validity-title" className="text-xl font-bold">{t('membershipValidity.title')}</h2>
      <p>{membership.authUserEmail}</p>
      <p>{t('membershipValidity.help')}</p>
      {error && <p role="alert">{error}</p>}
      <form className="space-y-3" onSubmit={async e => {
        e.preventDefault(); setError('');
        const validFrom = from ? new Date(from).toISOString() : null;
        const validUntil = until ? new Date(until).toISOString() : null;
        if (validFrom && validUntil && validFrom >= validUntil) { setError(t('membershipValidity.invalid')); return; }
        setBusy(true);
        try { await api.updateMembershipValidity(tenantId, membership.id, validFrom, validUntil); onSaved(); }
        catch { setError(t('membershipValidity.failed')); }
        finally { setBusy(false); }
      }}>
        <label className="block">{t('membershipValidity.from')}<input className="input w-full" type="datetime-local" step="1" value={from} onChange={e => setFrom(e.target.value)} /></label>
        <label className="block">{t('membershipValidity.until')}<input className="input w-full" type="datetime-local" step="1" value={until} onChange={e => setUntil(e.target.value)} /></label>
        <button type="submit" disabled={busy} className="btn-primary">{t('common.save')}</button>
        <button type="button" className="btn-secondary ml-2" onClick={onClose}>{t('common.cancel')}</button>
      </form>
    </section>
  </div>;
}
