import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuthStore } from '../stores/auth';
import api from '../services/api';
import type { Connector, BrokerCredential, ConnectorImpactSummary, ConnectorReferenceSummary } from '../types';

const connectorTypes = ['MQTT', 'REST', 'WEBSOCKET', 'UDP', 'FILE', 'CUSTOM'] as const;
const directions = ['INPUT', 'OUTPUT', 'BOTH'] as const;
type ConnectorType = typeof connectorTypes[number];

type ConnectorConfigField = {
  key: string;
  label: string;
  type?: 'text' | 'url' | 'number' | 'password';
  placeholder?: string;
  required?: boolean;
  hint?: string;
};

const connectorConfigSchema: Record<ConnectorType, ConnectorConfigField[]> = {
  MQTT: [
    { key: 'broker', label: 'Broker URL', type: 'url', required: true, placeholder: 'mqtt://broker:1883' },
    { key: 'topic', label: 'Topic', required: true, placeholder: 'sensors/+/telemetry' },
    { key: 'clientId', label: 'Client ID', placeholder: 'dt-edge-ingest' },
    { key: 'username', label: 'Username' },
    { key: 'password', label: 'Password', type: 'password' },
  ],
  REST: [
    { key: 'baseUrl', label: 'Base URL', type: 'url', required: true, placeholder: 'https://api.example.com' },
    { key: 'path', label: 'Path', placeholder: '/ingest' },
    { key: 'method', label: 'Method', placeholder: 'POST' },
    { key: 'authToken', label: 'Auth Token', type: 'password' },
  ],
  WEBSOCKET: [
    { key: 'url', label: 'WebSocket URL', type: 'url', required: true, placeholder: 'wss://stream.example.com/events' },
    { key: 'path', label: 'Path', placeholder: '/socket' },
    { key: 'authToken', label: 'Auth Token', type: 'password' },
  ],
  UDP: [
    { key: 'host', label: 'Host', required: true, placeholder: 'udp-gateway.internal' },
    { key: 'port', label: 'Port', type: 'number', required: true, placeholder: '5140' },
  ],
  FILE: [
    { key: 'path', label: 'File Path', required: true, placeholder: '/var/data/input.ndjson' },
    { key: 'format', label: 'Format', placeholder: 'jsonl' },
  ],
  CUSTOM: [
    { key: 'handler', label: 'Handler ID', placeholder: 'custom.transform.v1' },
    { key: 'endpoint', label: 'Endpoint', placeholder: 'plugin://transformers/default' },
  ],
};

const initialConfigByType = (type: ConnectorType, existing?: Record<string, unknown>) => {
  const fields = connectorConfigSchema[type] || [];
  return fields.reduce<Record<string, string>>((acc, field) => {
    const value = existing?.[field.key];
    acc[field.key] = value == null ? '' : String(value);
    return acc;
  }, {});
};

