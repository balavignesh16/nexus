import type { IDeviceRepository, ITelemetryRepository, IEventRepository, IRuleRepository, IActionRepository, IPipelineRepository, DeviceState, TelemetryData, PipelineTracePayload } from './types';
import { DemoDeviceRepository, DemoTelemetryRepository, DemoEventRepository, DemoRuleRepository, DemoActionRepository, DemoPipelineRepository } from './DemoRepository';
import { BackendDeviceRepository, BackendTelemetryRepository, BackendEventRepository, BackendRuleRepository, BackendActionRepository, BackendPipelineRepository } from './BackendRepository';
import { MetricEngine } from '../metrics/MetricEngine';

/**
 * Maps frontend simulator string IDs to Backend UUIDs.
 * Only applied for BackendRepository calls.
 */
const ID_MAP: Record<string, string> = {
  "hvac-1": "b5e9f1a2-7d3c-4e8a-9f1b-2c3d4e5f6a7b",
  "hvac-2": "a4d8e091-6c2b-3d79-8e0a-1b2c3d4e5f6a",
  'dev-hvac-001': '66666666-6666-6666-6666-666666666666',
};

export class DataSourceResolver implements IDeviceRepository, ITelemetryRepository, IEventRepository, IRuleRepository, IActionRepository, IPipelineRepository {
  private backendDevice = new BackendDeviceRepository();
  private demoDevice = new DemoDeviceRepository();
  
  private backendTelemetry = new BackendTelemetryRepository();
  private demoTelemetry = new DemoTelemetryRepository();

  private backendEvent = new BackendEventRepository();
  private demoEvent = new DemoEventRepository();

  private backendRule = new BackendRuleRepository();
  private demoRule = new DemoRuleRepository();
  
  private backendAction = new BackendActionRepository();
  private demoAction = new DemoActionRepository();

  private backendPipeline = new BackendPipelineRepository();
  private demoPipeline = new DemoPipelineRepository();

  async getDevice(id: string): Promise<DeviceState> {
    const backendId = ID_MAP[id] || id;
    let backendState: Partial<DeviceState> | null = null;
    let backendLatency = 0;

    const start = performance.now();
    try {
      // Try to fetch from backend
      backendState = await this.backendDevice.getDevice(backendId);
      backendLatency = performance.now() - start;
    } catch (e) {
      // Backend failed, disconnected, or ID is invalid. Fallback cleanly.
      console.warn(`Backend device fetch failed for ${backendId}, falling back to Demo Mode.`);
    }

    // Always fetch Demo State to blend missing fields
    const demoState = await this.demoDevice.getDevice(id);

    if (!backendState) {
      // Pure Demo Mode
      demoState.healthScore = MetricEngine.calculateHealthScore(demoState, 0);
      demoState.twinQuality = MetricEngine.calculateTwinQuality(demoState);
      return demoState;
    }

    // Hybrid Blending Mode: Overlay backend data on top of demo data
    const hybridState: DeviceState = {
      ...demoState, // base is demo state (contains signal strength, battery simulation, etc)
      ...backendState, // overwrite with any available backend data
      id: demoState.id, // Keep original frontend ID to prevent UI mismatch
      // Blend metrics: if backend provided telemetry metrics, use them. Otherwise keep simulated metrics.
      lastSeen: backendState.lastSeen || demoState.lastSeen,
      metrics: backendState.metrics?.length ? backendState.metrics : demoState.metrics,
      status: backendState.status || demoState.status,
    };

    // Derived Metrics
    hybridState.healthScore = MetricEngine.calculateHealthScore(hybridState, backendLatency);
    hybridState.twinQuality = MetricEngine.calculateTwinQuality(hybridState);

    // Track latency (simulate putting this in a store later)
    (window as any).__lastLatency = backendLatency;

    return hybridState;
  }

  async getDevices(): Promise<DeviceState[]> {
    try {
      const backendDevices = await this.backendDevice.getDevices();
      if (backendDevices && backendDevices.length > 0) return backendDevices;
    } catch (e) {
      console.warn(`Backend fetch failed for all devices, falling back to Demo Mode.`);
    }
    return this.demoDevice.getDevices();
  }

  async getTelemetry(deviceId: string, metric: string): Promise<TelemetryData[]> {
    const backendId = ID_MAP[deviceId] || deviceId;
    try {
      const data = await this.backendTelemetry.getTelemetry(backendId, metric);
      if (data && data.length > 0) return data;
    } catch (e) {
      // Fallback
    }
    
    // Pure Demo Mode Fallback
    return this.demoTelemetry.getTelemetry(deviceId, metric);
  }

  async getTelemetryHistory(deviceId: string): Promise<any[]> {
    const backendId = ID_MAP[deviceId] || deviceId;
    try {
      const data = await this.backendTelemetry.getTelemetryHistory(backendId);
      if (data && data.length > 0) return data;
    } catch (e) {
      // Fallback
    }
    
    return this.demoTelemetry.getTelemetryHistory(deviceId);
  }

  async getEvents(deviceId?: string, limit?: number): Promise<any[]> {
    const backendId = deviceId ? (ID_MAP[deviceId] || deviceId) : undefined;
    try {
      const data = await this.backendEvent.getEvents(backendId, limit);
      if (data && data.length > 0) return data;
    } catch (e) {
      // Fallback
    }

    return this.demoEvent.getEvents(deviceId, limit);
  }

  async getRules(deviceId?: string): Promise<any[]> {
    const backendId = deviceId ? (ID_MAP[deviceId] || deviceId) : undefined;
    try {
      const data = await this.backendRule.getRules(backendId);
      if (data && data.length > 0) return data;
    } catch (e) {
      // Fallback
    }

    return this.demoRule.getRules(deviceId);
  }

  async getActions(deviceId?: string, limit?: number): Promise<any[]> {
    const backendId = deviceId ? (ID_MAP[deviceId] || deviceId) : undefined;
    try {
      const data = await this.backendAction.getActions(backendId, limit);
      if (data && data.length > 0) return data;
    } catch (e) {
      // Fallback
    }

    return this.demoAction.getActions(deviceId, limit);
  }

  async getPipelineHistory(limit?: number): Promise<PipelineTracePayload[]> {
    try {
      const data = await this.backendPipeline.getPipelineHistory(limit);
      if (data && data.length > 0) return data;
    } catch (e) {
      // Fallback
    }
    return this.demoPipeline.getPipelineHistory(limit);
  }

  async getPipelineByCorrelationId(correlationId: string): Promise<PipelineTracePayload[]> {
    try {
      const data = await this.backendPipeline.getPipelineByCorrelationId(correlationId);
      if (data && data.length > 0) return data;
    } catch (e) {
      // Fallback
    }
    return this.demoPipeline.getPipelineByCorrelationId(correlationId);
  }

  async getPipelineMetrics(): Promise<any> {
    try {
      const data = await this.backendPipeline.getPipelineMetrics();
      if (data) return data;
    } catch (e) {
      // Fallback
    }
    return this.demoPipeline.getPipelineMetrics();
  }
}
