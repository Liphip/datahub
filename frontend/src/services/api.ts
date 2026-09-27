import axios, { AxiosError, AxiosInstance, InternalAxiosRequestConfig } from 'axios';
import type { AuthResponse, User, Tenant, TenantLineageDefaults, TenantLineageDefaultsDiagnostics, TenantLineageDiagnosticsPolicy, CodeModule, ModuleVersion, Pipeline, PipelineRun, PipelineRunLineage, TenantPipelineRun, PipelineRunSummary, TenantRunSummary, AlertChannel, AlertHistory, DataSummaryResponse, DataBlob, DataTableRegistrationResult, Connector, ConnectorImpactSummary, ConnectorReferenceSummary, ConnectorStatus, Role, Membership, TenantContext, BrokerCredential, MetadataType, MetadataTemplate, MetadataObjectSummary, MetadataObjectDetail, SaveMetadataMapping } from '../types';

/** Error shape returned by backend endpoints (`{ error, missing }` JSON bodies). */
export type ApiError = AxiosError<{ error?: string; missing?: string[] }>;

const API_BASE_URL = import.meta.env.VITE_API_URL || '/api/internal/v1';
const PUBLIC_API_BASE_URL = import.meta.env.VITE_PUBLIC_API_URL || '/api/v1';
export const API_STATUS_EVENT = 'dataturntable:api-status';

/**
 * Defines the type `ApiStatusDetail`, the contract implemented by this source unit.
 */
type ApiStatusDetail = {
  available: boolean;
  status?: number;
  message?: string;
};

/**
 * Delivers or routes the named data to its configured destination.
 */
function emitApiStatus(detail: ApiStatusDetail): void {
  if (typeof window === 'undefined') return;
  window.dispatchEvent(new CustomEvent<ApiStatusDetail>(API_STATUS_EVENT, { detail }));
}

/**
 * Defines the class `ApiService`, the contract implemented by this source unit.
 */
class ApiService {
  private client: AxiosInstance;
  private publicClient: AxiosInstance;

  /**
   * Implements `constructor` for this source unit.
   */
  constructor() {
    this.client = axios.create({
      baseURL: API_BASE_URL,
      timeout: 30000, // 30 seconds — prevents hung requests
      headers: {
        'Content-Type': 'application/json',
      },
    });

    this.publicClient = axios.create({
      baseURL: PUBLIC_API_BASE_URL,
      timeout: 30000,
      headers: {
        'Content-Type': 'application/json',
      },
    });

    this.client.interceptors.request.use((config: InternalAxiosRequestConfig) => {
      const token = localStorage.getItem('token');
      if (token && config.headers) {
        config.headers.Authorization = `Bearer ${token}`;
      }
      return config;
    });

    this.client.interceptors.response.use(
      (response) => {
        emitApiStatus({ available: true, status: response.status });
        return response;
      },
      (error) => {
        const requestUrl = String(error.config?.url || '');
        const isPublicAuthCall = requestUrl.includes('/auth/login')
          || requestUrl.includes('/auth/register')
          || requestUrl.includes('/auth/setup');

        const status = error.response?.status as number | undefined;
        const isConnectivityError = !error.response || (typeof status === 'number' && status >= 500);
        if (isConnectivityError) {
          emitApiStatus({
            available: false,
            status,
            message: !error.response ? 'Network request failed' : `Server error (${status})`,
          });
        }

        if (error.response?.status === 401 && !isPublicAuthCall) {
          localStorage.removeItem('token');
          window.location.href = '/login';
        }
        return Promise.reject(error);
      }
    );

    this.publicClient.interceptors.request.use((config: InternalAxiosRequestConfig) => {
      const token = localStorage.getItem('token');
      if (token && config.headers) {
        config.headers.Authorization = `Bearer ${token}`;
      }
      return config;
    });

    this.publicClient.interceptors.response.use(
      (response) => {
        emitApiStatus({ available: true, status: response.status });
        return response;
      },
      (error) => {
        const requestUrl = String(error.config?.url || '');
        const isPublicAuthCall = requestUrl.includes('/auth/login')
          || requestUrl.includes('/auth/register')
          || requestUrl.includes('/auth/setup');

        const status = error.response?.status as number | undefined;
        const isConnectivityError = !error.response || (typeof status === 'number' && status >= 500);
        if (isConnectivityError) {
          emitApiStatus({
            available: false,
            status,
            message: !error.response ? 'Network request failed' : `Server error (${status})`,
          });
        }

        if (error.response?.status === 401 && !isPublicAuthCall) {
          localStorage.removeItem('token');
          window.location.href = '/login';
        }
        return Promise.reject(error);
      }
    );

  }

  // Auth
  /**
   * Implements `login` for this source unit.
   */
  async login(email: string, password: string, tenantId?: string): Promise<AuthResponse> {
    const response = await this.client.post<AuthResponse>('/auth/login', { email, password, tenantId });
    return response.data;
  }

