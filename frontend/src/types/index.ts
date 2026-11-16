/**
 * Defines the interface `User`, the contract implemented by this source unit.
 */
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

/**
 * Defines the interface `Membership`, the contract implemented by this source unit.
 */
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

/** Provenance returned by the administrator permission explanation endpoint. */
export interface AuthorizationExplanation {
  userId: string;
  tenantId: string;
  ability: string;
  granted: boolean;
  sources: Record<'platformAdmin' | 'user' | 'membership' | 'role', string[]>;
  effectiveAbilities: string[];
}

/**
 * Defines the interface `AuthResponse`, the contract implemented by this source unit.
 */
export interface AuthResponse {
  token: string;
  user: User;
}

/**
 * Defines the interface `TenantContext`, the contract implemented by this source unit.
 */
export interface TenantContext {
  tenantId: string;
  tenantName: string;
  roleName?: string;
}

/**
 * Defines the interface `Tenant`, the contract implemented by this source unit.
 */
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

/**
 * Defines the interface `CodeModule`, the contract implemented by this source unit.
 */
export interface CodeModule {
  id: string;
  tenantId?: string;
  name: string;
  description?: string;
  moduleType: 'IMPORT' | 'DB_LOAD' | 'PROCESSING' | 'MERGE' | 'ALERT' | 'EXPORT' | 'DB_SAVE';
  isActive: boolean;
  createdAt: string;
  versionCount?: number;
  latestVersionId?: string;
  defaultVersionId?: string;
  versions: ModuleVersion[];
}

/**
 * Defines the interface `TenantLineageDefaultsConfig`, the contract implemented by this source unit.
 */
export interface TenantLineageDefaultsConfig {
  safeScopePolicy: 'AUTO' | 'RUN' | 'PIPELINE';
  defaultFetchMode: 'SUMMARY' | 'DETAILS';
  defaultDepth: number;
  maxDepthCap: number;
}

/**
 * Defines the interface `TenantLineageDefaultsHistoryEntry`, the contract implemented by this source unit.
 */
export interface TenantLineageDefaultsHistoryEntry {
  changedAt: string;
  changedByUserId?: string;
  changedByEmail?: string;
  changedFields?: string[];
  defaults: TenantLineageDefaultsConfig;
}

/**
 * Defines the interface `TenantLineageDefaults`, the contract implemented by this source unit.
 */
export interface TenantLineageDefaults {
  version?: string;
  safeScopePolicy: 'AUTO' | 'RUN' | 'PIPELINE';
  defaultFetchMode: 'SUMMARY' | 'DETAILS';
  defaultDepth: number;
  maxDepthCap: number;
  history?: TenantLineageDefaultsHistoryEntry[];
}

/**
 * Defines the interface `TenantLineageDefaultsDiagnostics`, the contract implemented by this source unit.
 */
export interface TenantLineageDefaultsDiagnostics {
  tenantId: string;
  get200Count: number;
  get304Count: number;
  patchSuccessCount: number;
  patchConflictCount: number;
  readTotalCount: number;
  readNotModifiedRate: number;
  patchConflictRate: number;
  resetCount?: number;
  lastResetAt?: string;
  resetHistory?: TenantLineageDiagnosticsResetHistoryEntry[];
  lastUpdatedAt?: string;
  windows?: {
    h1: {
      get200Count: number;
      get304Count: number;
      patchSuccessCount: number;
      patchConflictCount: number;
      readTotalCount: number;
      readNotModifiedRate: number;
      patchConflictRate: number;
    };
    h24: {
      get200Count: number;
      get304Count: number;
      patchSuccessCount: number;
      patchConflictCount: number;
      readTotalCount: number;
      readNotModifiedRate: number;
      patchConflictRate: number;
    };
  };
  advisory?: {
    health: 'STABLE' | 'WATCH' | 'HOT';
    reasons: Array<'CONFLICT_SPIKE' | 'ELEVATED_CONFLICT_RATE' | 'CONFLICT_TREND_UP' | 'POLL_OVERFETCH' | string>;
    recommendedActions: Array<'NONE' | 'REFRESH_BEFORE_SAVE' | 'COORDINATE_EDITOR_WINDOWS' | 'CHECK_STALE_POLL_HEALTH' | 'INCREASE_POLL_INTERVAL' | string>;
    conflictRateDelta: number;
    policy?: TenantLineageDiagnosticsPolicy;
  };
}

