import { useCallback, useEffect, useMemo, useRef, useState, memo } from 'react';
import { useParams, useNavigate, Link } from 'react-router';
import { useAuthStore } from '../stores/auth';
import api, { type ApiError } from '../services/api';
import type { AlertChannel, Pipeline, CodeModule, Connector, EnrichConfig, MetadataType, ModuleVersion, PipelineRun, SaveMetadataMapping } from '../types';
import { EnrichForm, SaveMetadataForm } from '../components/MetadataNodeForms';
import { DEFAULT_ENRICH, DEFAULT_SAVE_METADATA, validateMetadataNode } from '../lib/metadataNodes';
import AlertRuleForm from '../components/AlertRuleForm';
import { DEFAULT_RULES, RULE_TYPES, validateAlertRule, type AlertRuleConfig } from '../lib/alertRules';
import {
  ReactFlow,
  Background,
  Controls,
  addEdge,
  useNodesState,
  useEdgesState,
  Handle,
  Position,
  /**
   * Defines the type `NodeProps`, the contract implemented by this source unit.
   */
  type NodeProps,
  /**
   * Defines the type `Connection`, the contract implemented by this source unit.
   */
  type Connection,
  /**
   * Defines the type `Node`, the contract implemented by this source unit.
   */
  type Node,
  /**
   * Defines the type `Edge`, the contract implemented by this source unit.
   */
  type Edge,
  /**
   * Defines the type `NodeTypes`, the contract implemented by this source unit.
   */
  type NodeTypes,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';

// ─── Types ────────────────────────────────────────────────────────────────────

/**
 * Defines the type `NodeType`, the contract implemented by this source unit.
 */
type NodeType = 'IMPORT' | 'PROCESSING' | 'MERGE' | 'ALERT' | 'EXPORT';

/**
 * Defines the interface `PipelineNodeData`, the contract implemented by this source unit.
 */
interface PipelineNodeData extends Record<string, unknown> {
  nodeType: NodeType;
  label: string;
  moduleId?: string;
  moduleVersionId?: string;
  connectorId?: string;
  /** MQTT topic scoped to this pipeline node (connector only defines the broker) */
  mqttTopic?: string;
  /** ALERT nodes: default alert channel id when an alert names none */
  alertChannel?: string;
  /** ALERT nodes: built-in rule evaluated by the platform instead of a Python module */
  alertRule?: AlertRuleConfig;
  /** IMPORT nodes: forward records as received instead of running a Python module */
  passThrough?: boolean;
  /** IMPORT nodes: fields that identify a record, so resent data is processed once */
  deduplicateBy?: string[];
  /** PROCESSING nodes: built-in metadata enrichment instead of a Python module */
  enrich?: EnrichConfig;
  /** EXPORT nodes: records create or update metadata objects instead of going to a connector */
  saveMetadata?: SaveMetadataMapping;
}

/** Nodes that run a Python module (as opposed to a built-in step or pass-through). */
const usesModule = (d: PipelineNodeData) => !d.alertRule && !d.passThrough && !d.enrich && !d.saveMetadata;

/**
 * Defines the type `ModuleVersionOption`, the contract implemented by this source unit.
 */
type ModuleVersionOption = ModuleVersion & {
  moduleName: string;
  moduleType: CodeModule['moduleType'];
};

/**
 * Defines the type `ModuleOption`, the contract implemented by this source unit.
 */
type ModuleOption = Pick<CodeModule, 'id' | 'name' | 'moduleType' | 'defaultVersionId'>;

/**
 * Defines the type `DagNodeType`, the contract implemented by this source unit.
 */
type DagNodeType = Node<PipelineNodeData, 'dagNode'>;

// ─── Node colour map ──────────────────────────────────────────────────────────

const NODE_COLORS: Record<NodeType, string> = {
  IMPORT:     'border-emerald-500 bg-emerald-50',
  PROCESSING: 'border-blue-500 bg-blue-50',
  MERGE:      'border-violet-500 bg-violet-50',
  ALERT:      'border-amber-500 bg-amber-50',
  EXPORT:     'border-rose-500 bg-rose-50',
};

const NODE_ICON: Record<NodeType, string> = {
  IMPORT:     '↓',
  PROCESSING: '⚙',
  MERGE:      '⊕',
  ALERT:      '⚠',
  EXPORT:     '↑',
};

// ─── Custom DAG node ──────────────────────────────────────────────────────────

/**
 * Implements `DagNode` for this source unit.
 */
function DagNode({ data, selected }: NodeProps<DagNodeType>) {
  const d = data as PipelineNodeData;
  const colors = NODE_COLORS[d.nodeType] ?? 'border-gray-400 bg-gray-50';
  const isSource = d.nodeType === 'IMPORT';
  const isTarget = d.nodeType === 'ALERT' || d.nodeType === 'EXPORT';
  const isMerge  = d.nodeType === 'MERGE';

  return (
    <div
      className={`rounded-lg border-2 ${colors} ${selected ? 'ring-2 ring-offset-1 ring-primary-500' : ''} min-w-[160px] shadow-xs`}
    >
      {/* Incoming handle (all except IMPORT) */}
      {!isSource && (
        <Handle
          type="target"
          position={Position.Left}
          id={isMerge ? 'in-a' : 'in'}
          style={{ top: isMerge ? '35%' : '50%', background: '#64748b' }}
        />
      )}
      {/* Second incoming handle for MERGE */}
      {isMerge && (
        <Handle
          type="target"
          position={Position.Left}
          id="in-b"
          style={{ top: '65%', background: '#64748b' }}
        />
      )}

      <div className="px-3 py-2">
        <div className="flex items-center gap-1 mb-0.5">
          <span className="text-base leading-none">{NODE_ICON[d.nodeType]}</span>
          <span className="text-[11px] font-semibold uppercase tracking-wide text-gray-500">{d.nodeType}</span>
        </div>
        <div className="text-sm font-medium text-gray-800 truncate max-w-[140px]" title={d.label}>
          {d.label}
        </div>
        {d.connectorId && (
          <div className="text-[10px] text-gray-400 mt-0.5 truncate max-w-[140px]">⚡ connector</div>
        )}
        {d.passThrough && (
          <div className="text-[10px] text-gray-500 mt-0.5 truncate max-w-[140px]">↦ pass-through</div>
        )}
        {d.alertRule && (
          <div className="text-[10px] text-gray-500 mt-0.5 truncate max-w-[140px]">
            📏 {RULE_TYPES.find((t) => t.value === d.alertRule?.type)?.label ?? d.alertRule.type}
          </div>
        )}
      </div>

      {/* Outgoing handle (all except ALERT/EXPORT) */}
      {!isTarget && (
        <Handle
          type="source"
          position={Position.Right}
          id="out"
          style={{ background: '#64748b' }}
        />
      )}
    </div>
  );
}

const nodeTypes: NodeTypes = { dagNode: memo(DagNode) };

// ─── Helpers ──────────────────────────────────────────────────────────────────

/**
 * Implements `makeNodeId` for this source unit.
 */
function makeNodeId() {
  return `n-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`;
}

/**
 * Implements `graphToFlow` for this source unit.
 */
/**
 * Positions for graphs saved without them (e.g. created through the API): one column per
 * depth from the sources, nodes of a column stacked, so fan-out and fan-in stay visible.
 */
function layeredLayout(graph: NonNullable<Pipeline['graphDefinition']>): Map<string, { x: number; y: number }> {
  const ids = (graph.nodes ?? []).map((n, idx) => String(n.id ?? `n-${idx}`));
  const incoming = new Map(ids.map((id) => [id, [] as string[]]));
  for (const e of graph.edges ?? []) incoming.get(String(e.target))?.push(String(e.source));
  const depth = new Map<string, number>();
  const depthOf = (id: string, seen: Set<string>): number => {
    if (depth.has(id)) return depth.get(id)!;
    if (seen.has(id)) return 0; // cycle guard; the backend rejects cyclic graphs
    seen.add(id);
    const d = Math.max(-1, ...(incoming.get(id) ?? []).map((src) => depthOf(src, seen))) + 1;
    depth.set(id, d);
    return d;
  };
  const rows = new Map<number, number>();
  const positions = new Map<string, { x: number; y: number }>();
  for (const id of ids) {
    const d = depthOf(id, new Set());
    const row = rows.get(d) ?? 0;
    rows.set(d, row + 1);
    positions.set(id, { x: 80 + d * 260, y: 80 + row * 140 });
  }
  return positions;
}

function graphToFlow(
  graph: Pipeline['graphDefinition'],
): { nodes: DagNodeType[]; edges: Edge[] } {
  if (!graph) return { nodes: [], edges: [] };

  const layout = layeredLayout(graph);
  const nodes: DagNodeType[] = (graph.nodes ?? []).map((raw, idx) => ({
    id: String(raw.id ?? `n-${idx}`),
    type: 'dagNode',
    position: (raw.position as { x: number; y: number }) ?? layout.get(String(raw.id ?? `n-${idx}`)) ?? { x: 150 + idx * 200, y: 150 },
    data: {
      nodeType: raw.type as NodeType,
      label: String(raw.label ?? raw.type ?? 'Node'),
      moduleId: raw.moduleId as string | undefined,
      moduleVersionId: raw.moduleVersionId as string | undefined,
      connectorId: raw.connectorId as string | undefined,
      mqttTopic: raw.mqttTopic as string | undefined,
      alertChannel: raw.alertChannel as string | undefined,
      alertRule: raw.alertRule as AlertRuleConfig | undefined,
      passThrough: raw.type === 'IMPORT' && !raw.moduleVersionId,
      deduplicateBy: Array.isArray(raw.deduplicateBy) ? (raw.deduplicateBy as string[]) : undefined,
      enrich: raw.enrich as EnrichConfig | undefined,
      saveMetadata: raw.saveMetadata as SaveMetadataMapping | undefined,
    } satisfies PipelineNodeData,
  }));

  const edges: Edge[] = (graph.edges ?? []).map((raw, idx) => ({
    id: String(raw.id ?? `e-${idx}`),
    source: String(raw.source),
    target: String(raw.target),
    sourceHandle: (raw.sourceHandle as string) ?? 'out',
    targetHandle: (raw.targetHandle as string) ?? 'in',
  }));

  return { nodes, edges };
}

/**
 * Implements `flowToGraph` for this source unit.
 */
function flowToGraph(
  nodes: DagNodeType[],
  edges: Edge[],
): Pipeline['graphDefinition'] {
  return {
    nodes: nodes.map((n) => {
      const d = n.data;
      return {
        id: n.id,
        type: d.nodeType,
        label: d.label,
        moduleId: usesModule(d) ? d.moduleId : undefined,
        moduleVersionId: usesModule(d) ? d.moduleVersionId : undefined,
        mqttTopic: d.mqttTopic,
        alertChannel: d.alertChannel,
        alertRule: d.alertRule,
        deduplicateBy: d.nodeType === 'IMPORT' && d.deduplicateBy?.length ? d.deduplicateBy : undefined,
        enrich: d.nodeType === 'PROCESSING' ? d.enrich : undefined,
        saveMetadata: d.nodeType === 'EXPORT' ? d.saveMetadata : undefined,
        connectorId: d.saveMetadata ? undefined : d.connectorId,
        position: n.position,
      };
    }),
    edges: edges.map((e) => ({
      id: e.id,
      source: e.source,
      target: e.target,
      sourceHandle: e.sourceHandle,
      targetHandle: e.targetHandle,
    })),
  };
}

// ─── Component ────────────────────────────────────────────────────────────────

export default function PipelineEditor() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { user, selectedTenantId, tenantContexts } = useAuthStore();
  // Ref to current pipeline ID so polling closures always see the latest value
  const pipelineIdRef = useRef<string | undefined>(undefined);
  const canWritePipelines = Boolean(
    user?.superadmin || user?.abilities?.includes('*') || user?.abilities?.includes('pipeline:write')
  );
  const canExecutePipelines = Boolean(
    user?.superadmin || user?.abilities?.includes('*') || user?.abilities?.includes('pipeline:execute')
  );

  const [pipeline, setPipeline] = useState<Pipeline | null>(null);
  // Keep pipelineIdRef in sync so polling closures are never stale
  /**
   * Implements `useEffect` for this source unit.
   */
  useEffect(() => { pipelineIdRef.current = pipeline?.id; }, [pipeline?.id]);
  const [moduleVersionOptions, setModuleVersionOptions] = useState<ModuleVersionOption[]>([]);
  const [moduleOptions, setModuleOptions] = useState<ModuleOption[]>([]);
  const [connectors, setConnectors] = useState<Connector[]>([]);
  const [alertChannels, setAlertChannels] = useState<AlertChannel[]>([]);
  const [metadataTypes, setMetadataTypes] = useState<MetadataType[]>([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [triggering, setTriggering] = useState(false);
  const [toast, setToast] = useState<{ message: string; type: 'success' | 'error' } | null>(null);
  const [latestRun, setLatestRun] = useState<PipelineRun | null>(null);
  const [recentRuns, setRecentRuns] = useState<PipelineRun[]>([]);
  const [runPollingTimer, setRunPollingTimer] = useState<ReturnType<typeof setTimeout> | null>(null);

  // Pipeline metadata
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [isActive, setIsActive] = useState(false);

  // React Flow state — typed with our domain node type for inference
  const [nodes, setNodes, onNodesChange] = useNodesState<DagNodeType>([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState<Edge>([]);
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null);

  const tenantId = selectedTenantId || user?.tenantId || tenantContexts[0]?.tenantId;

  /**
   * Implements `showToast` for this source unit.
   */
  const showToast = (message: string, type: 'success' | 'error') => {
    setToast({ message, type });
    setTimeout(() => setToast(null), 3500);
  };

  // ── Load data ──────────────────────────────────────────────────────────────

  /**
   * Implements `useEffect` for this source unit.
   */
  useEffect(() => {
    /**
     * Implements `fetchData` for this source unit.
     */
    const fetchData = async () => {
      if (!id || !tenantId) { setLoading(false); return; }
      try {
        const [pipelineData, modulesData, connectorsData, alertChannelsData, metadataTypesData] = await Promise.all([
          id === 'new' ? Promise.resolve(null) : api.getPipeline(id),
          api.getModules(tenantId),
          api.getConnectors(tenantId),
          api.getAlertChannels(tenantId).catch(() => [] as AlertChannel[]),
          api.getMetadataTypes(tenantId).catch(() => [] as MetadataType[]),
        ]);

        const versionsByModule = await Promise.all(
          modulesData.map(async (m) => {
            const versions = await api.getModuleVersions(m.id);
            return versions.map((v) => ({ ...v, moduleName: m.name, moduleType: m.moduleType }));
          })
        );
        setModuleOptions(modulesData.map((m) => ({
          id: m.id,
          name: m.name,
          moduleType: m.moduleType,
          defaultVersionId: m.defaultVersionId,
        })));
        setModuleVersionOptions(versionsByModule.flat());
        setConnectors(connectorsData);
        setAlertChannels(alertChannelsData);
        setMetadataTypes(metadataTypesData);

        if (pipelineData) {
          setPipeline(pipelineData);
          setName(pipelineData.name);
          setDescription(pipelineData.description || '');
          setIsActive(pipelineData.isActive);
          const { nodes: fn, edges: fe } = graphToFlow(pipelineData.graphDefinition);
          setNodes(fn);
          setEdges(fe);
          if (pipelineData.latestRunId) {
            // Fetch recent runs and the latest with full details
            try {
              const [runs, run] = await Promise.all([
                api.getPipelineRuns(id!),
                api.getPipelineRun(id!, pipelineData.latestRunId),
              ]);
              setRecentRuns(runs.slice(0, 10));
              setLatestRun(run);
            } catch {
              // Non-critical: ignore if we can't load latest run
            }
          }
        } else {
          setName('New Pipeline');
        }
      } catch (err) {
        console.error('Failed to load pipeline:', err);
        showToast('Failed to load pipeline data', 'error');
      } finally {
        setLoading(false);
      }
    };
    fetchData();
  }, [id, tenantId]);

  // ── React Flow handlers ────────────────────────────────────────────────────

  const onConnect = useCallback(
    (connection: Connection) => setEdges((eds: Edge[]) => addEdge(connection, eds)),
    [setEdges],
  );

  const onNodeClick = useCallback((_: React.MouseEvent, node: Node) => {
    setSelectedNodeId(node.id);
  }, []);

  const onPaneClick = useCallback(() => {
    setSelectedNodeId(null);
  }, []);

  // ── Add node ───────────────────────────────────────────────────────────────

  /**
   * Creates or persists the requested state.
   */
  const addNode = (nodeType: NodeType) => {
    const newId = makeNodeId();
    const xOffset = nodes.length * 40;
    const newNode: Node = {
      id: newId,
      type: 'dagNode',
      position: { x: 100 + xOffset, y: 100 + (nodes.length % 3) * 120 },
      data: {
        nodeType,
        label: nodeType,
        moduleVersionId: undefined,
        connectorId: undefined,
        // Structured measurements need no parsing code; a module can still be chosen instead.
        passThrough: nodeType === 'IMPORT' ? true : undefined,
      } satisfies PipelineNodeData,
    };
    setNodes((nds: DagNodeType[]) => [...nds, newNode as DagNodeType]);
    setSelectedNodeId(newId);
  };

  /**
   * Removes, stops, or releases the requested state.
   */
  const deleteSelectedNode = () => {
    if (!selectedNodeId) return;
    setNodes((nds: DagNodeType[]) => nds.filter((n: DagNodeType) => n.id !== selectedNodeId));
    setEdges((eds: Edge[]) => eds.filter((e: Edge) => e.source !== selectedNodeId && e.target !== selectedNodeId));
    setSelectedNodeId(null);
  };

  // ── Selected node helpers ──────────────────────────────────────────────────

  const selectedNode = useMemo(
    () => nodes.find((n: DagNodeType) => n.id === selectedNodeId) ?? null,
    [nodes, selectedNodeId],
  );

  /**
   * Updates the requested state.
   */
  const updateSelectedNodeData = (patch: Partial<PipelineNodeData>) => {
    if (!selectedNodeId) return;
    setNodes((nds: DagNodeType[]) =>
      nds.map((n: DagNodeType) =>
        n.id === selectedNodeId
          ? { ...n, data: { ...n.data, ...patch } }
          : n,
      ),
    );
  };

  // ── Save ───────────────────────────────────────────────────────────────────

  /**
   * Processes the named request, event, or operation.
   */
  const handleSave = async () => {
    if (!tenantId || !name || !canWritePipelines) return;
    const invalidMetadataNode = nodes.find((n) => validateMetadataNode(n.data.enrich, n.data.saveMetadata));
    if (invalidMetadataNode) {
      showToast(`${invalidMetadataNode.data.label}: ${validateMetadataNode(invalidMetadataNode.data.enrich, invalidMetadataNode.data.saveMetadata)}`, 'error');
      return;
    }
    const invalidRuleNode = nodes.find((n) => n.data.alertRule && validateAlertRule(n.data.alertRule));
    if (invalidRuleNode) {
      showToast(`${invalidRuleNode.data.label}: ${validateAlertRule(invalidRuleNode.data.alertRule!)}`, 'error');
      setSelectedNodeId(invalidRuleNode.id);
      return;
    }
    const nodeWithoutModule = nodes.find((n) => usesModule(n.data) && !n.data.moduleVersionId);
    if (nodeWithoutModule) {
      showToast(`${nodeWithoutModule.data.label}: select a module version`, 'error');
      setSelectedNodeId(nodeWithoutModule.id);
      return;
    }
    setSaving(true);
    try {
      const graphDefinition = flowToGraph(nodes, edges);
      const payload = { name, description, graphDefinition, isActive };
      if (id && id !== 'new') {
        const updated = await api.updatePipeline(id, payload);
        setPipeline(updated);
        showToast('Pipeline saved', 'success');
      } else {
        const created = await api.createPipeline(tenantId, name, { description, graphDefinition });
        navigate(`/pipelines/${created.id}`);
        showToast('Pipeline created', 'success');
      }
    } catch (caught) {
      const err = caught as ApiError;
      console.error('Save failed:', err);
      showToast(err?.response?.data?.error ?? 'Failed to save pipeline', 'error');
    } finally {
      setSaving(false);
    }
  };

  // ── Trigger (manual run) ───────────────────────────────────────────────────

  /**
   * Processes the named request, event, or operation.
   */
  const handleTrigger = async () => {
    if (!pipeline || !canExecutePipelines) return;
    setTriggering(true);
    try {
      const run = await api.triggerPipeline(pipeline.id);
      setLatestRun(run);
      setRecentRuns((prev) => [run, ...prev].slice(0, 10));
      showToast('Pipeline triggered', 'success');
      // poll run status
      pollRunStatus(run.id);
    } catch (caught) {
      const err = caught as ApiError;
      showToast(err?.response?.data?.error ?? 'Failed to trigger pipeline', 'error');
    } finally {
      setTriggering(false);
    }
  };

  /**
   * Implements `pollRunStatus` for this source unit.
   */
  const pollRunStatus = (runId: string) => {
    const timer = setTimeout(async () => {
      const pid = pipelineIdRef.current;
      if (!pid) return;
      try {
        const run = await api.getPipelineRun(pid, runId);
        setLatestRun(run);
        setRecentRuns((prev) => {
          const exists = prev.some((r) => r.id === run.id);
          if (exists) return prev.map((r) => (r.id === run.id ? run : r));
          return [run, ...prev].slice(0, 10);
        });
        if (run.status === 'RUNNING' || run.status === 'PENDING') {
          pollRunStatus(runId);
        }
      } catch {
        // ignore polling errors
      }
    }, 2000);
    setRunPollingTimer(timer);
  };

  /**
   * Implements `useEffect` for this source unit.
   */
  useEffect(() => {
    return () => { if (runPollingTimer) clearTimeout(runPollingTimer); };
  }, [runPollingTimer]);

  // ── Background poll for externally-triggered runs (e.g. MQTT) ──────────────
  /**
   * Implements `useEffect` for this source unit.
   */
  useEffect(() => {
    if (!id || id === 'new' || !pipeline) return;
    const interval = setInterval(async () => {
      try {
        const p = await api.getPipeline(id);
        const newRunId = p.latestRunId;
        if (newRunId && newRunId !== latestRun?.id) {
          const run = await api.getPipelineRun(id, newRunId);
          setLatestRun(run);
          setRecentRuns((prev) => [run, ...prev.filter((r) => r.id !== run.id)].slice(0, 10));
          if (run.status === 'RUNNING' || run.status === 'PENDING') {
            pollRunStatus(run.id);
          }
        }
      } catch { /* ignore */ }
    }, 15000);
    return () => clearInterval(interval);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id, pipeline?.id, latestRun?.id]);

  // ── Render ─────────────────────────────────────────────────────────────────

  if (loading) {
    return (
      <div className="flex items-center justify-center h-screen">
        <div className="animate-spin rounded-full h-12 w-12 border-b-2 border-primary-600" />
      </div>
    );
  }

  const selectedData = selectedNode?.data as PipelineNodeData | undefined;
  const inputConnectors  = connectors.filter((c) => c.direction === 'INPUT');
  const outputConnectors = connectors.filter((c) => c.direction === 'OUTPUT');

  return (
    <div className="h-screen flex flex-col overflow-hidden">
      {/* Toast */}
      {toast && (
        <div className={`fixed top-4 right-4 z-50 px-4 py-3 rounded shadow-lg text-white text-sm ${
          toast.type === 'success' ? 'bg-green-600' : 'bg-red-600'
        }`}>
          {toast.message}
        </div>
      )}

      {/* Header bar */}
      <div className="flex items-center justify-between px-4 py-2 bg-white border-b shrink-0">
        <div className="flex items-center gap-3">
          <button onClick={() => navigate('/pipelines')} className="text-gray-500 hover:text-gray-700 text-sm">
            ← Pipelines
          </button>
          <input
            value={name}
            onChange={(e) => setName(e.target.value)}
            className="border rounded-sm px-2 py-1 text-sm font-semibold w-52"
            placeholder="Pipeline name"
            disabled={!canWritePipelines}
          />
          <input
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            className="border rounded-sm px-2 py-1 text-sm text-gray-500 w-72"
            placeholder="Description (optional)"
            disabled={!canWritePipelines}
          />
          <label className="flex items-center gap-1.5 text-sm cursor-pointer">
            <input
              type="checkbox"
              checked={isActive}
              onChange={(e) => setIsActive(e.target.checked)}
              disabled={!canWritePipelines}
              className="rounded-sm"
            />
            <span>Active</span>
          </label>
        </div>
        <div className="flex items-center gap-2">
          {pipeline && canExecutePipelines && (
            <button
              onClick={handleTrigger}
              disabled={triggering || !pipeline.isActive}
              className="btn-secondary text-sm"
              title={!pipeline.isActive ? 'Activate the pipeline to trigger it' : undefined}
            >
              {triggering ? 'Triggering…' : '▶ Run'}
            </button>
          )}
          <button
            onClick={handleSave}
            disabled={saving || !canWritePipelines}
            className="btn-primary text-sm"
          >
            {saving ? 'Saving…' : 'Save'}
          </button>
        </div>
      </div>

      {/* Node type toolbar */}
      {canWritePipelines && (
        <div className="flex items-center gap-2 px-4 py-1.5 bg-gray-50 border-b shrink-0 text-xs">
          <span className="text-gray-400 mr-1">Add node:</span>
          {(['IMPORT', 'PROCESSING', 'MERGE', 'ALERT', 'EXPORT'] as NodeType[]).map((nt) => (
            <button
              key={nt}
              onClick={() => addNode(nt)}
              className={`px-2 py-1 rounded-sm border font-medium ${NODE_COLORS[nt]} hover:opacity-80`}
            >
              {NODE_ICON[nt]} {nt}
            </button>
          ))}
          {selectedNodeId && (
            <button
              onClick={deleteSelectedNode}
              className="ml-auto px-2 py-1 rounded-sm border border-red-300 bg-red-50 text-red-700 hover:bg-red-100 font-medium"
            >
              Delete selected
            </button>
          )}
        </div>
      )}

      {/* Main area: canvas + side panel */}
      <div className="flex flex-1 overflow-hidden">
        {/* React Flow canvas */}
        <div className="flex-1 relative">
          <ReactFlow
            nodes={nodes}
            edges={edges}
            onNodesChange={onNodesChange}
            onEdgesChange={onEdgesChange}
            onConnect={onConnect}
            onNodeClick={onNodeClick}
            onPaneClick={onPaneClick}
            nodeTypes={nodeTypes}
            fitView
            deleteKeyCode="Delete"
            multiSelectionKeyCode="Shift"
          >
            <Background />
            <Controls />
          </ReactFlow>
          {nodes.length === 0 && (
            <div className="absolute inset-0 flex items-center justify-center pointer-events-none">
              <p className="text-gray-400 text-sm">
                Add nodes using the toolbar above, then connect them by dragging from node handles.
              </p>
            </div>
          )}
        </div>

        {/* Side panel */}
        <div className="w-72 border-l bg-white flex flex-col shrink-0 overflow-y-auto">
          {selectedData ? (
            <NodeConfigPanel
              nodeId={selectedNodeId!}
              data={selectedData}
              moduleOptions={moduleOptions}
              moduleVersionOptions={moduleVersionOptions}
              inputConnectors={inputConnectors}
              outputConnectors={outputConnectors}
              alertChannels={alertChannels}
              metadataTypes={metadataTypes}
              canWrite={canWritePipelines}
              onChange={updateSelectedNodeData}
            />
          ) : (
            <RunLogPanel latestRun={latestRun} recentRuns={recentRuns} pipelineId={pipeline?.id} />
          )}
        </div>
      </div>
    </div>
  );
}

// ─── Node config side panel ───────────────────────────────────────────────────

/**
 * Implements `NodeConfigPanel` for this source unit.
 */
function NodeConfigPanel({
  nodeId,
  data,
  moduleOptions,
  moduleVersionOptions,
  inputConnectors,
  outputConnectors,
  alertChannels,
  metadataTypes,
  canWrite,
  onChange,
}: {
  nodeId: string;
  data: PipelineNodeData;
  moduleOptions: ModuleOption[];
  moduleVersionOptions: ModuleVersionOption[];
  inputConnectors: Connector[];
  outputConnectors: Connector[];
  alertChannels: AlertChannel[];
  metadataTypes: MetadataType[];
  canWrite: boolean;
  onChange: (patch: Partial<PipelineNodeData>) => void;
}) {
  const compatibleTypes = data.nodeType === 'IMPORT'
    ? ['IMPORT', 'DB_LOAD']
    : data.nodeType === 'EXPORT'
      ? ['EXPORT', 'DB_SAVE']
      : [data.nodeType];
  const filteredModules = moduleOptions.filter((module) => compatibleTypes.includes(module.moduleType.toUpperCase()));
  const filteredVersions = moduleVersionOptions.filter(
    (v) => compatibleTypes.includes(v.moduleType.toUpperCase()) && (!data.moduleId || v.moduleId === data.moduleId),
  );

  return (
    <div className="p-4 space-y-4">
      <div>
        <span className={`inline-flex items-center gap-1 text-xs font-semibold px-2 py-0.5 rounded-sm ${NODE_COLORS[data.nodeType]}`}>
          {NODE_ICON[data.nodeType]} {data.nodeType}
        </span>
        <p className="text-[10px] text-gray-400 mt-1">Node ID: {nodeId}</p>
      </div>

      <div>
        <label className="block text-xs font-medium text-gray-600 mb-1">Label</label>
        <input
          className="border rounded-sm px-2 py-1 text-sm w-full"
          value={data.label}
          onChange={(e) => onChange({ label: e.target.value })}
          disabled={!canWrite}
        />
      </div>

      {data.nodeType === 'ALERT' && (
        <div>
          <label className="block text-xs font-medium text-gray-600 mb-1">Evaluation</label>
          <div className="grid grid-cols-2 gap-1 text-xs" role="radiogroup">
            {[
              { rule: true, label: 'Built-in rule' },
              { rule: false, label: 'Python module' },
            ].map((option) => {
              const active = Boolean(data.alertRule) === option.rule;
              return (
                <button
                  key={option.label}
                  type="button"
                  role="radio"
                  aria-checked={active}
                  disabled={!canWrite}
                  onClick={() => onChange(option.rule
                    ? { alertRule: data.alertRule ?? DEFAULT_RULES.threshold }
                    : { alertRule: undefined })}
                  className={`px-2 py-1 rounded-sm border ${active ? 'bg-primary-600 text-white border-primary-600' : 'bg-white text-gray-700 hover:bg-gray-50'}`}
                >
                  {option.label}
                </button>
              );
            })}
          </div>
        </div>
      )}

      {(data.nodeType === 'PROCESSING' || data.nodeType === 'EXPORT') && (
        <div>
          <label className="block text-xs font-medium text-gray-600 mb-1">Step</label>
          <div className="grid grid-cols-2 gap-1 text-xs" role="radiogroup">
            {[
              { builtin: false, label: 'Python module' },
              { builtin: true, label: data.nodeType === 'PROCESSING' ? 'Enrich with metadata' : 'Save to metadata' },
            ].map((option) => {
              const active = Boolean(data.enrich || data.saveMetadata) === option.builtin;
              return (
                <button
                  key={option.label}
                  type="button"
                  role="radio"
                  aria-checked={active}
                  disabled={!canWrite}
                  onClick={() => onChange(!option.builtin
                    ? { enrich: undefined, saveMetadata: undefined }
                    : data.nodeType === 'PROCESSING'
                      ? { enrich: data.enrich ?? DEFAULT_ENRICH }
                      : { saveMetadata: data.saveMetadata ?? DEFAULT_SAVE_METADATA })}
                  className={`px-2 py-1 rounded-sm border ${active ? 'bg-primary-600 text-white border-primary-600' : 'bg-white text-gray-700 hover:bg-gray-50'}`}
                >
                  {option.label}
                </button>
              );
            })}
          </div>
        </div>
      )}

      {data.enrich && (
        <EnrichForm config={data.enrich} types={metadataTypes} disabled={!canWrite} onChange={(enrich) => onChange({ enrich })} />
      )}
      {data.saveMetadata && (
        <SaveMetadataForm config={data.saveMetadata} types={metadataTypes} disabled={!canWrite} onChange={(saveMetadata) => onChange({ saveMetadata })} />
      )}

      {data.nodeType === 'IMPORT' && (
        <div>
          <label className="block text-xs font-medium text-gray-600 mb-1">Processing</label>
          <div className="grid grid-cols-2 gap-1 text-xs" role="radiogroup">
            {[
              { passThrough: true, label: 'Pass through' },
              { passThrough: false, label: 'Python module' },
            ].map((option) => {
              const active = Boolean(data.passThrough) === option.passThrough;
              return (
                <button
                  key={option.label}
                  type="button"
                  role="radio"
                  aria-checked={active}
                  disabled={!canWrite}
                  onClick={() => onChange({ passThrough: option.passThrough })}
                  className={`px-2 py-1 rounded-sm border ${active ? 'bg-primary-600 text-white border-primary-600' : 'bg-white text-gray-700 hover:bg-gray-50'}`}
                >
                  {option.label}
                </button>
              );
            })}
          </div>
          {data.passThrough && (
            <p className="text-[10px] text-gray-400 mt-1">
              Records are forwarded as received (JSON fields as sent by the device). Choose a Python module to parse
              or reshape other formats.
            </p>
          )}
          <label className="block mt-3">
            <span className="block text-xs font-medium text-gray-600 mb-1">Deduplicate by (optional)</span>
            <input
              className="border rounded-sm px-2 py-1 text-sm w-full"
              placeholder="device, ts"
              disabled={!canWrite}
              value={(data.deduplicateBy ?? []).join(', ')}
              onChange={(e) => {
                const fields = e.target.value.split(',').map((f) => f.trim()).filter(Boolean);
                onChange({ deduplicateBy: fields.length ? fields : undefined });
              }}
            />
          </label>
          <p className="text-[10px] text-gray-400 mt-1">
            Fields that identify a measurement. A record with the same values is processed only once, so data a
            device or gateway resends is not counted twice. HTTP clients can send an Idempotency-Key header instead.
          </p>
        </div>
      )}

      {data.nodeType === 'ALERT' && data.alertRule ? (
        <AlertRuleForm rule={data.alertRule} disabled={!canWrite} onChange={(alertRule) => onChange({ alertRule })} />
      ) : data.passThrough || data.enrich || data.saveMetadata ? null : (
        <>
        <div>
          <label className="block text-xs font-medium text-gray-600 mb-1">
            Module <span className="text-gray-400">({compatibleTypes.join('/')})</span>
          </label>
          {filteredModules.length === 0 ? (
            <p className="text-xs text-amber-600">No compatible modules available.</p>
          ) : (
            <select
              className="border rounded-sm px-2 py-1 text-sm w-full"
              value={data.moduleId ?? ''}
              onChange={(e) => {
                const nextModuleId = e.target.value || undefined;
                const defaultVersionId = moduleOptions.find((module) => module.id === nextModuleId)?.defaultVersionId;
                onChange({ moduleId: nextModuleId, moduleVersionId: defaultVersionId });
              }}
              disabled={!canWrite}
            >
              <option value="">— select module —</option>
              {filteredModules.map((module) => (
                <option key={module.id} value={module.id}>
                  {module.name} ({module.moduleType})
                </option>
              ))}
            </select>
          )}
        </div>

        <div>
          <label className="block text-xs font-medium text-gray-600 mb-1">
            Module version
          </label>
          {filteredVersions.length === 0 ? (
            <p className="text-xs text-amber-600">No versions available for the selected module.</p>
          ) : (
            <select
              className="border rounded-sm px-2 py-1 text-sm w-full"
              value={data.moduleVersionId ?? ''}
              onChange={(e) => onChange({ moduleVersionId: e.target.value || undefined })}
              disabled={!canWrite || !data.moduleId}
            >
              <option value="">— select module version —</option>
              {filteredVersions.map((version) => (
                <option key={version.id} value={version.id}>
                  v{version.version}{version.versionTag ? ` (${version.versionTag})` : ''}
                </option>
              ))}
            </select>
          )}
        </div>
        </>
      )}

      {data.nodeType === 'IMPORT' && (
        <div className="space-y-3">
          <div>
            <label className="block text-xs font-medium text-gray-600 mb-1">Input connector</label>
            <select
              className="border rounded-sm px-2 py-1 text-sm w-full"
              value={data.connectorId ?? ''}
              onChange={(e) => onChange({ connectorId: e.target.value || undefined })}
              disabled={!canWrite}
            >
              <option value="">— select INPUT connector —</option>
              {inputConnectors.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.name} ({c.connectorType})
                </option>
              ))}
            </select>
            <p className="text-[10px] text-gray-400 mt-1">
              The platform will fetch data from this connector before your parse() runs.
            </p>
          </div>
          {inputConnectors.find((c) => c.id === data.connectorId)?.connectorType === 'MQTT' && (
            <div>
              <label className="block text-xs font-medium text-gray-600 mb-1">MQTT Topic</label>
              <input
                className="border rounded-sm px-2 py-1 text-sm w-full"
                value={data.mqttTopic ?? ''}
                onChange={(e) => onChange({ mqttTopic: e.target.value || undefined })}
                placeholder="sensors/+/telemetry"
                disabled={!canWrite}
              />
              <p className="text-[10px] text-gray-400 mt-1">
                Topic to subscribe to (broker is defined in the connector).
              </p>
            </div>
          )}
        </div>
      )}

      {data.nodeType === 'EXPORT' && !data.saveMetadata && (
        <div className="space-y-3">
          <div>
            <label className="block text-xs font-medium text-gray-600 mb-1">Output connector</label>
            <select
              className="border rounded-sm px-2 py-1 text-sm w-full"
              value={data.connectorId ?? ''}
              onChange={(e) => onChange({ connectorId: e.target.value || undefined })}
              disabled={!canWrite}
            >
              <option value="">— select OUTPUT connector —</option>
              {outputConnectors.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.name} ({c.connectorType})
                </option>
              ))}
            </select>
            <p className="text-[10px] text-gray-400 mt-1">
              The platform will deliver your shape() result to this connector.
            </p>
          </div>
          {outputConnectors.find((c) => c.id === data.connectorId)?.connectorType === 'MQTT' && (
            <div>
              <label className="block text-xs font-medium text-gray-600 mb-1">MQTT Topic</label>
              <input
                className="border rounded-sm px-2 py-1 text-sm w-full"
                value={data.mqttTopic ?? ''}
                onChange={(e) => onChange({ mqttTopic: e.target.value || undefined })}
                placeholder="output/results"
                disabled={!canWrite}
              />
              <p className="text-[10px] text-gray-400 mt-1">
                Topic to publish to (broker is defined in the connector).
              </p>
            </div>
          )}
        </div>
      )}

      {data.nodeType === 'PROCESSING' && (
        <p className="text-[10px] text-gray-400">
          PROCESSING nodes take a single upstream input. Use MERGE to fan-in from multiple nodes.
        </p>
      )}

      {data.nodeType === 'MERGE' && (
        <p className="text-[10px] text-gray-400">
          Connect 2+ upstream nodes to the MERGE node's target handles. Your merge() receives a list of all upstream outputs.
        </p>
      )}

      {data.nodeType === 'ALERT' && (
        <div>
          <label className="block text-xs font-medium text-gray-600 mb-1">Default alert channel</label>
          <select
            className="border rounded-sm px-2 py-1 text-sm w-full"
            value={data.alertChannel ?? ''}
            onChange={(e) => onChange({ alertChannel: e.target.value || undefined })}
            disabled={!canWrite}
          >
            <option value="">— first active tenant channel —</option>
            {alertChannels.filter((c) => c.isActive).map((c) => (
              <option key={c.id} value={c.id}>
                {c.name} ({c.channelType})
              </option>
            ))}
          </select>
          <p className="text-[10px] text-gray-400 mt-1">
            ALERT is terminal. Alerts raised with alert_fn() go to this channel unless the call names another one.
          </p>
        </div>
      )}
    </div>
  );
}

