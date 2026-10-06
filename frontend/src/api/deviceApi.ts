import { apiClient } from './apiClient';

export const DeviceType = {
  TEMPERATURE_SENSOR: 'TEMPERATURE_SENSOR',
  GAS_SENSOR: 'GAS_SENSOR',
  CAMERA: 'CAMERA',
  LIGHT: 'LIGHT',
  FAN: 'FAN',
  DOOR_LOCK: 'DOOR_LOCK',
  MOTION_SENSOR: 'MOTION_SENSOR',
  CUSTOM: 'CUSTOM'
} as const;
export type DeviceType = typeof DeviceType[keyof typeof DeviceType];

export const DeviceStatus = {
  REGISTERED: 'REGISTERED',
  ACTIVE: 'ACTIVE',
  OFFLINE: 'OFFLINE',
  MAINTENANCE: 'MAINTENANCE'
} as const;
export type DeviceStatus = typeof DeviceStatus[keyof typeof DeviceStatus];

export const CapabilityType = {
  TEMPERATURE: 'TEMPERATURE',
  HUMIDITY: 'HUMIDITY',
  MOTION: 'MOTION',
  LED: 'LED',
  // Phase 11: complete authoritative BOM vocabulary.
  PRESSURE: 'PRESSURE',
  GAS: 'GAS',
  BUTTON: 'BUTTON',
  RELAY: 'RELAY',
  SERVO: 'SERVO',
  FAN: 'FAN',
  BUZZER: 'BUZZER',
} as const;
export type CapabilityType = typeof CapabilityType[keyof typeof CapabilityType];

/** READ capabilities usable as a rule condition source (Phase 13). */
export const READ_CAPABILITIES: CapabilityType[] = [
  CapabilityType.TEMPERATURE, CapabilityType.HUMIDITY, CapabilityType.PRESSURE,
  CapabilityType.GAS, CapabilityType.MOTION, CapabilityType.BUTTON,
];

/** WRITE capabilities usable as a rule action target (Phase 13). */
export const WRITE_CAPABILITIES: CapabilityType[] = [
  CapabilityType.LED, CapabilityType.RELAY, CapabilityType.SERVO, CapabilityType.FAN, CapabilityType.BUZZER,
];

/**
 * Phase 13: whether a READ capability's condition value is numeric or
 * boolean - mirrors the backend's CapabilityValueKind exactly (same 6
 * capabilities, same split). Drives operator filtering and value-input
 * type in the rule builder.
 */
export const BOOLEAN_CAPABILITIES: CapabilityType[] = [CapabilityType.MOTION, CapabilityType.BUTTON];
export function isBooleanCapability(type: CapabilityType): boolean {
  return BOOLEAN_CAPABILITIES.includes(type);
}

export const CapabilityDirection = {
  READ: 'READ',
  WRITE: 'WRITE',
} as const;
export type CapabilityDirection = typeof CapabilityDirection[keyof typeof CapabilityDirection];

/**
 * NEXUS-authoritative device capability metadata (Tier 5C) - deliberately
 * distinct from telemetry's own sensorType wire vocabulary. Never inferred
 * from telemetry; only ever provisioned through the capability API.
 */
export interface DeviceCapability {
  id: string;
  deviceId: string;
  capabilityType: CapabilityType;
  direction: CapabilityDirection;
  unit?: string;
  metadata?: Record<string, unknown>;
}

export interface CreateDeviceCapabilityRequest {
  capabilityType: CapabilityType;
  direction: CapabilityDirection;
  unit?: string;
  metadata?: Record<string, unknown>;
}

export interface Device {
  id: string;
  mode?: 'SIMULATED' | 'REAL';
  spaceId: string;
  name: string;
  deviceType: DeviceType;
  manufacturer: string;
  model: string;
  serialNumber: string;
  status: DeviceStatus;
  description?: string;
  createdAt: string;
  updatedAt: string;
  createdBy: string;
  updatedBy: string;
  capabilities: DeviceCapability[];
}

