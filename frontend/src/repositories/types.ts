import { DeviceStatus, type DeviceCapability } from '../api/deviceApi';
import type { AnalyticsSummary, AnalyticsTimeSeries, AnalyticsRangeParam, AnalyticsMetricParam, AnalyticsBucketParam } from '../api/analyticsApi';

export interface TelemetryData {
  timestamp: number;
  value: number;
}

export interface PipelineTracePayload {
  traceId: string;
  correlationId: string;
  deviceId: string | null;
  stage: string;
  stageIndex: number;
  previousStage: string | null;
  nextStage: string | null;
  startTimestamp: number;
  endTimestamp: number;
  durationMs: number;
  status: string;
  payloadSize: number;
  metadata: Record<string, any>;
}

export interface Metric {
  name: string;
  value: string;
  unit: string;
  trend?: string;
  trendLabel?: string;
}

export interface DeviceState {
  id: string;
  mode?: 'SIMULATED' | 'REAL';
  name: string;
  status: DeviceStatus;
  deviceType: string;
  mqttTopic: string;
  lastSeen: string;
  // Real backend hierarchy foreign key - undefined for demo-simulated
  // devices, which have no resolvable real Site/Building/Space record.
  spaceId?: string;
  // null means "not reported by this data source" - distinct from an
  // actual reading of 0 (e.g. 0% battery is a real measurement). The
  // backend Device API does not currently expose these fields; only the
  // demo simulator provides real numeric values for them.
  signalStrength: number | null;
  battery: number | null;
  temperature: number | null;
  humidity: number | null;
  metrics: Metric[];
  // Tier 5C: NEXUS-authoritative device capabilities. Optional/undefined
  // means "not provided by this data source" (e.g. the demo simulator) -
  // never fabricated; callers must treat that the same as an empty list.
  capabilities?: DeviceCapability[];
}

export interface IDeviceRepository {
  getDevice(id: string): Promise<DeviceState>;
  getDevices(): Promise<DeviceState[]>;
}

export interface ITelemetryRepository {
  getTelemetry(deviceId: string, metric: string): Promise<TelemetryData[]>;
  getTelemetryHistory(deviceId: string): Promise<any[]>;
}

export interface IEventRepository {
  // correlationId (Step 6): the real backend already supports
  // GET /events?correlationId= (Phase 3) - added here so the Pipeline
  // investigation surface can request events scoped to one correlation
  // directly, rather than fetching the whole event history and filtering
  // client-side. Optional and additive - every existing caller is unaffected.
  getEvents(deviceId?: string, limit?: number, correlationId?: string): Promise<any[]>;
}

export interface IRuleRepository {
  getRules(deviceId?: string): Promise<any[]>;
}

export interface IActionRepository {
  getActions(deviceId?: string, limit?: number): Promise<any[]>;
  // Phase 3: the real actions triggered by one specific rule match, via the
  // already-persisted ActionHistoryRecord.matchId FK.
  getActionsByMatchId(matchId: string): Promise<any[]>;
}

export interface IPipelineRepository {
  getPipelineHistory(limit?: number, deviceId?: string): Promise<PipelineTracePayload[]>;
  getPipelineByCorrelationId(correlationId: string): Promise<PipelineTracePayload[]>;
  getPipelineMetrics(): Promise<any>;
}

export interface IAnalyticsRepository {
  getAnalyticsSummary(range: AnalyticsRangeParam): Promise<AnalyticsSummary>;
  getAnalyticsTimeSeries(metric: AnalyticsMetricParam, range: AnalyticsRangeParam, bucket?: AnalyticsBucketParam): Promise<AnalyticsTimeSeries>;
}

export interface IRepositoryFactory {
  device: IDeviceRepository;
  telemetry: ITelemetryRepository;
  events: IEventRepository;
  rules: IRuleRepository;
  actions: IActionRepository;
  pipeline: IPipelineRepository;
  analytics: IAnalyticsRepository;
  // twin: ITwinRepository;
}
