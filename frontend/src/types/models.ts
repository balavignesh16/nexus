// Domain Models for NEXUS Platform

export type DeviceType = 'TEMPERATURE_SENSOR' | 'GAS_SENSOR' | 'CAMERA' | 'LIGHT' | 'FAN' | 'DOOR_LOCK' | 'MOTION_SENSOR' | 'CUSTOM';
export type DeviceStatus = 'REGISTERED' | 'ACTIVE' | 'OFFLINE' | 'MAINTENANCE';

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
}

export interface DigitalTwin {
  deviceId: string;
  metadata: Record<string, string>;
  runtimeState: Record<string, unknown>;
  lastSeenAt: string;
  quality: string;
}

export interface Telemetry {
  id: string;
  deviceId: string;
  payload: Record<string, unknown>;
  timestamp: string;
  correlationId: string;
}

export interface DomainEvent {
  eventId: string;
  eventType: string;
  deviceId: string;
  payload: Record<string, unknown>;
  timestamp: string;
  correlationId: string;
}

export interface RuleCondition {
  field: string;
  operator: string;
  expectedValue: string;
}

export interface RuleAction {
  actionType: string;
  parameters: Record<string, string>;
}

export interface Rule {
  id: string;
  name: string;
  enabled: boolean;
  priority: number;
  conditions: RuleCondition[];
  actions: RuleAction[];
  metadata: Record<string, string>;
}

export interface ActionExecutionResult {
  historyId: string;
  requestId: string;
  ruleId: string;
  deviceId: string;
  actionType: string;
  status: string;
  message: string;
  timestamp: string;
  parameters: Record<string, unknown>;
  correlationId: string;
}

export interface Site {
  id: string;
  name: string;
  description?: string;
  status: string;
}

export interface Building {
  id: string;
  siteId: string;
  name: string;
  description?: string;
  status: string;
}

export interface Space {
  id: string;
  buildingId: string;
  name: string;
  description?: string;
  status: string;
}

// Request DTOs

export interface CreateSiteRequest {
  name: string;
  description?: string;
}
export interface UpdateSiteRequest {
  name: string;
  description?: string;
  status: string;
}

export interface CreateBuildingRequest {
  name: string;
  description?: string;
}
export interface UpdateBuildingRequest {
  name: string;
  description?: string;
  status: string;
}

export interface CreateSpaceRequest {
  name: string;
  description?: string;
  spaceType: string;
}
export interface UpdateSpaceRequest {
  name: string;
  description?: string;
  status: string;
}

export interface DashboardStats {
  sites: number;
  buildings: number;
  spaces: number;
  devices: number;
  onlineDevices: number;
  offlineDevices: number;
}