export default function Connectors() {
  const navigate = useNavigate();
  const { user, selectedTenantId, tenantContexts } = useAuthStore();
  const abilities = user?.abilities || [];
  const hasAbility = (ability: string) => Boolean(user?.superadmin || abilities.includes('*') || abilities.includes(ability));
  const canManageTenant = hasAbility('manage:tenant');
  const canReadConnectors = canManageTenant || hasAbility('read:connector') || hasAbility('write:connector') || hasAbility('delete:connector');
  const canWriteConnectors = canManageTenant || hasAbility('write:connector');
  const canDeleteConnectors = canManageTenant || hasAbility('delete:connector');
  const canManageBrokerUsers = canManageTenant;
  const canReadBrokerUsers = canReadConnectors;
  const [connectors, setConnectors] = useState<Connector[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [showModal, setShowModal] = useState(false);
  const [editingConnector, setEditingConnector] = useState<Connector | null>(null);
  const [savingConnector, setSavingConnector] = useState(false);
  const [deletingId, setDeletingId] = useState<string | null>(null);
  const [togglingId, setTogglingId] = useState<string | null>(null);
  const [testingId, setTestingId] = useState<string | null>(null);
  const [cloningId, setCloningId] = useState<string | null>(null);
  const [cloneSource, setCloneSource] = useState<Connector | null>(null);
  const [showCloneModal, setShowCloneModal] = useState(false);
  const [cloneForm, setCloneForm] = useState({
    name: '',
    direction: 'INPUT' as Connector['direction'],
    description: '',
    keepSecrets: false,
  });
  const [confirmDeleteId, setConfirmDeleteId] = useState<string | null>(null);
  const [filterType, setFilterType] = useState<string>('ALL');
  const [filterDirection, setFilterDirection] = useState<string>('ALL');
  const [filterActive, setFilterActive] = useState<'ALL' | 'ACTIVE' | 'INACTIVE'>('ALL');
  const [filterQuery, setFilterQuery] = useState('');
  const [toast, setToast] = useState<{ message: string; type: 'success' | 'error' } | null>(null);
  const [formData, setFormData] = useState({
    name: '',
    connectorType: 'MQTT' as ConnectorType,
    direction: 'INPUT' as Connector['direction'],
    config: initialConfigByType('MQTT'),
    description: ''
  });
  const [mqttDefaults, setMqttDefaults] = useState<{
    internalBrokerUrl: string;
    externalBrokerUrl: string;
    websocketUrl: string;
    defaultTopic: string;
    integratedBrokerLabel: string;
  } | null>(null);
  const [brokerCredentials, setBrokerCredentials] = useState<BrokerCredential[]>([]);
  const [showBrokerModal, setShowBrokerModal] = useState(false);
  const [editingBrokerCredential, setEditingBrokerCredential] = useState<BrokerCredential | null>(null);
  const [savingBrokerCredential, setSavingBrokerCredential] = useState(false);
  const [deletingBrokerCredentialId, setDeletingBrokerCredentialId] = useState<string | null>(null);
  const [rotatingBrokerCredentialId, setRotatingBrokerCredentialId] = useState<string | null>(null);
  const [revealedBrokerPassword, setRevealedBrokerPassword] = useState<string>('');
  const [usageModal, setUsageModal] = useState<ConnectorReferenceSummary | null>(null);
  const [loadingUsageId, setLoadingUsageId] = useState<string | null>(null);
  const [impactModal, setImpactModal] = useState<ConnectorImpactSummary | null>(null);
  const [loadingImpactId, setLoadingImpactId] = useState<string | null>(null);
  const [impactWindowHours, setImpactWindowHours] = useState<1 | 24 | 168>(24);
  const [disconnectImpactModal, setDisconnectImpactModal] = useState<ConnectorImpactSummary | null>(null);
  const [pendingDisconnectConnector, setPendingDisconnectConnector] = useState<Connector | null>(null);
  const [brokerForm, setBrokerForm] = useState({
    name: '',
    username: '',
    password: '',
    allowedTopicsText: 'dataturntable/events/#',
    isActive: true,
  });

  const tenantId = selectedTenantId || user?.tenantId || tenantContexts[0]?.tenantId;

  const showToast = (message: string, type: 'success' | 'error') => {
    setToast({ message, type });
    setTimeout(() => setToast(null), 3500);
  };

  const loadConnectors = async (tid: string) => {
    const type = filterType === 'ALL' ? undefined : filterType;
    const direction = filterDirection === 'ALL' ? undefined : filterDirection;
    const isActive = filterActive === 'ALL' ? undefined : filterActive === 'ACTIVE';
    const data = await api.getConnectors(tid, type, direction, {
      query: filterQuery,
      isActive,
    });
    setConnectors(data);
  };

  const loadBrokerCredentials = async (tid: string) => {
    if (!canReadBrokerUsers) {
      setBrokerCredentials([]);
      return;
    }
    const data = await api.getMqttBrokerCredentials(tid);
    setBrokerCredentials(data);
  };

  useEffect(() => {
    const fetchConnectors = async () => {
      if (!tenantId) {
        setLoading(false);
        return;
      }
      if (!canReadConnectors) {
        setConnectors([]);
        setError('');
        setLoading(false);
        return;
      }

      try {
        await loadConnectors(tenantId);
        const defaults = await api.getMqttConnectorDefaults().catch(() => null);
        setMqttDefaults(defaults);
        await loadBrokerCredentials(tenantId);
        setError('');
      } catch (err) {
        console.error('Error fetching connectors:', err);
        setError('Failed to load connectors');
      } finally {
        setLoading(false);
      }
    };

    fetchConnectors();
  }, [tenantId, filterType, filterDirection, filterActive, filterQuery, canReadConnectors, canManageBrokerUsers, canReadBrokerUsers]);

  const openCreateBrokerCredential = () => {
    setEditingBrokerCredential(null);
    setBrokerForm({
      name: '',
      username: '',
      password: '',
      allowedTopicsText: mqttDefaults?.defaultTopic ? `${mqttDefaults.defaultTopic}/#` : 'dataturntable/events/#',
      isActive: true,
    });
    setShowBrokerModal(true);
  };

  const openEditBrokerCredential = (credential: BrokerCredential) => {
    setEditingBrokerCredential(credential);
    setBrokerForm({
      name: credential.name,
      username: credential.username,
      password: '',
      allowedTopicsText: credential.allowedTopics.join('\n'),
      isActive: credential.isActive,
    });
    setShowBrokerModal(true);
  };

  const handleSaveBrokerCredential = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!tenantId || !canManageBrokerUsers) return;
    const allowedTopics = brokerForm.allowedTopicsText
      .split(/\n|,/)
      .map((topic) => topic.trim())
      .filter(Boolean);
    if (allowedTopics.length === 0) {
      setError('At least one allowed topic is required for MQTT broker credentials');
      return;
    }

    setSavingBrokerCredential(true);
    try {
      if (editingBrokerCredential) {
        await api.updateMqttBrokerCredential(editingBrokerCredential.id, {
          name: brokerForm.name,
          allowedTopics,
          isActive: brokerForm.isActive,
        });
        if (brokerForm.password.trim()) {
          const rotated = await api.rotateMqttBrokerCredentialPassword(editingBrokerCredential.id, brokerForm.password.trim());
          setRevealedBrokerPassword(rotated.password || '');
        }
      } else {
        const created = await api.createMqttBrokerCredential(tenantId, {
          name: brokerForm.name,
          username: brokerForm.username.trim() || undefined,
          password: brokerForm.password.trim() || undefined,
          allowedTopics,
          isActive: brokerForm.isActive,
        });
        setRevealedBrokerPassword(created.password || '');
      }

      await loadBrokerCredentials(tenantId);
      await loadConnectors(tenantId);
      setShowBrokerModal(false);
      setEditingBrokerCredential(null);
      showToast(editingBrokerCredential ? 'Broker credential updated' : 'Broker credential created', 'success');
    } catch (err: any) {
      setError(err?.response?.data?.error || 'Failed to save broker credential');
    } finally {
      setSavingBrokerCredential(false);
    }
  };

  const handleDeleteBrokerCredential = async (id: string) => {
    if (!tenantId) return;
    const activeReferences = brokerCredentials.find((credential) => credential.id === id)?.referenceCount || 0;
    if (activeReferences > 0) {
      setError('Cannot delete this broker credential while MQTT connectors still reference it');
      return;
    }
    setDeletingBrokerCredentialId(id);
    try {
      await api.deleteMqttBrokerCredential(id);
      await loadBrokerCredentials(tenantId);
      await loadConnectors(tenantId);
      showToast('Broker credential deleted', 'success');
    } catch (err: any) {
      setError(err?.response?.data?.error || 'Failed to delete broker credential');
    } finally {
      setDeletingBrokerCredentialId(null);
    }
  };

  const handleRotateBrokerCredentialPassword = async (credential: BrokerCredential) => {
    setRotatingBrokerCredentialId(credential.id);
    try {
      const rotated = await api.rotateMqttBrokerCredentialPassword(credential.id);
      setRevealedBrokerPassword(rotated.password || '');
      if (tenantId) await loadBrokerCredentials(tenantId);
      showToast('Broker credential password rotated', 'success');
    } catch (err: any) {
      setError(err?.response?.data?.error || 'Failed to rotate broker credential password');
    } finally {
      setRotatingBrokerCredentialId(null);
    }
  };

  const applyMqttPreset = (mode: 'internal' | 'external') => {
    const broker = mode === 'internal'
      ? (mqttDefaults?.internalBrokerUrl || 'mqtt://mosquitto:1883')
      : (mqttDefaults?.externalBrokerUrl || 'mqtt://localhost:1883');
    const topicBase = mqttDefaults?.defaultTopic || 'dataturntable/events';
    const topicSuffix = formData.direction === 'OUTPUT' ? '/out' : formData.direction === 'BOTH' ? '/bi' : '/in';
    setFormData((prev) => ({
      ...prev,
      config: {
        ...prev.config,
        broker,
        topic: prev.config.topic?.trim() ? prev.config.topic : `${topicBase}${topicSuffix}`,
      },
    }));
  };

  const buildConnectorConfig = (): Record<string, unknown> => {
    const config = Object.entries(formData.config).reduce<Record<string, unknown>>((acc, [key, value]) => {
      const trimmed = value.trim();
      if (!trimmed) return acc;
      if (key === 'port') {
        const parsed = Number(trimmed);
        acc[key] = Number.isFinite(parsed) ? parsed : trimmed;
      } else {
        acc[key] = trimmed;
      }
      return acc;
    }, {});

    if (formData.connectorType === 'MQTT' && typeof config.brokerCredentialId === 'string' && String(config.brokerCredentialId).trim()) {
      delete config.username;
      delete config.password;
    }
    return config;
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!canWriteConnectors) {
      showToast('You do not have permission to manage connectors', 'error');
      return;
    }
    if (!tenantId) return;

    try {
      setSavingConnector(true);

      if (editingConnector) {
        const requiredMissing = connectorConfigSchema[formData.connectorType]
          .filter((field) => field.required && !formData.config[field.key]?.trim())
          .map((field) => field.label);
        if (requiredMissing.length > 0) {
          setError(`Missing required config fields: ${requiredMissing.join(', ')}`);
          return;
        }

        const parsedConfig = buildConnectorConfig();

        const updatePayload: Partial<Connector> = {
          name: formData.name,
          connectorType: formData.connectorType as Connector['connectorType'],
          direction: formData.direction as Connector['direction'],
          description: formData.description,
          config: parsedConfig,
        };

        await api.updateConnector(editingConnector.id, updatePayload);
      } else {
        const requiredMissing = connectorConfigSchema[formData.connectorType]
          .filter((field) => field.required && !formData.config[field.key]?.trim())
          .map((field) => field.label);
        if (requiredMissing.length > 0) {
          setError(`Missing required config fields: ${requiredMissing.join(', ')}`);
          return;
        }

        const config = buildConnectorConfig();

        await api.createConnector(
          tenantId,
          formData.name,
          formData.connectorType,
          formData.direction,
          config,
          formData.description
        );
      }

      setShowModal(false);
      setEditingConnector(null);
      setFormData({
        name: '',
        connectorType: 'MQTT',
        direction: 'INPUT',
        config: initialConfigByType('MQTT'),
        description: ''
      });

      await loadConnectors(tenantId);
      await loadBrokerCredentials(tenantId);
      setError('');
      showToast(editingConnector ? 'Connector updated' : 'Connector created', 'success');
    } catch (err) {
      console.error('Error saving connector:', err);
      setError(editingConnector ? 'Failed to update connector' : 'Failed to create connector');
    } finally {
      setSavingConnector(false);
    }
  };

  const handleDelete = async (id: string) => {
    if (!canDeleteConnectors) {
      showToast('You do not have permission to delete connectors', 'error');
      return;
    }
    const referenceCount = connectors.find((connector) => connector.id === id)?.pipelineReferenceCount || 0;
    if (referenceCount > 0) {
      setError(`Connector is referenced by ${referenceCount} pipeline node(s). Remove those references before deleting.`);
      return;
    }
    setDeletingId(id);
    try {
      await api.deleteConnector(id);
      if (tenantId) {
        await loadConnectors(tenantId);
        await loadBrokerCredentials(tenantId);
      }
      setConfirmDeleteId(null);
      setError('');
      showToast('Connector deleted', 'success');
    } catch (err: any) {
      console.error('Error deleting connector:', err);
      setError(err?.response?.data?.error || 'Failed to delete connector');
    } finally {
      setDeletingId(null);
    }
  };

  const performToggleConnection = async (connector: Connector) => {
    setTogglingId(connector.id);
    try {
      const updated = connector.isActive
        ? await api.disconnectConnector(connector.id)
        : await api.connectConnector(connector.id);
      setConnectors((prev) => prev.map((c) => (c.id === connector.id ? { ...c, ...updated } : c)));
      showToast(connector.isActive ? 'Connector disconnected' : 'Connector connected', 'success');
    } catch {
      showToast(connector.isActive ? 'Failed to disconnect connector' : 'Failed to connect connector', 'error');
    } finally {
      setTogglingId(null);
    }
  };

  const handleToggleConnection = async (connector: Connector) => {
    if (!canWriteConnectors) {
      showToast('You do not have permission to change connector state', 'error');
      return;
    }

    if (connector.isActive) {
      setTogglingId(connector.id);
      try {
        const impact = await api.getConnectorImpact(connector.id, impactWindowHours, { activeOnly: true });
        if ((impact.activeImpactedPipelineCount || 0) > 0) {
          setDisconnectImpactModal(impact);
          setPendingDisconnectConnector(connector);
          return;
        }
      } catch {
        // if impact lookup fails, allow direct action and rely on backend safety
      } finally {
        setTogglingId(null);
      }
    }

    await performToggleConnection(connector);
  };

  const handleTestConnection = async (connector: Connector) => {
    setTestingId(connector.id);
    try {
      const result = await api.testConnector(connector.id);
      showToast(result.message || 'Connector test succeeded', 'success');
    } catch (err: any) {
      const missing = err?.response?.data?.missing;
      if (Array.isArray(missing) && missing.length > 0) {
        showToast(`Missing config: ${missing.join(', ')}`, 'error');
      } else {
        showToast(err?.response?.data?.error || 'Connector test failed', 'error');
      }
    } finally {
      setTestingId(null);
    }
  };

  const openCloneModal = (connector: Connector) => {
    setCloneSource(connector);
    setCloneForm({
      name: `${connector.name} Copy`,
      direction: connector.direction,
      description: connector.description || '',
      keepSecrets: false,
    });
    setShowCloneModal(true);
  };

  const handleCloneConnector = async () => {
    const connector = cloneSource;
    if (!connector) return;
    if (!canWriteConnectors) {
      showToast('You do not have permission to clone connectors', 'error');
      return;
    }
    setCloningId(connector.id);
    try {
      const cloned = await api.cloneConnector(
        connector.id,
        {
          name: cloneForm.name.trim() || `${connector.name} Copy`,
          direction: cloneForm.direction,
          description: cloneForm.description,
          keepSecrets: cloneForm.keepSecrets,
        }
      );
      if (tenantId) await loadConnectors(tenantId);
      showToast(cloned.message || 'Connector cloned as disconnected copy', 'success');
      setShowCloneModal(false);
      setCloneSource(null);
    } catch (err: any) {
      setError(err?.response?.data?.error || 'Failed to clone connector');
    } finally {
      setCloningId(null);
    }
  };

  const handleViewUsage = async (connector: Connector) => {
    setLoadingUsageId(connector.id);
    try {
      const usage = await api.getConnectorReferences(connector.id);
      setUsageModal(usage);
    } catch (err: any) {
      setError(err?.response?.data?.error || 'Failed to load connector usage');
    } finally {
      setLoadingUsageId(null);
    }
  };

  const handleViewImpact = async (connector: Connector, sinceHours: 1 | 24 | 168 = impactWindowHours) => {
    setLoadingImpactId(connector.id);
    try {
      const impact = await api.getConnectorImpact(connector.id, sinceHours);
      setImpactWindowHours(sinceHours);
      setImpactModal(impact);
    } catch (err: any) {
      setError(err?.response?.data?.error || 'Failed to load connector impact');
    } finally {
      setLoadingImpactId(null);
    }
  };

  const handleEdit = (connector: Connector) => {
    if (!canWriteConnectors) return;
    setEditingConnector(connector);
    setFormData({
      name: connector.name,
      connectorType: connector.connectorType,
      direction: connector.direction,
      config: initialConfigByType(connector.connectorType, connector.config),
      description: connector.description || '',
    });
    setShowModal(true);
  };

  const handleOpenCreate = () => {
    if (!canWriteConnectors) return;
    setEditingConnector(null);
    setFormData({
      name: '',
      connectorType: 'MQTT',
      direction: 'INPUT',
      config: initialConfigByType('MQTT'),
      description: '',
    });
    setShowModal(true);
  };

  const getTypeColor = (type: string) => {
    switch (type) {
      case 'MQTT': return 'bg-blue-100 text-blue-800';
      case 'REST': return 'bg-green-100 text-green-800';
      case 'WEBSOCKET': return 'bg-purple-100 text-purple-800';
      case 'UDP': return 'bg-orange-100 text-orange-800';
      case 'FILE': return 'bg-gray-100 text-gray-800';
      default: return 'bg-yellow-100 text-yellow-800';
    }
  };

  const getDirectionColor = (direction: string) => {
    switch (direction) {
      case 'INPUT': return 'bg-green-100 text-green-800';
      case 'OUTPUT': return 'bg-red-100 text-red-800';
      case 'BOTH': return 'bg-purple-100 text-purple-800';
      default: return 'bg-gray-100 text-gray-800';
    }
  };

  if (loading) {
    return (
      <div className="px-4 py-6 sm:px-0">
        <div className="animate-pulse space-y-4">
          <div className="h-8 bg-gray-200 rounded w-1/4"></div>
          <div className="h-32 bg-gray-200 rounded"></div>
        </div>
      </div>
    );
  }

  if (!canReadConnectors) {
    return (
      <div className="px-4 py-6 sm:px-0">
        <div className="card text-center py-12">
          <p className="text-gray-500">You do not have permission to view connectors for this tenant.</p>
        </div>
      </div>
    );
  }

  return (
    <div className="px-4 py-6 sm:px-0">
      {toast && (
        <div className={`fixed top-4 right-4 z-50 px-4 py-3 rounded shadow-lg text-white text-sm ${
          toast.type === 'success' ? 'bg-green-600' : 'bg-red-600'
        }`}>
          {toast.message}
        </div>
      )}

      {revealedBrokerPassword && (
        <div className="mb-4 rounded border border-amber-300 bg-amber-50 px-4 py-3 text-amber-900">
          <div className="flex items-center justify-between gap-3">
            <div>
              <div className="font-semibold text-sm">MQTT broker password (shown once)</div>
              <code className="text-xs break-all">{revealedBrokerPassword}</code>
            </div>
            <button
              onClick={() => setRevealedBrokerPassword('')}
              className="text-xs underline"
            >
              Hide
            </button>
          </div>
        </div>
      )}

      <div className="mb-6 flex justify-between items-center">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">Connectors</h1>
          <p className="mt-1 text-sm text-gray-600">
            Manage protocol interface definitions (MQTT, REST, WebSocket, UDP, etc.)
          </p>
        </div>
        {canWriteConnectors && (
          <button
            onClick={handleOpenCreate}
            className="btn-primary"
          >
            Create Connector
          </button>
        )}
      </div>

      <div className="card mb-4">
        <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">Type Filter</label>
            <select value={filterType} onChange={(e) => setFilterType(e.target.value)} className="input">
              <option value="ALL">All types</option>
              {connectorTypes.map((type) => (
                <option key={type} value={type}>{type}</option>
              ))}
            </select>
          </div>
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">Direction Filter</label>
            <select value={filterDirection} onChange={(e) => setFilterDirection(e.target.value)} className="input">
              <option value="ALL">All directions</option>
              {directions.map((direction) => (
                <option key={direction} value={direction}>{direction}</option>
              ))}
            </select>
          </div>
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">Status Filter</label>
            <select value={filterActive} onChange={(e) => setFilterActive(e.target.value as 'ALL' | 'ACTIVE' | 'INACTIVE')} className="input">
              <option value="ALL">All statuses</option>
              <option value="ACTIVE">Active</option>
              <option value="INACTIVE">Inactive</option>
            </select>
          </div>
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">Search</label>
            <input
              value={filterQuery}
              onChange={(e) => setFilterQuery(e.target.value)}
              className="input"
              placeholder="Name or description"
            />
          </div>
        </div>
      </div>

      {error && (
        <div className="mb-4 bg-red-50 border border-red-200 text-red-700 px-4 py-3 rounded">
          {error}
        </div>
      )}

      {connectors.length === 0 ? (
        <div className="card text-center py-12">
          <svg className="mx-auto h-12 w-12 text-gray-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13.828 10.172a4 4 0 00-5.656 0l-4 4a4 4 0 105.656 5.656l1.102-1.101m-.758-4.899a4 4 0 005.656 0l4-4a4 4 0 00-5.656-5.656l-1.1 1.1" />
          </svg>
          <h3 className="mt-2 text-sm font-medium text-gray-900">No connectors</h3>
          <p className="mt-1 text-sm text-gray-500">Get started by creating your first connector.</p>
          {canWriteConnectors && (
            <div className="mt-6">
              <button
                onClick={handleOpenCreate}
                className="btn-primary"
              >
                Create Connector
              </button>
            </div>
          )}
        </div>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
          {connectors.map((connector) => (
            <div key={connector.id} className="card">
              <div className="flex justify-between items-start mb-2">
                <h3 className="text-lg font-semibold text-gray-900">{connector.name}</h3>
                <div className="flex space-x-1">
                  <span className={`px-2 py-1 text-xs font-medium rounded ${getTypeColor(connector.connectorType)}`}>
                    {connector.connectorType}
                  </span>
                  <span className={`px-2 py-1 text-xs font-medium rounded ${getDirectionColor(connector.direction)}`}>
                    {connector.direction}
                  </span>
                  <span className={`px-2 py-1 text-xs font-medium rounded ${connector.isActive ? 'bg-emerald-100 text-emerald-800' : 'bg-gray-100 text-gray-700'}`}>
                    {connector.isActive ? 'Connected' : 'Disconnected'}
                  </span>
                </div>
              </div>
              
              {connector.description && (
                <p className="text-sm text-gray-600 mb-3">{connector.description}</p>
              )}

              {connector.connectorType === 'MQTT' && typeof connector.config?.brokerCredentialId === 'string' && connector.config.brokerCredentialId && (
                <p className="text-xs text-blue-700 mb-2">
                  Broker User ID: <code>{connector.config.brokerCredentialId}</code>
                </p>
              )}
              
              <div className="text-xs text-gray-500 mb-3">
                Created: {new Date(connector.createdAt).toLocaleDateString()}
                <div>
                  Used in {connector.pipelineReferenceCount || 0} pipeline node(s)
                </div>
              </div>
              
              <div className="flex justify-end space-x-2">
                <button
                  onClick={() => handleTestConnection(connector)}
                  disabled={testingId === connector.id}
                  className="text-indigo-600 hover:text-indigo-800 text-sm disabled:opacity-40"
                >
                  {testingId === connector.id ? 'Testing...' : 'Test'}
                </button>
                <button
                  onClick={() => handleViewUsage(connector)}
                  disabled={loadingUsageId === connector.id}
                  className="text-slate-700 hover:text-slate-900 text-sm disabled:opacity-40"
                >
                  {loadingUsageId === connector.id ? 'Loading...' : 'Usage'}
                </button>
                <button
                  onClick={() => handleViewImpact(connector)}
                  disabled={loadingImpactId === connector.id}
                  className="text-amber-700 hover:text-amber-900 text-sm disabled:opacity-40"
                >
                  {loadingImpactId === connector.id ? 'Loading...' : 'Impact'}
                </button>
                {canWriteConnectors && (
                  <button
                    onClick={() => handleToggleConnection(connector)}
                    disabled={togglingId === connector.id}
                    className="text-emerald-700 hover:text-emerald-900 text-sm disabled:opacity-40"
                  >
                    {togglingId === connector.id ? 'Working...' : connector.isActive ? 'Disconnect' : 'Connect'}
                  </button>
                )}
                {canWriteConnectors && (
                  <button
                    onClick={() => openCloneModal(connector)}
                    disabled={cloningId === connector.id}
                    title="Clone as disconnected copy (sensitive config removed)"
                    className="text-violet-700 hover:text-violet-900 text-sm disabled:opacity-40"
                  >
                    Clone
                  </button>
                )}
                {canWriteConnectors && (
                  <button
                    onClick={() => handleEdit(connector)}
                    className="text-blue-600 hover:text-blue-800 text-sm"
                  >
                    Edit
                  </button>
                )}
                {canDeleteConnectors && (confirmDeleteId === connector.id ? (
                  <>
                    <span className="text-xs text-gray-500">Delete?</span>
                    <button
                      onClick={() => handleDelete(connector.id)}
                      disabled={deletingId === connector.id}
                      className="text-red-600 hover:text-red-800 text-sm"
                    >
                      {deletingId === connector.id ? 'Deleting...' : 'Yes'}
                    </button>
                    <button
                      onClick={() => setConfirmDeleteId(null)}
                      className="text-gray-500 hover:text-gray-700 text-sm"
                    >
                      No
                    </button>
                  </>
                ) : (
                  <button
                    onClick={() => setConfirmDeleteId(connector.id)}
                    disabled={(connector.pipelineReferenceCount || 0) > 0}
                    title={(connector.pipelineReferenceCount || 0) > 0 ? 'Remove pipeline references before deleting this connector' : undefined}
                    className="text-red-600 hover:text-red-800 text-sm"
                  >
                    Delete
                  </button>
                ))}
              </div>
            </div>
          ))}
        </div>
      )}

      {usageModal && (
        <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
          <div className="bg-white rounded-lg p-6 max-w-2xl w-full mx-4">
            <div className="flex items-start justify-between mb-4 gap-3">
              <div>
                <h2 className="text-xl font-bold">Connector Usage</h2>
                <p className="text-sm text-gray-600">
                  {usageModal.connectorName} is referenced by {usageModal.referenceCount} pipeline node(s).
                </p>
              </div>
              <button className="btn-secondary" onClick={() => setUsageModal(null)}>Close</button>
            </div>
            {usageModal.references.length === 0 ? (
              <p className="text-sm text-gray-500">No pipeline references found.</p>
            ) : (
              <div className="overflow-x-auto">
                <table className="min-w-full text-sm">
                  <thead>
                    <tr className="text-left border-b border-gray-200">
                      <th className="py-2 pr-3">Pipeline</th>
                      <th className="py-2 pr-3">State</th>
                      <th className="py-2 pr-3">Node ID</th>
                      <th className="py-2 pr-3">Node Type</th>
                      <th className="py-2 text-right">Action</th>
                    </tr>
                  </thead>
                  <tbody>
                    {usageModal.references.map((reference) => (
                      <tr key={`${reference.pipelineId}-${reference.nodeId}`} className="border-b border-gray-100 last:border-0">
                        <td className="py-2 pr-3">
                          <div className="font-medium text-gray-900">{reference.pipelineName}</div>
                          <div className="text-xs text-gray-500">{reference.pipelineId}</div>
                        </td>
                        <td className="py-2 pr-3">
                          <span className={`px-2 py-0.5 rounded text-xs ${reference.pipelineIsActive ? 'bg-emerald-100 text-emerald-800' : 'bg-slate-100 text-slate-700'}`}>
                            {reference.pipelineIsActive ? 'Active' : 'Inactive'}
                          </span>
                        </td>
                        <td className="py-2 pr-3"><code>{reference.nodeId}</code></td>
                        <td className="py-2 pr-3">{reference.nodeType}</td>
                        <td className="py-2 text-right">
                          <button
                            className="text-indigo-700 hover:text-indigo-900 text-xs"
                            onClick={() => {
                              setUsageModal(null);
                              navigate(`/pipelines/${reference.pipelineId}`);
                            }}
                          >
                            Open Pipeline
                          </button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </div>
      )}

      {impactModal && (
        <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
          <div className="bg-white rounded-lg p-6 max-w-3xl w-full mx-4">
            <div className="flex items-start justify-between mb-4 gap-3">
              <div>
                <h2 className="text-xl font-bold">Connector Impact</h2>
                <p className="text-sm text-gray-600">
                  {impactModal.connectorName} impacts {impactModal.impactedPipelineCount} pipeline(s), {impactModal.referenceCount} node reference(s) over last {impactModal.sinceHours || impactWindowHours}h.
                </p>
              </div>
              <button className="btn-secondary" onClick={() => setImpactModal(null)}>Close</button>
            </div>
            <div className="mb-3 flex items-center gap-2">
              <span className="text-xs text-gray-600">Window:</span>
              {([1, 24, 168] as const).map((hours) => (
                <button
                  key={hours}
                  className={`px-2 py-0.5 rounded text-xs border ${impactWindowHours === hours ? 'border-indigo-500 text-indigo-700 bg-white' : 'border-slate-300 text-slate-700 bg-slate-50'}`}
                  onClick={async () => {
                    if (!impactModal) return;
                    const connector = connectors.find((item) => item.id === impactModal.connectorId);
                    if (!connector) return;
                    await handleViewImpact(connector, hours);
                  }}
                >
                  {hours === 1 ? '1h' : hours === 24 ? '24h' : '7d'}
                </button>
                ))}
            </div>
            <div className="mb-3 flex flex-wrap gap-3 text-xs text-gray-700">
              <span>
                High risk: <strong>{impactModal.impactedPipelines.filter((p) => p.riskLevel === 'HIGH').length}</strong>
              </span>
              <span>
                Medium risk: <strong>{impactModal.impactedPipelines.filter((p) => p.riskLevel === 'MEDIUM').length}</strong>
              </span>
              <span>
                Low risk: <strong>{impactModal.impactedPipelines.filter((p) => p.riskLevel === 'LOW').length}</strong>
              </span>
            </div>
            {impactModal.impactedPipelines.length === 0 ? (
              <p className="text-sm text-gray-500">No impacted pipelines found.</p>
            ) : (
              <div className="overflow-x-auto">
                <table className="min-w-full text-sm">
                  <thead>
                    <tr className="text-left border-b border-gray-200">
                      <th className="py-2 pr-3">Pipeline</th>
                      <th className="py-2 pr-3">Risk</th>
                      <th className="py-2 pr-3">State</th>
                      <th className="py-2 pr-3">Node refs</th>
                      <th className="py-2 pr-3">Recent health</th>
                      <th className="py-2 pr-3">Latest run</th>
                      <th className="py-2 text-right">Action</th>
                    </tr>
                  </thead>
                  <tbody>
                    {impactModal.impactedPipelines.map((pipeline) => (
                      <tr key={pipeline.pipelineId} className="border-b border-gray-100 last:border-0">
                        <td className="py-2 pr-3">
                          <div className="font-medium text-gray-900">{pipeline.pipelineName}</div>
                          <div className="text-xs text-gray-500">{pipeline.pipelineId}</div>
                        </td>
                        <td className="py-2 pr-3">
                          <div className="flex items-center gap-1">
                            <span className={`px-2 py-0.5 rounded text-xs ${
                              pipeline.riskLevel === 'HIGH'
                                ? 'bg-red-100 text-red-800'
                                : pipeline.riskLevel === 'MEDIUM'
                                  ? 'bg-amber-100 text-amber-800'
                                  : 'bg-emerald-100 text-emerald-800'
                            }`}>
                              {pipeline.riskLevel || 'LOW'}
                            </span>
                            <span className="text-xs text-gray-500">{pipeline.impactPriorityScore ?? 0}</span>
                          </div>
                        </td>
                        <td className="py-2 pr-3">
                          <span className={`px-2 py-0.5 rounded text-xs ${pipeline.pipelineIsActive ? 'bg-emerald-100 text-emerald-800' : 'bg-slate-100 text-slate-700'}`}>
                            {pipeline.pipelineIsActive ? 'Active' : 'Inactive'}
                          </span>
                        </td>
                        <td className="py-2 pr-3">{pipeline.nodeReferenceCount}</td>
                        <td className="py-2 pr-3 text-xs">
                          <div>Runs: <strong>{pipeline.recentRuns ?? 0}</strong></div>
                          <div>Failed: <strong>{pipeline.recentFailedRuns ?? 0}</strong></div>
                          <div>In-flight: <strong>{pipeline.recentInFlightRuns ?? 0}</strong></div>
                          <div>Success: <strong>{Math.round((pipeline.recentSuccessRate ?? 0) * 100)}%</strong></div>
                        </td>
                        <td className="py-2 pr-3">
                          {pipeline.latestRunStatus ? (
                            <div className="text-xs">
                              <div>
                                {pipeline.latestRunStatus}
                                {pipeline.latestRunStartedAt ? ` (${new Date(pipeline.latestRunStartedAt).toLocaleString()})` : ''}
                              </div>
                              {pipeline.latestFailedRunId && (
                                <div className="text-red-700" title={pipeline.latestFailedRunErrorMessage || ''}>
                                  Latest failure: {pipeline.latestFailedRunStartedAt ? new Date(pipeline.latestFailedRunStartedAt).toLocaleString() : 'unknown'}
                                </div>
                              )}
                            </div>
                          ) : (
                            <span className="text-xs text-gray-500">No runs</span>
                          )}
                        </td>
                        <td className="py-2 text-right">
                          <div className="flex items-center justify-end gap-2">
                            <button
                              className="text-indigo-700 hover:text-indigo-900 text-xs"
                              onClick={() => {
                                setImpactModal(null);
                                navigate(`/pipelines/${pipeline.pipelineId}`);
                              }}
                            >
                              Edit
                            </button>
                            <button
                              className="text-amber-700 hover:text-amber-900 text-xs"
                              onClick={() => {
                                setImpactModal(null);
                                const targetRunId = pipeline.latestFailedRunId || pipeline.latestRunId;
                                const params = new URLSearchParams({ focusPipelineId: pipeline.pipelineId });
                                if (targetRunId) params.set('focusRunId', targetRunId);
                                navigate(`/pipelines?${params.toString()}`);
                              }}
                            >
                              Triage Runs
                            </button>
                          </div>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </div>
      )}

      {disconnectImpactModal && pendingDisconnectConnector && (
        <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
          <div className="bg-white rounded-lg p-6 max-w-2xl w-full mx-4">
            <h2 className="text-xl font-bold mb-2">Confirm Disconnect</h2>
            <p className="text-sm text-gray-700 mb-3">
              Disconnecting <strong>{pendingDisconnectConnector.name}</strong> affects
              {' '}<strong>{disconnectImpactModal.activeImpactedPipelineCount || 0}</strong> active pipeline(s).
            </p>
            <div className="grid grid-cols-3 gap-2 mb-4 text-xs">
              <div className="rounded border border-red-200 bg-red-50 p-2 text-red-800">
                High risk: <strong>{disconnectImpactModal.highRiskCount || 0}</strong>
              </div>
              <div className="rounded border border-amber-200 bg-amber-50 p-2 text-amber-800">
                Medium risk: <strong>{disconnectImpactModal.mediumRiskCount || 0}</strong>
              </div>
              <div className="rounded border border-emerald-200 bg-emerald-50 p-2 text-emerald-800">
                Low risk: <strong>{disconnectImpactModal.lowRiskCount || 0}</strong>
              </div>
            </div>
            <div className="max-h-48 overflow-y-auto border border-gray-200 rounded p-2 text-xs text-gray-700 mb-4">
              {disconnectImpactModal.impactedPipelines.slice(0, 8).map((pipeline) => (
                <div key={pipeline.pipelineId} className="py-1 border-b border-gray-100 last:border-0">
                  <strong>{pipeline.pipelineName}</strong> - {pipeline.riskLevel || 'LOW'} risk, latest {pipeline.latestRunStatus || 'N/A'}
                </div>
              ))}
            </div>
            <div className="flex justify-end gap-2">
              <button
                className="btn-secondary"
                onClick={() => {
                  setDisconnectImpactModal(null);
                  setPendingDisconnectConnector(null);
                }}
              >
                Cancel
              </button>
              <button
                className="btn-primary bg-red-600 hover:bg-red-700"
                onClick={async () => {
                  const target = pendingDisconnectConnector;
                  setDisconnectImpactModal(null);
                  setPendingDisconnectConnector(null);
                  if (target) await performToggleConnection(target);
                }}
              >
                Disconnect Anyway
              </button>
            </div>
          </div>
        </div>
      )}

      {canManageBrokerUsers && (
        <div className="card mt-6">
          <div className="flex items-start justify-between mb-3 gap-3">
            <div>
              <h2 className="text-lg font-semibold text-slate-900 dark:text-slate-100">Integrated MQTT Broker Users</h2>
              <p className="text-sm text-slate-600 dark:text-slate-300">
                Tenant-scoped broker credentials for the bundled Mosquitto broker. These are separate from app login users.
              </p>
            </div>
            <button onClick={openCreateBrokerCredential} className="btn-primary text-sm">Create Broker User</button>
          </div>

          {brokerCredentials.length === 0 ? (
            <p className="text-sm text-slate-500">No MQTT broker users configured for this tenant.</p>
          ) : (
            <div className="overflow-x-auto">
              <table className="min-w-full divide-y divide-slate-200 dark:divide-slate-700 text-sm">
                <thead className="bg-slate-50 dark:bg-slate-800">
                  <tr>
                    <th className="px-3 py-2 text-left font-medium text-slate-600 dark:text-slate-300">Name</th>
                    <th className="px-3 py-2 text-left font-medium text-slate-600 dark:text-slate-300">Username</th>
                    <th className="px-3 py-2 text-left font-medium text-slate-600 dark:text-slate-300">Allowed Topics</th>
                    <th className="px-3 py-2 text-left font-medium text-slate-600 dark:text-slate-300">Status</th>
                    <th className="px-3 py-2 text-right font-medium text-slate-600 dark:text-slate-300">Actions</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100 dark:divide-slate-800">
                  {brokerCredentials.map((credential) => (
                    <tr key={credential.id}>
                      <td className="px-3 py-2 text-slate-800 dark:text-slate-200">{credential.name}</td>
                      <td className="px-3 py-2"><code>{credential.username}</code></td>
                      <td className="px-3 py-2">
                        <div className="flex flex-wrap gap-1">
                          {credential.allowedTopics.map((topic) => (
                            <span key={`${credential.id}-${topic}`} className="rounded bg-slate-100 dark:bg-slate-800 px-2 py-0.5 text-xs">
                              {topic}
                            </span>
                          ))}
                        </div>
                      </td>
                      <td className="px-3 py-2">
                        <span className={`text-xs px-2 py-0.5 rounded ${credential.isActive ? 'bg-emerald-100 text-emerald-800' : 'bg-slate-200 text-slate-700'}`}>
                          {credential.isActive ? 'Active' : 'Inactive'}
                        </span>
                        <div className="text-xs text-slate-500 mt-1">
                          In use by {credential.referenceCount || 0} connector(s)
                        </div>
                      </td>
                      <td className="px-3 py-2 text-right space-x-2">
                        <button className="text-indigo-700 hover:text-indigo-900" onClick={() => openEditBrokerCredential(credential)}>
                          Edit
                        </button>
                        <button
                          className="text-emerald-700 hover:text-emerald-900"
                          onClick={() => handleRotateBrokerCredentialPassword(credential)}
                          disabled={rotatingBrokerCredentialId === credential.id}
                        >
                          {rotatingBrokerCredentialId === credential.id ? 'Rotating...' : 'Rotate Password'}
                        </button>
                        <button
                          className="text-red-700 hover:text-red-900"
                          onClick={() => handleDeleteBrokerCredential(credential.id)}
                          disabled={deletingBrokerCredentialId === credential.id || (credential.referenceCount || 0) > 0}
                          title={(credential.referenceCount || 0) > 0 ? 'Remove connector references before deleting' : undefined}
                        >
                          {deletingBrokerCredentialId === credential.id ? 'Deleting...' : 'Delete'}
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}

      {showModal && canWriteConnectors && (
        <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
          <div className="bg-white rounded-lg p-6 max-w-md w-full mx-4">
            <h2 className="text-xl font-bold mb-4">
              {editingConnector ? 'Edit Connector' : 'Create Connector'}
            </h2>
            
            <form onSubmit={handleSubmit}>
              <div className="space-y-4">
                <div>
                  <label className="block text-sm font-medium text-gray-700 mb-1">
                    Name
                  </label>
                  <input
                    type="text"
                    required
                    value={formData.name}
                    onChange={(e) => setFormData({ ...formData, name: e.target.value })}
                    className="input"
                    placeholder="My MQTT Broker"
                  />
                </div>
                
                <div>
                  <label className="block text-sm font-medium text-gray-700 mb-1">
                    Type
                  </label>
                  <select
                    value={formData.connectorType}
                    onChange={(e) => {
                      const nextType = e.target.value as ConnectorType;
                      setFormData({
                        ...formData,
                        connectorType: nextType,
                        config: initialConfigByType(nextType, formData.config),
                      });
                    }}
                    className="input"
                  >
                    {connectorTypes.map((type) => (
                      <option key={type} value={type}>{type}</option>
                    ))}
                  </select>
                </div>
                
                <div>
                  <label className="block text-sm font-medium text-gray-700 mb-1">
                    Direction
                  </label>
                  <select
                    value={formData.direction}
                    onChange={(e) => setFormData({ ...formData, direction: e.target.value as Connector['direction'] })}
                    className="input"
                  >
                    {directions.map((dir) => (
                      <option key={dir} value={dir}>{dir}</option>
                    ))}
                  </select>
                </div>
                
                <div className="rounded border border-slate-200 dark:border-slate-700 p-3">
                  <label className="block text-sm font-medium text-slate-700 dark:text-slate-200 mb-2">
                    {formData.connectorType} Configuration
                  </label>
                  {formData.connectorType === 'MQTT' && (
                    <div className="mb-3 rounded border border-blue-200 dark:border-blue-900 bg-blue-50 dark:bg-blue-950/30 p-2">
                      <p className="text-xs text-blue-900 dark:text-blue-200 mb-2">
                        Quick setup: use the integrated broker or localhost, then adjust as needed.
                      </p>
                      {brokerCredentials.length > 0 && (
                        <div className="mb-2">
                          <label className="block text-xs font-medium text-blue-900 dark:text-blue-200 mb-1">
                            Broker User Credential
                          </label>
                          <select
                            className="input text-sm"
                            value={formData.config.brokerCredentialId || ''}
                            onChange={(e) => {
                              const credentialId = e.target.value;
                              const selectedCredential = brokerCredentials.find((c) => c.id === credentialId);
                              setFormData((prev) => ({
                                ...prev,
                                config: {
                                  ...prev.config,
                                  brokerCredentialId: credentialId,
                                  username: credentialId ? '' : prev.config.username,
                                  password: credentialId ? '' : prev.config.password,
                                  topic: prev.config.topic?.trim()
                                    ? prev.config.topic
                                    : (selectedCredential?.allowedTopics?.[0] || prev.config.topic || ''),
                                },
                              }));
                            }}
                          >
                            <option value="">No broker credential</option>
                            {brokerCredentials.map((credential) => (
                              <option key={credential.id} value={credential.id}>
                                {credential.name} ({credential.username})
                              </option>
                            ))}
                          </select>
                          <p className="text-[11px] text-blue-800 dark:text-blue-300 mt-1">
                            Uses tenant-scoped MQTT broker users managed below.
                          </p>
                          {!!(formData.config.brokerCredentialId || '').trim() && (
                            <p className="text-[11px] text-blue-800 dark:text-blue-300 mt-1">
                              Username/password fields are locked and managed by the selected broker credential.
                            </p>
                          )}
                        </div>
                      )}
                      <div className="flex flex-wrap gap-2">
                        <button
                          type="button"
                          onClick={() => applyMqttPreset('internal')}
                          className="px-2 py-1 text-xs rounded border border-blue-300 text-blue-800 bg-white hover:bg-blue-100"
                        >
                          Use {mqttDefaults?.integratedBrokerLabel || 'Integrated Broker'}
                        </button>
                        <button
                          type="button"
                          onClick={() => applyMqttPreset('external')}
                          className="px-2 py-1 text-xs rounded border border-blue-300 text-blue-800 bg-white hover:bg-blue-100"
                        >
                          Use Localhost
                        </button>
                      </div>
                      <div className="mt-2 text-[11px] text-blue-800 dark:text-blue-300">
                        <div>Internal: <code>{mqttDefaults?.internalBrokerUrl || 'mqtt://mosquitto:1883'}</code></div>
                        <div>External: <code>{mqttDefaults?.externalBrokerUrl || 'mqtt://localhost:1883'}</code></div>
                      </div>
                    </div>
                  )}
                  <div className="grid grid-cols-1 gap-3">
                    {(connectorConfigSchema[formData.connectorType] || []).map((field) => (
                      <div key={field.key}>
                        <label className="block text-sm text-slate-600 dark:text-slate-300 mb-1">
                          {field.label}{field.required ? ' *' : ''}
                        </label>
                        <input
                          type={field.type || 'text'}
                          value={formData.config[field.key] || ''}
                          onChange={(e) => setFormData({
                            ...formData,
                            config: { ...formData.config, [field.key]: e.target.value },
                          })}
                          className="input"
                          placeholder={field.placeholder}
                          required={field.required}
                          disabled={
                            formData.connectorType === 'MQTT'
                            && Boolean((formData.config.brokerCredentialId || '').trim())
                            && (field.key === 'username' || field.key === 'password')
                          }
                        />
                        {field.hint && <p className="text-xs text-slate-500 mt-1">{field.hint}</p>}
                      </div>
                    ))}
                  </div>
                  <p className="mt-2 text-xs text-slate-500">
                    Direction: <strong>{formData.direction}</strong> - use INPUT for ingest, OUTPUT for delivery, BOTH for bidirectional adapters.
                  </p>
                </div>
                
                <div>
                  <label className="block text-sm font-medium text-gray-700 mb-1">
                    Description
                  </label>
                  <textarea
                    value={formData.description}
                    onChange={(e) => setFormData({ ...formData, description: e.target.value })}
                    className="input"
                    rows={2}
                    placeholder="Optional description"
                  />
                </div>
              </div>
              
              <div className="mt-6 flex justify-end space-x-3">
                <button
                  type="button"
                  onClick={() => {
                    setShowModal(false);
                    setEditingConnector(null);
                  }}
                  className="btn-secondary"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  disabled={savingConnector}
                  className="btn-primary"
                >
                  {savingConnector ? 'Saving...' : editingConnector ? 'Save Changes' : 'Create'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {showCloneModal && cloneSource && canWriteConnectors && (
        <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
          <div className="bg-white rounded-lg p-6 max-w-md w-full mx-4">
            <h2 className="text-xl font-bold mb-4">Clone Connector</h2>
            <div className="space-y-4">
              <div className="text-sm text-gray-600">
                Cloning <strong>{cloneSource.name}</strong>. New connector starts disconnected.
              </div>
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">Name</label>
                <input
                  className="input"
                  value={cloneForm.name}
                  onChange={(e) => setCloneForm((prev) => ({ ...prev, name: e.target.value }))}
                />
              </div>
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">Direction</label>
                <select
                  className="input"
                  value={cloneForm.direction}
                  onChange={(e) => setCloneForm((prev) => ({ ...prev, direction: e.target.value as Connector['direction'] }))}
                >
                  {directions.map((dir) => (
                    <option key={dir} value={dir}>{dir}</option>
                  ))}
                </select>
              </div>
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">Description</label>
                <textarea
                  className="input"
                  rows={2}
                  value={cloneForm.description}
                  onChange={(e) => setCloneForm((prev) => ({ ...prev, description: e.target.value }))}
                />
              </div>
              <label className="flex items-center gap-2 text-sm text-gray-700">
                <input
                  type="checkbox"
                  checked={cloneForm.keepSecrets}
                  onChange={(e) => setCloneForm((prev) => ({ ...prev, keepSecrets: e.target.checked }))}
                />
                Keep sensitive config (tokens/passwords)
              </label>
              <p className="text-xs text-gray-500">
                Recommended: keep this unchecked so cloned connectors are safer by default.
              </p>
            </div>
            <div className="mt-6 flex justify-end gap-2">
              <button
                className="btn-secondary"
                onClick={() => {
                  setShowCloneModal(false);
                  setCloneSource(null);
                }}
              >
                Cancel
              </button>
              <button
                className="btn-primary"
                onClick={() => void handleCloneConnector()}
                disabled={cloningId === cloneSource.id}
              >
                {cloningId === cloneSource.id ? 'Cloning...' : 'Clone Connector'}
              </button>
            </div>
          </div>
        </div>
      )}

      {showBrokerModal && canManageBrokerUsers && (
        <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
          <div className="bg-white dark:bg-slate-900 rounded-lg p-6 max-w-lg w-full mx-4">
            <h2 className="text-xl font-bold mb-4 text-slate-900 dark:text-slate-100">
              {editingBrokerCredential ? 'Edit MQTT Broker User' : 'Create MQTT Broker User'}
            </h2>
            <form onSubmit={handleSaveBrokerCredential} className="space-y-4">
              <div>
                <label className="block text-sm font-medium mb-1">Display Name</label>
                <input className="input" value={brokerForm.name} onChange={(e) => setBrokerForm((prev) => ({ ...prev, name: e.target.value }))} required />
              </div>
              <div>
                <label className="block text-sm font-medium mb-1">Broker Username</label>
                <input
                  className="input"
                  value={brokerForm.username}
                  onChange={(e) => setBrokerForm((prev) => ({ ...prev, username: e.target.value }))}
                  placeholder="optional; auto-generated if empty"
                  disabled={Boolean(editingBrokerCredential)}
                />
              </div>
              <div>
                <label className="block text-sm font-medium mb-1">Password {editingBrokerCredential ? '(leave blank to keep current)' : '(optional auto-generate)'}</label>
                <input
                  className="input"
                  type="password"
                  value={brokerForm.password}
                  onChange={(e) => setBrokerForm((prev) => ({ ...prev, password: e.target.value }))}
                />
              </div>
              <div>
                <label className="block text-sm font-medium mb-1">Allowed Topics (one per line or comma separated)</label>
                <textarea
                  className="input"
                  rows={4}
                  value={brokerForm.allowedTopicsText}
                  onChange={(e) => setBrokerForm((prev) => ({ ...prev, allowedTopicsText: e.target.value }))}
                  placeholder="dataturntable/events/#"
                />
              </div>
              <label className="flex items-center gap-2 text-sm">
                <input
                  type="checkbox"
                  checked={brokerForm.isActive}
                  onChange={(e) => setBrokerForm((prev) => ({ ...prev, isActive: e.target.checked }))}
                />
                Credential is active
              </label>

              <div className="flex justify-end gap-2 pt-2">
                <button
                  type="button"
                  className="btn-secondary"
                  onClick={() => {
                    setShowBrokerModal(false);
                    setEditingBrokerCredential(null);
                  }}
                >
                  Cancel
                </button>
                <button type="submit" className="btn-primary" disabled={savingBrokerCredential}>
                  {savingBrokerCredential ? 'Saving...' : editingBrokerCredential ? 'Save Changes' : 'Create User'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}