  /**
   * Creates or persists the requested state.
   */
  async register(email: string, password: string, tenantName?: string, tenantId?: string): Promise<AuthResponse> {
    const response = await this.client.post<AuthResponse>('/auth/register', { email, password, tenantName, tenantId });
    return response.data;
  }

  /**
   * Retrieves or enumerates the requested state.
   */
  async getCurrentUser(): Promise<User> {
    const response = await this.client.get<User>('/auth/me');
    return response.data;
  }

  /**
   * Retrieves or enumerates the requested state.
   */
  async getAuthTenants(): Promise<TenantContext[]> {
    const response = await this.client.get<TenantContext[]>('/auth/tenants');
    return response.data;
  }

  /**
   * Implements `switchTenant` for this source unit.
   */
  async switchTenant(tenantId: string): Promise<AuthResponse> {
    const response = await this.client.post<AuthResponse>('/auth/switch-tenant', { tenantId });
    return response.data;
  }

  // Tenants
  /**
   * Retrieves or enumerates the requested state.
   */
  async getTenants(): Promise<Tenant[]> {
    const response = await this.client.get<Tenant[]>('/tenants');
    return response.data;
  }

  /**
   * Retrieves or enumerates the requested state.
   */
  async getTenantsRecursive(query?: string): Promise<Tenant[]> {
    const queryParam = query ? `&query=${encodeURIComponent(query)}` : '';
    const response = await this.client.get<Tenant[]>(`/tenants?recursive=true${queryParam}`);
    return response.data;
  }

  /**
   * Retrieves or enumerates the requested state.
   */
  async getTenant(id: string): Promise<Tenant> {
    const response = await this.client.get<Tenant>(`/tenants/${id}`);
    return response.data;
  }

  /**
   * Creates or persists the requested state.
   */
  async createTenant(name: string, parentId?: string): Promise<Tenant> {
    const response = await this.client.post<Tenant>('/tenants', { name, parentId });
    return response.data;
  }

  /**
   * Updates the requested state.
   */
  async updateTenant(id: string, data: Partial<Tenant>): Promise<Tenant> {
    const response = await this.client.patch<Tenant>(`/tenants/${id}`, data);
    return response.data;
  }

  /**
   * Removes, stops, or releases the requested state.
   */
  async deleteTenant(id: string): Promise<void> {
    await this.client.delete(`/tenants/${id}`);
  }

  /**
   * Retrieves or enumerates the requested state.
   */
  async getTenantChildren(parentId: string): Promise<Tenant[]> {
    const response = await this.client.get<Tenant[]>(`/tenants/${parentId}/children`);
    return response.data;
  }

  /**
   * Retrieves or enumerates the requested state.
   */
  async getTenantLineageDefaults(tenantId: string): Promise<TenantLineageDefaults> {
    const response = await this.client.get<TenantLineageDefaults>(`/tenants/${tenantId}/lineage-defaults`);
    return response.data;
  }

  /**
   * Retrieves or enumerates the requested state.
   */
  async getTenantLineageDefaultsIfChanged(
    tenantId: string,
    etag?: string
  ): Promise<{ notModified: boolean; data?: TenantLineageDefaults; etag?: string }> {
    const response = await this.client.get<TenantLineageDefaults>(`/tenants/${tenantId}/lineage-defaults`, {
      headers: etag ? { 'If-None-Match': etag } : undefined,
      validateStatus: (status) => (status >= 200 && status < 300) || status === 304,
    });
    if (response.status === 304) {
      return { notModified: true, etag: (response.headers?.etag as string | undefined) || etag };
    }
    return {
      notModified: false,
      data: response.data,
      etag: response.headers?.etag as string | undefined,
    };
  }

  /**
   * Updates the requested state.
   */
  async updateTenantLineageDefaults(
    tenantId: string,
    data: Partial<TenantLineageDefaults>,
    ifVersion?: string
  ): Promise<TenantLineageDefaults> {
    const payload = ifVersion ? { ...data, ifVersion } : data;
    const response = await this.client.patch<TenantLineageDefaults>(`/tenants/${tenantId}/lineage-defaults`, payload);
    return response.data;
  }

  /**
   * Retrieves or enumerates the requested state.
   */
  async getTenantLineageDefaultsDiagnostics(tenantId: string): Promise<TenantLineageDefaultsDiagnostics> {
    const response = await this.client.get<TenantLineageDefaultsDiagnostics>(`/tenants/${tenantId}/lineage-defaults/diagnostics`);
    return response.data;
  }

  /**
   * Implements `resetTenantLineageDefaultsDiagnostics` for this source unit.
   */
  async resetTenantLineageDefaultsDiagnostics(tenantId: string, reason?: string): Promise<TenantLineageDefaultsDiagnostics> {
    const payload = reason ? { reason } : {};
    const response = await this.client.post<TenantLineageDefaultsDiagnostics>(`/tenants/${tenantId}/lineage-defaults/diagnostics/reset`, payload);
    return response.data;
  }

