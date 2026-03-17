import { useEffect, useState } from 'react';
import { useAuthStore } from '../stores/auth';
import api from '../services/api';
import type { AlertChannel, AlertRule, AlertHistory, Pipeline } from '../types';

export default function Alerts() {
  const { user, selectedTenantId } = useAuthStore();
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
  const [newChannelType, setNewChannelType] = useState('WEBHOOK');
  const [newChannelUrl, setNewChannelUrl] = useState('');
  const [creatingChannel, setCreatingChannel] = useState(false);
  const [newRuleName, setNewRuleName] = useState('');
  const [newRuleCondition, setNewRuleCondition] = useState('');
  const [newRuleChannelId, setNewRuleChannelId] = useState('');
  const [newRulePipelineId, setNewRulePipelineId] = useState('');
  const [creatingRule, setCreatingRule] = useState(false);
  const [confirmDeleteChannelId, setConfirmDeleteChannelId] = useState<string | null>(null);
  const [confirmDeleteRuleId, setConfirmDeleteRuleId] = useState<string | null>(null);
  const [deletingChannelId, setDeletingChannelId] = useState<string | null>(null);
  const [deletingRuleId, setDeletingRuleId] = useState<string | null>(null);
  const [toast, setToast] = useState<{ message: string; type: 'success' | 'error' } | null>(null);

  const tenantId = selectedTenantId || user?.tenantId;

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
      
      try {
        const [channelsData, rulesData, historyData, pipelinesData] = await Promise.all([
          api.getAlertChannels(tenantId),
          api.getAlertRules(tenantId),
          api.getAlertHistory(tenantId, 20),
          api.getPipelines(tenantId),
        ]);
        setChannels(channelsData);
        setRules(rulesData);
        setHistory(historyData);
        setPipelines(pipelinesData);
        setError('');
      } catch (error) {
        console.error('Error fetching alerts:', error);
        setError('Failed to load alerts data');
      } finally {
        setLoading(false);
      }
    };
    
    fetchData();
  }, [tenantId]);

  const handleCreateChannel = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!tenantId || !newChannelName) return;
    
    setCreatingChannel(true);
    try {
      const config = newChannelUrl ? { url: newChannelUrl } : {};
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
      setNewChannelUrl('');
      setError('');
      showToast(editingChannel ? 'Alert channel updated' : 'Alert channel created', 'success');
    } catch (error) {
      console.error('Error creating channel:', error);
      setError('Failed to create channel');
    } finally {
      setCreatingChannel(false);
    }
  };

  const handleCreateRule = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!tenantId || !newRuleName) return;
    
    setCreatingRule(true);
    try {
      const condition = JSON.parse(newRuleCondition || '{}');
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
      showToast(editingRule ? 'Alert rule updated' : 'Alert rule created', 'success');
    } catch (error) {
      console.error('Error creating rule:', error);
      setError('Failed to create rule. Check condition JSON and selected references.');
    } finally {
      setCreatingRule(false);
    }
  };

  const handleDeleteChannel = async (id: string) => {
    setDeletingChannelId(id);
    try {
      await api.deleteAlertChannel(id);
      setChannels(channels.filter((channel) => channel.id !== id));
      setConfirmDeleteChannelId(null);
      setError('');
      showToast('Alert channel deleted', 'success');
    } catch (err) {
      console.error('Error deleting channel:', err);
      setError('Failed to delete channel');
    } finally {
      setDeletingChannelId(null);
    }
  };

  const handleDeleteRule = async (id: string) => {
    setDeletingRuleId(id);
    try {
      await api.deleteAlertRule(id);
      setRules(rules.filter((rule) => rule.id !== id));
      setConfirmDeleteRuleId(null);
      setError('');
      showToast('Alert rule deleted', 'success');
    } catch (err) {
      console.error('Error deleting rule:', err);
      setError('Failed to delete rule');
    } finally {
      setDeletingRuleId(null);
    }
  };

  const openEditChannel = (channel: AlertChannel) => {
    setEditingChannel(channel);
    setNewChannelName(channel.name);
    setNewChannelType(channel.channelType);
    setNewChannelUrl(typeof channel.config?.url === 'string' ? channel.config.url : '');
    setShowChannelModal(true);
  };

  const openEditRule = (rule: AlertRule) => {
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
        <h1 className="text-2xl font-bold text-gray-900">Alerts</h1>
      </div>

      {error && (
        <div className="mb-4 bg-red-50 border border-red-200 text-red-700 px-4 py-3 rounded">
          {error}
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
            {tab.charAt(0).toUpperCase() + tab.slice(1)}
          </button>
        ))}
      </div>

      {loading ? (
        <div className="card animate-pulse">
          <div className="h-4 bg-gray-200 rounded w-1/3"></div>
        </div>
      ) : !tenantId ? (
        <div className="card text-center py-12">
          <p className="text-gray-500">No tenant ID found. Please contact support.</p>
        </div>
      ) : (
        <>
          {activeTab === 'channels' && (
            <div className="space-y-4">
              <div className="flex justify-end">
                <button onClick={() => setShowChannelModal(true)} className="btn-primary text-sm">Add Channel</button>
              </div>
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
                        {channel.isActive ? 'Active' : 'Inactive'}
                      </span>
                    </div>
                    <div className="mt-4 flex justify-end">
                      <button
                        onClick={() => openEditChannel(channel)}
                        className="mr-3 text-sm text-blue-600 hover:text-blue-800"
                      >
                        Edit
                      </button>
                      {confirmDeleteChannelId === channel.id ? (
                        <div className="flex items-center space-x-2">
                          <span className="text-xs text-gray-500">Delete?</span>
                          <button
                            onClick={() => handleDeleteChannel(channel.id)}
                            disabled={deletingChannelId === channel.id}
                            className="text-sm text-red-600 hover:text-red-800"
                          >
                            {deletingChannelId === channel.id ? 'Deleting...' : 'Yes'}
                          </button>
                          <button
                            onClick={() => setConfirmDeleteChannelId(null)}
                            className="text-sm text-gray-500 hover:text-gray-700"
                          >
                            No
                          </button>
                        </div>
                      ) : (
                        <button
                          onClick={() => setConfirmDeleteChannelId(channel.id)}
                          className="text-sm text-red-600 hover:text-red-800"
                        >
                          Delete
                        </button>
                      )}
                    </div>
                  </div>
                ))
              ) : (
                <div className="card text-center py-8">
                  <p className="text-gray-500">No alert channels configured</p>
                </div>
              )}
            </div>
          )}

          {activeTab === 'rules' && (
            <div className="space-y-4">
              <div className="flex justify-end">
                <button onClick={() => setShowRuleModal(true)} className="btn-primary text-sm">Add Rule</button>
              </div>
              {rules.length > 0 ? (
                rules.map((rule) => (
                  <div key={rule.id} className="card">
                    <div className="flex justify-between items-start">
                      <div>
                        <h3 className="text-lg font-semibold">{rule.name}</h3>
                        <p className="text-sm text-gray-500">{rule.description}</p>
                      </div>
                        <span className={`px-2 py-1 text-xs rounded ${rule.enabled ? 'bg-green-100 text-green-800' : 'bg-gray-100 text-gray-800'}`}>
                          {rule.enabled ? 'Enabled' : 'Disabled'}
                        </span>
                      </div>
                      <div className="mt-4 flex justify-end">
                        <button
                          onClick={() => openEditRule(rule)}
                          className="mr-3 text-sm text-blue-600 hover:text-blue-800"
                        >
                          Edit
                        </button>
                        {confirmDeleteRuleId === rule.id ? (
                          <div className="flex items-center space-x-2">
                            <span className="text-xs text-gray-500">Delete?</span>
                            <button
                              onClick={() => handleDeleteRule(rule.id)}
                              disabled={deletingRuleId === rule.id}
                              className="text-sm text-red-600 hover:text-red-800"
                            >
                              {deletingRuleId === rule.id ? 'Deleting...' : 'Yes'}
                            </button>
                            <button
                              onClick={() => setConfirmDeleteRuleId(null)}
                              className="text-sm text-gray-500 hover:text-gray-700"
                            >
                              No
                            </button>
                          </div>
                        ) : (
                          <button
                            onClick={() => setConfirmDeleteRuleId(rule.id)}
                            className="text-sm text-red-600 hover:text-red-800"
                          >
                            Delete
                          </button>
                        )}
                      </div>
                    </div>
                  ))
              ) : (
                <div className="card text-center py-8">
                  <p className="text-gray-500">No alert rules configured</p>
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
                  <p className="text-gray-500">No alert history</p>
                </div>
              )}
            </div>
          )}
        </>
      )}

      {showChannelModal && (
        <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
          <div className="bg-white rounded-lg p-6 max-w-md w-full">
            <h2 className="text-xl font-bold mb-4">{editingChannel ? 'Edit Alert Channel' : 'Add Alert Channel'}</h2>
            <form onSubmit={handleCreateChannel}>
              <div className="mb-4">
                <label className="block text-sm font-medium text-gray-700 mb-1">Name</label>
                <input
                  type="text"
                  value={newChannelName}
                  onChange={(e) => setNewChannelName(e.target.value)}
                  className="input w-full"
                  required
                />
              </div>
              <div className="mb-4">
                <label className="block text-sm font-medium text-gray-700 mb-1">Type</label>
                <select
                  value={newChannelType}
                  onChange={(e) => setNewChannelType(e.target.value)}
                  className="input w-full"
                >
                  <option value="WEBHOOK">Webhook</option>
                  <option value="SLACK">Slack</option>
                  <option value="EMAIL">Email</option>
                  <option value="REST">REST</option>
                  <option value="MQTT">MQTT</option>
                </select>
              </div>
              <div className="mb-4">
                <label className="block text-sm font-medium text-gray-700 mb-1">URL</label>
                <input
                  type="url"
                  value={newChannelUrl}
                  onChange={(e) => setNewChannelUrl(e.target.value)}
                  className="input w-full"
                  placeholder="https://..."
                />
              </div>
              <div className="flex justify-end space-x-2">
                <button
                  type="button"
                  onClick={() => {
                    setShowChannelModal(false);
                    setEditingChannel(null);
                  }}
                  className="px-4 py-2 border border-gray-300 rounded-md text-gray-700 hover:bg-gray-50"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  disabled={creatingChannel}
                  className="btn-primary"
                >
                  {creatingChannel ? 'Saving...' : editingChannel ? 'Save' : 'Create'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {showRuleModal && (
        <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
          <div className="bg-white rounded-lg p-6 max-w-md w-full">
            <h2 className="text-xl font-bold mb-4">{editingRule ? 'Edit Alert Rule' : 'Add Alert Rule'}</h2>
            <form onSubmit={handleCreateRule}>
              <div className="mb-4">
                <label className="block text-sm font-medium text-gray-700 mb-1">Name</label>
                <input
                  type="text"
                  value={newRuleName}
                  onChange={(e) => setNewRuleName(e.target.value)}
                  className="input w-full"
                  required
                />
              </div>
              <div className="mb-4">
                <label className="block text-sm font-medium text-gray-700 mb-1">Channel</label>
                <select
                  value={newRuleChannelId}
                  onChange={(e) => setNewRuleChannelId(e.target.value)}
                  className="input w-full"
                >
                  <option value="">Select channel</option>
                  {channels.map((channel) => (
                    <option key={channel.id} value={channel.id}>{channel.name}</option>
                  ))}
                </select>
              </div>
              <div className="mb-4">
                <label className="block text-sm font-medium text-gray-700 mb-1">Pipeline (Optional)</label>
                <select
                  value={newRulePipelineId}
                  onChange={(e) => setNewRulePipelineId(e.target.value)}
                  className="input w-full"
                >
                  <option value="">Any pipeline</option>
                  {pipelines.map((pipeline) => (
                    <option key={pipeline.id} value={pipeline.id}>{pipeline.name}</option>
                  ))}
                </select>
              </div>
              <div className="mb-4">
                <label className="block text-sm font-medium text-gray-700 mb-1">Condition (JSON)</label>
                <textarea
                  value={newRuleCondition}
                  onChange={(e) => setNewRuleCondition(e.target.value)}
                  className="input w-full"
                  placeholder='{"field": "temperature", "operator": ">", "value": 100}'
                  rows={3}
                />
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
                  Cancel
                </button>
                <button
                  type="submit"
                  disabled={creatingRule}
                  className="btn-primary"
                >
                  {creatingRule ? 'Saving...' : editingRule ? 'Save' : 'Create'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}
