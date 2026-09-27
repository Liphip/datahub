import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useAuthStore } from '../stores/auth';
import api from '../services/api';
import type { AlertChannel, AlertHistory, AlertRule, Pipeline } from '../types';

/**
 * Defines the type `AlertChannelType`, the contract implemented by this source unit.
 */
type AlertChannelType = AlertChannel['channelType'];
/**
 * Defines the type `ChannelConfigField`, the contract implemented by this source unit.
 */
type ChannelConfigField = {
  key: string;
  labelKey: string;
  type?: 'text' | 'url' | 'email' | 'number' | 'password';
  placeholderKey?: string;
  required?: boolean;
};

const channelConfigSchema: Record<AlertChannelType, ChannelConfigField[]> = {
  WEBHOOK: [
    { key: 'url', labelKey: 'alerts.webhookUrl', type: 'url', required: true, placeholderKey: 'alerts.webhookUrlPlaceholder' },
    { key: 'method', labelKey: 'alerts.httpMethod', placeholderKey: 'common.post' },
    { key: 'authToken', labelKey: 'alerts.authToken', type: 'password' },
  ],
  SLACK: [
    { key: 'webhookUrl', labelKey: 'alerts.slackWebhook', type: 'url', required: true, placeholderKey: 'alerts.slackWebhookPlaceholder' },
    { key: 'channel', labelKey: 'alerts.slackChannel', placeholderKey: 'alerts.slackChannelPlaceholder' },
  ],
  EMAIL: [
    { key: 'recipient', labelKey: 'alerts.recipientEmail', type: 'email', required: true, placeholderKey: 'alerts.recipientEmailPlaceholder' },
    { key: 'subjectTemplate', labelKey: 'alerts.subjectTemplate', placeholderKey: 'alerts.subjectTemplatePlaceholder' },
  ],
  REST: [
    { key: 'baseUrl', labelKey: 'alerts.baseUrl', type: 'url', required: true, placeholderKey: 'alerts.baseUrlPlaceholder' },
    { key: 'path', labelKey: 'common.path', placeholderKey: 'alerts.pathPlaceholder' },
    { key: 'method', labelKey: 'alerts.httpMethod', placeholderKey: 'common.post' },
    { key: 'authToken', labelKey: 'alerts.authToken', type: 'password' },
  ],
  MQTT: [
    { key: 'broker', labelKey: 'connectors.brokerUrl', type: 'url', required: true, placeholderKey: 'alerts.brokerUrlPlaceholder' },
    { key: 'topic', labelKey: 'common.topic', required: true, placeholderKey: 'alerts.topicPlaceholder' },
    { key: 'username', labelKey: 'common.username' },
    { key: 'password', labelKey: 'common.password', type: 'password' },
  ],
};

/**
 * Initializes, connects, or acquires the named runtime resource.
 */
const initialChannelConfig = (type: AlertChannelType, existing?: Record<string, unknown>) => {
  const fields = channelConfigSchema[type] || [];
  return fields.reduce<Record<string, string>>((acc, field) => {
    const value = existing?.[field.key];
    acc[field.key] = value == null ? '' : String(value);
    return acc;
  }, {});
};

type AlertCondition = {
  field?: string;
  operator?: string;
  value?: unknown;
  all?: AlertCondition[];
  any?: AlertCondition[];
};

type AlertRuleDraft = Omit<AlertRule, 'id'>;

const emptyCondition = (): AlertCondition => ({ field: '', operator: '>', value: '' });
const emptyRule = (): AlertRuleDraft => ({ name: '', condition: emptyCondition(), severity: 'WARNING', enabled: true, cooldownSeconds: 60 });
const isConditionGroup = (condition: AlertCondition) => Array.isArray(condition.all) || Array.isArray(condition.any);

const parseCondition = (condition?: AlertRule['condition']): AlertCondition => {
  if (!condition) return emptyCondition();
  try {
    const parsed = typeof condition === 'string' ? JSON.parse(condition) : condition;
    return parsed && typeof parsed === 'object' ? parsed as AlertCondition : emptyCondition();
  } catch {
    return emptyCondition();
  }
};

const normalizeValue = (value: unknown): unknown => {
  if (typeof value !== 'string') return value;
  const trimmed = value.trim();
  if (trimmed === '') return '';
  if (trimmed === 'true') return true;
  if (trimmed === 'false') return false;
  const numeric = Number(trimmed);
  return Number.isFinite(numeric) ? numeric : trimmed;
};

