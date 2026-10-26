import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import Phase6Page, { EvidenceView } from './Phase6Page';
import { intelligenceApi, type AssistantAnswer } from '../api/intelligenceApi';
import { ApiError } from '../api/apiClient';

vi.mock('../api/intelligenceApi', () => ({ intelligenceApi: {
  ask: vi.fn(), devices: vi.fn(), availability: vi.fn(), energy: vi.fn(), drafts: vi.fn(), propose: vi.fn(),
  recommendations: vi.fn(), anomalies: vi.fn(), trend: vi.fn(), readings: vi.fn(), nonMatch: vi.fn(), modelStatus: vi.fn(),
} }));
const answer: AssistantAnswer = {
  conversationId: 'conversation-test', turn: 1, status: 'ANSWERED', routedBy: 'DETERMINISTIC', orderedBy: 'DETERMINISTIC',
  statements: [{ id: 's1', kind: 'FACT', text: 'Stored status is ACTIVE.', refs: ['device:one/status'] }], additional: [],
  sources: [{ ref: 'device:one/status', label: 'Stored status', value: 'ACTIVE', presence: 'PRESENT' }],
  notices: [], clarification: null, evaluatedAt: '2026-10-20T12:00:00Z',
};
function page(kind: Parameters<typeof Phase6Page>[0]['screen']) { return render(<MemoryRouter><Phase6Page screen={kind} /></MemoryRouter>); }
beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(intelligenceApi.devices).mockResolvedValue([{ id: 'device-one', name: 'Stored sensor' }]);
  vi.mocked(intelligenceApi.availability).mockResolvedValue(['DEVICE_STATE']);
  vi.mocked(intelligenceApi.modelStatus).mockResolvedValue({ mode: 'DETERMINISTIC_ONLY', ollama: null,
    gateway: { identity: { providerId: 'none', modelId: 'none', configHash: 'none' }, configured: false,
      evidenceConsent: false, providerAttempts: 0, providerResponses: 0, lastOutcome: null } });
});
describe('Phase 6 interface (mocked unit tests, not live acceptance)', () => {
  it('labels validated model ordering separately from deterministic fallback', async () => {
    vi.mocked(intelligenceApi.ask).mockResolvedValue({ ...answer, orderedBy: 'MODEL_SELECTED' });
    page('assistant');
    fireEvent.change(screen.getByLabelText('Ask NEXUS'), { target: { value: 'What is the stored status?' } });
    fireEvent.click(screen.getByRole('button', { name: 'Ask assistant' }));
    expect(await screen.findByText('Validated model-assisted evidence ordering. Factual statements are platform-written.')).toBeInTheDocument();
  });
  it('shows all five destinations and posts real questions with conversation continuity', async () => {
    vi.mocked(intelligenceApi.ask).mockResolvedValue(answer);
    page('assistant');
    expect(screen.getByRole('navigation', { name: 'Phase 6' }).querySelectorAll('a')).toHaveLength(5);
    fireEvent.change(screen.getByLabelText('Ask NEXUS'), { target: { value: 'What is the sensor status?' } });
    fireEvent.click(screen.getByRole('button', { name: 'Ask assistant' }));
    expect(await screen.findByText('Stored status is ACTIVE.')).toBeInTheDocument();
    expect(intelligenceApi.ask).toHaveBeenCalledWith('What is the sensor status?', undefined);
    expect(screen.getByText('Evidence: device:one/status')).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('Ask NEXUS'), { target: { value: 'Is it online?' } });
    fireEvent.click(screen.getByRole('button', { name: 'Ask assistant' }));
    await waitFor(() => expect(intelligenceApi.ask).toHaveBeenLastCalledWith('Is it online?', 'conversation-test'));
    await waitFor(() => expect(screen.getAllByTestId('assistant-answer')).toHaveLength(2));
    fireEvent.click(screen.getByRole('button', { name: 'New conversation' }));
    expect(screen.queryByTestId('assistant-answer')).not.toBeInTheDocument();
  });
  it('shows loading, prevents duplicate sends and reports network failure without an answer', async () => {
    let reject!: (reason: unknown) => void;
    vi.mocked(intelligenceApi.ask).mockReturnValue(new Promise((_, fail) => { reject = fail; }));
    page('assistant');
    fireEvent.change(screen.getByLabelText('Ask NEXUS'), { target: { value: 'Question' } });
    fireEvent.click(screen.getByRole('button', { name: 'Ask assistant' }));
    expect(screen.getByRole('button', { name: 'Ask assistant' })).toBeDisabled();
    expect(screen.getByText('Reading stored evidence…')).toBeInTheDocument();
    reject(new Error('Network unavailable'));
    expect(await screen.findByRole('alert')).toHaveTextContent('Network unavailable');
    expect(screen.queryByTestId('assistant-answer')).not.toBeInTheDocument();
    expect(screen.getByLabelText('Ask NEXUS')).toHaveValue('Question');
  });
  it('renders clarification and refused-write statuses, rather than inventing success', async () => {
    vi.mocked(intelligenceApi.ask).mockResolvedValue({ ...answer, status: 'REFUSED_WRITE', statements: [], sources: [], clarification: 'The assistant cannot activate a rule.' });
    page('assistant');
    fireEvent.change(screen.getByLabelText('Ask NEXUS'), { target: { value: 'Activate every rule' } });
    fireEvent.click(screen.getByRole('button', { name: 'Ask assistant' }));
    expect(await screen.findByText('REFUSED_WRITE')).toBeInTheDocument();
    expect(screen.getByText('The assistant cannot activate a rule.')).toBeInTheDocument();
  });
  it('shows an honest energy unavailable state', async () => {
    vi.mocked(intelligenceApi.energy).mockResolvedValue({ available: false, unavailableReason: 'No power or energy readings', totals: [] });
    page('energy');
    fireEvent.click(screen.getByRole('button', { name: 'Load energy analysis' }));
    expect(await screen.findByText('No power or energy readings')).toBeInTheDocument();
    expect(intelligenceApi.energy).toHaveBeenCalledWith('', 24);
  });
  it('requires device and explicit unit for a trend limit and loads readings separately', async () => {
    vi.mocked(intelligenceApi.trend).mockResolvedValue({ trend: { direction: 'INSUFFICIENT_EVIDENCE' } });
    vi.mocked(intelligenceApi.readings).mockResolvedValue([]);
    page('trends');
    expect(screen.getByRole('button', { name: 'Load trend and readings' })).toBeDisabled();
    await screen.findByRole('option', { name: 'Stored sensor' });
    fireEvent.change(screen.getByLabelText('Stored device'), { target: { value: 'device-one' } });
    fireEvent.change(screen.getByLabelText('Configured upper limit'), { target: { value: '100' } });
    expect(screen.getByRole('button', { name: 'Load trend and readings' })).toBeDisabled();
    fireEvent.change(screen.getByLabelText('Limit unit'), { target: { value: 'celsius' } });
    fireEvent.click(screen.getByRole('button', { name: 'Load trend and readings' }));
    expect((await screen.findAllByText('INSUFFICIENT_EVIDENCE')).length).toBeGreaterThan(0);
    expect(intelligenceApi.trend).toHaveBeenCalledWith('device-one', 'temperature', 24, 100, 'celsius');
    await waitFor(() => expect(intelligenceApi.readings).toHaveBeenCalledWith('device-one'));
  });
  it('displays a refused draft outcome and exposes no approval/activation control', async () => {
    vi.mocked(intelligenceApi.propose).mockRejectedValue(new ApiError(422, 'refused', { code: 'MODEL_DISABLED' }));
    page('drafts');
    fireEvent.change(screen.getByLabelText('Proposed rule goal (requires a configured, consented model)'), { target: { value: 'Draft a rule' } });
    fireEvent.click(screen.getByRole('button', { name: 'Generate draft only' }));
    expect(await screen.findByText('MODEL_DISABLED')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /approve|activate/i })).not.toBeInTheDocument();
  });
  it('escapes hostile stored evidence instead of rendering HTML', () => {
    const { container } = render(<EvidenceView value={'<img src=x onerror=alert(1)>'} />);
    expect(container.querySelector('img')).toBeNull();
    expect(container).toHaveTextContent('<img src=x onerror=alert(1)>');
  });
});
