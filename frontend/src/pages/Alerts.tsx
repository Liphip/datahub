import { useEffect, useState } from 'react';
import { useAuthStore } from '../stores/auth';
import api from '../services/api';
import type { AlertChannel, AlertRule, AlertHistory } from '../types';

export default function Alerts() {
  const { user } = useAuthStore();
  const [channels, setChannels] = useState<AlertChannel[]>([]);
  const [rules, setRules] = useState<AlertRule[]>([]);
  const [history, setHistory] = useState<AlertHistory[]>([]);
  const [loading, setLoading] = useState(true);
  const [activeTab, setActiveTab] = useState<'channels' | 'rules' | 'history'>('channels');
  const [showChannelModal, setShowChannelModal] = useState(false);
  const [showRuleModal, setShowRuleModal] = useState(false);
  const [newChannelName, setNewChannelName] = useState('');
  const [newChannelType, setNewChannelType] = useState('WEBHOOK');
  const [newChannelUrl, setNewChannelUrl] = useState('');
  const [creatingChannel, setCreatingChannel] = useState(false);

  const tenantId = user?.tenantId || user?.id;

  useEffect(() => {
    const fetchData = async () => {
      if (!tenantId) {
        setLoading(false);
        return;
      }
      
      try {
        const [channelsData, rulesData, historyData] = await Promise.all([
          api.getAlertChannels(tenantId),
          api.getAlertRules(tenantId),
          api.getAlertHistory(tenantId, 20),
        ]);
        setChannels(channelsData);
        setRules(rulesData);
        setHistory(historyData);
      } catch (error) {
        console.error('Error fetching alerts:', error);
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
      const newChannel = await api.createAlertChannel(tenantId, newChannelName, newChannelType, { url: newChannelUrl });
      setChannels([...channels, newChannel]);
      setShowChannelModal(false);
      setNewChannelName('');
      setNewChannelType('WEBHOOK');
      setNewChannelUrl('');
    } catch (error) {
      console.error('Error creating channel:', error);
    } finally {
      setCreatingChannel(false);
    }
  };

  return (
    <div className="px-4 py-6 sm:px-0">
      <div className="flex justify-between items-center mb-6">
        <h1 className="text-2xl font-bold text-gray-900">Alerts</h1>
      </div>

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
            <h2 className="text-xl font-bold mb-4">Add Alert Channel</h2>
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
                  onClick={() => setShowChannelModal(false)}
                  className="px-4 py-2 border border-gray-300 rounded-md text-gray-700 hover:bg-gray-50"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  disabled={creatingChannel}
                  className="btn-primary"
                >
                  {creatingChannel ? 'Creating...' : 'Create'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}
