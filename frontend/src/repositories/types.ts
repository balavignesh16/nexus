import { DeviceStatus } from '../api/deviceApi';

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
  name: string;
  status: DeviceStatus;
  deviceType: string;
  mqttTopic: string;
  healthScore: number;
  twinQuality: number;
  lastSeen: string;
  signalStrength: number;
  battery: number;
  temperature: number;
  humidity: number;
  metrics: Metric[];
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
  getEvents(deviceId?: string, limit?: number): Promise<any[]>;
}

export interface IRuleRepository {
  getRules(deviceId?: string): Promise<any[]>;
}

export interface IActionRepository {
  getActions(deviceId?: string, limit?: number): Promise<any[]>;
}

export interface IPipelineRepository {
  getPipelineHistory(limit?: number): Promise<PipelineTracePayload[]>;
  getPipelineByCorrelationId(correlationId: string): Promise<PipelineTracePayload[]>;
  getPipelineMetrics(): Promise<any>;
}

export interface IRepositoryFactory {
  device: IDeviceRepository;
  telemetry: ITelemetryRepository;
  events: IEventRepository;
  rules: IRuleRepository;
  actions: IActionRepository;
  pipeline: IPipelineRepository;
  // twin: ITwinRepository;
}