// ─── Run log side panel ───────────────────────────────────────────────────────

const STATUS_DOT: Record<string, string> = {
  SUCCESS:   'bg-emerald-500',
  FAILED:    'bg-red-500',
  RUNNING:   'bg-amber-400 animate-pulse',
  PENDING:   'bg-slate-400 animate-pulse',
  CANCELLED: 'bg-slate-300',
};

const STATUS_TEXT: Record<string, string> = {
  SUCCESS:   'text-emerald-700',
  FAILED:    'text-red-700',
  RUNNING:   'text-blue-600',
  PENDING:   'text-amber-600',
  CANCELLED: 'text-gray-500',
};

/**
 * Implements `fmtDuration` for this source unit.
 */
function fmtDuration(ms?: number | null) {
  if (ms == null) return '';
  if (ms < 1000) return `${ms}ms`;
  if (ms < 60000) return `${(ms / 1000).toFixed(1)}s`;
  return `${Math.floor(ms / 60000)}m ${Math.floor((ms % 60000) / 1000)}s`;
}

/**
 * Processes the named request, event, or operation.
 */
function RunLogPanel({
  latestRun,
  recentRuns,
  pipelineId,
}: {
  latestRun: PipelineRun | null;
  recentRuns: PipelineRun[];
  pipelineId?: string;
}) {
  if (!latestRun && recentRuns.length === 0) {
    return (
      <div className="p-4 text-sm text-gray-400">
        <p className="font-medium text-gray-600 mb-1">Execution log</p>
        <p>No runs yet. Select a node to configure it, or press Run.</p>
      </div>
    );
  }

  return (
    <div className="flex flex-col h-full">
      {/* Latest run detail */}
      {latestRun && (
        <div className="p-4 border-b space-y-2">
          <div className="flex items-start justify-between gap-2">
            <div>
              <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide mb-1">Latest run</p>
              <div className="flex items-center gap-2">
                <span className={`w-2 h-2 rounded-full shrink-0 ${STATUS_DOT[latestRun.status] ?? 'bg-gray-400'}`} />
                <span className={`text-sm font-bold ${STATUS_TEXT[latestRun.status] ?? 'text-gray-700'}`}>
                  {latestRun.status}
                </span>
                {latestRun.durationMs != null && (
                  <span className="text-xs text-gray-400 font-mono">{fmtDuration(latestRun.durationMs)}</span>
                )}
              </div>
              <p className="text-[10px] text-gray-400 mt-0.5">
                {new Date(latestRun.startedAt).toLocaleString()} · {latestRun.triggerType}
              </p>
              {latestRun.errorMessage && (
                <p className="text-xs text-red-600 mt-1 wrap-break-word">{latestRun.errorMessage}</p>
              )}
            </div>
            {pipelineId && (
              <Link
                to={`/pipelines/${pipelineId}/runs/${latestRun.id}`}
                className="shrink-0 text-[11px] text-primary-600 hover:underline whitespace-nowrap"
              >
                Full logs →
              </Link>
            )}
          </div>

          {/* Module summary for latest run */}
          {latestRun.moduleLogs && latestRun.moduleLogs.length > 0 && (
            <div className="space-y-1">
              {latestRun.moduleLogs
                .slice()
                .sort((a, b) => (a.order ?? 0) - (b.order ?? 0))
                .map((log, i) => (
                  <div
                    key={i}
                    className={`flex items-center gap-2 text-[11px] rounded px-2 py-1 ${
                      log.success === false ? 'bg-red-50 border border-red-200' : 'bg-gray-50 border border-gray-100'
                    }`}
                  >
                    <span className={`font-semibold ${log.success ? 'text-emerald-600' : 'text-red-600'}`}>
                      {log.success ? '✓' : '✗'}
                    </span>
                    <span className="font-medium text-gray-700 truncate flex-1">
                      {log.moduleName ?? log.stage}
                    </span>
                    <span className="text-gray-400 shrink-0">{log.stage}</span>
                    {log.durationMs != null && (
                      <span className="text-gray-400 font-mono shrink-0">{fmtDuration(log.durationMs)}</span>
                    )}
                  </div>
                ))}
            </div>
          )}
        </div>
      )}

      {/* Recent runs list */}
      {recentRuns.length > 0 && (
        <div className="flex-1 overflow-y-auto p-4">
          <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide mb-2">
            Recent runs ({recentRuns.length})
          </p>
          <div className="space-y-1">
            {recentRuns.map((run) => (
              <Link
                key={run.id}
                to={pipelineId ? `/pipelines/${pipelineId}/runs/${run.id}` : '#'}
                className={`flex items-center gap-2 px-2 py-1.5 rounded text-[11px] hover:bg-gray-50 transition-colors ${
                  run.id === latestRun?.id ? 'bg-primary-50 border border-primary-200' : 'border border-transparent'
                }`}
              >
                <span className={`w-2 h-2 rounded-full shrink-0 ${STATUS_DOT[run.status] ?? 'bg-gray-400'}`} />
                <span className={`font-semibold shrink-0 ${STATUS_TEXT[run.status] ?? 'text-gray-500'}`}>
                  {run.status}
                </span>
                <span className="text-gray-400 flex-1 truncate">
                  {new Date(run.startedAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })}
                </span>
                {run.durationMs != null && (
                  <span className="text-gray-400 font-mono shrink-0">{fmtDuration(run.durationMs)}</span>
                )}
              </Link>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
