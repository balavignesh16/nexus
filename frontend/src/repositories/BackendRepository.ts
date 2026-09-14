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
      deviceType: data.deviceType || 'Generic',
      mqttTopic: data.mqttTopic || '',
      lastSeen: data.lastSeen || 'Never',
      spaceId: data.spaceId,
      // The backend Device API does not currently expose these fields.
      // null (not 0) means "not available" - 0 would be a real measurement.
      signalStrength: null,
      battery: null,
      temperature: null,
      humidity: null,
      metrics: [], // We'll populate this if backend sends generic metrics, otherwise telemetry API handles it
      // Tier 5C: real, backend-provisioned capabilities - never fabricated,
      // defaults to an empty (never null/undefined) array when the API
      // reports none configured.
      capabilities: data.capabilities || []
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
      deviceType: d.deviceType || 'Generic',
      mqttTopic: d.mqttTopic || '',
      lastSeen: d.lastSeen || 'Never',
      spaceId: d.spaceId,
      // Not available from the backend Device API - see getDevice() above.
      signalStrength: null,
      battery: null,
      temperature: null,
      humidity: null,
      metrics: [],
      capabilities: d.capabilities || []
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

    // Phase 8 fix: the real endpoint returns { records: [{sensorType, value, ...}] },
    // not { content: [{payload: {...}}] } - the previous parsing here always
    // silently returned [] against the real backend (data.content is always
    // undefined). See getTelemetryHistory() below for the same fix.
    const records = data.records || data.content || [];
    return records
      .filter((item: any) => item.sensorType === metric)
      .map((item: any) => ({
        timestamp: new Date(item.timestamp).getTime(),
        value: Number(item.value)
      }));
  }

  async getTelemetryHistory(deviceId: string): Promise<any[]> {
    const response = await fetch(`${API_BASE}/devices/${deviceId}/telemetry?limit=50&sort=desc`);
    if (!response.ok) {
      throw new Error(`Failed to fetch telemetry: ${response.status}`);
    }
    const data = await response.json();

    // Real records are flat ({id, deviceId, timestamp, sensorType, value,
    // unit}) - normalized here into the {timestamp, payload: {[sensorType]:
    // value}} shape every downstream consumer (TelemetryPage, OverviewTab,
    // the Digital Twin state) already expects, so the fix lives in exactly
    // one place rather than in every caller.
    const records = data.records || data.content || [];
    return records.map((item: any) => ({
      id: item.id,
      deviceId: item.deviceId,
      timestamp: item.timestamp,
      payload: { [item.sensorType]: item.value },
      unit: item.unit,
    }));
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
  async getRules(deviceId?: string): Promise<any[]> {
    const url = deviceId ? `${API_BASE}/rules?deviceId=${encodeURIComponent(deviceId)}` : `${API_BASE}/rules`;
    const response = await fetch(url);
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

  async getActionsByMatchId(matchId: string): Promise<any[]> {
    const response = await fetch(`${API_BASE}/actions/history?matchId=${encodeURIComponent(matchId)}`);
    if (!response.ok) {
      throw new Error(`Failed to fetch actions for match: ${response.status}`);
    }
    const data = await response.json();
    return data.content || data || [];
  }
}

export class BackendPipelineRepository implements IPipelineRepository {
  async getPipelineHistory(limit?: number, deviceId?: string): Promise<PipelineTracePayload[]> {
    try {
      let url = `${API_BASE}/pipeline/history?limit=${limit || 100}`;
      if (deviceId) url += `&deviceId=${encodeURIComponent(deviceId)}`;
      const response = await fetch(url);
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
