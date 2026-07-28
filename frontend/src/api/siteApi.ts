import type { Site, CreateSiteRequest, UpdateSiteRequest } from '../types/models';
import { apiClient } from './apiClient';

export const siteApi = {
  getSites: () => apiClient.get<Site[]>('/sites'),
  getSite: (id: string) => apiClient.get<Site>(`/sites/${id}`),
  createSite: (data: CreateSiteRequest) => apiClient.post<Site>('/sites', data),
  updateSite: (id: string, data: UpdateSiteRequest) => apiClient.put<Site>(`/sites/${id}`, data),
  deleteSite: (id: string) => apiClient.delete(`/sites/${id}`)
};