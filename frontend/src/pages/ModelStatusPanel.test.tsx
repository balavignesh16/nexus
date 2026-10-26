import { render, screen } from '@testing-library/react';
import { beforeEach, expect, it, vi } from 'vitest';
import ModelStatusPanel from './ModelStatusPanel';
import { intelligenceApi, type ModelStatus } from '../api/intelligenceApi';
vi.mock('../api/intelligenceApi', () => ({ intelligenceApi: { modelStatus: vi.fn() } }));
const status: ModelStatus = {
  mode: 'OLLAMA_LOCAL', gateway: { identity: { providerId: 'ollama-local', modelId: 'llama3.2:3b', configHash: 'test' },
    configured: true, evidenceConsent: true, providerAttempts: 1, providerResponses: 1,
    lastOutcome: { outcome: 'OUTPUT_INVALID', durationMillis: 100, finishedAt: '2026-10-20T00:00:00Z' } },
  ollama: { inspection: { reachable: true, installed: true, digest: 'testdigest', remotelyHosted: false, problem: null }, loaded: false, checkedAt: 'now' },
};
beforeEach(() => vi.resetAllMocks());
it('distinguishes configured, installed, unloaded, actual attempts and rejected output', async () => {
  vi.mocked(intelligenceApi.modelStatus).mockResolvedValue(status);
  render(<ModelStatusPanel refreshKey={0} />);
  expect(await screen.findByText(/Provider: ollama-local/)).toBeInTheDocument();
  expect(screen.getByText(/loaded: no/)).toBeInTheDocument();
  expect(screen.getByText(/Last gateway outcome: OUTPUT_INVALID/)).toBeInTheDocument();
  expect(screen.getByText(/Provider attempts: 1/)).toBeInTheDocument();
});
it('does not turn a failed probe request into a connected model', async () => {
  vi.mocked(intelligenceApi.modelStatus).mockRejectedValue(new Error('offline'));
  render(<ModelStatusPanel refreshKey={0} />);
  expect(await screen.findByRole('status')).toHaveTextContent('Model status unavailable');
});
it('shows deterministic mode without claiming an Ollama connection', async () => {
  vi.mocked(intelligenceApi.modelStatus).mockResolvedValue({ ...status, mode: 'DETERMINISTIC_ONLY', ollama: null,
    gateway: { ...status.gateway, configured: false, providerAttempts: 0, providerResponses: 0, lastOutcome: null } });
  render(<ModelStatusPanel refreshKey={0} />);
  expect(await screen.findByText(/Deterministic-only/)).toBeInTheDocument();
  expect(screen.queryByText(/Ollama: reachable/)).not.toBeInTheDocument();
});
