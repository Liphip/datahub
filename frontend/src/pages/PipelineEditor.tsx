import { useEffect, useMemo, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useAuthStore } from '../stores/auth';
import api from '../services/api';
import type { Pipeline, CodeModule, Connector, ModuleVersion } from '../types';

type ModuleVersionOption = ModuleVersion & {
  moduleName: string;
  moduleType: CodeModule['moduleType'];
};

type GraphEdge = {
  from: string;
  to: string;
};

type PipelineRunView = {
  id: string;
  status: 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'CANCELLED';
  startedAt: string;
  completedAt?: string;
  errorMessage?: string;
  moduleLogs?: Array<{
    stage?: string;
    nodeId?: string;
    moduleName?: string;
    stdout?: string;
    stderr?: string;
    success?: boolean;
  }>;
  executionLog?: string[];
};

export default function PipelineEditor() {
  const { t } = useTranslation();
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
  const [importModuleIds, setImportModuleIds] = useState<string[]>([]);
  const [importConnectorIds, setImportConnectorIds] = useState<string[]>([]);
  const [processingModuleIds, setProcessingModuleIds] = useState<string[]>([]);
  const [alertModuleIds, setAlertModuleIds] = useState<string[]>([]);
  const [exportModuleIds, setExportModuleIds] = useState<string[]>([]);
  const [exportConnectorIds, setExportConnectorIds] = useState<string[]>([]);
  const [processingMergeFlags, setProcessingMergeFlags] = useState<boolean[]>([]);
  const [graphEdges, setGraphEdges] = useState<GraphEdge[]>([]);
  const [pipelineRuns, setPipelineRuns] = useState<PipelineRunView[]>([]);
  const [selectedRunId, setSelectedRunId] = useState<string>('');
  const [loadingRuns, setLoadingRuns] = useState(false);
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
          const graphNodes = pipelineData.graphDefinition?.nodes || [];
          const importNodes = graphNodes.filter((node) => node.type === 'IMPORT');
          const parsedImportModuleIds = importNodes
            .map((node) => (typeof node.moduleVersionId === 'string' ? node.moduleVersionId : ''))
            .filter(Boolean);
          const parsedImportConnectorIds = importNodes
            .map((node) => (typeof node.connectorId === 'string' ? node.connectorId : ''));
          if (parsedImportModuleIds.length > 0) {
            setImportModuleIds(parsedImportModuleIds);
            setImportConnectorIds(parsedImportConnectorIds);
          } else {
            setImportModuleIds(pipelineData.importModuleVersionId ? [pipelineData.importModuleVersionId] : []);
            setImportConnectorIds([]);
          }
          setProcessingModuleIds(pipelineData.processingModuleVersionIds || []);
          const processingNodes = (pipelineData.graphDefinition?.nodes || []).filter((node) => node.type === 'PROCESSING');
          const parsedMergeFlags = processingNodes.map((node) => {
            if (typeof node.allowMultiInput === 'boolean') return node.allowMultiInput;
            if (typeof node.isMergeNode === 'boolean') return node.isMergeNode;
            return false;
          });
          setProcessingMergeFlags(parsedMergeFlags);
          const alertNodes = (pipelineData.graphDefinition?.nodes || []).filter((node) => node.type === 'ALERT');
          const parsedAlertModuleIds = alertNodes
            .map((node) => (typeof node.moduleVersionId === 'string' ? node.moduleVersionId : ''))
            .filter(Boolean);
          setAlertModuleIds(parsedAlertModuleIds);
          setExportModuleIds(pipelineData.exportModuleVersionIds || []);
          setIsActive(pipelineData.isActive);

          const exportNodes = graphNodes.filter((node) => node.type === 'EXPORT');
          const parsedExportConnectorIds = exportNodes.map((node) => (
            typeof node.connectorId === 'string' ? node.connectorId : ''
          ));
          setExportConnectorIds(parsedExportConnectorIds);
          const parsedEdges = (pipelineData.graphDefinition?.edges || [])
            .map((edge) => ({
              from: typeof edge.from === 'string' ? edge.from : '',
              to: typeof edge.to === 'string' ? edge.to : '',
            }))
            .filter((edge) => edge.from && edge.to);
          setGraphEdges(parsedEdges);
        } else {
          setName('New Pipeline');
          setDescription('');
          setImportModuleIds([]);
          setImportConnectorIds([]);
          setProcessingModuleIds([]);
          setProcessingMergeFlags([]);
          setAlertModuleIds([]);
          setExportModuleIds([]);
          setExportConnectorIds([]);
          setGraphEdges([]);
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

  useEffect(() => {
    if (!id || id === 'new' || !canReadPipelines) return;
    void loadRuns(id);
  }, [id, canReadPipelines]);

  useEffect(() => {
    if (!id || id === 'new') return;
    const hasActiveRun = pipelineRuns.some((run) => run.status === 'RUNNING' || run.status === 'PENDING');
    if (!hasActiveRun) return;
    const interval = window.setInterval(() => {
      void loadRuns(id);
    }, 4000);
    return () => window.clearInterval(interval);
  }, [id, pipelineRuns]);

  const showToast = (message: string, type: 'success' | 'error') => {
    setToast({ message, type });
    setTimeout(() => setToast(null), 3500);
  };

  const loadRuns = async (pipelineId: string) => {
    setLoadingRuns(true);
    try {
      const runs = await api.getPipelineRuns(pipelineId, { limit: 10, includeDetails: true });
      setPipelineRuns(runs as PipelineRunView[]);
      if (runs.length > 0) {
        setSelectedRunId((prev) => (prev && runs.some((run) => run.id === prev) ? prev : runs[0].id));
      } else {
        setSelectedRunId('');
      }
    } catch {
      setPipelineRuns([]);
    } finally {
      setLoadingRuns(false);
    }
  };

  const configuredNodes = useMemo(() => {
    const nodes: Array<{ id: string; type: 'IMPORT' | 'PROCESSING' | 'ALERT' | 'EXPORT'; label: string; allowMultiInput?: boolean }> = [];
    importModuleIds.forEach((moduleVersionId, index) => {
      if (!moduleVersionId) return;
      nodes.push({ id: `n-import-${index + 1}`, type: 'IMPORT', label: `Import ${index + 1}` });
    });
    processingModuleIds.forEach((moduleVersionId, index) => {
      if (!moduleVersionId) return;
      nodes.push({
        id: `n-processing-${index + 1}`,
        type: 'PROCESSING',
        label: `Processing ${index + 1}`,
        allowMultiInput: Boolean(processingMergeFlags[index]),
      });
    });
    alertModuleIds.forEach((moduleVersionId, index) => {
      if (!moduleVersionId) return;
      nodes.push({ id: `n-alert-${index + 1}`, type: 'ALERT', label: `Alert ${index + 1}` });
    });
    exportModuleIds.forEach((moduleVersionId, index) => {
      if (!moduleVersionId) return;
      nodes.push({ id: `n-export-${index + 1}`, type: 'EXPORT', label: `Export ${index + 1}` });
    });
    return nodes;
  }, [importModuleIds, processingModuleIds, processingMergeFlags, alertModuleIds, exportModuleIds]);

  useEffect(() => {
    setProcessingMergeFlags((previous) => {
      if (previous.length === processingModuleIds.length) return previous;
      return processingModuleIds.map((_, index) => previous[index] || false);
    });
  }, [processingModuleIds]);

  useEffect(() => {
    setImportConnectorIds((previous) => {
      if (previous.length === importModuleIds.length) return previous;
      return importModuleIds.map((_, index) => previous[index] || '');
    });
  }, [importModuleIds]);

  useEffect(() => {
    const validNodeIds = new Set(configuredNodes.map((node) => node.id));
    setGraphEdges((previous) => previous.filter((edge) => validNodeIds.has(edge.from) && validNodeIds.has(edge.to)));
  }, [configuredNodes]);

  const handleSave = async () => {
    if (!tenantId || !name || !canWritePipelines) return;

    try {
      const importVersionIds = importModuleIds.filter(Boolean);
      const processingVersionIds = processingModuleIds.filter(Boolean);
      const alertVersionIds = alertModuleIds.filter(Boolean);
      const exportVersionIds = exportModuleIds.filter(Boolean);
      const availableVersionIds = new Set(moduleVersionOptions.map((option) => option.id));

      if (importVersionIds.length === 0) {
        showToast('Add at least one import module version before saving', 'error');
        return;
      }
      if (importVersionIds.some((id) => !availableVersionIds.has(id))) {
        showToast('One or more selected import module versions are no longer available. Reload and choose another version.', 'error');
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
      const missingImportConnectorIndex = importVersionIds.findIndex((_, index) => !(importConnectorIds[index] || '').trim());
      if (missingImportConnectorIndex >= 0) {
        showToast(`Select an input connector for import module #${missingImportConnectorIndex + 1}`, 'error');
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
      importVersionIds.forEach((moduleVersionId, index) => {
        graphNodes.push({
          id: `n-import-${index + 1}`,
          type: 'IMPORT',
          moduleVersionId,
          connectorId: importConnectorIds[index] || undefined,
        });
      });
      processingVersionIds.forEach((moduleVersionId, index) => {
        graphNodes.push({
          id: `n-processing-${index + 1}`,
          type: 'PROCESSING',
          moduleVersionId,
          allowMultiInput: Boolean(processingMergeFlags[index]),
        });
      });
      alertVersionIds.forEach((moduleVersionId, index) => {
        graphNodes.push({ id: `n-alert-${index + 1}`, type: 'ALERT', moduleVersionId });
      });
      exportVersionIds.forEach((moduleVersionId, index) => {
        graphNodes.push({
          id: `n-export-${index + 1}`,
          type: 'EXPORT',
          moduleVersionId,
          connectorId: exportConnectorIds[index] || undefined,
        });
      });

      const validNodeIds = new Set(graphNodes.map((node) => String(node.id)));
      let selectedEdges = graphEdges
        .map((edge) => ({ from: edge.from.trim(), to: edge.to.trim() }))
        .filter((edge) => edge.from && edge.to && edge.from !== edge.to)
        .filter((edge) => validNodeIds.has(edge.from) && validNodeIds.has(edge.to));

      if (selectedEdges.length === 0) {
        const importNodeIds = graphNodes.filter((node) => node.type === 'IMPORT').map((node) => String(node.id));
        const processingNodeId = graphNodes.find((node) => node.type === 'PROCESSING')?.id as string | undefined;
        const exportNodeIds = graphNodes.filter((node) => node.type === 'EXPORT').map((node) => String(node.id));
        const alertNodeIds = graphNodes.filter((node) => node.type === 'ALERT').map((node) => String(node.id));
        if (processingNodeId) {
          importNodeIds.forEach((importNodeId) => selectedEdges.push({ from: importNodeId, to: processingNodeId }));
          exportNodeIds.forEach((id) => selectedEdges.push({ from: processingNodeId, to: id }));
          alertNodeIds.forEach((id) => selectedEdges.push({ from: processingNodeId, to: id }));
        } else {
          importNodeIds.forEach((importNodeId) => {
            exportNodeIds.forEach((id) => selectedEdges.push({ from: importNodeId, to: id }));
            alertNodeIds.forEach((id) => selectedEdges.push({ from: importNodeId, to: id }));
          });
        }
      }

      const edgeKey = (edge: GraphEdge) => `${edge.from}->${edge.to}`;
      const uniqueEdgeMap = new Map<string, GraphEdge>();
      selectedEdges.forEach((edge) => {
        uniqueEdgeMap.set(edgeKey(edge), edge);
      });
      selectedEdges = Array.from(uniqueEdgeMap.values());

      const indegree = new Map<string, number>();
      const outdegree = new Map<string, number>();
      graphNodes.forEach((node) => {
        indegree.set(String(node.id), 0);
        outdegree.set(String(node.id), 0);
      });
      selectedEdges.forEach((edge) => {
        indegree.set(edge.to, (indegree.get(edge.to) || 0) + 1);
        outdegree.set(edge.from, (outdegree.get(edge.from) || 0) + 1);
      });
      for (const node of graphNodes) {
        const nodeId = String(node.id);
        const nodeType = String(node.type);
        const incoming = indegree.get(nodeId) || 0;
        const outgoing = outdegree.get(nodeId) || 0;
        if (nodeType === 'IMPORT' && incoming > 0) {
          showToast('Import nodes cannot have incoming connections', 'error');
          return;
        }
        if (nodeType === 'IMPORT' && outgoing === 0) {
          showToast('Import node requires at least one outgoing connection', 'error');
          return;
        }
        if (nodeType === 'PROCESSING') {
          const allowMultiInput = Boolean((node as { allowMultiInput?: boolean }).allowMultiInput);
          if (incoming === 0) {
            showToast('Every processing node needs an incoming connection', 'error');
            return;
          }
          if (!allowMultiInput && incoming > 1) {
            showToast(`Processing node ${nodeId} has multiple inputs; enable "Merge/Join node" for it`, 'error');
            return;
          }
          if (outgoing === 0) {
            showToast('Every processing node needs at least one outgoing connection', 'error');
            return;
          }
        }
        if ((nodeType === 'ALERT' || nodeType === 'EXPORT') && incoming === 0) {
          showToast(`${nodeType} nodes need an incoming connection`, 'error');
          return;
        }
        if ((nodeType === 'ALERT' || nodeType === 'EXPORT') && outgoing > 0) {
          showToast(`${nodeType} nodes cannot have outgoing connections`, 'error');
          return;
        }
      }

      const graphDefinition = {
        nodes: graphNodes,
        edges: selectedEdges,
      };
    
      setSaving(true);
      if (id === 'new') {
        const newPipeline = await api.createPipeline(tenantId, name, {
          description,
          importModuleVersionId: importVersionIds[0] || undefined,
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
          importModuleVersionId: importVersionIds[0] || undefined,
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
      await loadRuns(id);
    } catch (error) {
      console.error('Error running pipeline:', error);
      showToast('Failed to trigger pipeline', 'error');
    }
  };

  const addProcessingModule = () => {
    setProcessingModuleIds([...processingModuleIds, '']);
    setProcessingMergeFlags([...processingMergeFlags, false]);
  };

  const addImportModule = () => {
    setImportModuleIds([...importModuleIds, '']);
    setImportConnectorIds([...importConnectorIds, '']);
  };

  const updateImportModule = (index: number, value: string) => {
    const updated = [...importModuleIds];
    updated[index] = value;
    setImportModuleIds(updated);
  };

  const removeImportModule = (index: number) => {
    setImportModuleIds(importModuleIds.filter((_, i) => i !== index));
    setImportConnectorIds(importConnectorIds.filter((_, i) => i !== index));
  };

  const updateImportConnector = (index: number, connectorId: string) => {
    const updated = [...importConnectorIds];
    updated[index] = connectorId;
    setImportConnectorIds(updated);
  };

  const updateProcessingModule = (index: number, value: string) => {
    const updated = [...processingModuleIds];
    updated[index] = value;
    setProcessingModuleIds(updated);
  };

  const removeProcessingModule = (index: number) => {
    setProcessingModuleIds(processingModuleIds.filter((_, i) => i !== index));
    setProcessingMergeFlags(processingMergeFlags.filter((_, i) => i !== index));
  };

  const updateProcessingMergeFlag = (index: number, enabled: boolean) => {
    const updated = [...processingMergeFlags];
    updated[index] = enabled;
    setProcessingMergeFlags(updated);
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

  const addEdge = () => {
    const defaultFrom = configuredNodes.find((node) => node.type === 'IMPORT' || node.type === 'PROCESSING')?.id || '';
    const defaultTo = configuredNodes.find((node) => node.type === 'PROCESSING' || node.type === 'ALERT' || node.type === 'EXPORT')?.id || '';
    setGraphEdges([...graphEdges, { from: defaultFrom, to: defaultTo }]);
  };

  const updateEdge = (index: number, patch: Partial<GraphEdge>) => {
    const updated = [...graphEdges];
    updated[index] = { ...updated[index], ...patch };
    setGraphEdges(updated);
  };

  const removeEdge = (index: number) => {
    setGraphEdges(graphEdges.filter((_, i) => i !== index));
  };

  const importVersionOptions = moduleVersionOptions.filter(v => v.moduleType === 'IMPORT');
  const processingVersionOptions = moduleVersionOptions.filter(v => v.moduleType === 'PROCESSING');
  const alertVersionOptions = moduleVersionOptions.filter(v => v.moduleType === 'ALERT');
  const exportVersionOptions = moduleVersionOptions.filter(v => v.moduleType === 'EXPORT');
  const importConnectors = connectors.filter((c) => c.direction === 'INPUT' || c.direction === 'BOTH');
  const exportConnectors = connectors.filter((c) => c.direction === 'OUTPUT' || c.direction === 'BOTH');
  const pipelineHealthChecks = useMemo(() => {
    const availableVersionIds = new Set(moduleVersionOptions.map((option) => option.id));
    const selectedImports = importModuleIds.filter(Boolean);
    const selectedImportConnectors = importConnectorIds.map((id) => id.trim()).filter(Boolean);
    const selectedProcessing = processingModuleIds.filter(Boolean);
    const selectedAlert = alertModuleIds.filter(Boolean);
    const selectedExport = exportModuleIds.filter(Boolean);
    const selectedExportConnectors = exportConnectorIds.map((id) => id.trim()).filter(Boolean);

    return [
      {
        label: 'At least one import module selected',
        ok: selectedImports.length > 0,
      },
      {
        label: 'All import modules are available',
        ok: selectedImports.every((id) => availableVersionIds.has(id)),
      },
      {
        label: 'Import connectors selected for each import module',
        ok: selectedImports.every((_, index) => Boolean((importConnectorIds[index] || '').trim())),
      },
      {
        label: 'Import connectors are unique per import module instance',
        ok: new Set(selectedImportConnectors).size === selectedImportConnectors.length,
      },
      {
        label: 'At least one processing module selected',
        ok: selectedProcessing.length > 0,
      },
      {
        label: 'All processing modules are available',
        ok: selectedProcessing.every((id) => availableVersionIds.has(id)),
      },
      {
        label: 'All alert modules are available',
        ok: selectedAlert.every((id) => availableVersionIds.has(id)),
      },
      {
        label: 'At least one export module selected',
        ok: selectedExport.length > 0,
      },
      {
        label: 'Export connectors selected for each export module',
        ok: selectedExport.every((_, index) => Boolean((exportConnectorIds[index] || '').trim())),
      },
      {
        label: 'Export connectors are unique (no duplicate deliveries)',
        ok: new Set(selectedExportConnectors).size === selectedExportConnectors.length,
      },
    ];
  }, [
    moduleVersionOptions,
    importModuleIds,
    importConnectorIds,
    processingModuleIds,
    alertModuleIds,
    exportModuleIds,
    exportConnectorIds,
  ]);

  const selectedRun = useMemo(() => {
    if (pipelineRuns.length === 0) return null;
    return pipelineRuns.find((run) => run.id === selectedRunId) || pipelineRuns[0];
  }, [pipelineRuns, selectedRunId]);

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
          {id === 'new' ? t('pipelineEditor.createPipeline') : t('pipelineEditor.editPipeline')}
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
            <h2 className="text-lg font-semibold mb-4">{t('pipelineEditor.basicInformation')}</h2>
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
            <div className="flex justify-between items-center mb-4">
              <h2 className="text-lg font-semibold">Import Modules</h2>
              <button
                type="button"
                onClick={addImportModule}
                className="text-sm text-primary-600 hover:text-primary-700"
                disabled={!canWritePipelines}
              >
                + Add Module
              </button>
            </div>
            {importModuleIds.length > 0 ? (
              <div className="space-y-2">
                {importModuleIds.map((moduleId, index) => (
                  <div key={index} className="space-y-2">
                    <div className="flex space-x-2">
                      <select
                        value={moduleId}
                        onChange={(e) => updateImportModule(index, e.target.value)}
                        className="input flex-1"
                        disabled={!canWritePipelines}
                      >
                        <option value="">Select module</option>
                        {importVersionOptions.map((version) => (
                          <option key={version.id} value={version.id}>
                            {version.moduleName} (v{version.version}{version.versionTag ? ` - ${version.versionTag}` : ''})
                          </option>
                        ))}
                      </select>
                      <button
                        type="button"
                        onClick={() => removeImportModule(index)}
                        className="text-red-600 hover:text-red-700"
                        disabled={!canWritePipelines}
                      >
                        Remove
                      </button>
                    </div>
                    <select
                      value={importConnectorIds[index] || ''}
                      onChange={(e) => updateImportConnector(index, e.target.value)}
                      className="input w-full"
                      disabled={!canWritePipelines}
                    >
                      <option value="">Select connector</option>
                      {importConnectors.map((connector) => (
                        <option key={connector.id} value={connector.id}>
                          {connector.name} ({connector.connectorType})
                        </option>
                      ))}
                    </select>
                    <p className="text-[11px] text-gray-500">
                      The selected connector handles transport. The module only transforms payload data and receives this connector config at runtime.
                    </p>
                  </div>
                ))}
              </div>
            ) : (
              <p className="text-sm text-gray-500">No import modules added.</p>
            )}
            {importVersionOptions.length === 0 && (
              <p className="text-sm text-gray-500 mt-2">
                No import modules available. Create one in the Modules section.
              </p>
            )}
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
                  <div key={index} className="space-y-2">
                    <div className="flex space-x-2">
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
                    <label className="flex items-center gap-2 text-xs text-gray-600">
                      <input
                        type="checkbox"
                        checked={Boolean(processingMergeFlags[index])}
                        onChange={(e) => updateProcessingMergeFlag(index, e.target.checked)}
                        disabled={!canWritePipelines}
                      />
                      Merge/Join node (allow multiple incoming connections)
                    </label>
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

          <div className="card">
            <div className="flex justify-between items-center mb-4">
              <h2 className="text-lg font-semibold">Pipeline Connections</h2>
              <button
                type="button"
                onClick={addEdge}
                className="text-sm text-primary-600 hover:text-primary-700"
                disabled={!canWritePipelines || configuredNodes.length < 2}
              >
                + Add Connection
              </button>
            </div>
            {graphEdges.length > 0 ? (
              <div className="space-y-2">
                {graphEdges.map((edge, index) => (
                  <div key={`${edge.from}-${edge.to}-${index}`} className="flex items-center gap-2">
                    <select
                      value={edge.from}
                      onChange={(e) => updateEdge(index, { from: e.target.value })}
                      className="input flex-1"
                      disabled={!canWritePipelines}
                    >
                      <option value="">From node</option>
                      {configuredNodes
                        .filter((node) => node.type === 'IMPORT' || node.type === 'PROCESSING')
                        .map((node) => (
                          <option key={node.id} value={node.id}>{node.label} ({node.type})</option>
                        ))}
                    </select>
                    <span className="text-gray-400">→</span>
                    <select
                      value={edge.to}
                      onChange={(e) => updateEdge(index, { to: e.target.value })}
                      className="input flex-1"
                      disabled={!canWritePipelines}
                    >
                      <option value="">To node</option>
                      {configuredNodes
                        .filter((node) => node.type === 'PROCESSING' || node.type === 'ALERT' || node.type === 'EXPORT')
                        .map((node) => (
                          <option key={node.id} value={node.id}>{node.label} ({node.type})</option>
                        ))}
                    </select>
                    <button
                      type="button"
                      onClick={() => removeEdge(index)}
                      className="text-red-600 hover:text-red-700"
                      disabled={!canWritePipelines}
                    >
                      Remove
                    </button>
                  </div>
                ))}
              </div>
            ) : (
              <p className="text-sm text-gray-500">No explicit connections yet. Default wiring is generated on save.</p>
            )}
            <p className="mt-3 text-xs text-gray-500">
              Rules: Import only outgoing, Processing incoming + outgoing, Alert/Export incoming only. Enable Merge/Join on processing nodes to allow multiple inputs.
            </p>
          </div>
        </div>

        <div className="space-y-6">
          <div className="card">
            <h2 className="text-lg font-semibold mb-4">{t('pipelineEditor.pipelineInfo')}</h2>
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
            <h2 className="text-lg font-semibold mb-4">{t('pipelineEditor.configurationHealth')}</h2>
            <div className="space-y-2 text-sm">
              {pipelineHealthChecks.map((check) => (
                <div key={check.label} className="flex items-start justify-between gap-3">
                  <span className="text-gray-700">{check.label}</span>
                  <span className={check.ok ? 'text-emerald-600 font-medium' : 'text-amber-700 font-medium'}>
                    {check.ok ? 'OK' : 'Needs attention'}
                  </span>
                </div>
              ))}
            </div>
          </div>

          <div className="card">
            <div className="flex items-center justify-between mb-3">
              <h2 className="text-lg font-semibold">Run Logs</h2>
              {id !== 'new' && (
                <button
                  type="button"
                  onClick={() => { if (id) void loadRuns(id); }}
                  className="text-xs px-2 py-1 rounded border border-gray-300 bg-white"
                  disabled={loadingRuns}
                >
                  {loadingRuns ? 'Refreshing...' : 'Refresh'}
                </button>
              )}
            </div>
            {id === 'new' ? (
              <p className="text-sm text-gray-500">Save pipeline first to run and inspect logs.</p>
            ) : pipelineRuns.length === 0 ? (
              <p className="text-sm text-gray-500">No runs yet.</p>
            ) : (
              <div className="space-y-3 text-xs">
                <div className="flex flex-wrap gap-2">
                  {pipelineRuns.map((run) => (
                    <button
                      key={run.id}
                      type="button"
                      onClick={() => setSelectedRunId(run.id)}
                      className={`px-2 py-1 rounded border ${selectedRun?.id === run.id ? 'border-primary-500 bg-primary-50 text-primary-700' : 'border-gray-300 bg-white text-gray-700'}`}
                    >
                      {run.status} {new Date(run.startedAt).toLocaleTimeString()}
                    </button>
                  ))}
                </div>
                {selectedRun && (
                  <div className="space-y-2">
                    <div className="text-gray-600">
                      <strong>Status:</strong> {selectedRun.status}
                      {selectedRun.errorMessage ? ` • ${selectedRun.errorMessage}` : ''}
                    </div>
                    {Array.isArray(selectedRun.executionLog) && selectedRun.executionLog.length > 0 && (
                      <div>
                        <div className="font-medium text-gray-700 mb-1">Execution</div>
                        <pre className="max-h-28 overflow-auto rounded bg-gray-50 border border-gray-200 p-2 text-[11px] text-gray-700 whitespace-pre-wrap">{selectedRun.executionLog.join('\n')}</pre>
                      </div>
                    )}
                    {Array.isArray(selectedRun.moduleLogs) && selectedRun.moduleLogs.length > 0 ? (
                      <div className="space-y-2">
                        {selectedRun.moduleLogs.map((log, index) => (
                          <div key={`${selectedRun.id}-module-${index}`} className="rounded border border-gray-200 p-2 bg-white">
                            <div className="text-gray-700 font-medium mb-1">
                              {log.stage || 'MODULE'} {log.nodeId ? `• ${log.nodeId}` : ''} {log.moduleName ? `• ${log.moduleName}` : ''}
                            </div>
                            <div className="grid grid-cols-1 gap-2">
                              <div>
                                <div className="font-medium text-gray-700 mb-1">stdout</div>
                                <pre className="max-h-24 overflow-auto rounded bg-gray-50 border border-gray-200 p-2 text-[11px] text-gray-700 whitespace-pre-wrap">{log.stdout || '(empty)'}</pre>
                              </div>
                              <div>
                                <div className="font-medium text-gray-700 mb-1">stderr</div>
                                <pre className="max-h-24 overflow-auto rounded bg-gray-50 border border-gray-200 p-2 text-[11px] text-gray-700 whitespace-pre-wrap">{log.stderr || '(empty)'}</pre>
                              </div>
                            </div>
                          </div>
                        ))}
                      </div>
                    ) : (
                      <p className="text-gray-500">No module logs in selected run.</p>
                    )}
                  </div>
                )}
              </div>
            )}
          </div>

          <div className="card">
            <h2 className="text-lg font-semibold mb-4">Help</h2>
            <div className="text-sm text-gray-600 space-y-2">
              <p><strong>Import Module:</strong> Handles inbound payload data; connector transport is platform-managed</p>
              <p><strong>Processing Modules:</strong> Transform data (run in order)</p>
              <p><strong>Alert Modules:</strong> Evaluate conditions and emit alert events</p>
              <p><strong>Export Modules:</strong> Shape outbound payload; connector transport is platform-managed</p>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