  /**
   * Retrieves or enumerates the requested state.
   */
  async getTenantLineageDefaultsDiagnosticsPolicy(tenantId: string): Promise<TenantLineageDiagnosticsPolicy> {
    const response = await this.client.get<TenantLineageDiagnosticsPolicy>(`/tenants/${tenantId}/lineage-defaults/diagnostics-policy`);
    return response.data;
  }

  /**
   * Updates the requested state.
   */
  async updateTenantLineageDefaultsDiagnosticsPolicy(
    tenantId: string,
    data: Partial<TenantLineageDiagnosticsPolicy>,
    ifVersion?: string
  ): Promise<TenantLineageDiagnosticsPolicy> {
    const payload = ifVersion ? { ...data, ifVersion } : data;
    const response = await this.client.patch<TenantLineageDiagnosticsPolicy>(`/tenants/${tenantId}/lineage-defaults/diagnostics-policy`, payload);
    return response.data;
  }

  // Users
  /**
   * Retrieves or enumerates the requested state.
   */
  async getUsers(tenantId: string): Promise<User[]> {
    const response = await this.client.get<User[]>(`/auth-users?tenantId=${tenantId}`);
    return response.data;
  }

  /**
   * Creates or persists the requested state.
   */
  async createUser(tenantId: string, email: string, password: string, data?: Partial<User>): Promise<User> {
    const response = await this.client.post<User>(`/auth-users?tenantId=${tenantId}`, { email, password, ...data });
    return response.data;
  }

  /**
   * Updates the requested state.
   */
  async updateUser(id: string, data: Partial<User>): Promise<User> {
    const response = await this.client.patch<User>(`/auth-users/${id}`, data);
    return response.data;
  }

  /**
   * Removes, stops, or releases the requested state.
   */
  async deleteUser(id: string): Promise<void> {
    await this.client.delete(`/auth-users/${id}`);
  }

  /**
   * Retrieves or enumerates the requested state.
   */
  async getTenantMemberships(tenantId: string): Promise<Membership[]> {
    const response = await this.client.get<Membership[]>(`/tenants/${tenantId}/memberships`);
    return response.data;
  }

  /**
   * Creates or persists the requested state.
   */
  async createTenantMembership(tenantId: string, authUserId: string, roleId: string, abilities: string[] = []): Promise<Membership> {
    const response = await this.client.post<Membership>(`/tenants/${tenantId}/memberships`, {
      authUserId,
      roleId,
      abilities,
    });
    return response.data;
  }

  /**
   * Updates the requested state.
   */
  async updateTenantMembership(tenantId: string, membershipId: string, roleId?: string, abilities?: string[]): Promise<Membership> {
    const response = await this.client.patch<Membership>(`/tenants/${tenantId}/memberships/${membershipId}`, {
      roleId,
      abilities,
    });
    return response.data;
  }

  /**
   * Removes, stops, or releases the requested state.
   */
  async deleteTenantMembership(tenantId: string, membershipId: string): Promise<void> {
    await this.client.delete(`/tenants/${tenantId}/memberships/${membershipId}`);
  }

  // Modules
  /**
   * Retrieves or enumerates the requested state.
   */
  async getModules(tenantId: string, type?: string): Promise<CodeModule[]> {
    const params = new URLSearchParams({ tenantId });
    if (type) params.append('type', type);
    const response = await this.client.get<CodeModule[]>(`/modules?${params}`);
    return response.data;
  }

  /**
   * Retrieves or enumerates the requested state.
   */
  async getModule(id: string): Promise<CodeModule> {
    const response = await this.client.get<CodeModule>(`/modules/${id}`);
    return response.data;
  }

  /**
   * Creates or persists the requested state.
   */
  async createModule(tenantId: string, name: string, moduleType: string, description?: string): Promise<CodeModule> {
    const response = await this.client.post<CodeModule>(`/modules?tenantId=${tenantId}`, { name, moduleType, description });
    return response.data;
  }

  /**
   * Removes, stops, or releases the requested state.
   */
  async deleteModule(id: string): Promise<void> {
    await this.client.delete(`/modules/${id}`);
  }

  /**
   * Retrieves or enumerates the requested state.
   */
  async getModuleVersions(moduleId: string): Promise<ModuleVersion[]> {
    const response = await this.client.get<ModuleVersion[]>(`/modules/${moduleId}/versions`);
    return response.data;
  }

  /**
   * Creates or persists the requested state.
   */
  async createModuleVersion(
    moduleId: string,
    code: string,
    requirements?: string,
    versionTag?: string,
    config?: Record<string, unknown>
  ): Promise<ModuleVersion> {
    const response = await this.client.post<ModuleVersion>(`/modules/${moduleId}/versions`, { code, requirements, versionTag, config });
    return response.data;
  }

