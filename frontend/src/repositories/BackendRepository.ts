import type { IDeviceRepository, ITelemetryRepository, IEventRepository, IRuleRepository, IActionRepository, IPipelineRepository, DeviceState, TelemetryData, PipelineTracePayload } from './types';
const API_BASE = 'http://localhost:8080/api/v1';

export class BackendDeviceRepository implements IDeviceRepository {
  async getDevice(id: string): Promise<DeviceState> {
    const response = await fetch(`${API_BASE}/devices/${id}`);
    if (!response.ok) {
      if (response.status === 404) throw new Error('Device not found');
      throw new Error(`Backend fetch failed: ${response.status}`);
    }
    const data = await response.json();
    
    return {
      id: data.id,
      name: data.name,
      status: data.status,
      deviceType: data.type || 'Generic',
      mqttTopic: data.mqttTopic || '',
      healthScore: 0, // Calculated by MetricEngine in resolver
      twinQuality: 0, // Calculated by MetricEngine in resolver
      lastSeen: data.lastSeen || 'Never',
      // The backend Device API does not currently expose these fields.
      // null (not 0) means "not available" - 0 would be a real measurement.
      signalStrength: null,
      battery: null,
      temperature: null,
      humidity: null,
      metrics: [] // We'll populate this if backend sends generic metrics, otherwise telemetry API handles it
    };
  }

  async getDevices(): Promise<DeviceState[]> {
    const response = await fetch(`${API_BASE}/devices`);
    if (!response.ok) {
      throw new Error(`Failed to fetch devices: ${response.status}`);
    }
    const data = await response.json();
    const items = data.content || data || [];
    return items.map((d: any) => ({
      id: d.id,
      name: d.name,
      status: d.status,
      deviceType: d.type || 'Generic',
      mqttTopic: d.mqttTopic || '',
      healthScore: 0,
      twinQuality: 0,
      lastSeen: d.lastSeen || 'Never',
      // Not available from the backend Device API - see getDevice() above.
      signalStrength: null,
      battery: null,
      temperature: null,
      humidity: null,
      metrics: []
    }));
  }
}

export class BackendTelemetryRepository implements ITelemetryRepository {
  async getTelemetry(deviceId: string, metric: string): Promise<TelemetryData[]> {
    const response = await fetch(`${API_BASE}/devices/${deviceId}/telemetry?limit=50&sort=desc`);
    if (!response.ok) {
      throw new Error(`Failed to fetch telemetry: ${response.status}`);
    }
    const data = await response.json();
    
    if (data.content) {
      // Filter the paginated response to only the requested metric
      const filtered = data.content.filter((item: any) => item.payload && item.payload[metric] !== undefined);
      return filtered.map((item: any) => ({
        timestamp: new Date(item.timestamp).getTime(),
        value: Number(item.payload[metric])
      }));
    }
    return [];
  }

  async getTelemetryHistory(deviceId: string): Promise<any[]> {
    const response = await fetch(`${API_BASE}/devices/${deviceId}/telemetry?limit=50&sort=desc`);
    if (!response.ok) {
      throw new Error(`Failed to fetch telemetry: ${response.status}`);
    }
    const data = await response.json();
    return data.content || [];
  }
}

export class BackendEventRepository implements IEventRepository {
  async getEvents(deviceId?: string, limit: number = 50): Promise<any[]> {
    let url = `${API_BASE}/events?limit=${limit}`;
    if (deviceId) url += `&deviceId=${deviceId}`;
    
    const response = await fetch(url);
    if (!response.ok) {
      throw new Error(`Failed to fetch events: ${response.status}`);
    }
    const data = await response.json();
    return data.content || data || [];
  }
}

export class BackendRuleRepository implements IRuleRepository {
  async getRules(_deviceId?: string): Promise<any[]> {
    const response = await fetch(`${API_BASE}/rules`);
    if (!response.ok) {
      throw new Error(`Failed to fetch rules: ${response.status}`);
    }
    const data = await response.json();
    return data || [];
  }
}

export class BackendActionRepository implements IActionRepository {
  async getActions(deviceId?: string, limit: number = 50): Promise<any[]> {
    let url = `${API_BASE}/actions/history?limit=${limit}`;
    if (deviceId) url += `&deviceId=${deviceId}`;
    
    const response = await fetch(url);
    if (!response.ok) {
      throw new Error(`Failed to fetch actions: ${response.status}`);
    }
    const data = await response.json();
    return data.content || data || [];
  }
}

export class BackendPipelineRepository implements IPipelineRepository {
  async getPipelineHistory(limit?: number): Promise<PipelineTracePayload[]> {
    try {
      const response = await fetch(`${API_BASE}/pipeline/history?limit=${limit || 100}`);
      if (!response.ok) return [];
      return await response.json();
    } catch (e) {
      return [];
    }
  }

  async getPipelineByCorrelationId(correlationId: string): Promise<PipelineTracePayload[]> {
    try {
      const response = await fetch(`${API_BASE}/pipeline/${correlationId}`);
      if (!response.ok) return [];
      return await response.json();
    } catch (e) {
      return [];
    }
  }

  async getPipelineMetrics(): Promise<any> {
    try {
      const response = await fetch(`${API_BASE}/pipeline/metrics`);
      if (!response.ok) return null;
      return await response.json();
    } catch (e) {
      return null;
    }
  }
}
