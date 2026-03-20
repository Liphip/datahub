import { useCallback, useEffect, useMemo, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { useAuthStore } from '../stores/auth';
import api from '../services/api';
import type { Pipeline, CodeModule, Connector, ModuleVersion, PipelineRun } from '../types';
import {
  ReactFlow,
  Background,
  Controls,
  addEdge,
  useNodesState,
  useEdgesState,
  Handle,
  Position,
  type NodeProps,
  type Connection,
  type Node,
  type Edge,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';

// ─── Types ────────────────────────────────────────────────────────────────────

type NodeType = 'IMPORT' | 'PROCESSING' | 'MERGE' | 'ALERT' | 'EXPORT';

interface PipelineNodeData extends Record<string, unknown> {
  nodeType: NodeType;
  label: string;
  moduleVersionId?: string;
  connectorId?: string;
}

type ModuleVersionOption = ModuleVersion & {
  moduleName: string;
  moduleType: CodeModule['moduleType'];
};

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

// eslint-disable-next-line @typescript-eslint/no-explicit-any
function DagNode({ id, data, selected }: NodeProps<any>) {
  const d = data as PipelineNodeData;
  const colors = NODE_COLORS[d.nodeType] ?? 'border-gray-400 bg-gray-50';
  const isSource = d.nodeType === 'IMPORT';
  const isTarget = d.nodeType === 'ALERT' || d.nodeType === 'EXPORT';
  const isMerge  = d.nodeType === 'MERGE';

  return (
    <div
      className={`rounded-lg border-2 ${colors} ${selected ? 'ring-2 ring-offset-1 ring-primary-500' : ''} min-w-[160px] shadow-sm`}
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

const nodeTypes = { dagNode: DagNode };

// ─── Helpers ──────────────────────────────────────────────────────────────────

function makeNodeId() {
  return `n-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`;
}

function graphToFlow(
  graph: Pipeline['graphDefinition'],
): { nodes: Node[]; edges: Edge[] } {
  if (!graph) return { nodes: [], edges: [] };

  const nodes: Node[] = (graph.nodes ?? []).map((raw, idx) => ({
    id: String(raw.id ?? `n-${idx}`),
    type: 'dagNode',
    position: (raw.position as { x: number; y: number }) ?? { x: 150 + idx * 200, y: 150 },
    data: {
      nodeType: raw.type as NodeType,
      label: String(raw.label ?? raw.type ?? 'Node'),
      moduleVersionId: raw.moduleVersionId as string | undefined,
      connectorId: raw.connectorId as string | undefined,
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

function flowToGraph(
  nodes: Node[],
  edges: Edge[],
): Pipeline['graphDefinition'] {
  return {
    nodes: nodes.map((n) => {
      const d = n.data as PipelineNodeData;
      return {
        id: n.id,
        type: d.nodeType,
        label: d.label,
        moduleVersionId: d.moduleVersionId,
        connectorId: d.connectorId,
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
  const canWritePipelines = Boolean(
    user?.superadmin || user?.abilities?.includes('*') || user?.abilities?.includes('pipeline:write')
  );
  const canExecutePipelines = Boolean(
    user?.superadmin || user?.abilities?.includes('*') || user?.abilities?.includes('pipeline:execute')
  );

  const [pipeline, setPipeline] = useState<Pipeline | null>(null);
  const [moduleVersionOptions, setModuleVersionOptions] = useState<ModuleVersionOption[]>([]);
  const [connectors, setConnectors] = useState<Connector[]>([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [triggering, setTriggering] = useState(false);
  const [toast, setToast] = useState<{ message: string; type: 'success' | 'error' } | null>(null);
  const [latestRun, setLatestRun] = useState<PipelineRun | null>(null);
  const [runPollingTimer, setRunPollingTimer] = useState<ReturnType<typeof setTimeout> | null>(null);

  // Pipeline metadata
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [isActive, setIsActive] = useState(false);

  // React Flow state
  const [nodes, setNodes, onNodesChange] = useNodesState<Node>([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState<Edge>([]);
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null);

  const tenantId = selectedTenantId || user?.tenantId || tenantContexts[0]?.tenantId;

  const showToast = (message: string, type: 'success' | 'error') => {
    setToast({ message, type });
    setTimeout(() => setToast(null), 3500);
  };

  // ── Load data ──────────────────────────────────────────────────────────────

  useEffect(() => {
    const fetchData = async () => {
      if (!id || !tenantId) { setLoading(false); return; }
      try {
        const [pipelineData, modulesData, connectorsData] = await Promise.all([
          id === 'new' ? Promise.resolve(null) : api.getPipeline(id),
          api.getModules(tenantId),
          api.getConnectors(tenantId),
        ]);

        const versionsByModule = await Promise.all(
          modulesData.map(async (m) => {
            const versions = await api.getModuleVersions(m.id);
            return versions.map((v) => ({ ...v, moduleName: m.name, moduleType: m.moduleType }));
          })
        );
        setModuleVersionOptions(versionsByModule.flat());
        setConnectors(connectorsData);

        if (pipelineData) {
          setPipeline(pipelineData);
          setName(pipelineData.name);
          setDescription(pipelineData.description || '');
          setIsActive(pipelineData.isActive);
          const { nodes: fn, edges: fe } = graphToFlow(pipelineData.graphDefinition);
          setNodes(fn);
          setEdges(fe);
          if (pipelineData.latestRunId) {
            const run = pipelineData.runs?.find((r) => r.id === pipelineData.latestRunId) ?? null;
            setLatestRun(run);
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
    (connection: Connection) => setEdges((eds) => addEdge(connection, eds)),
    [setEdges],
  );

  const onNodeClick = useCallback((_: React.MouseEvent, node: Node) => {
    setSelectedNodeId(node.id);
  }, []);

  const onPaneClick = useCallback(() => {
    setSelectedNodeId(null);
  }, []);

  // ── Add node ───────────────────────────────────────────────────────────────

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
      } satisfies PipelineNodeData,
    };
    setNodes((nds) => [...nds, newNode]);
    setSelectedNodeId(newId);
  };

  const deleteSelectedNode = () => {
    if (!selectedNodeId) return;
    setNodes((nds) => nds.filter((n) => n.id !== selectedNodeId));
    setEdges((eds) => eds.filter((e) => e.source !== selectedNodeId && e.target !== selectedNodeId));
    setSelectedNodeId(null);
  };

  // ── Selected node helpers ──────────────────────────────────────────────────

  const selectedNode = useMemo(
    () => nodes.find((n) => n.id === selectedNodeId) ?? null,
    [nodes, selectedNodeId],
  );

  const updateSelectedNodeData = (patch: Partial<PipelineNodeData>) => {
    if (!selectedNodeId) return;
    setNodes((nds) =>
      nds.map((n) =>
        n.id === selectedNodeId
          ? { ...n, data: { ...(n.data as PipelineNodeData), ...patch } }
          : n,
      ),
    );
  };

  // ── Save ───────────────────────────────────────────────────────────────────

  const handleSave = async () => {
    if (!tenantId || !name || !canWritePipelines) return;
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
    } catch (err: any) {
      console.error('Save failed:', err);
      showToast(err?.response?.data?.error ?? 'Failed to save pipeline', 'error');
    } finally {
      setSaving(false);
    }
  };

  // ── Trigger (manual run) ───────────────────────────────────────────────────

  const handleTrigger = async () => {
    if (!pipeline || !canExecutePipelines) return;
    setTriggering(true);
    try {
      const run = await api.triggerPipeline(pipeline.id);
      setLatestRun(run);
      showToast('Pipeline triggered', 'success');
      // poll run status
      pollRunStatus(run.id);
    } catch (err: any) {
      showToast(err?.response?.data?.error ?? 'Failed to trigger pipeline', 'error');
    } finally {
      setTriggering(false);
    }
  };

  const pollRunStatus = (runId: string) => {
    const timer = setTimeout(async () => {
      try {
        const run = await api.getPipelineRun(pipeline!.id, runId);
        setLatestRun(run);
        if (run.status === 'RUNNING' || run.status === 'PENDING') {
          pollRunStatus(runId);
        }
      } catch {
        // ignore polling errors
      }
    }, 2000);
    setRunPollingTimer(timer);
  };

  useEffect(() => {
    return () => { if (runPollingTimer) clearTimeout(runPollingTimer); };
  }, [runPollingTimer]);

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
            className="border rounded px-2 py-1 text-sm font-semibold w-52"
            placeholder="Pipeline name"
            disabled={!canWritePipelines}
          />
          <input
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            className="border rounded px-2 py-1 text-sm text-gray-500 w-72"
            placeholder="Description (optional)"
            disabled={!canWritePipelines}
          />
          <label className="flex items-center gap-1.5 text-sm cursor-pointer">
            <input
              type="checkbox"
              checked={isActive}
              onChange={(e) => setIsActive(e.target.checked)}
              disabled={!canWritePipelines}
              className="rounded"
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
              className={`px-2 py-1 rounded border font-medium ${NODE_COLORS[nt]} hover:opacity-80`}
            >
              {NODE_ICON[nt]} {nt}
            </button>
          ))}
          {selectedNodeId && (
            <button
              onClick={deleteSelectedNode}
              className="ml-auto px-2 py-1 rounded border border-red-300 bg-red-50 text-red-700 hover:bg-red-100 font-medium"
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
              moduleVersionOptions={moduleVersionOptions}
              inputConnectors={inputConnectors}
              outputConnectors={outputConnectors}
              canWrite={canWritePipelines}
              onChange={updateSelectedNodeData}
            />
          ) : (
            <RunLogPanel latestRun={latestRun} />
          )}
        </div>
      </div>
    </div>
  );
}

// ─── Node config side panel ───────────────────────────────────────────────────

function NodeConfigPanel({
  nodeId,
  data,
  moduleVersionOptions,
  inputConnectors,
  outputConnectors,
  canWrite,
  onChange,
}: {
  nodeId: string;
  data: PipelineNodeData;
  moduleVersionOptions: ModuleVersionOption[];
  inputConnectors: Connector[];
  outputConnectors: Connector[];
  canWrite: boolean;
  onChange: (patch: Partial<PipelineNodeData>) => void;
}) {
  const filteredVersions = moduleVersionOptions.filter(
    (v) => v.moduleType.toUpperCase() === data.nodeType,
  );

  return (
    <div className="p-4 space-y-4">
      <div>
        <span className={`inline-flex items-center gap-1 text-xs font-semibold px-2 py-0.5 rounded ${NODE_COLORS[data.nodeType]}`}>
          {NODE_ICON[data.nodeType]} {data.nodeType}
        </span>
        <p className="text-[10px] text-gray-400 mt-1">Node ID: {nodeId}</p>
      </div>

      <div>
        <label className="block text-xs font-medium text-gray-600 mb-1">Label</label>
        <input
          className="border rounded px-2 py-1 text-sm w-full"
          value={data.label}
          onChange={(e) => onChange({ label: e.target.value })}
          disabled={!canWrite}
        />
      </div>

      <div>
        <label className="block text-xs font-medium text-gray-600 mb-1">
          Module version <span className="text-gray-400">({data.nodeType})</span>
        </label>
        {filteredVersions.length === 0 ? (
          <p className="text-xs text-amber-600">No {data.nodeType} module versions available.</p>
        ) : (
          <select
            className="border rounded px-2 py-1 text-sm w-full"
            value={data.moduleVersionId ?? ''}
            onChange={(e) => onChange({ moduleVersionId: e.target.value || undefined })}
            disabled={!canWrite}
          >
            <option value="">— select module version —</option>
            {filteredVersions.map((v) => (
              <option key={v.id} value={v.id}>
                {v.moduleName} v{v.version}{v.versionTag ? ` (${v.versionTag})` : ''}
              </option>
            ))}
          </select>
        )}
      </div>

      {data.nodeType === 'IMPORT' && (
        <div>
          <label className="block text-xs font-medium text-gray-600 mb-1">Input connector</label>
          <select
            className="border rounded px-2 py-1 text-sm w-full"
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
      )}

      {data.nodeType === 'EXPORT' && (
        <div>
          <label className="block text-xs font-medium text-gray-600 mb-1">Output connector</label>
          <select
            className="border rounded px-2 py-1 text-sm w-full"
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
        <p className="text-[10px] text-gray-400">
          ALERT is terminal — it evaluates a condition and fires alerts via the platform. No connector needed.
        </p>
      )}
    </div>
  );
}

// ─── Run log side panel ───────────────────────────────────────────────────────

function RunLogPanel({ latestRun }: { latestRun: PipelineRun | null }) {
  if (!latestRun) {
    return (
      <div className="p-4 text-sm text-gray-400">
        <p className="font-medium text-gray-600 mb-1">Execution log</p>
        <p>No runs yet. Select a node to configure it, or press Run.</p>
      </div>
    );
  }

  const statusColor: Record<string, string> = {
    SUCCESS: 'text-green-700',
    FAILED:  'text-red-700',
    RUNNING: 'text-blue-700',
    PENDING: 'text-amber-600',
  };

  return (
    <div className="p-4 space-y-3 text-sm">
      <div>
        <p className="font-medium text-gray-700">Latest run</p>
        <p className={`text-xs font-semibold ${statusColor[latestRun.status] ?? 'text-gray-500'}`}>
          {latestRun.status}
        </p>
        <p className="text-[10px] text-gray-400">
          {new Date(latestRun.startedAt).toLocaleString()}
          {latestRun.durationMs != null && ` · ${latestRun.durationMs}ms`}
        </p>
        {latestRun.errorMessage && (
          <p className="text-xs text-red-600 mt-1 break-words">{latestRun.errorMessage}</p>
        )}
      </div>

      {latestRun.moduleLogs && latestRun.moduleLogs.length > 0 && (
        <div className="space-y-2">
          <p className="text-xs font-medium text-gray-600">Module logs</p>
          {latestRun.moduleLogs.map((log, i) => (
            <div key={i} className="rounded border p-2 text-[11px] bg-gray-50">
              <div className="flex items-center justify-between mb-1">
                <span className="font-semibold text-gray-700">{log.stage}{log.moduleName ? ` · ${log.moduleName}` : ''}</span>
                <span className={log.success ? 'text-green-600' : 'text-red-600'}>
                  {log.success ? '✓' : '✗'}
                </span>
              </div>
              {log.stdout && (
                <pre className="text-gray-600 whitespace-pre-wrap break-all bg-white rounded p-1 border text-[10px] max-h-24 overflow-y-auto">
                  {log.stdout}
                </pre>
              )}
              {log.stderr && (
                <pre className="text-red-500 whitespace-pre-wrap break-all bg-white rounded p-1 border text-[10px] max-h-16 overflow-y-auto mt-1">
                  {log.stderr}
                </pre>
              )}
              {log.error && (
                <p className="text-red-600 mt-1 break-words">{log.error}</p>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
