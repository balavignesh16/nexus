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

export const deviceApi = {
  getDevicesBySpaceId: (spaceId: string) => 
    apiClient.get<Device[]>(`/spaces/${spaceId}/devices`),
    
  getDevice: (id: string) => 
    apiClient.get<Device>(`/devices/${id}`),
    
  createDevice: (spaceId: string, data: CreateDeviceRequest) => 
    apiClient.post<Device>(`/spaces/${spaceId}/devices`, data),
    
  updateDevice: (id: string, data: UpdateDeviceRequest) => 
    apiClient.put<Device>(`/devices/${id}`, data),
    
  deleteDevice: (id: string) => 
    apiClient.delete(`/devices/${id}`)
};