export interface CreateDeviceRequest {
  name: string;
  deviceType: DeviceType;
  manufacturer: string;
  model: string;
  serialNumber: string;
  description?: string;
}

export interface UpdateDeviceRequest {
  name: string;
  status: DeviceStatus;
  description?: string;
}

export const ExecutionStatus = {
  ACCEPTED_FOR_DISPATCH: 'ACCEPTED_FOR_DISPATCH',
  SUCCESS: 'SUCCESS',
  FAILURE: 'FAILURE',
  IGNORED: 'IGNORED',
  UNSUPPORTED: 'UNSUPPORTED',
} as const;
export type ExecutionStatus = typeof ExecutionStatus[keyof typeof ExecutionStatus];

/**
 * Tier 5D / Phase 13: request to dispatch a physical command. Value shape
 * depends on the target capability - boolean for LED/FAN/BUZZER, an
 * integer 0-180 for SERVO, {channel,state} for RELAY - validated
 * server-side (CommandValueValidator); an unsupported capability/command
 * combination is rejected (FAILURE), never silently accepted.
 */
export interface CreateDeviceCommandRequest {
  capability: CapabilityType;
  command: string;
  value: boolean | number | { channel: number; state: boolean };
}

/**
 * The truthful outcome of a command dispatch. `status`/`message` are what
 * actually happened - a 201 HTTP response alone never means the command
 * succeeded; callers must read `status`. SUCCESS here means only "NEXUS
 * published the command to MQTT" - never that the device received,
 * processed, or acted on it.
 */
export interface DeviceCommandResult {
  executionId: string;
  requestId: string;
  matchId: string | null;
  actionType: string;
  parameters: Record<string, unknown>;
  status: ExecutionStatus;
  timestamp: string;
  message: string;
}

export const deviceApi = {
  getDevicesBySpaceId: (spaceId: string) => 
    apiClient.get<Device[]>(`/spaces/${spaceId}/devices`),
    
  getActiveDevices: () => 
    apiClient.get<Device[]>(`/devices/active`),
    
  getDevice: (id: string) => 
    apiClient.get<Device>(`/devices/${id}`),
    
  createDevice: (spaceId: string, data: CreateDeviceRequest) => 
    apiClient.post<Device>(`/spaces/${spaceId}/devices`, data),
    
  updateDevice: (id: string, data: UpdateDeviceRequest) => 
    apiClient.put<Device>(`/devices/${id}`, data),
    
  deleteDevice: (id: string) =>
    apiClient.delete(`/devices/${id}`),

  getDeviceCapabilities: (deviceId: string) =>
    apiClient.get<DeviceCapability[]>(`/devices/${deviceId}/capabilities`),

  addDeviceCapability: (deviceId: string, data: CreateDeviceCapabilityRequest) =>
    apiClient.post<DeviceCapability>(`/devices/${deviceId}/capabilities`, data),

  deleteDeviceCapability: (deviceId: string, capabilityId: string) =>
    apiClient.delete(`/devices/${deviceId}/capabilities/${capabilityId}`),

  sendDeviceCommand: (deviceId: string, data: CreateDeviceCommandRequest) =>
    apiClient.post<DeviceCommandResult>(`/devices/${deviceId}/commands`, data),

  getDeviceConnectivity: (deviceId: string) =>
    apiClient.get<DeviceConnectivityResult>(`/devices/${deviceId}/connectivity`)
};

/**
 * Truthful last-known connectivity - NOT an active probe (no round-trip
 * ping to the device exists). "UNKNOWN" means no status has ever been
 * observed for this device; never a fabricated default.
 */
export interface DeviceConnectivityResult {
  deviceId: string;
  status: 'ONLINE' | 'OFFLINE' | 'UNKNOWN';
  lastChangedAt: string | null;
}
