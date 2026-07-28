import type { Space, CreateSpaceRequest, UpdateSpaceRequest } from '../types/models';
import { apiClient } from './apiClient';

export const spaceApi = {
  getSpacesByBuildingId: (buildingId: string) => apiClient.get<Space[]>(`/buildings/${buildingId}/spaces`),
  getSpace: (id: string) => apiClient.get<Space>(`/spaces/${id}`),
  createSpace: (buildingId: string, data: CreateSpaceRequest) => apiClient.post<Space>(`/buildings/${buildingId}/spaces`, data),
  updateSpace: (id: string, data: UpdateSpaceRequest) => apiClient.put<Space>(`/spaces/${id}`, data),
  deleteSpace: (id: string) => apiClient.delete(`/spaces/${id}`)
};