/**
 * Defines the interface `TenantLineageDiagnosticsPolicy`, the contract implemented by this source unit.
 */
export interface TenantLineageDiagnosticsPolicy {
  version?: string;
  conflictWatchRate: number;
  conflictHotRate: number;
  conflictTrendDelta: number;
  minPatchSamplesForHot: number;
  pollOverfetchMinReads: number;
  pollOverfetchRate: number;
  history?: TenantLineageDiagnosticsPolicyHistoryEntry[];
}

/**
 * Defines the interface `TenantLineageDiagnosticsPolicyHistoryEntry`, the contract implemented by this source unit.
 */
export interface TenantLineageDiagnosticsPolicyHistoryEntry {
  changedAt: string;
  changedByUserId?: string;
  changedByEmail?: string;
  changedFields?: string[];
  policy: Omit<TenantLineageDiagnosticsPolicy, 'version' | 'history'>;
}

/**
 * Defines the interface `TenantLineageDiagnosticsResetHistoryEntry`, the contract implemented by this source unit.
 */
export interface TenantLineageDiagnosticsResetHistoryEntry {
  resetAt: string;
  resetByUserId?: string;
  resetByEmail?: string;
  reason?: string;
}

/**
 * Defines the interface `ModuleVersion`, the contract implemented by this source unit.
 */
export interface ModuleVersion {
  id: string;
  moduleId?: string;
  version: number;
  versionTag?: string;
  contentFingerprint?: string;
  code: string;
  requirements: string;
  config: Record<string, unknown>;
  isActive: boolean;
  createdAt: string;
}

/**
 * Defines the interface `Pipeline`, the contract implemented by this source unit.
 */
