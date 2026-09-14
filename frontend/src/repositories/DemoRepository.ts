import type { IDeviceRepository, ITelemetryRepository, IEventRepository, IRuleRepository, IActionRepository, IPipelineRepository, DeviceState, TelemetryData, PipelineTracePayload } from './types';
import { ScenarioEngine } from '../simulation/ScenarioEngine';

export class DemoDeviceRepository implements IDeviceRepository {
  async getDevice(id: string): Promise<DeviceState> {
    // Artificial delay to simulate network
    await new Promise(resolve => setTimeout(resolve, 300));
    const device = ScenarioEngine.getDevice(id);
    if (!device) {
      throw new Error(`Device ${id} not found in Demo Simulator`);
    }
    return device;
  }

  async getDevices(): Promise<DeviceState[]> {
    return ScenarioEngine.getDevices();
  }
}

export class DemoTelemetryRepository implements ITelemetryRepository {
  async getTelemetry(deviceId: string, metric: string): Promise<TelemetryData[]> {
    const device = ScenarioEngine.getDevice(deviceId);
    if (!device) return [];
    
    const m = device.metrics.find(m => m.name === metric);
    if (m) {
      return [{ timestamp: Date.now(), value: Number(m.value) }];
    }
    return [];
  }

  async getTelemetryHistory(deviceId: string): Promise<any[]> {
    const device = ScenarioEngine.getDevice(deviceId);
    if (!device) return [];

    // Construct a fake payload for Demo Mode
    const payload: any = {};
    device.metrics.forEach(m => {
      payload[m.name] = m.value;
    });

    return [{
      deviceId,
      timestamp: new Date().toISOString(),
      payload
    }];
  }
}

export class DemoEventRepository implements IEventRepository {
  async getEvents(deviceId?: string, limit: number = 50, correlationId?: string): Promise<any[]> {
    let events = ScenarioEngine.getEvents();
    if (deviceId) {
      events = events.filter(e => e.deviceId === deviceId);
    }
    if (correlationId) {
      events = events.filter((e: any) => e.correlationId === correlationId);
    }
    return events.slice(0, limit);
  }
}

export class DemoRuleRepository implements IRuleRepository {
  async getRules(_deviceId?: string): Promise<any[]> {
    return [
      {
        id: 'rule-1',
        name: 'High Temperature Alert (Demo)',
        description: 'Triggers when temperature exceeds 40C',
        enabled: true,
        condition: 'telemetry.temperature > 40',
        actions: [{ type: 'ALERT', payload: { level: 'CRITICAL' } }],
        priority: 'HIGH'
      }
    ];
  }
}

export class DemoActionRepository implements IActionRepository {
  async getActions(deviceId?: string, limit: number = 50): Promise<any[]> {
    let events = ScenarioEngine.getEvents();
    let actions = events
      .filter(e => e.type === 'ACTION_EXECUTED' || e.type === 'ACTION_FAILED' || e.type === 'ACTION_STARTED')
      .map(e => ({
        ...e,
        status: e.type === 'ACTION_FAILED' ? 'FAILED' : e.type === 'ACTION_STARTED' ? 'PENDING' : 'SUCCESS'
      }));
      
    if (deviceId) {
      actions = actions.filter(a => a.deviceId === deviceId);
    }
    
    return actions.slice(0, limit);
  }

  async getActionsByMatchId(_matchId: string): Promise<any[]> {
    // Demo mode has no real RuleMatch/Action correlation - honestly empty
    // rather than fabricating a match-to-action relationship.
    return [];
  }
}

export class DemoPipelineRepository implements IPipelineRepository {
  async getPipelineHistory(_limit?: number, _deviceId?: string): Promise<PipelineTracePayload[]> {
    return []; // Start empty in demo, scenario engine will populate it live
  }

  async getPipelineByCorrelationId(_correlationId: string): Promise<PipelineTracePayload[]> {
    return [];
  }

  async getPipelineMetrics(): Promise<any> {
    return {
      totalStored: 0,
      recentErrorCount: 0,
      averageLatencyMs: 0
    };
  }
}
