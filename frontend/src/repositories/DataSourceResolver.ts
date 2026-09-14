import type { IDeviceRepository, ITelemetryRepository, IEventRepository, IRuleRepository, IActionRepository, IPipelineRepository, DeviceState, TelemetryData, PipelineTracePayload } from './types';
import { DemoDeviceRepository, DemoTelemetryRepository, DemoEventRepository, DemoRuleRepository, DemoActionRepository, DemoPipelineRepository } from './DemoRepository';
import { BackendDeviceRepository, BackendTelemetryRepository, BackendEventRepository, BackendRuleRepository, BackendActionRepository, BackendPipelineRepository } from './BackendRepository';
import { FEATURES } from '../config/features';

/**
 * Maps frontend simulator string IDs to Backend UUIDs.
 * Only applied for BackendRepository calls.
 */
const ID_MAP: Record<string, string> = {
  "hvac-1": "b5e9f1a2-7d3c-4e8a-9f1b-2c3d4e5f6a7b",
  "hvac-2": "a4d8e091-6c2b-3d79-8e0a-1b2c3d4e5f6a",
  'dev-hvac-001': '66666666-6666-6666-6666-666666666666',
};

/**
 * Resolves each data request against either the real backend or the demo
 * simulator, depending on FEATURES.DEMO_MODE:
 *
 * - DEMO_MODE=true (explicit, opt-in): the original hybrid behavior -
 *   try the backend, blend with or fall back to DemoRepository fixtures.
 *   This is intentional and unchanged.
 * - DEMO_MODE=false (the shipped default, "live mode"): backend data is
 *   used exactly as returned - a successful response (including an empty
 *   list) is shown as-is, and a failure propagates as a real error rather
 *   than being silently replaced with demo fixtures. Demo data must never
 *   appear in live mode.
 */
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

    if (!FEATURES.DEMO_MODE) {
      // LIVE MODE: real backend data, as returned. A failure (404, network
      // error, etc.) propagates so the caller's error state is honest.
      return this.backendDevice.getDevice(backendId);
    }

    // DEMO MODE (explicit, opt-in): original hybrid-blend behavior.
    let backendState: Partial<DeviceState> | null = null;
    const start = performance.now();
    try {
      backendState = await this.backendDevice.getDevice(backendId);
      (window as any).__lastLatency = performance.now() - start;
    } catch (e) {
      console.warn(`[DEMO_MODE] Backend device fetch failed for ${backendId}, falling back to demo fixture.`);
    }

    const demoState = await this.demoDevice.getDevice(id);

    if (!backendState) {
      return demoState;
    }

    const hybridState: DeviceState = {
      ...demoState,
      ...backendState,
      id: demoState.id,
      lastSeen: backendState.lastSeen || demoState.lastSeen,
      metrics: backendState.metrics?.length ? backendState.metrics : demoState.metrics,
      status: backendState.status || demoState.status,
    };

    return hybridState;
  }

  async getDevices(): Promise<DeviceState[]> {
    if (!FEATURES.DEMO_MODE) {
      // LIVE MODE: an empty backend list is a real, honest empty state -
      // it must be returned as-is, not replaced with demo fixtures.
      return this.backendDevice.getDevices();
    }
    try {
      const backendDevices = await this.backendDevice.getDevices();
      if (backendDevices && backendDevices.length > 0) return backendDevices;
    } catch (e) {
      console.warn(`[DEMO_MODE] Backend fetch failed for all devices, falling back to demo fixtures.`);
    }
    return this.demoDevice.getDevices();
  }

  async getTelemetry(deviceId: string, metric: string): Promise<TelemetryData[]> {
    const backendId = ID_MAP[deviceId] || deviceId;
    if (!FEATURES.DEMO_MODE) {
      return this.backendTelemetry.getTelemetry(backendId, metric);
    }
    try {
      const data = await this.backendTelemetry.getTelemetry(backendId, metric);
      if (data && data.length > 0) return data;
    } catch (e) {
      // Fallback
    }
    return this.demoTelemetry.getTelemetry(deviceId, metric);
  }

  async getTelemetryHistory(deviceId: string): Promise<any[]> {
    const backendId = ID_MAP[deviceId] || deviceId;
    if (!FEATURES.DEMO_MODE) {
      return this.backendTelemetry.getTelemetryHistory(backendId);
    }
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
    if (!FEATURES.DEMO_MODE) {
      return this.backendEvent.getEvents(backendId, limit);
    }
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
    if (!FEATURES.DEMO_MODE) {
      return this.backendRule.getRules(backendId);
    }
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
    if (!FEATURES.DEMO_MODE) {
      return this.backendAction.getActions(backendId, limit);
    }
    try {
      const data = await this.backendAction.getActions(backendId, limit);
      if (data && data.length > 0) return data;
    } catch (e) {
      // Fallback
    }
    return this.demoAction.getActions(deviceId, limit);
  }

  async getActionsByMatchId(matchId: string): Promise<any[]> {
    if (!FEATURES.DEMO_MODE) {
      return this.backendAction.getActionsByMatchId(matchId);
    }
    try {
      const data = await this.backendAction.getActionsByMatchId(matchId);
      if (data && data.length > 0) return data;
    } catch (e) {
      // Fallback
    }
    return this.demoAction.getActionsByMatchId(matchId);
  }

  async getPipelineHistory(limit?: number, deviceId?: string): Promise<PipelineTracePayload[]> {
    if (!FEATURES.DEMO_MODE) {
      return this.backendPipeline.getPipelineHistory(limit, deviceId);
    }
    try {
      const data = await this.backendPipeline.getPipelineHistory(limit, deviceId);
      if (data && data.length > 0) return data;
    } catch (e) {
      // Fallback
    }
    return this.demoPipeline.getPipelineHistory(limit, deviceId);
  }

  async getPipelineByCorrelationId(correlationId: string): Promise<PipelineTracePayload[]> {
    if (!FEATURES.DEMO_MODE) {
      return this.backendPipeline.getPipelineByCorrelationId(correlationId);
    }
    try {
      const data = await this.backendPipeline.getPipelineByCorrelationId(correlationId);
      if (data && data.length > 0) return data;
    } catch (e) {
      // Fallback
    }
    return this.demoPipeline.getPipelineByCorrelationId(correlationId);
  }

  async getPipelineMetrics(): Promise<any> {
    if (!FEATURES.DEMO_MODE) {
      return this.backendPipeline.getPipelineMetrics();
    }
    try {
      const data = await this.backendPipeline.getPipelineMetrics();
      if (data) return data;
    } catch (e) {
      // Fallback
    }
    return this.demoPipeline.getPipelineMetrics();
  }
}