function ConditionEditor({ condition, onChange, onRemove, depth = 0 }: {
  condition: AlertCondition;
  onChange: (condition: AlertCondition) => void;
  onRemove?: () => void;
  depth?: number;
}) {
  const groupKey = Array.isArray(condition.all) ? 'all' : 'any';
  if (isConditionGroup(condition)) {
    const children = condition[groupKey] || [];
    const updateChild = (index: number, next: AlertCondition) => onChange({ ...condition, [groupKey]: children.map((child, childIndex) => childIndex === index ? next : child) });
    return (
      <div className="rounded-lg border border-slate-200 bg-slate-50 p-3" style={{ marginLeft: `${Math.min(depth, 3) * 12}px` }}>
        <div className="mb-3 flex items-center justify-between gap-2">
          <span className="rounded-full bg-slate-800 px-2 py-1 text-xs font-semibold tracking-wide text-white">{groupKey === 'all' ? 'ALL / AND' : 'ANY / OR'}</span>
          <div className="flex gap-2 text-xs">
            <button type="button" className="text-primary-700 hover:text-primary-900" onClick={() => onChange({ ...condition, [groupKey]: [...children, emptyCondition()] })}>+ Condition</button>
            <button type="button" className="text-primary-700 hover:text-primary-900" onClick={() => onChange({ ...condition, [groupKey]: [...children, { all: [emptyCondition()] }] })}>+ Group</button>
            {onRemove && <button type="button" className="text-red-600 hover:text-red-800" onClick={onRemove}>Remove</button>}
          </div>
        </div>
        <div className="space-y-2">
          {children.map((child, index) => (
            <ConditionEditor key={index} condition={child} depth={depth + 1} onChange={(next) => updateChild(index, next)} onRemove={() => onChange({ ...condition, [groupKey]: children.filter((_, childIndex) => childIndex !== index) })} />
          ))}
        </div>
      </div>
    );
  }
  return (
    <div className="flex flex-wrap items-center gap-2 rounded border border-slate-200 bg-white p-2" style={{ marginLeft: `${Math.min(depth, 3) * 12}px` }}>
      <input className="input min-w-[8rem] flex-1" aria-label="Alert field" placeholder="field" value={condition.field || ''} onChange={(event) => onChange({ ...condition, field: event.target.value })} />
      <select className="input w-28" aria-label="Alert operator" value={condition.operator || '>'} onChange={(event) => onChange({ ...condition, operator: event.target.value })}>
        <option value=">">&gt;</option><option value=">=">&ge;</option><option value="<">&lt;</option><option value="<=">&le;</option><option value="=">equals</option><option value="!=">not equal</option><option value="exists">exists</option><option value="contains">contains</option>
      </select>
      {condition.operator !== 'exists' && <input className="input min-w-[7rem] flex-1" aria-label="Alert value" placeholder="value" value={String(condition.value ?? '')} onChange={(event) => onChange({ ...condition, value: normalizeValue(event.target.value) })} />}
      {onRemove && <button type="button" className="px-1 text-sm text-red-600 hover:text-red-800" aria-label="Remove condition" onClick={onRemove}>×</button>}
    </div>
  );
}

