import { apiClient } from './apiClient';
import type { OnboardingSession, ProvisionDeviceRequest } from './frameworkTypes';

export const onboardingApi = {
  all: () => apiClient.get<OnboardingSession[]>('/device-onboarding'),
  get: (id: string) => apiClient.get<OnboardingSession>(`/device-onboarding/${id}`),
  provision: (data: ProvisionDeviceRequest) => apiClient.post<OnboardingSession>('/device-onboarding', data),
  retry: (id: string) => apiClient.post<OnboardingSession>(`/device-onboarding/${id}/retry`, {}),
  disable: (id: string) => apiClient.post<OnboardingSession>(`/device-onboarding/${id}/disable`, {}),
};
