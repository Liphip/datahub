import axios, { AxiosInstance, InternalAxiosRequestConfig } from 'axios';
import type { AuthResponse, User, Tenant, CodeModule, ModuleVersion, Pipeline, PipelineRun, AlertChannel, AlertRule, AlertHistory, Connector, Role, Membership, TenantContext } from '../types';

const API_BASE_URL = import.meta.env.VITE_API_URL || '/api/internal/v1';
export const API_STATUS_EVENT = 'dataturntable:api-status';

type ApiStatusDetail = {
  available: boolean;
  status?: number;
  message?: string;
};

function emitApiStatus(detail: ApiStatusDetail): void {
  if (typeof window === 'undefined') return;
  window.dispatchEvent(new CustomEvent<ApiStatusDetail>(API_STATUS_EVENT, { detail }));
}

async function sha256(message: string): Promise<string> {
  const msgBuffer = new TextEncoder().encode(message);
  const hashBuffer = await crypto.subtle.digest('SHA-256', msgBuffer);
  const hashArray = new Uint8Array(hashBuffer);
  let binary = '';
  for (let i = 0; i < hashArray.length; i++) {
    binary += String.fromCharCode(hashArray[i]);
  }
  return btoa(binary);
}

class ApiService {
  private client: AxiosInstance;

