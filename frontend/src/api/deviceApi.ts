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
} as const;
export type CapabilityType = typeof CapabilityType[keyof typeof CapabilityType];

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
  SUCCESS: 'SUCCESS',
  FAILURE: 'FAILURE',
  IGNORED: 'IGNORED',
  UNSUPPORTED: 'UNSUPPORTED',
} as const;
export type ExecutionStatus = typeof ExecutionStatus[keyof typeof ExecutionStatus];

/**
 * Tier 5D: request to dispatch a physical command. Only capability=LED,
 * command="SET_STATE" is currently supported by the backend - any other
 * combination is rejected (400/FAILURE), never silently accepted.
 */
export interface CreateDeviceCommandRequest {
  capability: CapabilityType;
  command: string;
  value: boolean;
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