export default function Alerts() {
  const { t } = useTranslation();
  const { user, selectedTenantId, tenantContexts } = useAuthStore();
  const abilities = user?.abilities || [];
  /**
   * Evaluates the named capability or state predicate.
   */
  const hasAbility = (ability: string) => Boolean(user?.superadmin || abilities.includes('*') || abilities.includes(ability));
  const canReadAlerts = hasAbility('alert:read') || hasAbility('alert:configure');
  const canConfigureAlerts = hasAbility('alert:configure');

  const [channels, setChannels] = useState<AlertChannel[]>([]);
  const [rules, setRules] = useState<AlertRule[]>([]);
  const [pipelines, setPipelines] = useState<Pipeline[]>([]);
  const [channelPage, setChannelPage] = useState(0);
  const [channelHasMore, setChannelHasMore] = useState(false);
  const [history, setHistory] = useState<AlertHistory[]>([]);
  const [historyPage, setHistoryPage] = useState(0);
  const [historyHasMore, setHistoryHasMore] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [activeTab, setActiveTab] = useState<'channels' | 'rules' | 'history'>('channels');
  const [showChannelModal, setShowChannelModal] = useState(false);
  const [editingChannel, setEditingChannel] = useState<AlertChannel | null>(null);
  const [newChannelName, setNewChannelName] = useState('');
  const [newChannelType, setNewChannelType] = useState<AlertChannelType>('WEBHOOK');
  const [newChannelConfig, setNewChannelConfig] = useState<Record<string, string>>(initialChannelConfig('WEBHOOK'));
  const [creatingChannel, setCreatingChannel] = useState(false);
  const [confirmDeleteChannelId, setConfirmDeleteChannelId] = useState<string | null>(null);
  const [deletingChannelId, setDeletingChannelId] = useState<string | null>(null);
  const [toast, setToast] = useState<{ message: string; type: 'success' | 'error' } | null>(null);
  const [copiedChannelId, setCopiedChannelId] = useState<string | null>(null);
  const [selectedRuleId, setSelectedRuleId] = useState<string | null>(null);
  const [ruleDraft, setRuleDraft] = useState<AlertRuleDraft>(emptyRule());
  const [savingRule, setSavingRule] = useState(false);
  const [deletingRuleId, setDeletingRuleId] = useState<string | null>(null);
  const [testPayload, setTestPayload] = useState('{\n  \n}');
  const [testResult, setTestResult] = useState<{ matched: boolean; actions: Array<{ channelId: string; channelType: string; channelName: string }> } | null>(null);
  const [testingRule, setTestingRule] = useState(false);

  const tenantId = selectedTenantId || user?.tenantId || tenantContexts[0]?.tenantId;

  useEffect(() => {
    setHistoryPage(0);
  }, [tenantId]);

  const toastTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    return () => {
      if (toastTimerRef.current) clearTimeout(toastTimerRef.current);
    };
  }, []);

  /**
   * Implements `showToast` for this source unit.
   */
  const showToast = (message: string, type: 'success' | 'error') => {
    if (toastTimerRef.current) clearTimeout(toastTimerRef.current);
    setToast({ message, type });
    toastTimerRef.current = setTimeout(() => setToast(null), 3500);
  };

  /**
   * Implements `useEffect` for this source unit.
   */
  useEffect(() => {
    /**
     * Implements `fetchData` for this source unit.
     */
    const fetchData = async () => {
      if (!tenantId) {
        setLoading(false);
        return;
      }
      if (!canReadAlerts) {
        setChannels([]);
        setHistory([]);
        setError('');
        setLoading(false);
        return;
      }

      try {
        const [channelsData, historyData] = await Promise.all([
          api.getAlertChannels(tenantId, channelPage),
          api.getAlertHistory(tenantId, 20, historyPage),
        ]);
        setChannels(channelsData.items);
        setChannelHasMore(channelsData.hasMore);
        setHistory(historyData.items);
        setHistoryHasMore(historyData.hasMore);
        setError('');
      } catch (err) {
        console.error('Error fetching alerts:', err);
        setError(t('common.loadingFailed'));
      } finally {
        setLoading(false);
      }
    };

    fetchData();
  }, [tenantId, canReadAlerts, channelPage, historyPage, t]);

  useEffect(() => {
    if (!tenantId || !canReadAlerts) {
      setRules([]);
      return;
    }
    api.getAlertRules(tenantId)
      .then((data) => {
        setRules(data.items);
        setSelectedRuleId((current) => current && data.items.some((rule) => rule.id === current) ? current : null);
      })
      .catch((err) => {
        console.error('Error loading alert rules:', err);
        setError(t('common.loadingFailed'));
      });
  }, [tenantId, canReadAlerts, t]);

  useEffect(() => {
    if (!tenantId || !canReadAlerts) {
      setPipelines([]);
      return;
    }
    api.getPipelines(tenantId).then(setPipelines).catch((err) => {
      console.error('Error loading alert pipelines:', err);
    });
  }, [tenantId, canReadAlerts]);

  const selectRule = (rule: AlertRule) => {
    setSelectedRuleId(rule.id);
    setRuleDraft({ ...rule, condition: parseCondition(rule.condition) });
    setTestResult(null);
  };

  const startRule = () => {
    setSelectedRuleId(null);
    setRuleDraft(emptyRule());
    setTestResult(null);
  };

  const saveRule = async () => {
    if (!tenantId || !canConfigureAlerts || !ruleDraft.name.trim()) return;
    const condition = parseCondition(ruleDraft.condition);
    setSavingRule(true);
    try {
      const payload = {
        name: ruleDraft.name.trim(),
        description: ruleDraft.description || '',
        condition,
        severity: ruleDraft.severity || 'WARNING',
        message: ruleDraft.message || '',
        channelId: ruleDraft.channelId || undefined,
        channelIds: ruleDraft.channelIds || (ruleDraft.channelId ? [ruleDraft.channelId] : []),
        pipelineId: ruleDraft.pipelineId || '',
        enabled: ruleDraft.enabled,
        cooldownSeconds: Number(ruleDraft.cooldownSeconds) || 0,
      };
      const saved = selectedRuleId
        ? await api.updateAlertRule(selectedRuleId, payload)
        : await api.createAlertRule(tenantId, payload.name, payload.condition, payload.channelId, ruleDraft.pipelineId, payload.channelIds);
      const complete = selectedRuleId ? saved : await api.updateAlertRule(saved.id, payload);
      setRules((current) => selectedRuleId ? current.map((rule) => rule.id === complete.id ? complete : rule) : [complete, ...current]);
      setSelectedRuleId(complete.id);
      setRuleDraft({ ...complete, condition: parseCondition(complete.condition) });
      setError('');
      showToast(selectedRuleId ? t('alerts.alertRuleUpdated') : t('alerts.alertRuleCreated'), 'success');
    } catch (err) {
      console.error('Error saving alert rule:', err);
      setError(t('alerts.failedCreateRuleCheckJson'));
    } finally {
      setSavingRule(false);
    }
  };

  const deleteRule = async (ruleId: string) => {
    if (!canConfigureAlerts) return;
    setDeletingRuleId(ruleId);
    try {
      await api.deleteAlertRule(ruleId);
      setRules((current) => current.filter((rule) => rule.id !== ruleId));
      if (selectedRuleId === ruleId) startRule();
      showToast(t('alerts.alertRuleDeleted'), 'success');
    } catch (err) {
      console.error('Error deleting alert rule:', err);
      setError(t('alerts.failedDeleteRule'));
    } finally {
      setDeletingRuleId(null);
    }
  };

  const testRule = async () => {
    if (!selectedRuleId) {
      setError('Save the rule before testing it.');
      return;
    }
    try {
      const payload = JSON.parse(testPayload) as Record<string, unknown>;
      setTestingRule(true);
      const result = await api.testAlertRule(selectedRuleId, payload);
      setTestResult(result);
      setError('');
    } catch (err) {
      console.error('Error testing alert rule:', err);
      setError('Enter a valid JSON test payload.');
    } finally {
      setTestingRule(false);
    }
  };

  /**
   * Processes the named request, event, or operation.
   */
  const handleCreateChannel = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!canConfigureAlerts) {
      showToast(t('alerts.permissionManageChannels'), 'error');
      return;
    }
    if (!tenantId || !newChannelName) return;

    setCreatingChannel(true);
    try {
      const requiredMissing = channelConfigSchema[newChannelType]
        .filter((field) => field.required && !newChannelConfig[field.key]?.trim())
        .map((field) => field.labelKey);
      if (requiredMissing.length > 0) {
        setError(`${t('alerts.missingRequiredFields')}: ${requiredMissing.join(', ')}`);
        return;
      }

      const config = Object.entries(newChannelConfig).reduce<Record<string, unknown>>((acc, [key, value]) => {
        const trimmed = value.trim();
        if (!trimmed) return acc;
        if (key === 'qos') {
          const parsed = Number(trimmed);
          acc[key] = Number.isFinite(parsed) ? parsed : trimmed;
        } else {
          acc[key] = trimmed;
        }
        return acc;
      }, {});

      if (editingChannel) {
        const updated = await api.updateAlertChannel(editingChannel.id, {
          name: newChannelName,
          channelType: newChannelType as AlertChannel['channelType'],
          config,
        });
        setChannels(channels.map((c) => (c.id === updated.id ? updated : c)));
      } else {
        const newChannel = await api.createAlertChannel(tenantId, newChannelName, newChannelType, config);
        setChannels([...channels, newChannel]);
      }
      setShowChannelModal(false);
      setEditingChannel(null);
      setNewChannelName('');
      setNewChannelType('WEBHOOK');
      setNewChannelConfig(initialChannelConfig('WEBHOOK'));
      setError('');
      showToast(editingChannel ? t('alerts.alertChannelUpdated') : t('alerts.alertChannelCreated'), 'success');
    } catch (err) {
      console.error('Error creating channel:', err);
      setError(t('alerts.failedCreateChannel'));
    } finally {
      setCreatingChannel(false);
    }
  };

  /**
   * Processes the named request, event, or operation.
   */
  const handleDeleteChannel = async (id: string) => {
    if (!canConfigureAlerts) {
      showToast(t('alerts.permissionDeleteChannels'), 'error');
      return;
    }
    setDeletingChannelId(id);
    try {
      await api.deleteAlertChannel(id);
      setChannels(channels.filter((channel) => channel.id !== id));
      setConfirmDeleteChannelId(null);
      setError('');
      showToast(t('alerts.alertChannelDeleted'), 'success');
    } catch (err) {
      console.error('Error deleting channel:', err);
      setError(t('alerts.failedDeleteChannel'));
    } finally {
      setDeletingChannelId(null);
    }
  };

  /**
   * Implements `openEditChannel` for this source unit.
   */
  const openEditChannel = (channel: AlertChannel) => {
    if (!canConfigureAlerts) return;
    setEditingChannel(channel);
    setNewChannelName(channel.name);
    setNewChannelType(channel.channelType);
    setNewChannelConfig(initialChannelConfig(channel.channelType, channel.config));
    setShowChannelModal(true);
  };

  /**
   * Implements `openCreateChannel` for this source unit.
   */
  const openCreateChannel = () => {
    setEditingChannel(null);
    setNewChannelName('');
    setNewChannelType('WEBHOOK');
    setNewChannelConfig(initialChannelConfig('WEBHOOK'));
    setShowChannelModal(true);
  };

  /**
   * Implements `copyChannelId` for this source unit.
   */
  const copyChannelId = async (id: string) => {
    try {
      await navigator.clipboard.writeText(id);
      setCopiedChannelId(id);
      setTimeout(() => setCopiedChannelId(null), 2000);
    } catch {
      // clipboard not available
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
        <h1 className="text-2xl font-bold text-gray-900">{t('alerts.title')}</h1>
      </div>

      {error && (
        <div className="mb-4 bg-red-50 border border-red-200 text-red-700 px-4 py-3 rounded">
          {error}
        </div>
      )}

      {!canReadAlerts && (
        <div className="mb-4 rounded border border-amber-200 bg-amber-50 px-4 py-3 text-amber-800">
          {t('alerts.permissionViewAlerts')}
        </div>
      )}

      <div className="mb-4 flex space-x-2">
        {(['channels', 'rules', 'history'] as const).map((tab) => (
          <button
            key={tab}
            onClick={() => setActiveTab(tab)}
            className={`px-4 py-2 rounded-lg text-sm font-medium ${
              activeTab === tab
                ? 'bg-primary-600 text-white'
                : 'bg-gray-100 text-gray-700 hover:bg-gray-200'
            }`}
          >
            {t(`alerts.${tab}`)}
          </button>
        ))}
      </div>

      {loading ? (
        <div className="card animate-pulse">
          <div className="h-4 bg-gray-200 rounded w-1/3"></div>
        </div>
      ) : !tenantId ? (
        <div className="card text-center py-12">
          <p className="text-gray-500">{t('common.noTenantId')}</p>
        </div>
      ) : (
        <>
          {activeTab === 'channels' && (
            <div className="space-y-4">
              <div className="rounded border border-blue-200 bg-blue-50 px-4 py-3 text-sm text-blue-900">
                <p className="font-semibold mb-1">How ALERT modules fire channels</p>
                <p className="mb-2">
                  Alert modules dispatch alerts by returning a special object from their code. The platform detects this return value and fires the matching channel:
                </p>
                <pre className="bg-blue-100 rounded px-3 py-2 text-xs font-mono overflow-x-auto whitespace-pre-wrap">{`return {
  "__alert__": {
    "channelId": "<id>",
    "title": "Threshold exceeded",
    "message": "Temperature is above limit",
    "severity": "WARNING",
    "payload": {}
  }
}`}</pre>
                <p className="mt-2 text-blue-800 text-xs">
                  Copy the channel ID from any channel card below to use in your module code.
                </p>
              </div>

              {canConfigureAlerts && (
                <div className="flex justify-end">
                  <button onClick={openCreateChannel} className="btn-primary text-sm">{t('alerts.addChannel')}</button>
                </div>
              )}

              {channels.length > 0 ? (
                channels.map((channel) => (
                  <div key={channel.id} className="card">
                    <div className="flex justify-between items-start">
                      <div>
                        <h3 className="text-lg font-semibold">{channel.name}</h3>
                        {channel.description && (
                          <p className="text-sm text-gray-500">{channel.description}</p>
                        )}
                        <div className="flex items-center gap-2 mt-2 flex-wrap">
                          <span className="inline-block px-2 py-1 text-xs font-medium rounded bg-gray-100">
                            {channel.channelType}
                          </span>
                          <span className="inline-flex items-center gap-1 px-2 py-1 text-xs rounded bg-slate-100 text-slate-700 font-mono">
                            {channel.id}
                            <button
                              onClick={() => copyChannelId(channel.id)}
                              className="ml-1 text-slate-500 hover:text-slate-800"
                              title="Copy channel ID"
                            >
                              {copiedChannelId === channel.id ? '✓' : '⎘'}
                            </button>
                          </span>
                        </div>
                      </div>
                      <span className={`px-2 py-1 text-xs rounded ${channel.isActive ? 'bg-green-100 text-green-800' : 'bg-gray-100 text-gray-800'}`}>
                        {channel.isActive ? t('common.active') : t('common.inactive')}
                      </span>
                    </div>
                    {canConfigureAlerts && (
                      <div className="mt-4 flex justify-end">
                        <button
                          onClick={() => openEditChannel(channel)}
                          className="mr-3 text-sm text-blue-600 hover:text-blue-800"
                        >
                          {t('common.edit')}
                        </button>
                        {confirmDeleteChannelId === channel.id ? (
                          <div className="flex items-center space-x-2">
                            <span className="text-xs text-gray-500">{t('common.deleteConfirm')}</span>
                            <button
                              onClick={() => handleDeleteChannel(channel.id)}
                              disabled={deletingChannelId === channel.id}
                              className="text-sm text-red-600 hover:text-red-800"
                            >
                              {deletingChannelId === channel.id ? t('common.deleting') : t('common.yes')}
                            </button>
                            <button
                              onClick={() => setConfirmDeleteChannelId(null)}
                              className="text-sm text-gray-500 hover:text-gray-700"
                            >
                              {t('common.no')}
                            </button>
                          </div>
                        ) : (
                          <button
                            onClick={() => setConfirmDeleteChannelId(channel.id)}
                            className="text-sm text-red-600 hover:text-red-800"
                          >
                            {t('common.delete')}
                          </button>
                        )}
                      </div>
                    )}
                  </div>
                ))
              ) : (
                <div className="card text-center py-8">
                  <p className="text-gray-500">{t('alerts.noChannels')}</p>
                </div>
              )}
              <div className="flex items-center justify-between">
                <span className="text-sm text-gray-500">Page {channelPage + 1}</span>
                <div className="flex gap-2">
                  <button
                    className="btn-secondary text-sm"
                    disabled={channelPage === 0}
                    onClick={() => setChannelPage((page) => page - 1)}
                  >
                    Previous
                  </button>
                  <button
                    className="btn-secondary text-sm"
                    disabled={!channelHasMore}
                    onClick={() => setChannelPage((page) => page + 1)}
                  >
                    Next
                  </button>
                </div>
              </div>
            </div>
          )}

          {activeTab === 'rules' && (
            <div className="grid gap-5 lg:grid-cols-[minmax(15rem,0.75fr)_minmax(0,1.6fr)]">
              <aside className="card h-fit space-y-3">
                <div className="flex items-center justify-between gap-3">
                  <div><p className="text-xs font-semibold uppercase tracking-wider text-slate-500">Rule library</p><p className="text-sm text-slate-600">Conditions decide when a channel is notified.</p></div>
                  {canConfigureAlerts && <button className="btn-primary whitespace-nowrap text-sm" onClick={startRule}>+ Rule</button>}
                </div>
                <div className="space-y-2">
                  {rules.length ? rules.map((rule) => (
                    <button key={rule.id} type="button" onClick={() => selectRule(rule)} className={`w-full rounded-lg border p-3 text-left transition-colors ${selectedRuleId === rule.id ? 'border-primary-500 bg-primary-50 shadow-sm' : 'border-slate-200 hover:border-slate-300 hover:bg-slate-50'}`}>
                      <div className="flex items-start justify-between gap-2"><span className="font-medium text-slate-900">{rule.name}</span><span className={`rounded-full px-2 py-0.5 text-xs ${rule.enabled ? 'bg-emerald-100 text-emerald-800' : 'bg-slate-100 text-slate-600'}`}>{rule.enabled ? 'Enabled' : 'Disabled'}</span></div>
                      <p className="mt-1 text-xs text-slate-500">{rule.cooldownSeconds || 0}s cooldown · {(rule.channelIds || (rule.channelId ? [rule.channelId] : [])).map((id) => channels.find((channel) => channel.id === id)?.name).filter(Boolean).join(', ') || 'No channel'}</p>
                    </button>
                  )) : <p className="rounded border border-dashed border-slate-200 px-3 py-6 text-center text-sm text-slate-500">{t('alerts.noRules')}</p>}
                </div>
              </aside>

              <section className="card space-y-5">
                <div className="flex flex-wrap items-start justify-between gap-3 border-b border-slate-100 pb-4">
                  <div><p className="text-xs font-semibold uppercase tracking-wider text-primary-700">Alert rule</p><h2 className="text-xl font-bold text-slate-900">{selectedRuleId ? 'Edit rule' : 'New rule'}</h2></div>
                  {selectedRuleId && canConfigureAlerts && <button type="button" className="text-sm text-red-600 hover:text-red-800" disabled={deletingRuleId === selectedRuleId} onClick={() => deleteRule(selectedRuleId)}>{deletingRuleId === selectedRuleId ? 'Deleting…' : 'Delete rule'}</button>}
                </div>
                <div className="grid gap-4 sm:grid-cols-2">
                  <label className="block text-sm font-medium text-slate-700">Name<input className="input mt-1 w-full" disabled={!canConfigureAlerts} value={ruleDraft.name} onChange={(event) => setRuleDraft((draft) => ({ ...draft, name: event.target.value }))} placeholder="High temperature" /></label>
                  <label className="block text-sm font-medium text-slate-700">Notification channels<select multiple className="input mt-1 min-h-28 w-full" disabled={!canConfigureAlerts} value={ruleDraft.channelIds || (ruleDraft.channelId ? [ruleDraft.channelId] : [])} onChange={(event) => { const channelIds = Array.from(event.currentTarget.selectedOptions, (option) => option.value); setRuleDraft((draft) => ({ ...draft, channelIds, channelId: channelIds[0] })); }}>{channels.map((channel) => <option value={channel.id} key={channel.id}>{channel.name} ({channel.channelType})</option>)}</select><span className="mt-1 block text-xs font-normal text-slate-500">Hold Ctrl/Cmd to select more than one channel.</span></label>
                  <label className="block text-sm font-medium text-slate-700">Pipeline scope<select className="input mt-1 w-full" disabled={!canConfigureAlerts} value={ruleDraft.pipelineId || ''} onChange={(event) => setRuleDraft((draft) => ({ ...draft, pipelineId: event.target.value || undefined }))}><option value="">Any pipeline</option>{pipelines.map((pipeline) => <option value={pipeline.id} key={pipeline.id}>{pipeline.name}</option>)}</select></label>
                  <label className="block text-sm font-medium text-slate-700">Cooldown (seconds)<input className="input mt-1 w-full" type="number" min="0" disabled={!canConfigureAlerts} value={ruleDraft.cooldownSeconds ?? 60} onChange={(event) => setRuleDraft((draft) => ({ ...draft, cooldownSeconds: Number(event.target.value) }))} /></label>
                  <label className="block text-sm font-medium text-slate-700">Severity<select className="input mt-1 w-full" disabled={!canConfigureAlerts} value={ruleDraft.severity || 'WARNING'} onChange={(event) => setRuleDraft((draft) => ({ ...draft, severity: event.target.value as AlertRule['severity'] }))}><option value="INFO">Info</option><option value="WARNING">Warning</option><option value="CRITICAL">Critical</option><option value="ERROR">Error</option></select></label>
                  <label className="flex items-end gap-3 pb-2 text-sm font-medium text-slate-700"><input type="checkbox" disabled={!canConfigureAlerts} checked={ruleDraft.enabled} onChange={(event) => setRuleDraft((draft) => ({ ...draft, enabled: event.target.checked }))} />Enable this rule</label>
                </div>
                <label className="block text-sm font-medium text-slate-700">Description<input className="input mt-1 w-full" disabled={!canConfigureAlerts} value={ruleDraft.description || ''} onChange={(event) => setRuleDraft((draft) => ({ ...draft, description: event.target.value }))} placeholder="Tell the operator why this matters" /></label>
                <label className="block text-sm font-medium text-slate-700">Alert message<input className="input mt-1 w-full" disabled={!canConfigureAlerts} value={ruleDraft.message || ''} onChange={(event) => setRuleDraft((draft) => ({ ...draft, message: event.target.value }))} placeholder="Temperature is above the safe operating threshold" /></label>
                <div className="rounded-xl border border-slate-200 bg-white p-4">
                  <div className="mb-3 flex flex-wrap items-center justify-between gap-2"><div><h3 className="font-semibold text-slate-900">Condition logic</h3><p className="text-xs text-slate-500">Nest groups to express AND / OR logic. Values are sent as numbers or booleans when possible.</p></div>{canConfigureAlerts && !isConditionGroup(parseCondition(ruleDraft.condition)) && <button type="button" className="text-sm text-primary-700 hover:text-primary-900" onClick={() => setRuleDraft((draft) => ({ ...draft, condition: { all: [parseCondition(draft.condition)] } }))}>Convert to AND group</button>}</div>
                  <ConditionEditor condition={parseCondition(ruleDraft.condition)} onChange={(condition) => setRuleDraft((draft) => ({ ...draft, condition }))} />
                </div>
                {canConfigureAlerts && <div className="flex justify-end"><button type="button" className="btn-primary" disabled={savingRule || !ruleDraft.name.trim()} onClick={saveRule}>{savingRule ? 'Saving…' : 'Save rule'}</button></div>}
                <div className="rounded-xl border border-amber-200 bg-amber-50 p-4">
                  <div className="flex flex-wrap items-center justify-between gap-2"><div><h3 className="font-semibold text-amber-950">Test payload</h3><p className="text-xs text-amber-800">Evaluation is safe: it never dispatches a notification.</p></div><button type="button" className="btn-secondary text-sm" disabled={!selectedRuleId || testingRule} onClick={testRule}>{testingRule ? 'Testing…' : 'Test rule'}</button></div>
                  <textarea className="input mt-3 min-h-28 w-full font-mono text-xs" aria-label="Test alert payload JSON" value={testPayload} onChange={(event) => setTestPayload(event.target.value)} />
                  {testResult && <div className={`mt-3 rounded-lg px-3 py-2 text-sm ${testResult.matched ? 'bg-emerald-100 text-emerald-900' : 'bg-slate-100 text-slate-700'}`}><strong>{testResult.matched ? 'Matched' : 'Did not match'}.</strong> {testResult.actions.length ? `Would notify ${testResult.actions.map((action) => action.channelName).join(', ')}.` : 'No notification channel is attached.'}</div>}
                </div>
              </section>
            </div>
          )}

          {activeTab === 'history' && (
            <div className="space-y-3">
              {history.length > 0 ? (
                <>
                {history.map((alert) => (
                  <div key={alert.id} className="card">
                    <div className="flex items-center justify-between">
                      <div className="flex items-center space-x-3">
                        <span className={`w-3 h-3 rounded-full ${
                          alert.severity === 'CRITICAL' ? 'bg-red-500' :
                          alert.severity === 'ERROR' ? 'bg-red-400' :
                          alert.severity === 'WARNING' ? 'bg-yellow-500' :
                          'bg-blue-500'
                        }`}></span>
                        <span className="font-medium">{alert.title}</span>
                      </div>
                      <span className={`px-2 py-1 text-xs rounded ${
                        alert.status === 'SENT' ? 'bg-green-100 text-green-800' :
                        alert.status === 'FAILED' ? 'bg-red-100 text-red-800' :
                        'bg-gray-100 text-gray-800'
                      }`}>
                        {alert.status}
                      </span>
                    </div>
                    <p className="text-sm text-gray-500 mt-2">{alert.message}</p>
                    <p className="text-xs text-gray-400 mt-2">
                      {new Date(alert.sentAt).toLocaleString()}
                    </p>
                  </div>
                ))}
                <div className="flex justify-between pt-2">
                  <button className="btn-secondary text-sm" disabled={historyPage === 0} onClick={() => setHistoryPage((page) => Math.max(0, page - 1))}>Previous</button>
                  <span className="text-sm text-slate-500">Page {historyPage + 1}</span>
                  <button className="btn-secondary text-sm" disabled={!historyHasMore} onClick={() => setHistoryPage((page) => page + 1)}>Next</button>
                </div>
                </>
              ) : (
                <div className="card text-center py-8">
                  <p className="text-gray-500">{t('alerts.noHistory')}</p>
                </div>
              )}
            </div>
          )}
        </>
      )}

      {showChannelModal && canConfigureAlerts && (
        <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
          <div className="bg-white rounded-lg p-6 max-w-md w-full">
            <h2 className="text-xl font-bold mb-4">{editingChannel ? t('alerts.editChannel') : t('alerts.addChannel')}</h2>
            <form onSubmit={handleCreateChannel}>
              <div className="mb-4">
                <label className="block text-sm font-medium text-gray-700 mb-1">{t('alerts.channelName')}</label>
                <input
                  type="text"
                  value={newChannelName}
                  onChange={(e) => setNewChannelName(e.target.value)}
                  className="input w-full"
                  required
                />
              </div>
              <div className="mb-4">
                <label className="block text-sm font-medium text-gray-700 mb-1">{t('alerts.channelType')}</label>
                <select
                  value={newChannelType}
                  onChange={(e) => {
                    const nextType = e.target.value as AlertChannelType;
                    setNewChannelType(nextType);
                    setNewChannelConfig(initialChannelConfig(nextType, newChannelConfig));
                  }}
                  className="input w-full"
                >
                  <option value="WEBHOOK">Webhook</option>
                  <option value="SLACK">Slack</option>
                  <option value="EMAIL">Email</option>
                  <option value="REST">REST</option>
                  <option value="MQTT">MQTT</option>
                </select>
              </div>
              <div className="mb-4 rounded border border-slate-200 p-3">
                <label className="block text-sm font-medium text-slate-700 mb-2">{newChannelType} Configuration</label>
                <div className="space-y-3">
                  {(channelConfigSchema[newChannelType] || []).map((field) => (
                    <div key={field.key}>
                      <label className="block text-sm text-slate-600 mb-1">
                        {t(field.labelKey)}{field.required ? ` ${t('common.required')}` : ''}
                      </label>
                      <input
                        type={field.type || 'text'}
                        value={newChannelConfig[field.key] || ''}
                        onChange={(e) => setNewChannelConfig((prev) => ({ ...prev, [field.key]: e.target.value }))}
                        className="input w-full"
                        placeholder={field.placeholderKey ? t(field.placeholderKey) : ''}
                        required={field.required}
                      />
                    </div>
                  ))}
                </div>
              </div>
              <div className="flex justify-end space-x-2">
                <button
                  type="button"
                  onClick={() => {
                    setShowChannelModal(false);
                    setEditingChannel(null);
                    setNewChannelName('');
                    setNewChannelType('WEBHOOK');
                    setNewChannelConfig(initialChannelConfig('WEBHOOK'));
                  }}
                  className="px-4 py-2 border border-gray-300 rounded-md text-gray-700 hover:bg-gray-50"
                >
                  {t('common.cancel')}
                </button>
                <button
                  type="submit"
                  disabled={creatingChannel}
                  className="btn-primary"
                >
                  {creatingChannel ? t('common.saving') : editingChannel ? t('common.save') : t('common.create')}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}