  constructor() {
    this.client = axios.create({
      baseURL: API_BASE_URL,
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
  }

  // Auth
  async login(email: string, password: string, tenantId?: string): Promise<AuthResponse> {
    const passwordHash = await sha256(password);
    const response = await this.client.post<AuthResponse>('/auth/login', { email, passwordHash, tenantId });
    return response.data;
  }

  async register(email: string, password: string, tenantName?: string, tenantId?: string): Promise<AuthResponse> {
    const passwordHash = await sha256(password);
    const response = await this.client.post<AuthResponse>('/auth/register', { email, passwordHash, tenantName, tenantId });
    return response.data;
  }

  async getCurrentUser(): Promise<User> {
    const response = await this.client.get<User>('/auth/me');
    return response.data;
  }

  async getAuthTenants(): Promise<TenantContext[]> {
    const response = await this.client.get<TenantContext[]>('/auth/tenants');
    return response.data;
  }

  async switchTenant(tenantId: string): Promise<AuthResponse> {
    const response = await this.client.post<AuthResponse>('/auth/switch-tenant', { tenantId });
    return response.data;
  }

  // Tenants
  async getTenants(): Promise<Tenant[]> {
    const response = await this.client.get<Tenant[]>('/tenants');
    return response.data;
  }

  async getTenantsRecursive(): Promise<Tenant[]> {
    const response = await this.client.get<Tenant[]>('/tenants?recursive=true');
    return response.data;
  }

  async getTenant(id: string): Promise<Tenant> {
    const response = await this.client.get<Tenant>(`/tenants/${id}`);
    return response.data;
  }

  async createTenant(name: string, parentId?: string): Promise<Tenant> {
    const response = await this.client.post<Tenant>('/tenants', { name, parentId });
    return response.data;
  }

  async updateTenant(id: string, data: Partial<Tenant>): Promise<Tenant> {
    const response = await this.client.patch<Tenant>(`/tenants/${id}`, data);
    return response.data;
  }

  async deleteTenant(id: string): Promise<void> {
    await this.client.delete(`/tenants/${id}`);
  }

  async getTenantChildren(parentId: string): Promise<Tenant[]> {
    const response = await this.client.get<Tenant[]>(`/tenants/${parentId}/children`);
    return response.data;
  }

  // Users
  async getUsers(tenantId: string): Promise<User[]> {
    const response = await this.client.get<User[]>(`/auth-users?tenantId=${tenantId}`);
    return response.data;
  }

  async createUser(tenantId: string, email: string, password: string, data?: Partial<User>): Promise<User> {
    const passwordHash = await sha256(password);
    const response = await this.client.post<User>(`/auth-users?tenantId=${tenantId}`, { email, passwordHash, ...data });
    return response.data;
  }

  async updateUser(id: string, data: Partial<User>): Promise<User> {
    const response = await this.client.patch<User>(`/auth-users/${id}`, data);
    return response.data;
  }

  async deleteUser(id: string): Promise<void> {
    await this.client.delete(`/auth-users/${id}`);
  }

  async getTenantMemberships(tenantId: string): Promise<Membership[]> {
    const response = await this.client.get<Membership[]>(`/tenants/${tenantId}/memberships`);
    return response.data;
  }

  async createTenantMembership(tenantId: string, authUserId: string, roleId: string, abilities: string[] = []): Promise<Membership> {
    const response = await this.client.post<Membership>(`/tenants/${tenantId}/memberships`, {
      authUserId,
      roleId,
      abilities,
    });
    return response.data;
  }

  async updateTenantMembership(tenantId: string, membershipId: string, roleId?: string, abilities?: string[]): Promise<Membership> {
    const response = await this.client.patch<Membership>(`/tenants/${tenantId}/memberships/${membershipId}`, {
      roleId,
      abilities,
    });
    return response.data;
  }

  async deleteTenantMembership(tenantId: string, membershipId: string): Promise<void> {
    await this.client.delete(`/tenants/${tenantId}/memberships/${membershipId}`);
  }

  // Modules
  async getModules(tenantId: string, type?: string): Promise<CodeModule[]> {
    const params = new URLSearchParams({ tenantId });
    if (type) params.append('type', type);
    const response = await this.client.get<CodeModule[]>(`/modules?${params}`);
    return response.data;
  }

  async getModule(id: string): Promise<CodeModule> {
    const response = await this.client.get<CodeModule>(`/modules/${id}`);
    return response.data;
  }

  async createModule(tenantId: string, name: string, moduleType: string, description?: string): Promise<CodeModule> {
    const response = await this.client.post<CodeModule>(`/modules?tenantId=${tenantId}`, { name, moduleType, description });
    return response.data;
  }

  async deleteModule(id: string): Promise<void> {
    await this.client.delete(`/modules/${id}`);
  }

  async getModuleVersions(moduleId: string): Promise<ModuleVersion[]> {
    const response = await this.client.get<ModuleVersion[]>(`/modules/${moduleId}/versions`);
    return response.data;
  }

  async createModuleVersion(moduleId: string, code: string, requirements?: string, versionTag?: string): Promise<ModuleVersion> {
    const response = await this.client.post<ModuleVersion>(`/modules/${moduleId}/versions`, { code, requirements, versionTag });
    return response.data;
  }

  // Pipelines
  async getPipelines(tenantId: string, options?: { includeRunSummary?: boolean; runHealth?: 'ALL' | 'PROBLEMATIC' | 'RUNNING' | 'FAILED' | 'HEALTHY' }): Promise<Pipeline[]> {
    const includeRunSummary = options?.includeRunSummary ? '&includeRunSummary=true' : '';
    const runHealth = options?.runHealth && options.runHealth !== 'ALL' ? `&runHealth=${options.runHealth}` : '';
    const response = await this.client.get<Pipeline[]>(`/pipelines?tenantId=${tenantId}${includeRunSummary}${runHealth}`);
    return response.data;
  }

  async getPipeline(id: string): Promise<Pipeline> {
    const response = await this.client.get<Pipeline>(`/pipelines/${id}`);
    return response.data;
  }

  async createPipeline(tenantId: string, name: string, data?: Partial<Pipeline>): Promise<Pipeline> {
    const response = await this.client.post<Pipeline>(`/pipelines?tenantId=${tenantId}`, { name, ...data });
    return response.data;
  }

  async updatePipeline(id: string, data: Partial<Pipeline>): Promise<Pipeline> {
    const response = await this.client.patch<Pipeline>(`/pipelines/${id}`, data);
    return response.data;
  }

  async deletePipeline(id: string): Promise<void> {
    await this.client.delete(`/pipelines/${id}`);
  }

  async triggerPipeline(id: string, triggerType?: string): Promise<PipelineRun> {
    const response = await this.client.post<PipelineRun>(`/pipelines/${id}/run${triggerType ? `?triggerType=${triggerType}` : ''}`);
    return response.data;
  }

  async getPipelineRuns(pipelineId: string): Promise<PipelineRun[]> {
    const response = await this.client.get<PipelineRun[]>(`/pipelines/${pipelineId}/runs`);
    return response.data;
  }

  // Alerts
  async getAlertChannels(tenantId: string): Promise<AlertChannel[]> {
    const response = await this.client.get<AlertChannel[]>(`/alerts/channels?tenantId=${tenantId}`);
    return response.data;
  }

  async createAlertChannel(tenantId: string, name: string, channelType: string, config?: Record<string, unknown>): Promise<AlertChannel> {
    const response = await this.client.post<AlertChannel>(`/alerts/channels?tenantId=${tenantId}`, {
      tenantId,
      name,
      channelType,
      config,
    });
    return response.data;
  }

  async deleteAlertChannel(id: string): Promise<void> {
    await this.client.delete(`/alerts/channels/${id}`);
  }

  async updateAlertChannel(id: string, data: Partial<AlertChannel>): Promise<AlertChannel> {
    const response = await this.client.patch<AlertChannel>(`/alerts/channels/${id}`, data);
    return response.data;
  }

  async getAlertRules(tenantId: string): Promise<AlertRule[]> {
    const response = await this.client.get<AlertRule[]>(`/alerts/rules?tenantId=${tenantId}`);
    return response.data;
  }

  async createAlertRule(
    tenantId: string,
    name: string,
    condition: Record<string, unknown> | string,
    channelId?: string,
    pipelineId?: string
  ): Promise<AlertRule> {
    const response = await this.client.post<AlertRule>(`/alerts/rules?tenantId=${tenantId}`, {
      tenantId,
      name,
      condition,
      channelId,
      pipelineId,
    });
    return response.data;
  }

  async deleteAlertRule(id: string): Promise<void> {
    await this.client.delete(`/alerts/rules/${id}`);
  }

  async updateAlertRule(id: string, data: Partial<AlertRule>): Promise<AlertRule> {
    const response = await this.client.patch<AlertRule>(`/alerts/rules/${id}`, data);
    return response.data;
  }

  async getAlertHistory(tenantId: string, limit?: number): Promise<AlertHistory[]> {
    const params = new URLSearchParams({ tenantId });
    if (limit) params.append('limit', limit.toString());
    const response = await this.client.get<AlertHistory[]>(`/alerts/history?${params}`);
    return response.data;
  }

  // Data
  async queryData(
    tenantId: string,
    table: string,
    params?: Record<string, unknown>
  ): Promise<{ data: unknown[]; count: number }> {
    const response = await this.client.get<{ data: unknown[]; count: number }>(`/data/${tenantId}/query`, {
      params: { table, ...params },
    });
    return response.data;
  }

  async insertData(tenantId: string, table: string, data: unknown[]): Promise<{ inserted: number }> {
    const response = await this.client.post(`/data/${tenantId}/insert?table=${table}`, data);
    return response.data;
  }

  async listTables(tenantId: string): Promise<{ tables: { name: string; type: string }[] }> {
    const response = await this.client.get(`/data/${tenantId}/tables`);
    return response.data;
  }

  async deleteTable(tenantId: string, table: string): Promise<void> {
    await this.client.delete(`/data/${tenantId}/tables/${encodeURIComponent(table)}`);
  }

  // Connectors
  async getConnectors(tenantId: string, type?: string, direction?: string): Promise<Connector[]> {
    const params = new URLSearchParams({ tenantId });
    if (type) params.append('type', type);
    if (direction) params.append('direction', direction);
    const response = await this.client.get<Connector[]>(`/connectors?${params}`);
    return response.data;
  }

  async getConnector(id: string): Promise<Connector> {
    const response = await this.client.get<Connector>(`/connectors/${id}`);
    return response.data;
  }

  async createConnector(tenantId: string, name: string, connectorType: string, direction: string, config?: Record<string, unknown>, description?: string): Promise<Connector> {
    const response = await this.client.post<Connector>(`/connectors?tenantId=${tenantId}`, { 
      name, 
      connectorType, 
      direction, 
      config: config || {},
      description 
    });
    return response.data;
  }

  async updateConnector(id: string, data: Partial<Connector>): Promise<Connector> {
    const response = await this.client.patch<Connector>(`/connectors/${id}`, data);
    return response.data;
  }

  async deleteConnector(id: string): Promise<void> {
    await this.client.delete(`/connectors/${id}`);
  }

  // Roles
  async getRoles(tenantId?: string): Promise<Role[]> {
    const params = tenantId ? new URLSearchParams({ tenantId }) : '';
    const response = await this.client.get<Role[]>(`/roles${params ? `?${params}` : ''}`);
    return response.data;
  }

  async getRole(id: string): Promise<Role> {
    const response = await this.client.get<Role>(`/roles/${id}`);
    return response.data;
  }

  async createRole(tenantId: string | undefined, name: string, abilities?: string[], description?: string): Promise<Role> {
    const response = await this.client.post<Role>(`/roles${tenantId ? `?tenantId=${tenantId}` : ''}`, { 
      name, 
      abilities: abilities || [],
      description 
    });
    return response.data;
  }

  async updateRole(id: string, data: Partial<Role>): Promise<Role> {
    const response = await this.client.patch<Role>(`/roles/${id}`, data);
    return response.data;
  }

  async deleteRole(id: string): Promise<void> {
    await this.client.delete(`/roles/${id}`);
  }
}

export const api = new ApiService();
export default api;