  /**
   * Updates the requested state.
   */
  async updateModuleVersion(
    moduleId: string,
    versionId: string,
    data: { isActive?: boolean; versionTag?: string }
  ): Promise<ModuleVersion> {
    const response = await this.client.patch<ModuleVersion>(`/modules/${moduleId}/versions/${versionId}`, data);
    return response.data;
  }

  /**
   * Updates the requested state.
   */
  async setDefaultModuleVersion(moduleId: string, versionId: string): Promise<CodeModule> {
    const response = await this.client.post<CodeModule>(`/modules/${moduleId}/versions/${versionId}/default`);
    return response.data;
  }

  // Pipelines
  /**
   * Retrieves or enumerates the requested state.
   */
  async getPipelines(tenantId: string, options?: {
    includeRunSummary?: boolean;
    runHealth?: 'ALL' | 'PROBLEMATIC' | 'RUNNING' | 'FAILED' | 'HEALTHY';
    latestSinceHours?: 1 | 24 | 168;
    latestFrom?: string;
    latestTo?: string;
  }): Promise<Pipeline[]> {
    const includeRunSummary = options?.includeRunSummary ? '&includeRunSummary=true' : '';
    const runHealth = options?.runHealth && options.runHealth !== 'ALL' ? `&runHealth=${options.runHealth}` : '';
    const latestSinceHours = options?.latestSinceHours ? `&latestSinceHours=${options.latestSinceHours}` : '';
    const latestFrom = options?.latestFrom ? `&latestFrom=${encodeURIComponent(options.latestFrom)}` : '';
    const latestTo = options?.latestTo ? `&latestTo=${encodeURIComponent(options.latestTo)}` : '';
    const response = await this.client.get<Pipeline[]>(`/pipelines?tenantId=${tenantId}${includeRunSummary}${runHealth}${latestSinceHours}${latestFrom}${latestTo}`);
    return response.data;
  }

  /**
   * Retrieves or enumerates the requested state.
   */
  async getPipeline(id: string): Promise<Pipeline> {
    const response = await this.client.get<Pipeline>(`/pipelines/${id}`);
    return response.data;
  }

  /**
   * Creates or persists the requested state.
   */
  async createPipeline(tenantId: string, name: string, data?: Partial<Pipeline>): Promise<Pipeline> {
    const response = await this.client.post<Pipeline>(`/pipelines?tenantId=${tenantId}`, { name, ...data });
    return response.data;
  }

  /**
   * Updates the requested state.
   */
  async updatePipeline(id: string, data: Partial<Pipeline>): Promise<Pipeline> {
    const response = await this.client.patch<Pipeline>(`/pipelines/${id}`, data);
    return response.data;
  }

  /**
   * Removes, stops, or releases the requested state.
   */
  async deletePipeline(id: string): Promise<void> {
    await this.client.delete(`/pipelines/${id}`);
  }

  /**
   * Implements `triggerPipeline` for this source unit.
   */
  async triggerPipeline(id: string, triggerType?: string): Promise<PipelineRun> {
    const response = await this.client.post<PipelineRun>(`/pipelines/${id}/run${triggerType ? `?triggerType=${triggerType}` : ''}`);
    return response.data;
  }

  /**
   * Evaluates the named capability or state predicate.
   */
  async cancelPipelineRun(pipelineId: string, runId: string): Promise<PipelineRun> {
    const response = await this.client.post<PipelineRun>(`/pipelines/${pipelineId}/runs/${runId}/cancel`);
    return response.data;
  }

  /**
   * Implements `retryPipelineRun` for this source unit.
   */
  async retryPipelineRun(pipelineId: string, runId: string): Promise<PipelineRun> {
    const response = await this.client.post<PipelineRun>(`/pipelines/${pipelineId}/runs/${runId}/retry`);
    return response.data;
  }

  /**
   * Retrieves or enumerates the requested state.
   */
  async getPipelineRuns(
    pipelineId: string,
    options?: {
      limit?: number;
      includeDetails?: boolean;
      startedFrom?: string;
      startedTo?: string;
      runStatus?: 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED';
    }
  ): Promise<PipelineRun[]> {
    const params = new URLSearchParams();
    if (options?.limit) params.set('limit', String(Math.max(1, options.limit)));
    if (options?.includeDetails !== undefined) params.set('includeDetails', String(options.includeDetails));
    if (options?.startedFrom) params.set('startedFrom', options.startedFrom);
    if (options?.startedTo) params.set('startedTo', options.startedTo);
    if (options?.runStatus) params.set('runStatus', options.runStatus);
    const suffix = params.toString() ? `?${params.toString()}` : '';
    const response = await this.client.get<PipelineRun[]>(`/pipelines/${pipelineId}/runs${suffix}`);
    return response.data;
  }

  /**
   * Retrieves or enumerates the requested state.
   */
  async getPipelineRun(pipelineId: string, runId: string, includeDetails = true): Promise<PipelineRun> {
    const response = await this.client.get<PipelineRun>(`/pipelines/${pipelineId}/runs/${runId}?includeDetails=${includeDetails}`);
    return response.data;
  }

