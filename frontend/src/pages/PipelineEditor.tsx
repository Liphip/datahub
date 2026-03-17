import { useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { useAuthStore } from '../stores/auth';
import api from '../services/api';
import type { Pipeline, CodeModule, Connector, ModuleVersion } from '../types';

type ModuleVersionOption = ModuleVersion & {
  moduleName: string;
  moduleType: CodeModule['moduleType'];
};

export default function PipelineEditor() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { user, selectedTenantId, tenantContexts } = useAuthStore();
  const canReadPipelines = Boolean(user?.superadmin || user?.abilities?.includes('*') || user?.abilities?.includes('pipeline:read'));
  const canWritePipelines = Boolean(user?.superadmin || user?.abilities?.includes('*') || user?.abilities?.includes('pipeline:write'));
  const canExecutePipelines = Boolean(user?.superadmin || user?.abilities?.includes('*') || user?.abilities?.includes('pipeline:execute'));
  
  const [pipeline, setPipeline] = useState<Pipeline | null>(null);
  const [moduleVersionOptions, setModuleVersionOptions] = useState<ModuleVersionOption[]>([]);
  const [connectors, setConnectors] = useState<Connector[]>([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [toast, setToast] = useState<{ message: string; type: 'success' | 'error' } | null>(null);
  
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [importModuleId, setImportModuleId] = useState('');
  const [importConnectorId, setImportConnectorId] = useState('');
  const [processingModuleIds, setProcessingModuleIds] = useState<string[]>([]);
  const [alertModuleIds, setAlertModuleIds] = useState<string[]>([]);
  const [exportModuleIds, setExportModuleIds] = useState<string[]>([]);
  const [exportConnectorIds, setExportConnectorIds] = useState<string[]>([]);
  const [isActive, setIsActive] = useState(false);

  const tenantId = selectedTenantId || user?.tenantId || tenantContexts[0]?.tenantId;

  useEffect(() => {
    const fetchData = async () => {
      if (!id || !tenantId) {
        setLoading(false);
        return;
      }
      
      try {
        const [pipelineData, modulesData, connectorsData] = await Promise.all([
          id === 'new' ? Promise.resolve(null) : api.getPipeline(id),
          api.getModules(tenantId),
          api.getConnectors(tenantId),
        ]);

        const versionsByModule = await Promise.all(
          modulesData.map(async (module) => {
            const versions = await api.getModuleVersions(module.id);
            return versions.map((version) => ({
              ...version,
              moduleName: module.name,
              moduleType: module.moduleType,
            }));
          })
        );
        const flattenedOptions = versionsByModule.flat();
        
        setModuleVersionOptions(flattenedOptions);
        setConnectors(connectorsData);
        
        if (pipelineData) {
          setPipeline(pipelineData);
          setName(pipelineData.name);
          setDescription(pipelineData.description || '');
          setImportModuleId(pipelineData.importModuleVersionId || '');
          setProcessingModuleIds(pipelineData.processingModuleVersionIds || []);
          const alertNodes = (pipelineData.graphDefinition?.nodes || []).filter((node) => node.type === 'ALERT');
          const parsedAlertModuleIds = alertNodes
            .map((node) => (typeof node.moduleVersionId === 'string' ? node.moduleVersionId : ''))
            .filter(Boolean);
          setAlertModuleIds(parsedAlertModuleIds);
          setExportModuleIds(pipelineData.exportModuleVersionIds || []);
          setIsActive(pipelineData.isActive);

          const graphNodes = pipelineData.graphDefinition?.nodes || [];
          const importNode = graphNodes.find((node) => node.type === 'IMPORT');
          if (importNode && typeof importNode.connectorId === 'string') {
            setImportConnectorId(importNode.connectorId);
          }

          const exportNodes = graphNodes.filter((node) => node.type === 'EXPORT');
          const parsedExportConnectorIds = exportNodes.map((node) => (
            typeof node.connectorId === 'string' ? node.connectorId : ''
          ));
          setExportConnectorIds(parsedExportConnectorIds);
        } else {
          setName('New Pipeline');
          setDescription('');
          setImportModuleId('');
          setImportConnectorId('');
          setProcessingModuleIds([]);
          setAlertModuleIds([]);
          setExportModuleIds([]);
          setExportConnectorIds([]);
          setIsActive(false);
        }
      } catch (error) {
        console.error('Error fetching pipeline:', error);
      } finally {
        setLoading(false);
      }
    };
    
    fetchData();
  }, [id, tenantId]);

  const showToast = (message: string, type: 'success' | 'error') => {
    setToast({ message, type });
    setTimeout(() => setToast(null), 3500);
  };

  const handleSave = async () => {
    if (!tenantId || !name || !canWritePipelines) return;

    try {
      const importVersionId = importModuleId || '';
      const processingVersionIds = processingModuleIds.filter(Boolean);
      const alertVersionIds = alertModuleIds.filter(Boolean);
      const exportVersionIds = exportModuleIds.filter(Boolean);
      const availableVersionIds = new Set(moduleVersionOptions.map((option) => option.id));

      if (!importVersionId) {
        showToast('Select an import module version before saving', 'error');
        return;
      }
      if (!availableVersionIds.has(importVersionId)) {
        showToast('Selected import module version is no longer available. Reload and choose another version.', 'error');
        return;
      }
      if (processingVersionIds.length === 0) {
        showToast('Add at least one processing module version', 'error');
        return;
      }
      if (processingVersionIds.some((id) => !availableVersionIds.has(id))) {
        showToast('One or more selected processing module versions are no longer available.', 'error');
        return;
      }
      if (alertVersionIds.some((id) => !availableVersionIds.has(id))) {
        showToast('One or more selected alert module versions are no longer available.', 'error');
        return;
      }
      if (exportVersionIds.length === 0) {
        showToast('Add at least one export module version', 'error');
        return;
      }
      if (exportVersionIds.some((id) => !availableVersionIds.has(id))) {
        showToast('One or more selected export module versions are no longer available.', 'error');
        return;
      }
      if (!importConnectorId) {
        showToast('Select an input connector for the import stage', 'error');
        return;
      }
      const missingExportConnectorIndex = exportVersionIds.findIndex((_, index) => !(exportConnectorIds[index] || '').trim());
      if (missingExportConnectorIndex >= 0) {
        showToast(`Select an output connector for export module #${missingExportConnectorIndex + 1}`, 'error');
        return;
      }
      if (new Set(exportConnectorIds.filter(Boolean)).size !== exportConnectorIds.filter(Boolean).length) {
        showToast('Each export module should use a distinct connector to avoid duplicate deliveries.', 'error');
        return;
      }

      const graphNodes: Array<Record<string, unknown>> = [];
      const graphEdges: Array<Record<string, unknown>> = [];

      let importNodeId = '';
      if (importVersionId) {
        importNodeId = 'n-import';
        graphNodes.push({
          id: importNodeId,
          type: 'IMPORT',
          moduleVersionId: importVersionId,
          connectorId: importConnectorId || undefined,
        });
      }

      const processingNodeIds: string[] = [];
      processingVersionIds.forEach((moduleVersionId, index) => {
        const nodeId = `n-processing-${index + 1}`;
        processingNodeIds.push(nodeId);
        graphNodes.push({ id: nodeId, type: 'PROCESSING', moduleVersionId });
      });

      exportVersionIds.forEach((moduleVersionId, index) => {
        const nodeId = `n-export-${index + 1}`;
        graphNodes.push({
          id: nodeId,
          type: 'EXPORT',
          moduleVersionId,
          connectorId: exportConnectorIds[index] || undefined,
        });
      });

      const alertNodeIds: string[] = [];
      alertVersionIds.forEach((moduleVersionId, index) => {
        const nodeId = `n-alert-${index + 1}`;
        alertNodeIds.push(nodeId);
        graphNodes.push({ id: nodeId, type: 'ALERT', moduleVersionId });
      });

      const firstProcessingNodeId = processingNodeIds[0];
      if (importNodeId && firstProcessingNodeId) {
        graphEdges.push({ from: importNodeId, to: firstProcessingNodeId });
      }
      if (importNodeId && !firstProcessingNodeId) {
        graphNodes
          .filter((node) => node.type === 'EXPORT')
          .forEach((node) => graphEdges.push({ from: importNodeId, to: node.id }));
      }
      if (firstProcessingNodeId) {
        graphNodes
          .filter((node) => node.type === 'EXPORT')
          .forEach((node) => graphEdges.push({ from: firstProcessingNodeId, to: node.id }));
      }
      const alertSourceNodeId = firstProcessingNodeId || importNodeId;
      if (alertSourceNodeId) {
        alertNodeIds.forEach((nodeId) => graphEdges.push({ from: alertSourceNodeId, to: nodeId }));
      }

      const graphDefinition = {
        nodes: graphNodes,
        edges: graphEdges,
      };
    
      setSaving(true);
      if (id === 'new') {
        const newPipeline = await api.createPipeline(tenantId, name, {
          description,
          importModuleVersionId: importVersionId || undefined,
          processingModuleVersionIds: processingVersionIds,
          exportModuleVersionIds: exportVersionIds,
          graphDefinition,
          isActive,
        });
        navigate(`/pipelines/${newPipeline.id}`);
      } else if (id) {
        await api.updatePipeline(id, {
          name,
          description,
          importModuleVersionId: importVersionId || undefined,
          processingModuleVersionIds: processingVersionIds,
          exportModuleVersionIds: exportVersionIds,
          graphDefinition,
          isActive,
        });
        showToast('Pipeline saved successfully', 'success');
      }
    } catch (error: any) {
      console.error('Error saving pipeline:', error);
      showToast(error?.response?.data?.error || 'Failed to save pipeline', 'error');
    } finally {
      setSaving(false);
    }
  };

  const handleRun = async () => {
    if (!id || id === 'new' || !canExecutePipelines) return;
    
    try {
      await api.triggerPipeline(id);
      showToast('Pipeline triggered successfully', 'success');
    } catch (error) {
      console.error('Error running pipeline:', error);
      showToast('Failed to trigger pipeline', 'error');
    }
  };

  const addProcessingModule = () => {
    setProcessingModuleIds([...processingModuleIds, '']);
  };

  const updateProcessingModule = (index: number, value: string) => {
    const updated = [...processingModuleIds];
    updated[index] = value;
    setProcessingModuleIds(updated);
  };

  const removeProcessingModule = (index: number) => {
    setProcessingModuleIds(processingModuleIds.filter((_, i) => i !== index));
  };

  const addExportModule = () => {
    setExportModuleIds([...exportModuleIds, '']);
    setExportConnectorIds([...exportConnectorIds, '']);
  };

  const addAlertModule = () => {
    setAlertModuleIds([...alertModuleIds, '']);
  };

  const updateAlertModule = (index: number, value: string) => {
    const updated = [...alertModuleIds];
    updated[index] = value;
    setAlertModuleIds(updated);
  };

  const removeAlertModule = (index: number) => {
    setAlertModuleIds(alertModuleIds.filter((_, i) => i !== index));
  };

  const updateExportModule = (index: number, value: string) => {
    const updated = [...exportModuleIds];
    updated[index] = value;
    setExportModuleIds(updated);
  };

  const removeExportModule = (index: number) => {
    setExportModuleIds(exportModuleIds.filter((_, i) => i !== index));
    setExportConnectorIds(exportConnectorIds.filter((_, i) => i !== index));
  };

  const updateExportConnector = (index: number, connectorId: string) => {
    const updated = [...exportConnectorIds];
    updated[index] = connectorId;
    setExportConnectorIds(updated);
  };

  const importVersionOptions = moduleVersionOptions.filter(v => v.moduleType === 'IMPORT');
  const processingVersionOptions = moduleVersionOptions.filter(v => v.moduleType === 'PROCESSING');
  const alertVersionOptions = moduleVersionOptions.filter(v => v.moduleType === 'ALERT');
  const exportVersionOptions = moduleVersionOptions.filter(v => v.moduleType === 'EXPORT');
  const importConnectors = connectors.filter((c) => c.direction === 'INPUT' || c.direction === 'BOTH');
  const exportConnectors = connectors.filter((c) => c.direction === 'OUTPUT' || c.direction === 'BOTH');

  if (loading) {
    return (
      <div className="px-4 py-6">
        <div className="card animate-pulse">
          <div className="h-4 bg-gray-200 rounded w-1/3 mb-4"></div>
        </div>
      </div>
    );
  }

  if (!tenantId) {
    return (
      <div className="px-4 py-6">
        <div className="card text-center py-12">
          <p className="text-gray-500">No tenant ID found. Please contact support.</p>
        </div>
      </div>
    );
  }

  if (!canReadPipelines) {
    return (
      <div className="px-4 py-6">
        <div className="card text-center py-12">
          <p className="text-gray-500">You do not have permission to view pipelines in this tenant.</p>
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

      <div className="flex justify-between items-center mb-6">
        <h1 className="text-2xl font-bold text-gray-900">
          {id === 'new' ? 'Create Pipeline' : 'Edit Pipeline'}
        </h1>
        <div className="flex space-x-2">
          <button
            onClick={() => navigate('/pipelines')}
            className="btn-secondary"
          >
            Cancel
          </button>
          {id !== 'new' && (
            <button
              onClick={handleRun}
              disabled={!canExecutePipelines}
              className="btn-primary bg-green-600 hover:bg-green-700"
              title={!canExecutePipelines ? 'You need pipeline execute permission to run pipelines' : undefined}
            >
              Run Pipeline
            </button>
          )}
          <button
            onClick={handleSave}
            disabled={saving || !canWritePipelines}
            className="btn-primary"
            title={!canWritePipelines ? 'You need pipeline write permission to save changes' : undefined}
          >
            {saving ? 'Saving...' : 'Save'}
          </button>
        </div>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        <div className="lg:col-span-2 space-y-6">
          <div className="card">
            <h2 className="text-lg font-semibold mb-4">Basic Information</h2>
            <div className="space-y-4">
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">Name</label>
                <input
                  type="text"
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  className="input w-full"
                  required
                />
              </div>
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">Description</label>
                <textarea
                  value={description}
                  onChange={(e) => setDescription(e.target.value)}
                  className="input w-full"
                  rows={3}
                />
              </div>
              <div className="flex items-center">
                <input
                  type="checkbox"
                  id="isActive"
                  checked={isActive}
                  onChange={(e) => setIsActive(e.target.checked)}
                  className="h-4 w-4 text-primary-600 border-gray-300 rounded"
                />
                <label htmlFor="isActive" className="ml-2 text-sm text-gray-700">
                  Active (start processing automatically)
                </label>
              </div>
            </div>
          </div>

          <div className="card">
            <h2 className="text-lg font-semibold mb-4">Import Module</h2>
            <select
              value={importModuleId}
              onChange={(e) => setImportModuleId(e.target.value)}
              className="input w-full"
              disabled={!canWritePipelines}
            >
              <option value="">None</option>
              {importVersionOptions.map((version) => (
                <option key={version.id} value={version.id}>
                  {version.moduleName} (v{version.version}{version.versionTag ? ` - ${version.versionTag}` : ''})
                </option>
              ))}
            </select>
            {importVersionOptions.length === 0 && (
              <p className="text-sm text-gray-500 mt-2">
                No import modules available. Create one in the Modules section.
              </p>
            )}
            <div className="mt-3">
              <label className="block text-sm font-medium text-gray-700 mb-1">Import Connector</label>
              <select
                value={importConnectorId}
                onChange={(e) => setImportConnectorId(e.target.value)}
                className="input w-full"
                disabled={!canWritePipelines}
              >
                <option value="">Select connector</option>
                {importConnectors.map((connector) => (
                  <option key={connector.id} value={connector.id}>{connector.name}</option>
                ))}
              </select>
            </div>
          </div>

          <div className="card">
            <div className="flex justify-between items-center mb-4">
              <h2 className="text-lg font-semibold">Processing Modules</h2>
              <button
                type="button"
                onClick={addProcessingModule}
                className="text-sm text-primary-600 hover:text-primary-700"
                disabled={!canWritePipelines}
              >
                + Add Module
              </button>
            </div>
            {processingModuleIds.length > 0 ? (
              <div className="space-y-2">
                {processingModuleIds.map((moduleId, index) => (
                  <div key={index} className="flex space-x-2">
                    <select
                      value={moduleId}
                      onChange={(e) => updateProcessingModule(index, e.target.value)}
                      className="input flex-1"
                      disabled={!canWritePipelines}
                    >
                      <option value="">Select module</option>
                      {processingVersionOptions.map((version) => (
                        <option key={version.id} value={version.id}>
                          {version.moduleName} (v{version.version}{version.versionTag ? ` - ${version.versionTag}` : ''})
                        </option>
                      ))}
                    </select>
                    <button
                      type="button"
                      onClick={() => removeProcessingModule(index)}
                      className="text-red-600 hover:text-red-700"
                      disabled={!canWritePipelines}
                    >
                      Remove
                    </button>
                  </div>
                ))}
              </div>
            ) : (
              <p className="text-sm text-gray-500">No processing modules added.</p>
            )}
          </div>

          <div className="card">
            <div className="flex justify-between items-center mb-4">
              <h2 className="text-lg font-semibold">Alert Modules</h2>
              <button
                type="button"
                onClick={addAlertModule}
                className="text-sm text-primary-600 hover:text-primary-700"
                disabled={!canWritePipelines}
              >
                + Add Module
              </button>
            </div>
            {alertModuleIds.length > 0 ? (
              <div className="space-y-2">
                {alertModuleIds.map((moduleId, index) => (
                  <div key={index} className="flex space-x-2">
                    <select
                      value={moduleId}
                      onChange={(e) => updateAlertModule(index, e.target.value)}
                      className="input flex-1"
                      disabled={!canWritePipelines}
                    >
                      <option value="">Select module</option>
                      {alertVersionOptions.map((version) => (
                        <option key={version.id} value={version.id}>
                          {version.moduleName} (v{version.version}{version.versionTag ? ` - ${version.versionTag}` : ''})
                        </option>
                      ))}
                    </select>
                    <button
                      type="button"
                      onClick={() => removeAlertModule(index)}
                      className="text-red-600 hover:text-red-700"
                      disabled={!canWritePipelines}
                    >
                      Remove
                    </button>
                  </div>
                ))}
              </div>
            ) : (
              <p className="text-sm text-gray-500">No alert modules added.</p>
            )}
          </div>

          <div className="card">
            <div className="flex justify-between items-center mb-4">
              <h2 className="text-lg font-semibold">Export Modules</h2>
              <button
                type="button"
                onClick={addExportModule}
                className="text-sm text-primary-600 hover:text-primary-700"
                disabled={!canWritePipelines}
              >
                + Add Module
              </button>
            </div>
            {exportModuleIds.length > 0 ? (
              <div className="space-y-2">
                {exportModuleIds.map((moduleId, index) => (
                  <div key={index} className="space-y-2">
                    <div className="flex space-x-2">
                      <select
                        value={moduleId}
                        onChange={(e) => updateExportModule(index, e.target.value)}
                        className="input flex-1"
                        disabled={!canWritePipelines}
                      >
                        <option value="">Select module</option>
                        {exportVersionOptions.map((version) => (
                          <option key={version.id} value={version.id}>
                            {version.moduleName} (v{version.version}{version.versionTag ? ` - ${version.versionTag}` : ''})
                          </option>
                        ))}
                      </select>
                      <button
                        type="button"
                        onClick={() => removeExportModule(index)}
                        className="text-red-600 hover:text-red-700"
                        disabled={!canWritePipelines}
                      >
                        Remove
                      </button>
                    </div>
                    <select
                      value={exportConnectorIds[index] || ''}
                      onChange={(e) => updateExportConnector(index, e.target.value)}
                      className="input w-full"
                      disabled={!canWritePipelines}
                    >
                      <option value="">Select connector</option>
                      {exportConnectors.map((connector) => (
                        <option key={connector.id} value={connector.id}>{connector.name}</option>
                      ))}
                    </select>
                  </div>
                ))}
              </div>
            ) : (
              <p className="text-sm text-gray-500">No export modules added.</p>
            )}
          </div>
        </div>

        <div className="space-y-6">
          <div className="card">
            <h2 className="text-lg font-semibold mb-4">Pipeline Info</h2>
            {pipeline ? (
              <div className="space-y-2 text-sm">
                <div className="flex justify-between">
                  <span className="text-gray-500">Status</span>
                  <span className={isActive ? 'text-green-600' : 'text-gray-600'}>
                    {isActive ? 'Active' : 'Inactive'}
                  </span>
                </div>
                <div className="flex justify-between">
                  <span className="text-gray-500">Created</span>
                  <span>{pipeline.createdAt ? new Date(pipeline.createdAt).toLocaleDateString() : 'N/A'}</span>
                </div>
              </div>
            ) : (
              <p className="text-sm text-gray-500">New pipeline</p>
            )}
          </div>

          <div className="card">
            <h2 className="text-lg font-semibold mb-4">Help</h2>
            <div className="text-sm text-gray-600 space-y-2">
              <p><strong>Import Module:</strong> Fetches data from external sources</p>
              <p><strong>Processing Modules:</strong> Transform data (run in order)</p>
              <p><strong>Alert Modules:</strong> Evaluate conditions and emit alert events</p>
              <p><strong>Export Modules:</strong> Send data to external systems</p>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
