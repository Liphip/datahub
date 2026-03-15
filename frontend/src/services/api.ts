import axios, { AxiosInstance, InternalAxiosRequestConfig } from 'axios';
import type { AuthResponse, User, Tenant, CodeModule, ModuleVersion, Pipeline, PipelineRun, AlertChannel, AlertRule, AlertHistory } from '../types';

const API_BASE_URL = import.meta.env.VITE_API_URL || '/api/internal/v1';

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
      (response) => response,
      (error) => {
        if (error.response?.status === 401) {
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

  // Tenants
  async getTenants(): Promise<Tenant[]> {
    const response = await this.client.get<Tenant[]>('/tenants');
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

  // Users
  async getUsers(tenantId: string): Promise<User[]> {
    const response = await this.client.get<User[]>(`/users?tenantId=${tenantId}`);
    return response.data;
  }

  async createUser(tenantId: string, email: string, password: string, data?: Partial<User>): Promise<User> {
    const passwordHash = await sha256(password);
    const response = await this.client.post<User>(`/users?tenantId=${tenantId}`, { email, passwordHash, ...data });
    return response.data;
  }

  async updateUser(id: string, data: Partial<User>): Promise<User> {
    const response = await this.client.patch<User>(`/users/${id}`, data);
    return response.data;
  }

  async deleteUser(id: string): Promise<void> {
    await this.client.delete(`/users/${id}`);
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
  async getPipelines(tenantId: string): Promise<Pipeline[]> {
    const response = await this.client.get<Pipeline[]>(`/pipelines?tenantId=${tenantId}`);
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
    const response = await this.client.post<AlertChannel>(`/alerts/channels?tenantId=${tenantId}`, { name, channelType, config });
    return response.data;
  }

  async getAlertRules(tenantId: string): Promise<AlertRule[]> {
    const response = await this.client.get<AlertRule[]>(`/alerts/rules?tenantId=${tenantId}`);
    return response.data;
  }

  async createAlertRule(tenantId: string, name: string, condition: Record<string, unknown>, channelId?: string, pipelineId?: string): Promise<AlertRule> {
    const response = await this.client.post<AlertRule>(`/alerts/rules?tenantId=${tenantId}`, { name, condition, channelId, pipelineId });
    return response.data;
  }

  async getAlertHistory(tenantId: string, limit?: number): Promise<AlertHistory[]> {
    const params = new URLSearchParams({ tenantId });
    if (limit) params.append('limit', limit.toString());
    const response = await this.client.get<AlertHistory[]>(`/alerts/history?${params}`);
    return response.data;
  }

  // Data
  async queryData(tenantId: string, table: string, params?: Record<string, unknown>): Promise<unknown> {
    const response = await this.client.get(`/data/${tenantId}/query`, { params: { table, ...params } });
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
}

export const api = new ApiService();
export default api;