export interface Pipeline {
  id: string;
  tenantId?: string;
  name: string;
  description?: string;
  graphDefinition?: {
    nodes: Array<Record<string, unknown> & { moduleId?: string; moduleVersionId?: string }>;
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

/**
 * Defines the interface `PipelineRun`, the contract implemented by this source unit.
 */
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
    stage: 'IMPORT' | 'PROCESSING' | 'MERGE' | 'ALERT' | 'EXPORT' | string;
    order?: number;
    nodeId?: string;
    moduleVersionId?: string;
    moduleName?: string;
    success?: boolean;
    stdout?: string;
    stderr?: string;
    exitCode?: number;
    durationMs?: number;
    executedAt?: string;
    error?: string;
    result?: unknown;
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

/**
 * Defines the interface `TenantPipelineRun`, the contract implemented by this source unit.
 */
export interface TenantPipelineRun extends PipelineRun {
  pipelineId: string;
  pipelineName: string;
}

/**
 * Defines the interface `PipelineRunSummary`, the contract implemented by this source unit.
 */
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

/**
 * Defines the interface `PipelineRunLineage`, the contract implemented by this source unit.
 */
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
  lineagePerformanceHint?: 'NORMAL' | 'HEAVY_QUERY' | 'HEAVY_PAYLOAD' | 'HEAVY_BOTH';
  recommendedAction?: 'NONE' | 'SWITCH_TO_SUMMARY' | 'REDUCE_DEPTH';
  recommendedSafeFetchMode?: 'SUMMARY' | 'DETAILS';
  recommendedSafeDepth?: number;
  recommendedSafeReason?: 'SWITCH_TO_SUMMARY' | 'REDUCE_DEPTH' | null;
  recommendedSafeTtlHours?: number;
  recommendedSafeScope?: 'RUN' | 'PIPELINE';
  safeScopePolicy?: 'AUTO' | 'RUN' | 'PIPELINE';
  appliedSafeScope?: 'RUN' | 'PIPELINE';
  ancestorsTotal?: number;
  ancestorsStatusCounts?: Partial<Record<'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'CANCELLED', number>>;
  descendantsTotal?: number;
  descendantsStatusCounts?: Partial<Record<'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'CANCELLED', number>>;
  ancestorsTruncated?: boolean;
  descendantsTruncated?: boolean;
  nextRecommendedDepth?: number;
}

/**
 * Defines the interface `AlertChannel`, the contract implemented by this source unit.
 */
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

/**
 * Defines the interface `AlertHistory`, the contract implemented by this source unit.
 */
export interface AlertHistory {
  id: string;
  tenantId?: string;
  channelId?: string;
  severity: 'INFO' | 'WARNING' | 'CRITICAL' | 'ERROR';
  title: string;
  message?: string;
  payload?: Record<string, unknown>;
  createdAt: string;
  sentAt?: string;
  status: 'PENDING' | 'SENDING' | 'SENT' | 'SUPPRESSED' | 'FAILED';
  attempts?: number;
  nextAttemptAt?: string;
  lastError?: string;
}

/**
 * Defines the interface `DataSummaryResponse`, the contract implemented by this source unit.
 */
export interface DataSummaryResponse {
  summary: {
    rowCount: number;
    numericCount: number;
    min?: number;
    max?: number;
    avg?: number;
    latestTime?: string;
    latestValue?: number;
  };
  series: Array<{ time: string; value: number }>;
  bars: Array<{ label: string; count: number; avgValue: number }>;
  applied: {
    table: string;
    numericField: string;
    tagField: string;
    limit: number;
    start?: string;
    end?: string;
    topTags: number;
  };
}

/**
 * Defines the interface `Connector`, the contract implemented by this source unit.
 */
export interface Connector {
  id: string;
  tenantId?: string;
  name: string;
  connectorType: 'MQTT' | 'REST' | 'WEBSOCKET' | 'UDP' | 'FILE' | 'FTP' | 'SFTP' | 'EMAIL' | 'CUSTOM';
  direction: 'INPUT' | 'OUTPUT';
  config: Record<string, unknown>;
  description?: string;
  isActive: boolean;
  pollingEnabled: boolean;
  pollingIntervalSeconds: number;
  pipelineReferenceCount?: number;
  createdAt: string;
}

/**
 * Defines the interface `StatusCheck`, the contract implemented by this source unit.
 */
export interface StatusCheck {
  name: string;
  status: 'OK' | 'WARN' | 'ERROR' | 'UNKNOWN';
  message?: string;
  detail?: string;
}

/**
 * Defines the interface `ConnectorStatus`, the contract implemented by this source unit.
 */
export interface ConnectorStatus {
  connectorId: string;
  connectorType: string;
  direction: string;
  checks: StatusCheck[];
}

/**
 * Defines the interface `ConnectorReference`, the contract implemented by this source unit.
 */
export interface ConnectorReference {
  pipelineId: string;
  pipelineName: string;
  pipelineIsActive?: boolean;
  nodeId: string;
  nodeType: string;
}

/**
 * Defines the interface `ConnectorReferenceSummary`, the contract implemented by this source unit.
 */
export interface ConnectorReferenceSummary {
  connectorId: string;
  connectorName: string;
  referenceCount: number;
  references: ConnectorReference[];
}

/**
 * Defines the interface `ConnectorImpactSummary`, the contract implemented by this source unit.
 */
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

/**
 * Defines the interface `BrokerCredential`, the contract implemented by this source unit.
 */
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

/**
 * Defines the interface `Role`, the contract implemented by this source unit.
 */
export interface Role {
  id: string;
  tenantId?: string;
  name: string;
  description?: string;
  abilities: string[];
  isDefault: boolean;
  isSystem: boolean;
  /** A disabled role grants nothing and cannot be assigned (IAM-05). */
  isDisabled?: boolean;
  createdAt: string;
}

/**
 * Defines the interface `ModuleLog`, the contract implemented by this source unit.
 */
export interface ModuleLog {
  nodeId?: string;
  stage: string;
  moduleName?: string;
  success: boolean;
  stdout?: string;
  stderr?: string;
  error?: string;
  durationMs?: number;
  executedAt?: string;
}

/**
 * Defines the interface `DataTableRegistrationResult`, the contract implemented by this source unit.
 */
export interface DataTableRegistrationResult {
  tableName: string;
  tableType: 'timeseries' | 'relational';
}

/** Returned by GET /pipelines/runs/summary/all — one entry per tenant the user belongs to */
/**
 * Defines the interface `TenantRunSummary`, the contract implemented by this source unit.
 */
export interface TenantRunSummary {
  tenantId: string;
  tenantName: string;
  pipelineCount: number;
  activePipelineCount: number;
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

/** Metadata: kinds of objects (meter, location, ...) with their schemas. */
export interface MetadataType {
  id: string;
  tenantId: string;
  typeKey: string;
  name: string;
  description?: string;
  attributeSchema: { name: string; type?: string; description?: string; unit?: string }[];
  identifierSchemes: { scheme: string; name?: string; pattern?: string }[];
  relationSchema: { relation: string; targetType?: string; many?: boolean }[];
  template?: string;
  objectCount: number;
}

export interface MetadataTemplate {
  key: string;
  name: string;
  description: string;
  types: string[];
}

export interface MetadataObjectSummary {
  id: string;
  name: string;
  typeKey: string;
  typeName: string;
  identifiers: Record<string, string>;
  attributes: Record<string, unknown>;
  updatedAt: string;
}

export interface MetadataPeriod {
  validFrom?: string | null;
  validTo?: string | null;
}

export interface MetadataObjectDetail {
  id: string;
  name: string;
  typeKey: string;
  typeName: string;
  at: string;
  identifiers: Record<string, string>;
  attributes: Record<string, unknown>;
  identifierHistory: ({ scheme: string; value: string } & MetadataPeriod)[];
  attributeHistory: ({ attributes: Record<string, unknown>; source?: string; changedAt: string } & MetadataPeriod)[];
  relations: ({ relation: string; target: { id: string; name: string; typeKey: string } } & MetadataPeriod)[];
  referencedBy: ({ relation: string; source: { id: string; name: string; typeKey: string } } & MetadataPeriod)[];
}

/** Mapping of records to metadata objects (the saveMetadata node / import config). */
export interface SaveMetadataMapping {
  type: string;
  match: { scheme: string; field: string };
  nameField?: string;
  identifiers?: Record<string, string>;
  attributes?: Record<string, string> | '*';
  relations?: { relation: string; type?: string; scheme: string; field: string; create?: boolean }[];
  validFromField?: string;
}

/** Built-in PROCESSING step: adds metadata valid at the record's time to every record. */
export interface EnrichConfig {
  match: { scheme: string; field: string };
  timestampField?: string;
  outputs: { as: string; path?: string[] }[];
  onMissing?: 'keep' | 'drop' | 'fail';
}

/** Reference to a stored secret; the value itself is never returned. */
export interface SecretReference {
  id: string;
  name?: string;
  version: number;
}

/**
 * Defines the interface `ConnectorInboxItem`, one row of the ledger that makes reading
 * from a pull connector exactly-once (directory, FTP/FTPS, SFTP, IMAP).
 */
export interface ConnectorInboxItem {
  id: string;
  name: string;
  size?: number;
  status: 'QUEUED' | 'DONE' | 'FAILED';
  chunks: number;
  records?: number;
  error?: string;
  firstSeenAt: string;
  queuedAt?: string;
  acknowledgedAt?: string;
}

/** Ingest credential for devices and systems that push data; `key` is only present right after creation. */
export interface ApiKey {
  id: string;
  name: string;
  pipelineIds: string[];
  connectorIds: string[];
  expiresAt?: string | null;
  lastUsedAt?: string | null;
  isActive: boolean;
  key?: string;
}

export interface AuditEvent {
  id: string;
  occurredAt: string;
  actorId?: string | null;
  tenantId?: string | null;
  action: string;
  resourceType: string;
  resourceId?: string | null;
  outcome: string;
  requestId?: string | null;
  metadata?: unknown;
  seq?: number | null;
  hash?: string | null;
  /** The actor's name when the record was written (AUD-03). */
  actorName?: string | null;
  actorIp?: string | null;
  userAgent?: string | null;
  source?: string | null;
}

export interface AuditNote {
  id: string;
  eventId?: string | null;
  authorId?: string | null;
  note: string;
  createdAt: string;
}

export interface AuditChainReport {
  intact: boolean;
  checked: number;
  unchained: number;
  firstSeq?: number | null;
  lastSeq?: number | null;
  brokenAtSeq?: number | null;
  problem?: string | null;
}

export interface AuditPage {
  total: number;
  limit: number;
  offset: number;
  items: AuditEvent[];
}

export interface AuditFilter {
  tenantId?: string;
  from?: string;
  to?: string;
  action?: string;
  resourceType?: string;
  outcome?: string;
  q?: string;
}

export interface DeadLetter {
  id: string;
  channel: string;
  payload: string;
  error: string;
  createdAt: string;
}
