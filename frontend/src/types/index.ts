export interface User {
  id: string;
  email: string;
  isActive?: boolean;
  firstName?: string;
  lastName?: string;
  tenantId?: string;
  roleName?: string;
  abilities: string[];
  superadmin: boolean;
}

export interface Membership {
  id: string;
  authUserId: string;
  authUserEmail?: string;
  authUserFirstName?: string;
  authUserLastName?: string;
  roleId?: string;
  roleName?: string;
  abilities?: string[] | string;
}

export interface AuthResponse {
  token: string;
  user: User;
}

export interface TenantContext {
  tenantId: string;
  tenantName: string;
  roleName?: string;
}

export interface Tenant {
  id: string;
  name: string;
  parentId?: string;
  settings: Record<string, unknown>;
  quota: Record<string, unknown>;
  createdAt: string;
  updatedAt: string;
  children?: Tenant[];
}

export interface CodeModule {
  id: string;
  tenantId?: string;
  name: string;
  description?: string;
  moduleType: 'IMPORT' | 'PROCESSING' | 'ALERT' | 'EXPORT';
  isActive: boolean;
  createdAt: string;
  versionCount?: number;
  versions: ModuleVersion[];
}

export interface ModuleVersion {
  id: string;
  moduleId?: string;
  version: number;
  versionTag?: string;
  code: string;
  requirements: string;
  config: Record<string, unknown>;
  isActive: boolean;
  createdAt: string;
}

export interface Pipeline {
  id: string;
  tenantId?: string;
  name: string;
  description?: string;
  importModuleVersionId?: string;
  processingModuleVersionIds: string[];
  exportModuleVersionIds: string[];
  graphDefinition?: {
    nodes: Array<Record<string, unknown>>;
    edges: Array<Record<string, unknown>>;
  };
  kafkaConsumerGroup?: string;
  isActive: boolean;
  config: Record<string, unknown>;
  createdAt: string;
  updatedAt?: string;
  latestRunId?: string;
  latestRunStatus?: PipelineRun['status'];
  latestRunStartedAt?: string;
  latestRunTriggerType?: PipelineRun['triggerType'];
  runs: PipelineRun[];
}

export interface PipelineRun {
  id: string;
  pipelineId?: string;
  retryOfRunId?: string;
  lineageDepth?: number;
  status: 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'CANCELLED';
  triggerType: 'MANUAL' | 'SCHEDULED' | 'EVENT' | 'DATA';
  startedAt: string;
  completedAt?: string;
  durationMs?: number;
  errorMessage?: string;
  alertsFired?: number;
  alertEvaluation?: Record<string, unknown>;
  moduleLogs?: Array<{
    stage: 'IMPORT' | 'PROCESSING' | 'EXPORT' | string;
    order?: number;
    moduleVersionId?: string;
    moduleName?: string;
    success?: boolean;
    stdout?: string;
    stderr?: string;
    exitCode?: number;
    durationMs?: number;
    error?: string;
    connector?: {
      id?: string;
      name?: string;
      connectorType?: string;
      direction?: string;
      broker?: string;
      topic?: string;
      username?: string;
      brokerCredentialId?: string;
      brokerCredentialName?: string;
    };
  }>;
  executionLog?: string[];
  metrics: Record<string, unknown>;
}

export interface TenantPipelineRun extends PipelineRun {
  pipelineId: string;
  pipelineName: string;
}

export interface PipelineRunSummary {
  pipelineId?: string;
  pipelineName?: string;
  windowSize?: number;
  sinceHours: number;
  bucketSeconds: number;
  totalRuns: number;
  successRuns: number;
  failedRuns: number;
  inFlightRuns: number;
  successRate: number;
  avgDurationMs?: number;
  trendBuckets: {
    bucketStart: string;
    totalRuns: number;
    successRuns: number;
    failedRuns: number;
    inFlightRuns: number;
  }[];
}

