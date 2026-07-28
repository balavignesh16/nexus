import type { Building, CreateBuildingRequest, UpdateBuildingRequest } from '../types/models';
import { apiClient } from './apiClient';

export const buildingApi = {
  getBuildingsBySiteId: (siteId: string) => apiClient.get<Building[]>(`/sites/${siteId}/buildings`),
  getBuilding: (id: string) => apiClient.get<Building>(`/buildings/${id}`),
  createBuilding: (siteId: string, data: CreateBuildingRequest) => apiClient.post<Building>(`/sites/${siteId}/buildings`, data),
  updateBuilding: (id: string, data: UpdateBuildingRequest) => apiClient.put<Building>(`/buildings/${id}`, data),
  deleteBuilding: (id: string) => apiClient.delete(`/buildings/${id}`)
};