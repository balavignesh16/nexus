import { beforeEach, describe, expect, it, vi } from 'vitest';
import { apiClient } from './apiClient';
import { intelligenceApi } from './intelligenceApi';
vi.mock('./apiClient', () => ({ apiClient: { get: vi.fn(), post: vi.fn() } }));
beforeEach(() => vi.clearAllMocks());
describe('Phase 6 live API paths', () => {
  it('reads model metadata only from NEXUS, never directly from Ollama', () => {
    intelligenceApi.modelStatus();
    expect(apiClient.get).toHaveBeenCalledWith('/assistant/model-status');
  });
  it('posts questions to the assistant, not a legacy explanation or external model', () => {
    intelligenceApi.ask('What is stored?', 'conversation-1');
    expect(apiClient.post).toHaveBeenCalledWith('/assistant/ask', { question: 'What is stored?', conversationId: 'conversation-1' });
  });
  it('preserves units and window arguments without conversion', () => {
    intelligenceApi.trend('device', 'temperature', 24, 100, 'degrees C');
    expect(apiClient.get).toHaveBeenCalledWith('/intelligence/trends/devices/device?capabilityKey=temperature&windowHours=24&limitValue=100&limitUnit=degrees+C');
  });
  it('omits empty energy scopes rather than sending an invalid UUID', () => {
    intelligenceApi.energy('', 24);
    expect(apiClient.get).toHaveBeenCalledWith('/intelligence/energy?windowHours=24');
  });
  it('has draft proposal and inspection only, no approval or activation helper', () => {
    intelligenceApi.propose('a draft goal'); intelligenceApi.drafts();
    expect(apiClient.post).toHaveBeenCalledWith('/intelligence/drafts', { goal: 'a draft goal' });
    expect(apiClient.get).toHaveBeenCalledWith('/intelligence/drafts');
    expect(Object.keys(intelligenceApi).join(' ')).not.toMatch(/approve|activate/);
  });
});
