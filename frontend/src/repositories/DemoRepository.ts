import type { IDeviceRepository, ITelemetryRepository, IEventRepository, IRuleRepository, IActionRepository, IPipelineRepository, IAnalyticsRepository, DeviceState, TelemetryData, PipelineTracePayload } from './types';
import { ScenarioEngine } from '../simulation/ScenarioEngine';
import type { AnalyticsRangeParam, AnalyticsMetricParam, AnalyticsBucketParam, AnalyticsSummary, AnalyticsTimeSeries } from '../api/analyticsApi';

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

/**
 * The demo simulator (ScenarioEngine) has no analytics/aggregation concept -
 * there is no fixture data to draw from. Following the same honesty
 * precedent as DemoPipelineRepository.getPipelineMetrics() above, this
 * returns structurally-valid, genuinely zero/empty responses rather than
 * inventing populated-looking numbers just to make the demo page look busy.
 */
export class DemoAnalyticsRepository implements IAnalyticsRepository {
  async getAnalyticsSummary(range: AnalyticsRangeParam): Promise<AnalyticsSummary> {
    const now = new Date().toISOString();
    return {
      range,
      from: now,
      to: now,
      totalDeviceCount: 0,
      deviceCountByType: {},
      connectivityDistribution: {},
      eventCount: 0,
      ruleMatchCount: 0,
      actionStatusDistribution: {},
      commandAckDistribution: {},
    };
  }

  async getAnalyticsTimeSeries(metric: AnalyticsMetricParam, _range: AnalyticsRangeParam, bucket?: AnalyticsBucketParam): Promise<AnalyticsTimeSeries> {
    const now = new Date().toISOString();
    return {
      metric,
      from: now,
      to: now,
      bucket: bucket ?? 'hour',
      points: [],
    };
  }
}