  /**
   * Retrieves or enumerates the requested state.
   */
  async getPipelineRunLineage(
    pipelineId: string,
    runId: string,
    maxDepth = 20,
    direction: 'both' | 'ancestors' | 'descendants' = 'both',
    statuses?: Array<'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'CANCELLED'>,
    statusPreset?: 'all' | 'failures' | 'inflight' | 'terminal',
    includeDetails = false,
    safeScopePolicy: 'AUTO' | 'RUN' | 'PIPELINE' = 'AUTO'
  ): Promise<PipelineRunLineage> {
    const statusQuery = statuses && statuses.length > 0 ? `&statuses=${statuses.join(',')}` : '';
    const presetQuery = statusPreset ? `&statusPreset=${statusPreset}` : '';
    const detailsQuery = includeDetails ? '&includeDetails=true' : '';
    const safeScopePolicyQuery = safeScopePolicy === 'AUTO' ? '' : `&safeScopePolicy=${safeScopePolicy.toLowerCase()}`;
    const response = await this.client.get<PipelineRunLineage>(
      `/pipelines/${pipelineId}/runs/${runId}/lineage?maxDepth=${Math.max(1, maxDepth)}&direction=${direction}${statusQuery}${presetQuery}${detailsQuery}${safeScopePolicyQuery}`
    );
    return response.data;
  }

  /**
   * Retrieves or enumerates the requested state.
   */
  async getPipelineRunSummary(tenantId: string, limit = 50, sinceHours = 24): Promise<PipelineRunSummary> {
    const response = await this.client.get<PipelineRunSummary>(`/pipelines/runs/summary?tenantId=${tenantId}&limit=${limit}&sinceHours=${sinceHours}`);
    return response.data;
  }

  /** Returns per-tenant run stats for every tenant the current user belongs to — single roundtrip. */
  /**
   * Retrieves or enumerates the requested state.
   */
  async getAllTenantRunSummaries(sinceHours: 1 | 24 | 168 = 24): Promise<TenantRunSummary[]> {
    const response = await this.client.get<TenantRunSummary[]>(`/pipelines/runs/summary/all?sinceHours=${sinceHours}`);
    return response.data;
  }

  /**
   * Retrieves or enumerates the requested state.
   */
  async getPipelineRunSummaryByPipeline(
    pipelineId: string,
    sinceHours: 1 | 24 | 168 = 24,
  ): Promise<PipelineRunSummary> {
    const response = await this.client.get<PipelineRunSummary>(`/pipelines/${pipelineId}/runs/summary?sinceHours=${sinceHours}`);
    return response.data;
  }

  /**
   * Retrieves or enumerates the requested state.
   */
  async getTenantPipelineRuns(
    tenantId: string,
    options?: {
      from?: string;
      to?: string;
      limit?: number;
      runStatus?: 'RUNNING' | 'PENDING' | 'SUCCESS' | 'FAILED' | 'CANCELLED';
      pipelineId?: string;
    }
  ): Promise<TenantPipelineRun[]> {
    const from = options?.from ? `&from=${encodeURIComponent(options.from)}` : '';
    const to = options?.to ? `&to=${encodeURIComponent(options.to)}` : '';
    const limit = options?.limit ? `&limit=${options.limit}` : '';
    const runStatus = options?.runStatus ? `&runStatus=${options.runStatus}` : '';
    const pipelineId = options?.pipelineId ? `&pipelineId=${options.pipelineId}` : '';
    const response = await this.client.get<TenantPipelineRun[]>(`/pipelines/runs?tenantId=${tenantId}${from}${to}${limit}${runStatus}${pipelineId}`);
    return response.data;
  }

  // Alerts
  /**
   * Retrieves or enumerates the requested state.
   */
  async getAlertChannels(tenantId: string): Promise<AlertChannel[]> {
    const response = await this.publicClient.get<AlertChannel[]>(`/alerts/channels?tenantId=${tenantId}`);
    return response.data;
  }

  /**
   * Creates or persists the requested state.
   */
  async createAlertChannel(tenantId: string, name: string, channelType: string, config?: Record<string, unknown>): Promise<AlertChannel> {
    const response = await this.publicClient.post<AlertChannel>(`/alerts/channels?tenantId=${tenantId}`, {
      tenantId,
      name,
      channelType,
      config,
    });
    return response.data;
  }

  /**
   * Removes, stops, or releases the requested state.
   */
  async deleteAlertChannel(id: string): Promise<void> {
    await this.publicClient.delete(`/alerts/channels/${id}`);
  }

  /**
   * Updates the requested state.
   */
  async updateAlertChannel(id: string, data: Partial<AlertChannel>): Promise<AlertChannel> {
    const response = await this.publicClient.patch<AlertChannel>(`/alerts/channels/${id}`, data);
    return response.data;
  }