export interface PipelineRunLineage {
  run: PipelineRun;
  ancestors: PipelineRun[];
  descendants: PipelineRun[];
  rootRunId?: string;
  latestDescendantRunId?: string;
  maxDepth?: number;
  defaultDepth?: number;
  includeDetails?: boolean;
  direction?: 'both' | 'ancestors' | 'descendants';
  statuses?: Array<'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'CANCELLED'>;
  statusPreset?: 'all' | 'failures' | 'inflight' | 'terminal' | 'custom';
  statusFiltered?: boolean;
  rootRunInView?: boolean;
  latestDescendantRunInView?: boolean;
  estimatedNodesInResponse?: number;
  estimatedPayloadBytes?: number;
  estimatedPayloadClass?: 'SMALL' | 'MEDIUM' | 'LARGE';
  lineageQueryMs?: number;
  lineageBuildMs?: number;
  lineageTotalMs?: number;
  lineageMeasuredAt?: string;
  ancestorsTotal?: number;
  ancestorsStatusCounts?: Partial<Record<'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'CANCELLED', number>>;
  descendantsTotal?: number;
  descendantsStatusCounts?: Partial<Record<'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'CANCELLED', number>>;
  ancestorsTruncated?: boolean;
  descendantsTruncated?: boolean;
  nextRecommendedDepth?: number;
}

export interface AlertChannel {
  id: string;
  tenantId?: string;
  name: string;
  description?: string;
  channelType: 'MQTT' | 'WEBHOOK' | 'REST' | 'EMAIL' | 'SLACK';
  config: Record<string, unknown>;
  isActive: boolean;
  createdAt: string;
}

export interface AlertRule {
  id: string;
  tenantId?: string;
  pipelineId?: string;
  channelId?: string;
  name: string;
  description?: string;
  condition?: Record<string, unknown> | string;
  enabled: boolean;
  cooldownSeconds?: number;
  createdAt?: string;
}

export interface AlertHistory {
  id: string;
  tenantId?: string;
  ruleId?: string;
  channelId?: string;
  severity: 'INFO' | 'WARNING' | 'CRITICAL' | 'ERROR';
  title: string;
  message?: string;
  payload?: Record<string, unknown>;
  sentAt: string;
  status: 'PENDING' | 'SENT' | 'FAILED' | 'RETRY';
}

export interface Connector {
  id: string;
  tenantId?: string;
  name: string;
  connectorType: 'MQTT' | 'REST' | 'WEBSOCKET' | 'UDP' | 'FILE' | 'CUSTOM';
  direction: 'INPUT' | 'OUTPUT' | 'BOTH';
  config: Record<string, unknown>;
  description?: string;
  isActive: boolean;
  pipelineReferenceCount?: number;
  createdAt: string;
}

export interface ConnectorReference {
  pipelineId: string;
  pipelineName: string;
  pipelineIsActive?: boolean;
  nodeId: string;
  nodeType: string;
}

export interface ConnectorReferenceSummary {
  connectorId: string;
  connectorName: string;
  referenceCount: number;
  references: ConnectorReference[];
}

export interface ConnectorImpactSummary {
  connectorId: string;
  connectorName: string;
  sinceHours?: number;
  activeOnly?: boolean;
  riskAtLeast?: 'LOW' | 'MEDIUM' | 'HIGH' | string;
  referenceCount: number;
  impactedPipelineCount: number;
  activeImpactedPipelineCount?: number;
  highRiskCount?: number;
  mediumRiskCount?: number;
  lowRiskCount?: number;
  impactedPipelines: Array<{
    pipelineId: string;
    pipelineName: string;
    pipelineIsActive: boolean;
    nodeReferenceCount: number;
    nodeReferences: Array<{ nodeId: string; nodeType: string }>;
    latestRunId?: string;
    latestRunStatus?: string;
    latestRunStartedAt?: string;
    latestFailedRunId?: string;
    latestFailedRunStartedAt?: string;
    latestFailedRunErrorMessage?: string;
    windowSinceHours?: number;
    recentRuns?: number;
    recentFailedRuns?: number;
    recentInFlightRuns?: number;
    recentSuccessRate?: number;
    impactPriorityScore?: number;
    riskLevel?: 'HIGH' | 'MEDIUM' | 'LOW' | string;
  }>;
}

export interface BrokerCredential {
  id: string;
  tenantId: string;
  name: string;
  username: string;
  allowedTopics: string[];
  isActive: boolean;
  referenceCount?: number;
  createdAt: string;
  updatedAt: string;
}

export interface Role {
  id: string;
  tenantId?: string;
  name: string;
  description?: string;
  abilities: string[];
  isDefault: boolean;
  isSystem: boolean;
  createdAt: string;
}
