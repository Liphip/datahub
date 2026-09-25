/** Built-in alert rules of ALERT nodes (shared by the rule form and the pipeline editor). */

export type AlertRuleType = 'threshold' | 'rate_of_change' | 'time_window' | 'absence';

/** Built-in alert rule of an ALERT node, stored as `alertRule` in the pipeline graph. */
export interface AlertRuleConfig {
  type: AlertRuleType;
  field?: string;
  operator?: string;
  value?: number | string;
  severity?: 'info' | 'warning' | 'error' | 'critical';
  title?: string;
  seriesField?: string;
  timestampField?: string;
  delta?: number;
  intervalSeconds?: number;
  direction?: 'any' | 'up' | 'down';
  aggregation?: 'avg' | 'min' | 'max' | 'sum' | 'count';
  windowSeconds?: number;
  timeoutSeconds?: number;
  cooldownSeconds?: number;
}

export const RULE_TYPES: { value: AlertRuleType; label: string; help: string }[] = [
  { value: 'threshold', label: 'Threshold', help: 'Fires when a field crosses a limit.' },
  { value: 'rate_of_change', label: 'Rate of change', help: 'Fires when a value changes faster than allowed.' },
  { value: 'time_window', label: 'Time window', help: 'Fires when an aggregate over a sliding window crosses a limit.' },
  { value: 'absence', label: 'Absence of data', help: 'Fires when no data arrives within a timeout.' },
];

export const DEFAULT_RULES: Record<AlertRuleType, AlertRuleConfig> = {
  threshold: { type: 'threshold', field: 'value', operator: '>', value: 80, severity: 'warning' },
  rate_of_change: { type: 'rate_of_change', field: 'value', delta: 10, intervalSeconds: 60, direction: 'any', severity: 'warning' },
  time_window: { type: 'time_window', field: 'value', aggregation: 'avg', windowSeconds: 300, operator: '>', value: 80, severity: 'warning' },
  absence: { type: 'absence', timeoutSeconds: 300, severity: 'warning' },
};

/** Returns the first problem that would make the backend reject the rule, or null. */
export function validateAlertRule(rule: AlertRuleConfig): string | null {
  if (rule.type !== 'absence' && !rule.field?.trim()) return 'Field is required';
  if ((rule.type === 'threshold' || rule.type === 'time_window') && (rule.value === undefined || rule.value === '')) {
    return 'Value is required';
  }
  if (rule.type === 'rate_of_change' && (rule.delta === undefined || Number.isNaN(rule.delta))) return 'Max change is required';
  if (rule.type === 'time_window' && !(Number(rule.windowSeconds) > 0)) return 'Window must be greater than 0';
  if (rule.type === 'absence' && !(Number(rule.timeoutSeconds) > 0)) return 'Timeout must be greater than 0';
  return null;
}