  /**
   * Retrieves or enumerates the requested state.
   */
  async getAlertHistory(tenantId: string, limit?: number): Promise<AlertHistory[]> {
    const params = new URLSearchParams({ tenantId });
    if (limit) params.append('limit', limit.toString());
    const response = await this.publicClient.get<AlertHistory[]>(`/alerts/history?${params}`);
    return response.data;
  }

  // Data
  /**
   * Retrieves or enumerates the requested state.
   */
  async queryData(
    tenantId: string,
    table: string,
    params?: Record<string, unknown>
  ): Promise<{ data: unknown[]; count: number }> {
    const response = await this.publicClient.get<{ data: unknown[]; count: number }>(`/data/${tenantId}/query`, {
      params: { table, ...params },
    });
    return response.data;
  }

  /**
   * Creates or persists the requested state.
   */
  async insertData(tenantId: string, table: string, data: unknown[]): Promise<{ inserted: number }> {
    const response = await this.publicClient.post(`/data/${tenantId}/insert?table=${table}`, data);
    return response.data;
  }

  /**
   * Retrieves or enumerates the requested state.
   */
  async listTables(tenantId: string): Promise<{ tables: { name: string; type: string }[] }> {
    const response = await this.publicClient.get(`/data/${tenantId}/tables`);
    return response.data;
  }

  /**
   * Creates or persists the requested state.
   */
  async registerTable(
    tenantId: string,
    payload: {
      tableName: string;
      tableType: 'timeseries' | 'relational';
      columns?: Array<{ name: string; type: string }>;
    }
  ): Promise<DataTableRegistrationResult> {
    const response = await this.publicClient.post<DataTableRegistrationResult>(`/data/${tenantId}/tables/register`, payload);
    return response.data;
  }

  /**
   * Removes, stops, or releases the requested state.
   */
  async deleteTable(tenantId: string, table: string): Promise<void> {
    await this.publicClient.delete(`/data/${tenantId}/tables/${encodeURIComponent(table)}`);
  }

  /**
   * Retrieves or enumerates the requested state.
   */
  async getDataSummary(
    tenantId: string,
    table: string,
    params?: {
      start?: string;
      end?: string;
      limit?: number;
      numericField?: string;
      tagField?: string;
      topTags?: number;
    }
  ): Promise<DataSummaryResponse> {
    const response = await this.publicClient.get<DataSummaryResponse>(`/data/${tenantId}/summary`, {
      params: {
        table,
        ...params,
      },
    });
    return response.data;
  }

  // Connectors
  /**
   * Retrieves or enumerates the requested state.
   */
  async getMqttConnectorDefaults(): Promise<{
    internalBrokerUrl: string;
    externalBrokerUrl: string;
    websocketUrl: string;
    defaultTopic: string;
    integratedBrokerLabel: string;
  }> {
    const response = await this.client.get<{
      internalBrokerUrl: string;
      externalBrokerUrl: string;
      websocketUrl: string;
      defaultTopic: string;
      integratedBrokerLabel: string;
    }>(`/connectors/mqtt/defaults`);
    return response.data;
  }

  /**
   * Retrieves or enumerates the requested state.
   */
  async getMqttBrokerCredentials(tenantId: string): Promise<BrokerCredential[]> {
    const response = await this.client.get<BrokerCredential[]>(`/connectors/mqtt/credentials?tenantId=${tenantId}`);
    return response.data;
  }

  /**
   * Creates or persists the requested state.
   */
  async createMqttBrokerCredential(
    tenantId: string,
    data: { name: string; username?: string; password?: string; allowedTopics: string[]; isActive?: boolean }
  ): Promise<BrokerCredential & { password?: string }> {
    const response = await this.client.post<BrokerCredential & { password?: string }>(`/connectors/mqtt/credentials?tenantId=${tenantId}`, data);
    return response.data;
  }

  /**
   * Updates the requested state.
   */
  async updateMqttBrokerCredential(
    id: string,
    data: Partial<Pick<BrokerCredential, 'name' | 'allowedTopics' | 'isActive'>>
  ): Promise<BrokerCredential> {
    const response = await this.client.patch<BrokerCredential>(`/connectors/mqtt/credentials/${id}`, data);
    return response.data;
  }

  /**
   * Implements `rotateMqttBrokerCredentialPassword` for this source unit.
   */
  async rotateMqttBrokerCredentialPassword(id: string, password?: string): Promise<BrokerCredential & { password: string }> {
    const response = await this.client.post<BrokerCredential & { password: string }>(`/connectors/mqtt/credentials/${id}/rotate-password`, password ? { password } : {});
    return response.data;
  }

  /**
   * Removes, stops, or releases the requested state.
   */
  async deleteMqttBrokerCredential(id: string): Promise<void> {
    await this.client.delete(`/connectors/mqtt/credentials/${id}`);
  }

