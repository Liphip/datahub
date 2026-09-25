import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useAuthStore } from '../stores/auth';
import api from '../services/api';
import type { AlertChannel, AlertHistory, Connector } from '../types';

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
  type?: 'text' | 'url' | 'email' | 'number' | 'password' | 'mqttConnector';
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
    { key: 'recipient', labelKey: 'alerts.recipientEmail', type: 'text', required: true, placeholderKey: 'alerts.recipientEmailPlaceholder' },
    { key: 'subjectTemplate', labelKey: 'alerts.subjectTemplate', placeholderKey: 'alerts.subjectTemplatePlaceholder' },
  ],
  REST: [
    { key: 'baseUrl', labelKey: 'alerts.baseUrl', type: 'url', required: true, placeholderKey: 'alerts.baseUrlPlaceholder' },
    { key: 'path', labelKey: 'common.path', placeholderKey: 'alerts.pathPlaceholder' },
    { key: 'method', labelKey: 'alerts.httpMethod', placeholderKey: 'common.post' },
    { key: 'authToken', labelKey: 'alerts.authToken', type: 'password' },
  ],
  MQTT: [
    { key: 'connectorId', labelKey: 'alerts.mqttConnector', type: 'mqttConnector', required: true },
    { key: 'topic', labelKey: 'common.topic', placeholderKey: 'alerts.topicPlaceholder' },
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
  const [history, setHistory] = useState<AlertHistory[]>([]);
  const [mqttConnectors, setMqttConnectors] = useState<Connector[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [activeTab, setActiveTab] = useState<'channels' | 'history'>('channels');
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

  const tenantId = selectedTenantId || user?.tenantId || tenantContexts[0]?.tenantId;

  /**
   * Implements `showToast` for this source unit.
   */
  const showToast = (message: string, type: 'success' | 'error') => {
    setToast({ message, type });
    // FIXME: Prevent stale toast timers from clearing newer messages
    // Details: Calling showToast twice within 3.5 seconds leaves both timers active, so the first timer can hide the second notification early. Store and clear the previous timeout, and clear it on unmount.
    setTimeout(() => setToast(null), 3500);
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
        const [channelsData, historyData, connectorsData] = await Promise.all([
          api.getAlertChannels(tenantId),
          api.getAlertHistory(tenantId, 20),
          api.getConnectors(tenantId).catch(() => [] as Connector[]),
        ]);
        setChannels(channelsData);
        setHistory(historyData);
        setMqttConnectors(connectorsData.filter((c) => c.connectorType === 'MQTT' && c.direction === 'OUTPUT'));
        setError('');
      } catch (err) {
        console.error('Error fetching alerts:', err);
        setError(t('common.loadingFailed'));
      } finally {
        setLoading(false);
      }
    };

    fetchData();
  }, [tenantId, canReadAlerts, t]);

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
        <div className="mb-4 bg-red-50 border border-red-200 text-red-700 px-4 py-3 rounded-sm">
          {error}
        </div>
      )}

      {!canReadAlerts && (
        <div className="mb-4 rounded-sm border border-amber-200 bg-amber-50 px-4 py-3 text-amber-800">
          {t('alerts.permissionViewAlerts')}
        </div>
      )}

      <div className="mb-4 flex space-x-2">
        {(['channels', 'history'] as const).map((tab) => (
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
          <div className="h-4 bg-gray-200 rounded-sm w-1/3"></div>
        </div>
      ) : !tenantId ? (
        <div className="card text-center py-12">
          <p className="text-gray-500">{t('common.noTenantId')}</p>
        </div>
      ) : (
        <>
          {activeTab === 'channels' && (
            <div className="space-y-4">
              <div className="rounded-sm border border-blue-200 bg-blue-50 px-4 py-3 text-sm text-blue-900">
                <p className="font-semibold mb-1">{t('alerts.howTitle')}</p>
                <ul className="list-disc pl-5 space-y-1">
                  <li>{t('alerts.howRules')}</li>
                  <li>
                    {t('alerts.howModules')}
                    <pre className="bg-blue-100 rounded-sm px-3 py-2 mt-1 text-xs font-mono overflow-x-auto whitespace-pre-wrap">{`alert_fn(severity="critical", title="Boiler too hot",
         message=f"temp = {record['temp']}", channel="<channel id or name>")`}</pre>
                  </li>
                </ul>
                <p className="mt-2 text-blue-800 text-xs">{t('alerts.howChannelHint')}</p>
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
                          <span className="inline-block px-2 py-1 text-xs font-medium rounded-sm bg-gray-100">
                            {channel.channelType}
                          </span>
                          <span className="inline-flex items-center gap-1 px-2 py-1 text-xs rounded-sm bg-slate-100 text-slate-700 font-mono">
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
                      <span className={`px-2 py-1 text-xs rounded-sm ${channel.isActive ? 'bg-green-100 text-green-800' : 'bg-gray-100 text-gray-800'}`}>
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
              <div className="mb-4 rounded-sm border border-slate-200 p-3">
                <label className="block text-sm font-medium text-slate-700 mb-2">{newChannelType} Configuration</label>
                <div className="space-y-3">
                  {(channelConfigSchema[newChannelType] || []).map((field) => (
                    <div key={field.key}>
                      <label className="block text-sm text-slate-600 mb-1">
                        {t(field.labelKey)}{field.required ? ` ${t('common.required')}` : ''}
                      </label>
                      {field.type === 'mqttConnector' ? (
                        <select
                          value={newChannelConfig[field.key] || ''}
                          onChange={(e) => setNewChannelConfig((prev) => ({ ...prev, [field.key]: e.target.value }))}
                          className="input w-full"
                          required={field.required}
                        >
                          <option value="">{t('alerts.selectMqttConnector')}</option>
                          {mqttConnectors.map((c) => (
                            <option key={c.id} value={c.id}>{c.name}</option>
                          ))}
                        </select>
                      ) : (
                        <input
                          type={field.type || 'text'}
                          value={newChannelConfig[field.key] || ''}
                          onChange={(e) => setNewChannelConfig((prev) => ({ ...prev, [field.key]: e.target.value }))}
                          className="input w-full"
                          placeholder={field.placeholderKey ? t(field.placeholderKey) : ''}
                          required={field.required}
                        />
                      )}
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
