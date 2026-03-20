import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useAuthStore } from '../stores/auth';
import api from '../services/api';
import type { AlertChannel, AlertRule, AlertHistory, Pipeline } from '../types';

type AlertChannelType = AlertChannel['channelType'];
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

const initialChannelConfig = (type: AlertChannelType, existing?: Record<string, unknown>) => {
  const fields = channelConfigSchema[type] || [];
  return fields.reduce<Record<string, string>>((acc, field) => {
    const value = existing?.[field.key];
    acc[field.key] = value == null ? '' : String(value);
    return acc;
  }, {});
};

export default function Alerts() {
  const { t } = useTranslation();
  const { user, selectedTenantId, tenantContexts } = useAuthStore();
  const abilities = user?.abilities || [];
  const hasAbility = (ability: string) => Boolean(user?.superadmin || abilities.includes('*') || abilities.includes(ability));
  const canReadAlerts = hasAbility('alert:read') || hasAbility('alert:configure');
  const canConfigureAlerts = hasAbility('alert:configure');
  const canReadPipelines = hasAbility('pipeline:read');
  const [channels, setChannels] = useState<AlertChannel[]>([]);
  const [rules, setRules] = useState<AlertRule[]>([]);
  const [history, setHistory] = useState<AlertHistory[]>([]);
  const [pipelines, setPipelines] = useState<Pipeline[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [activeTab, setActiveTab] = useState<'channels' | 'rules' | 'history'>('channels');
  const [showChannelModal, setShowChannelModal] = useState(false);
  const [showRuleModal, setShowRuleModal] = useState(false);
  const [editingChannel, setEditingChannel] = useState<AlertChannel | null>(null);
  const [editingRule, setEditingRule] = useState<AlertRule | null>(null);
  const [newChannelName, setNewChannelName] = useState('');
  const [newChannelType, setNewChannelType] = useState<AlertChannelType>('WEBHOOK');
  const [newChannelConfig, setNewChannelConfig] = useState<Record<string, string>>(initialChannelConfig('WEBHOOK'));
  const [creatingChannel, setCreatingChannel] = useState(false);
  const [newRuleName, setNewRuleName] = useState('');
  const [newRuleCondition, setNewRuleCondition] = useState('');
  const [ruleConditionError, setRuleConditionError] = useState('');
  const [builderLogic, setBuilderLogic] = useState<'single' | 'any' | 'all'>('single');
  const [builderFieldA, setBuilderFieldA] = useState('temperature');
  const [builderOperatorA, setBuilderOperatorA] = useState('>');
  const [builderValueA, setBuilderValueA] = useState('80');
  const [builderFieldB, setBuilderFieldB] = useState('status');
  const [builderOperatorB, setBuilderOperatorB] = useState('==');
  const [builderValueB, setBuilderValueB] = useState('CRITICAL');
  const [builderSeverity, setBuilderSeverity] = useState('WARNING');
  const [builderMessage, setBuilderMessage] = useState('Threshold exceeded');
  const [newRuleChannelId, setNewRuleChannelId] = useState('');
  const [newRulePipelineId, setNewRulePipelineId] = useState('');
  const [creatingRule, setCreatingRule] = useState(false);
  const [confirmDeleteChannelId, setConfirmDeleteChannelId] = useState<string | null>(null);
  const [confirmDeleteRuleId, setConfirmDeleteRuleId] = useState<string | null>(null);
  const [deletingChannelId, setDeletingChannelId] = useState<string | null>(null);
  const [deletingRuleId, setDeletingRuleId] = useState<string | null>(null);
  const [toast, setToast] = useState<{ message: string; type: 'success' | 'error' } | null>(null);

  const tenantId = selectedTenantId || user?.tenantId || tenantContexts[0]?.tenantId;

  const showToast = (message: string, type: 'success' | 'error') => {
    setToast({ message, type });
    setTimeout(() => setToast(null), 3500);
  };

  useEffect(() => {
    const fetchData = async () => {
      if (!tenantId) {
        setLoading(false);
        return;
      }
      if (!canReadAlerts) {
        setChannels([]);
        setRules([]);
        setHistory([]);
        setPipelines([]);
        setError('');
        setLoading(false);
        return;
      }
      
      try {
        const [channelsData, rulesData, historyData, pipelinesData] = await Promise.all([
          api.getAlertChannels(tenantId),
          api.getAlertRules(tenantId),
          api.getAlertHistory(tenantId, 20),
          canReadPipelines ? api.getPipelines(tenantId) : Promise.resolve([] as Pipeline[]),
        ]);
        setChannels(channelsData);
        setRules(rulesData);
        setHistory(historyData);
        setPipelines(pipelinesData);
        setError('');
      } catch (error) {
        console.error('Error fetching alerts:', error);
        setError(t('common.loadingFailed'));
      } finally {
        setLoading(false);
      }
    };
    
    fetchData();
  }, [tenantId, canReadAlerts, canReadPipelines, t]);

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
    } catch (error) {
      console.error('Error creating channel:', error);
      setError(t('alerts.failedCreateChannel'));
    } finally {
      setCreatingChannel(false);
    }
  };

  const handleCreateRule = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!canConfigureAlerts) {
      showToast(t('alerts.permissionManageRules'), 'error');
      return;
    }
    if (!tenantId || !newRuleName) return;
    
    setCreatingRule(true);
    try {
      const condition = JSON.parse(newRuleCondition || '{}');
      setRuleConditionError('');
      if (editingRule) {
        const updated = await api.updateAlertRule(editingRule.id, {
          name: newRuleName,
          condition,
          channelId: newRuleChannelId || undefined,
          pipelineId: newRulePipelineId || undefined,
        });
        setRules(rules.map((r) => (r.id === updated.id ? updated : r)));
      } else {
        const newRule = await api.createAlertRule(
          tenantId,
          newRuleName,
          condition,
          newRuleChannelId || undefined,
          newRulePipelineId || undefined
        );
        setRules([...rules, newRule]);
      }
      setShowRuleModal(false);
      setEditingRule(null);
      setNewRuleName('');
      setNewRuleCondition('');
      setNewRuleChannelId('');
      setNewRulePipelineId('');
      setError('');
      showToast(editingRule ? t('alerts.alertRuleUpdated') : t('alerts.alertRuleCreated'), 'success');
    } catch (error) {
      console.error('Error creating rule:', error);
      setRuleConditionError(t('alerts.invalidConditionJson'));
      setError(t('alerts.failedCreateRuleCheckJson'));
    } finally {
      setCreatingRule(false);
    }
  };

  const useConditionTemplate = (type: 'threshold' | 'exists' | 'any') => {
    if (type === 'threshold') {
      setNewRuleCondition(JSON.stringify({ field: 'temperature', operator: '>', value: 80, severity: 'WARNING', message: 'Temperature threshold exceeded' }, null, 2));
    } else if (type === 'exists') {
      setNewRuleCondition(JSON.stringify({ field: 'deviceId', operator: 'exists', severity: 'INFO', message: 'Device payload received' }, null, 2));
    } else {
      setNewRuleCondition(JSON.stringify({ any: [{ field: 'temperature', operator: '>', value: 80 }, { field: 'status', operator: '==', value: 'CRITICAL' }], severity: 'CRITICAL', message: 'Critical condition met' }, null, 2));
    }
    setRuleConditionError('');
  };

  const coerceConditionValue = (value: string): string | number | boolean => {
    const normalized = value.trim();
    if (normalized.toLowerCase() === 'true') return true;
    if (normalized.toLowerCase() === 'false') return false;
    const asNumber = Number(normalized);
    if (!Number.isNaN(asNumber) && normalized !== '') return asNumber;
    return normalized;
  };

  const buildConditionObject = () => {
    const makeClause = (field: string, operator: string, value: string) => {
      if (operator === 'exists') {
        return { field, operator };
      }
      return { field, operator, value: coerceConditionValue(value) };
    };

    const clauseA = makeClause(builderFieldA, builderOperatorA, builderValueA);
    const clauseB = makeClause(builderFieldB, builderOperatorB, builderValueB);
    const meta = {
      severity: builderSeverity,
      message: builderMessage,
    };

    if (builderLogic === 'single') {
      return { ...clauseA, ...meta };
    }

    return {
      [builderLogic]: [clauseA, clauseB],
      ...meta,
    };
  };

  const applyBuilderToJson = () => {
    const condition = buildConditionObject();
    setNewRuleCondition(JSON.stringify(condition, null, 2));
    setRuleConditionError('');
  };

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

  const handleDeleteRule = async (id: string) => {
    if (!canConfigureAlerts) {
      showToast(t('alerts.permissionDeleteRules'), 'error');
      return;
    }
    setDeletingRuleId(id);
    try {
      await api.deleteAlertRule(id);
      setRules(rules.filter((rule) => rule.id !== id));
      setConfirmDeleteRuleId(null);
      setError('');
      showToast(t('alerts.alertRuleDeleted'), 'success');
    } catch (err) {
      console.error('Error deleting rule:', err);
      setError(t('alerts.failedDeleteRule'));
    } finally {
      setDeletingRuleId(null);
    }
  };

  const openEditChannel = (channel: AlertChannel) => {
    if (!canConfigureAlerts) return;
    setEditingChannel(channel);
    setNewChannelName(channel.name);
    setNewChannelType(channel.channelType);
    setNewChannelConfig(initialChannelConfig(channel.channelType, channel.config));
    setShowChannelModal(true);
  };

  const openCreateChannel = () => {
    setEditingChannel(null);
    setNewChannelName('');
    setNewChannelType('WEBHOOK');
    setNewChannelConfig(initialChannelConfig('WEBHOOK'));
    setShowChannelModal(true);
  };

  const openEditRule = (rule: AlertRule) => {
    if (!canConfigureAlerts) return;
    setEditingRule(rule);
    setNewRuleName(rule.name);
    setNewRuleChannelId(rule.channelId || '');
    setNewRulePipelineId(rule.pipelineId || '');
    const conditionValue = typeof rule.condition === 'string'
      ? rule.condition
      : JSON.stringify(rule.condition ?? {}, null, 2);
    setNewRuleCondition(conditionValue);
    setShowRuleModal(true);
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
                        <p className="text-sm text-gray-500">{channel.description}</p>
                        <span className="inline-block mt-2 px-2 py-1 text-xs font-medium rounded bg-gray-100">
                          {channel.channelType}
                        </span>
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
            </div>
          )}

          {activeTab === 'rules' && (
            <div className="space-y-4">
              {canConfigureAlerts && (
                <div className="flex justify-end">
                  <button onClick={() => setShowRuleModal(true)} className="btn-primary text-sm">{t('alerts.addRule')}</button>
                </div>
              )}
              {rules.length > 0 ? (
                rules.map((rule) => (
                  <div key={rule.id} className="card">
                    <div className="flex justify-between items-start">
                      <div>
                        <h3 className="text-lg font-semibold">{rule.name}</h3>
                        <p className="text-sm text-gray-500">{rule.description}</p>
                      </div>
                        <span className={`px-2 py-1 text-xs rounded ${rule.enabled ? 'bg-green-100 text-green-800' : 'bg-gray-100 text-gray-800'}`}>
                          {rule.enabled ? t('alerts.enabled') : t('alerts.disabled')}
                        </span>
                      </div>
                      {canConfigureAlerts && (
                        <div className="mt-4 flex justify-end">
                          <button
                            onClick={() => openEditRule(rule)}
                            className="mr-3 text-sm text-blue-600 hover:text-blue-800"
                          >
                            {t('common.edit')}
                          </button>
                          {confirmDeleteRuleId === rule.id ? (
                            <div className="flex items-center space-x-2">
                              <span className="text-xs text-gray-500">{t('common.deleteConfirm')}</span>
                              <button
                                onClick={() => handleDeleteRule(rule.id)}
                                disabled={deletingRuleId === rule.id}
                                className="text-sm text-red-600 hover:text-red-800"
                              >
                                {deletingRuleId === rule.id ? t('common.deleting') : t('common.yes')}
                              </button>
                              <button
                                onClick={() => setConfirmDeleteRuleId(null)}
                                className="text-sm text-gray-500 hover:text-gray-700"
                              >
                                {t('common.no')}
                              </button>
                            </div>
                          ) : (
                            <button
                              onClick={() => setConfirmDeleteRuleId(rule.id)}
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
                  <p className="text-gray-500">{t('alerts.noRules')}</p>
                </div>
              )}
            </div>
          )}

          {activeTab === 'history' && (
            <div className="space-y-3">
              {history.length > 0 ? (
                history.map((alert) => (
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
                ))
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
              <div className="mb-4 rounded border border-slate-200 dark:border-slate-700 p-3">
                <label className="block text-sm font-medium text-slate-700 dark:text-slate-200 mb-2">{newChannelType} Configuration</label>
                <div className="space-y-3">
                  {(channelConfigSchema[newChannelType] || []).map((field) => (
                    <div key={field.key}>
                      <label className="block text-sm text-slate-600 dark:text-slate-300 mb-1">
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

      {showRuleModal && canConfigureAlerts && (
        <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
          <div className="bg-white rounded-lg p-6 max-w-md w-full">
            <h2 className="text-xl font-bold mb-4">{editingRule ? t('alerts.editRule') : t('alerts.addRule')}</h2>
            <form onSubmit={handleCreateRule}>
              <div className="mb-4">
                <label className="block text-sm font-medium text-gray-700 mb-1">{t('alerts.channelName')}</label>
                <input
                  type="text"
                  value={newRuleName}
                  onChange={(e) => setNewRuleName(e.target.value)}
                  className="input w-full"
                  required
                />
              </div>
              <div className="mb-4">
                <label className="block text-sm font-medium text-gray-700 mb-1">{t('alerts.channelType')}</label>
                <select
                  value={newRuleChannelId}
                  onChange={(e) => setNewRuleChannelId(e.target.value)}
                  className="input w-full"
                >
                  <option value="">{t('alerts.selectChannel')}</option>
                  {channels.map((channel) => (
                    <option key={channel.id} value={channel.id}>{channel.name}</option>
                  ))}
                </select>
              </div>
              <div className="mb-4">
                <label className="block text-sm font-medium text-gray-700 mb-1">{t('alerts.pipelineOptional')}</label>
                <select
                  value={newRulePipelineId}
                  onChange={(e) => setNewRulePipelineId(e.target.value)}
                  className="input w-full"
                >
                  <option value="">{t('alerts.anyPipeline')}</option>
                  {pipelines.map((pipeline) => (
                    <option key={pipeline.id} value={pipeline.id}>{pipeline.name}</option>
                  ))}
                </select>
              </div>
              <div className="mb-4">
                <label className="block text-sm font-medium text-gray-700 mb-1">{t('alerts.conditionJson')}</label>
                <div className="mb-3 rounded border border-gray-200 p-3 bg-gray-50">
                  <div className="grid grid-cols-2 gap-2 mb-2">
                    <select value={builderLogic} onChange={(e) => setBuilderLogic(e.target.value as 'single' | 'any' | 'all')} className="input w-full">
                      <option value="single">{t('alerts.singleCondition')}</option>
                      <option value="any">{t('alerts.anyOf')}</option>
                      <option value="all">{t('alerts.allOf')}</option>
                    </select>
                    <select value={builderSeverity} onChange={(e) => setBuilderSeverity(e.target.value)} className="input w-full">
                      <option value="INFO">{t('alerts.severityInfo')}</option>
                      <option value="WARNING">{t('alerts.severityWarning')}</option>
                      <option value="CRITICAL">{t('alerts.severityCritical')}</option>
                      <option value="ERROR">{t('alerts.severityError')}</option>
                    </select>
                  </div>
                  <div className="grid grid-cols-3 gap-2 mb-2">
                    <input value={builderFieldA} onChange={(e) => setBuilderFieldA(e.target.value)} className="input w-full" placeholder={t('alerts.fieldPlaceholder')} />
                    <select value={builderOperatorA} onChange={(e) => setBuilderOperatorA(e.target.value)} className="input w-full">
                      <option value=">">&gt;</option>
                      <option value=">=">&gt;=</option>
                      <option value="<">&lt;</option>
                      <option value="<=">&lt;=</option>
                      <option value="==">==</option>
                      <option value="!=">!=</option>
                      <option value="contains">contains</option>
                      <option value="exists">exists</option>
                    </select>
                    <input value={builderValueA} onChange={(e) => setBuilderValueA(e.target.value)} className="input w-full" placeholder={t('alerts.valuePlaceholder')} disabled={builderOperatorA === 'exists'} />
                  </div>
                  {builderLogic !== 'single' && (
                    <div className="grid grid-cols-3 gap-2 mb-2">
                      <input value={builderFieldB} onChange={(e) => setBuilderFieldB(e.target.value)} className="input w-full" placeholder={t('alerts.fieldPlaceholder')} />
                      <select value={builderOperatorB} onChange={(e) => setBuilderOperatorB(e.target.value)} className="input w-full">
                        <option value=">">&gt;</option>
                        <option value=">=">&gt;=</option>
                        <option value="<">&lt;</option>
                        <option value="<=">&lt;=</option>
                        <option value="==">==</option>
                        <option value="!=">!=</option>
                        <option value="contains">contains</option>
                        <option value="exists">exists</option>
                      </select>
                      <input value={builderValueB} onChange={(e) => setBuilderValueB(e.target.value)} className="input w-full" placeholder={t('alerts.valuePlaceholder')} disabled={builderOperatorB === 'exists'} />
                    </div>
                  )}
                  <div className="flex gap-2">
                    <input value={builderMessage} onChange={(e) => setBuilderMessage(e.target.value)} className="input w-full" placeholder={t('alerts.messagePlaceholder')} />
                    <button type="button" onClick={applyBuilderToJson} className="px-3 py-2 text-xs border border-gray-300 rounded hover:bg-white">{t('common.applyBuilder')}</button>
                  </div>
                </div>
                <div className="mb-2 flex gap-2 flex-wrap">
                  <button type="button" onClick={() => useConditionTemplate('threshold')} className="px-2 py-1 text-xs border border-gray-300 rounded hover:bg-gray-50">{t('alerts.threshold')}</button>
                  <button type="button" onClick={() => useConditionTemplate('exists')} className="px-2 py-1 text-xs border border-gray-300 rounded hover:bg-gray-50">{t('alerts.exists')}</button>
                  <button type="button" onClick={() => useConditionTemplate('any')} className="px-2 py-1 text-xs border border-gray-300 rounded hover:bg-gray-50">{t('alerts.anyOfButton')}</button>
                </div>
                <textarea
                  value={newRuleCondition}
                  onChange={(e) => {
                    setNewRuleCondition(e.target.value);
                    setRuleConditionError('');
                  }}
                  className="input w-full"
                  placeholder={t('alerts.conditionBuilderPlaceholder')}
                  rows={3}
                />
                {ruleConditionError && <p className="mt-1 text-xs text-red-600">{ruleConditionError}</p>}
              </div>
              <div className="flex justify-end space-x-2">
                <button
                  type="button"
                  onClick={() => {
                    setShowRuleModal(false);
                    setEditingRule(null);
                  }}
                  className="px-4 py-2 border border-gray-300 rounded-md text-gray-700 hover:bg-gray-50"
                >
                  {t('common.cancel')}
                </button>
                <button
                  type="submit"
                  disabled={creatingRule}
                  className="btn-primary"
                >
                  {creatingRule ? t('common.saving') : editingRule ? t('common.save') : t('common.create')}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}