  /**
   * Retrieves or enumerates the requested state.
   */
  async getConnectors(
    tenantId: string,
    type?: string,
    direction?: string,
    options?: { query?: string; isActive?: boolean }
  ): Promise<Connector[]> {
    const params = new URLSearchParams({ tenantId });
    if (type) params.append('type', type);
    if (direction) params.append('direction', direction);
    if (options?.query?.trim()) params.append('q', options.query.trim());
    if (options?.isActive !== undefined) params.append('isActive', String(options.isActive));
    const response = await this.client.get<Connector[]>(`/connectors?${params}`);
    return response.data;
  }

  /**
   * Retrieves or enumerates the requested state.
   */
  async getConnector(id: string): Promise<Connector> {
    const response = await this.client.get<Connector>(`/connectors/${id}`);
    return response.data;
  }

  /**
   * Retrieves or enumerates the requested state.
   */
  async getConnectorReferences(id: string): Promise<ConnectorReferenceSummary> {
    const response = await this.client.get<ConnectorReferenceSummary>(`/connectors/${id}/references`);
    return response.data;
  }

  /**
   * Retrieves or enumerates the requested state.
   */
  async getConnectorImpact(
    id: string,
    sinceHours: 1 | 24 | 168 = 24,
    options?: { activeOnly?: boolean; riskAtLeast?: 'LOW' | 'MEDIUM' | 'HIGH' }
  ): Promise<ConnectorImpactSummary> {
    const params = new URLSearchParams({ sinceHours: String(sinceHours) });
    if (options?.activeOnly !== undefined) params.set('activeOnly', String(options.activeOnly));
    if (options?.riskAtLeast) params.set('riskAtLeast', options.riskAtLeast);
    const response = await this.client.get<ConnectorImpactSummary>(`/connectors/${id}/impact?${params.toString()}`);
    return response.data;
  }

  /**
   * Creates or persists the requested state.
   */
  async createConnector(tenantId: string, name: string, connectorType: string, direction: string, config?: Record<string, unknown>, description?: string, options?: { pollingEnabled?: boolean; pollingIntervalSeconds?: number }): Promise<Connector> {
    const response = await this.client.post<Connector>(`/connectors?tenantId=${tenantId}`, {
      name,
      connectorType,
      direction,
      config: config || {},
      description,
      pollingEnabled: options?.pollingEnabled ?? false,
      pollingIntervalSeconds: options?.pollingIntervalSeconds ?? 60,
    });
    return response.data;
  }

  /**
   * Updates the requested state.
   */
  async updateConnector(id: string, data: Partial<Connector>): Promise<Connector> {
    const response = await this.client.patch<Connector>(`/connectors/${id}`, data);
    return response.data;
  }

  /**
   * Removes, stops, or releases the requested state.
   */
  async deleteConnector(id: string): Promise<void> {
    await this.client.delete(`/connectors/${id}`);
  }

  /**
   * Implements `cloneConnector` for this source unit.
   */
  async cloneConnector(
    id: string,
    options?: { name?: string; direction?: string; description?: string; keepSecrets?: boolean }
  ): Promise<Connector & { message?: string; secretsStripped?: boolean }> {
    const response = await this.client.post<Connector & { message?: string; secretsStripped?: boolean }>(
      `/connectors/${id}/clone`,
      {
        ...(options?.name ? { name: options.name } : {}),
        ...(options?.direction ? { direction: options.direction } : {}),
        ...(options?.description !== undefined ? { description: options.description } : {}),
        keepSecrets: options?.keepSecrets ?? false,
      }
    );
    return response.data;
  }

  /**
   * Initializes, connects, or acquires the named runtime resource.
   */
  async connectConnector(id: string): Promise<Connector> {
    const response = await this.client.post<Connector>(`/connectors/${id}/connect`);
    return response.data;
  }

  /**
   * Removes, stops, or releases the requested state.
   */
  async disconnectConnector(id: string, force = false): Promise<Connector> {
    const suffix = force ? '?force=true' : '';
    const response = await this.client.post<Connector>(`/connectors/${id}/disconnect${suffix}`);
    return response.data;
  }

  /**
   * Implements `testConnector` for this source unit.
   */
  async testConnector(id: string): Promise<{ ok: boolean; message: string; missing?: string[] }> {
    const response = await this.client.post<{ ok: boolean; message: string; missing?: string[] }>(`/connectors/${id}/test`);
    return response.data;
  }

  /**
   * Retrieves or enumerates the requested state.
   */
  async getConnectorStatus(id: string): Promise<ConnectorStatus> {
    const response = await this.client.get<ConnectorStatus>(`/connectors/${id}/status`);
    return response.data;
  }

  /**
   * Retrieves or enumerates the requested state.
   */
  async getConnectorStatuses(tenantId: string): Promise<Record<string, ConnectorStatus>> {
    const response = await this.client.get<Record<string, ConnectorStatus>>(`/connectors/statuses?tenantId=${tenantId}`);
    return response.data;
  }

