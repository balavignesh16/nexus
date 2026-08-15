import { apiClient } from './apiClient';

export interface EventResponse {
  eventId: string;
  eventType: string;
  deviceId: string;
  payload: Record<string, unknown>;
  timestamp: string;
  correlationId: string;
}

export const eventApi = {
  getEvents: (limit: number = 100) => apiClient.get<EventResponse[]>(`/events?limit=${limit}`)
};
