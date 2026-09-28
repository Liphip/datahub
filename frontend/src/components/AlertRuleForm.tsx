import type { ReactNode } from 'react';
import { DEFAULT_RULES, RULE_TYPES, validateAlertRule, type AlertRuleConfig, type AlertRuleType } from '../lib/alertRules';


const inputClass = 'border rounded-sm px-2 py-1 text-sm w-full';

function Field({ label, hint, children }: { label: string; hint?: string; children: ReactNode }) {
  return (
    <div>
      <label className="block">
        <span className="block text-xs font-medium text-gray-600 mb-1">{label}</span>
        {children}
      </label>
      {hint && <p className="text-[10px] text-gray-400 mt-1">{hint}</p>}
    </div>
  );
}

const toNumber = (raw: string): number | undefined => (raw.trim() === '' ? undefined : Number(raw));

export default function AlertRuleForm({
  rule,
  disabled,
  onChange,
}: {
  rule: AlertRuleConfig;
  disabled?: boolean;
  onChange: (rule: AlertRuleConfig) => void;
}) {
  const set = (patch: Partial<AlertRuleConfig>) => onChange({ ...rule, ...patch });
  const needsComparison = rule.type === 'threshold' || rule.type === 'time_window';
  const error = validateAlertRule(rule);

  return (
    <div className="space-y-3">
      <Field label="Rule type" hint={RULE_TYPES.find((t) => t.value === rule.type)?.help}>
        <select
          className={inputClass}
          value={rule.type}
          disabled={disabled}
          onChange={(e) => {
            const type = e.target.value as AlertRuleType;
            onChange({ ...DEFAULT_RULES[type], severity: rule.severity, title: rule.title, cooldownSeconds: rule.cooldownSeconds, seriesField: rule.seriesField });
          }}
        >
          {RULE_TYPES.map((t) => (
            <option key={t.value} value={t.value}>{t.label}</option>
          ))}
        </select>
      </Field>

      {rule.type !== 'absence' && (
        <Field label="Field" hint="Field of the incoming record, dotted for nested values (e.g. sensor.temperature).">
          <input className={inputClass} value={rule.field ?? ''} disabled={disabled} placeholder="value"
            onChange={(e) => set({ field: e.target.value })} />
        </Field>
      )}

      {rule.type === 'time_window' && (
        <div className="grid grid-cols-2 gap-2">
          <Field label="Aggregation">
            <select className={inputClass} value={rule.aggregation ?? 'avg'} disabled={disabled}
              onChange={(e) => set({ aggregation: e.target.value as AlertRuleConfig['aggregation'] })}>
              {['avg', 'min', 'max', 'sum', 'count'].map((a) => <option key={a} value={a}>{a}</option>)}
            </select>
          </Field>
          <Field label="Window (s)">
            <input type="number" min={1} className={inputClass} value={rule.windowSeconds ?? ''} disabled={disabled}
              onChange={(e) => set({ windowSeconds: toNumber(e.target.value) })} />
          </Field>
        </div>
      )}

      {needsComparison && (
        <div className="grid grid-cols-[5rem_1fr] gap-2">
          <Field label="Operator">
            <select className={inputClass} value={rule.operator ?? '>'} disabled={disabled}
              onChange={(e) => set({ operator: e.target.value })}>
              {['>', '>=', '<', '<=', '==', '!='].map((o) => <option key={o} value={o}>{o}</option>)}
            </select>
          </Field>
          <Field label="Value">
            <input className={inputClass} value={rule.value ?? ''} disabled={disabled} placeholder="80"
              onChange={(e) => {
                const raw = e.target.value;
                const n = Number(raw);
                set({ value: raw.trim() !== '' && !Number.isNaN(n) ? n : raw });
              }} />
          </Field>
        </div>
      )}

      {rule.type === 'rate_of_change' && (
        <>
          <div className="grid grid-cols-2 gap-2">
            <Field label="Max change">
              <input type="number" min={0} className={inputClass} value={rule.delta ?? ''} disabled={disabled}
                onChange={(e) => set({ delta: toNumber(e.target.value) })} />
            </Field>
            <Field label="Per (s)">
              <input type="number" min={1} className={inputClass} value={rule.intervalSeconds ?? 60} disabled={disabled}
                onChange={(e) => set({ intervalSeconds: toNumber(e.target.value) })} />
            </Field>
          </div>
          <Field label="Direction">
            <select className={inputClass} value={rule.direction ?? 'any'} disabled={disabled}
              onChange={(e) => set({ direction: e.target.value as AlertRuleConfig['direction'] })}>
              <option value="any">Rise or fall</option>
              <option value="up">Rise only</option>
              <option value="down">Fall only</option>
            </select>
          </Field>
        </>
      )}

      {rule.type === 'absence' && (
        <Field label="Timeout (s)" hint="Alert when no record reaches this node for this long.">
          <input type="number" min={1} className={inputClass} value={rule.timeoutSeconds ?? ''} disabled={disabled}
            onChange={(e) => set({ timeoutSeconds: toNumber(e.target.value) })} />
        </Field>
      )}

      <Field label="Series field (optional)" hint="Evaluate each series separately, e.g. sensorId.">
        <input className={inputClass} value={rule.seriesField ?? ''} disabled={disabled} placeholder="sensorId"
          onChange={(e) => set({ seriesField: e.target.value || undefined })} />
      </Field>

      {(rule.type === 'rate_of_change' || rule.type === 'time_window') && (
        <Field label="Timestamp field (optional)" hint="Epoch seconds/milliseconds or ISO-8601; arrival time when empty.">
          <input className={inputClass} value={rule.timestampField ?? ''} disabled={disabled} placeholder="ts"
            onChange={(e) => set({ timestampField: e.target.value || undefined })} />
        </Field>
      )}

      <div className="grid grid-cols-2 gap-2">
        <Field label="Severity">
          <select className={inputClass} value={rule.severity ?? 'warning'} disabled={disabled}
            onChange={(e) => set({ severity: e.target.value as AlertRuleConfig['severity'] })}>
            {['info', 'warning', 'error', 'critical'].map((s) => <option key={s} value={s}>{s}</option>)}
          </select>
        </Field>
        <Field label="Cooldown (s)">
          <input type="number" min={0} className={inputClass} value={rule.cooldownSeconds ?? ''} disabled={disabled}
            placeholder="300" onChange={(e) => set({ cooldownSeconds: toNumber(e.target.value) })} />
        </Field>
      </div>

      <Field label="Alert title (optional)">
        <input className={inputClass} value={rule.title ?? ''} disabled={disabled} placeholder="Generated from the rule"
          onChange={(e) => set({ title: e.target.value || undefined })} />
      </Field>

      {error && <p className="text-xs text-amber-600">{error}</p>}
    </div>
  );
}