  // Roles
  /**
   * Retrieves or enumerates the requested state.
   */
  async getRoles(tenantId?: string): Promise<Role[]> {
    const params = tenantId ? new URLSearchParams({ tenantId }) : '';
    const response = await this.client.get<Role[]>(`/roles${params ? `?${params}` : ''}`);
    return response.data;
  }

  /**
   * Retrieves or enumerates the requested state.
   */
  async getRole(id: string): Promise<Role> {
    const response = await this.client.get<Role>(`/roles/${id}`);
    return response.data;
  }

  /**
   * Creates or persists the requested state.
   */
  async createRole(tenantId: string | undefined, name: string, abilities?: string[], description?: string): Promise<Role> {
    const response = await this.client.post<Role>(`/roles${tenantId ? `?tenantId=${tenantId}` : ''}`, { 
      name, 
      abilities: abilities || [],
      description 
    });
    return response.data;
  }

  /**
   * Updates the requested state.
   */
  async updateRole(id: string, data: Partial<Role>): Promise<Role> {
    const response = await this.client.patch<Role>(`/roles/${id}`, data);
    return response.data;
  }

  /**
   * Removes, stops, or releases the requested state.
   */
  async deleteRole(id: string): Promise<void> {
    await this.client.delete(`/roles/${id}`);
  }

  // Data blobs
  /**
   * Retrieves or enumerates the requested state.
   */
  async getDataBlobs(tenantId: string): Promise<DataBlob[]> {
    const response = await this.publicClient.get<{ blobs: DataBlob[] }>(`/data/${tenantId}/blobs`);
    return response.data.blobs;
  }

  /**
   * Implements `uploadDataBlob` for this source unit.
   */
  async uploadDataBlob(tenantId: string, file: File): Promise<{ id: string; name: string; sizeBytes: number }> {
    const formData = new FormData();
    formData.append('name', file.name);
    formData.append('file', file);
    formData.append('contentType', file.type || 'application/octet-stream');
    const response = await this.publicClient.post<{ id: string; name: string; sizeBytes: number }>(`/data/${tenantId}/blobs`, formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
    });
    return response.data;
  }

  /**
   * Retrieves or enumerates the requested state.
   */
  getDataBlobUrl(tenantId: string, blobId: string): string {
    return `${PUBLIC_API_BASE_URL}/data/${tenantId}/blobs/${encodeURIComponent(blobId)}/download`;
  }

  /**
   * Removes, stops, or releases the requested state.
   */
  async deleteDataBlob(tenantId: string, blobId: string): Promise<void> {
    await this.publicClient.delete(`/data/${tenantId}/blobs/${encodeURIComponent(blobId)}`);
  }

  // ── Metadata ──────────────────────────────────────────────────────────────

  async getMetadataTypes(tenantId: string): Promise<MetadataType[]> {
    return (await this.client.get<MetadataType[]>('/metadata/types', { params: { tenantId } })).data;
  }

  async createMetadataType(tenantId: string, type: Partial<MetadataType>): Promise<MetadataType> {
    return (await this.client.post<MetadataType>('/metadata/types', type, { params: { tenantId } })).data;
  }

  async deleteMetadataType(id: string): Promise<void> {
    await this.client.delete(`/metadata/types/${id}`);
  }

  async getMetadataTemplates(): Promise<MetadataTemplate[]> {
    return (await this.client.get<MetadataTemplate[]>('/metadata/templates')).data;
  }

  async installMetadataTemplate(tenantId: string, key: string): Promise<{ installed: string[]; skipped: number }> {
    return (await this.client.post(`/metadata/templates/${key}/install`, null, { params: { tenantId } })).data;
  }

  async getMetadataObjects(tenantId: string, params: { type?: string; q?: string; limit?: number; offset?: number }): Promise<{ total: number; items: MetadataObjectSummary[] }> {
    return (await this.client.get('/metadata/objects', { params: { tenantId, ...params } })).data;
  }

  async getMetadataObject(id: string, at?: string): Promise<MetadataObjectDetail> {
    return (await this.client.get<MetadataObjectDetail>(`/metadata/objects/${id}`, { params: at ? { at } : {} })).data;
  }

  async upsertMetadataObject(tenantId: string, body: Record<string, unknown>): Promise<{ id: string; created: boolean; changed: boolean }> {
    return (await this.client.post('/metadata/objects/upsert', body, { params: { tenantId } })).data;
  }

  async importMetadata(tenantId: string, mapping: SaveMetadataMapping, records: Record<string, unknown>[]): Promise<{ created: number; changed: number; unchanged: number }> {
    return (await this.client.post('/metadata/objects/import', { mapping, records }, { params: { tenantId }, timeout: 300000 })).data;
  }

  async deleteMetadataObject(id: string): Promise<void> {
    await this.client.delete(`/metadata/objects/${id}`);
  }
}

export const api = new ApiService();
export default api;
