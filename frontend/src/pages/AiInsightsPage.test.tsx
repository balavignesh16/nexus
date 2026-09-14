import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import AiInsightsPage from './AiInsightsPage';
import { aiApi } from '../api/aiApi';
import { ApiError } from '../api/apiClient';

vi.mock('../api/aiApi', async () => {
  const actual = await vi.importActual<typeof import('../api/aiApi')>('../api/aiApi');
  return {
    ...actual,
    aiApi: {
      getRuleMatchHistory: vi.fn(),
      explainRuleMatch: vi.fn(),
      getActionsForMatch: vi.fn(),
    },
  };
});

function renderPage(initialPath = '/ai') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[initialPath]}>
        <AiInsightsPage />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

function match(overrides: Partial<{ matchId: string; ruleId: string; ruleName: string | null; matchedAt: string; correlationId: string | null; message: string | null }> = {}) {
  return {
    matchId: 'match-1',
    ruleId: 'rule-1',
    ruleName: 'High Temperature Alert',
    matchedAt: '2026-01-01T00:00:00Z',
    correlationId: 'corr-abc',
    message: 'All conditions met',
    ...overrides,
  };
}

function matchAction(overrides: Partial<{ executionId: string; actionType: string; status: string; timestamp: string; ackStatus: 'ACCEPTED' | 'REJECTED' | null }> = {}) {
  return {
    executionId: 'exec-1',
    requestId: 'exec-1',
    matchId: 'match-1',
    deviceId: 'device-1',
    actionType: 'SEND_COMMAND',
    status: 'SUCCESS',
    timestamp: '2026-01-01T00:05:00Z',
    message: 'Published',
    ackStatus: null,
    ackReceivedAt: null,
    ackMessage: null,
    ...overrides,
  };
}

describe('AiInsightsPage - evidence-grounded rule-match explanations, never a chatbot', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(aiApi.getActionsForMatch).mockResolvedValue([]);
  });

  it('renders no chat input, no send button, no fabricated sample content', async () => {
    vi.mocked(aiApi.getRuleMatchHistory).mockResolvedValue([]);
    renderPage();

    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
    expect(screen.queryByText(/Operations Copilot/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/Coming Soon/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/sample/i)).not.toBeInTheDocument();
  });

  it('shows an honest empty state when no rule matches exist', async () => {
    vi.mocked(aiApi.getRuleMatchHistory).mockResolvedValue([]);
    renderPage();

    await waitFor(() => expect(screen.getByText(/No rule matches recorded yet/i)).toBeInTheDocument());
  });

  it('shows an empty-selection state before any match is picked', async () => {
    vi.mocked(aiApi.getRuleMatchHistory).mockResolvedValue([match()]);
    renderPage();

    await waitFor(() => expect(screen.getByText('High Temperature Alert')).toBeInTheDocument());
    expect(screen.getByText(/Select a rule match to view/i)).toBeInTheDocument();
  });

  it('fetches and renders a real AI-generated explanation, labeled AI', async () => {
    vi.mocked(aiApi.getRuleMatchHistory).mockResolvedValue([match()]);
    vi.mocked(aiApi.explainRuleMatch).mockResolvedValue({
      matchId: 'match-1',
      summary: 'The rule matched because temperature exceeded the threshold.',
      claims: [
        { tier: 'FACT', text: 'Temperature was 42.0', evidenceRef: 'telemetry:temp', assertedValue: 42.0, supportingEvidenceRefs: null },
      ],
      actionNote: null,
      generatedBy: 'AI',
    });
    renderPage();

    await waitFor(() => screen.getByText('High Temperature Alert'));
    fireEvent.click(screen.getByTestId('rule-match-match-1'));

    await waitFor(() => expect(aiApi.explainRuleMatch).toHaveBeenCalledWith('match-1'));
    await waitFor(() => expect(screen.getByText(/exceeded the threshold/i)).toBeInTheDocument());
    expect(screen.getByText('AI-Generated')).toBeInTheDocument();
    expect(screen.queryByText('Calculated')).not.toBeInTheDocument();
  });

  it('renders a deterministic-fallback explanation labeled Calculated, never as AI', async () => {
    vi.mocked(aiApi.getRuleMatchHistory).mockResolvedValue([match()]);
    vi.mocked(aiApi.explainRuleMatch).mockResolvedValue({
      matchId: 'match-1',
      summary: 'Deterministic summary of what matched.',
      claims: [],
      actionNote: 'All action executors are stubs.',
      generatedBy: 'DETERMINISTIC_FALLBACK',
    });
    renderPage();

    await waitFor(() => screen.getByText('High Temperature Alert'));
    fireEvent.click(screen.getByTestId('rule-match-match-1'));

    await waitFor(() => expect(screen.getByText('Calculated')).toBeInTheDocument());
    expect(screen.queryByText('AI-Generated')).not.toBeInTheDocument();
  });

  it('shows the truthful 404 message when the match no longer exists', async () => {
    vi.mocked(aiApi.getRuleMatchHistory).mockResolvedValue([match()]);
    vi.mocked(aiApi.explainRuleMatch).mockRejectedValue(new ApiError(404, 'RuleMatch not found: match-1'));
    renderPage();

    await waitFor(() => screen.getByText('High Temperature Alert'));
    fireEvent.click(screen.getByTestId('rule-match-match-1'));

    await waitFor(() => expect(screen.getByTestId('explanation-error')).toHaveTextContent('This rule match no longer exists.'));
  });

  it('shows the truthful 429 message when rate-limited - never a fabricated countdown', async () => {
    vi.mocked(aiApi.getRuleMatchHistory).mockResolvedValue([match()]);
    vi.mocked(aiApi.explainRuleMatch).mockRejectedValue(new ApiError(429, 'Too Many Requests'));
    renderPage();

    await waitFor(() => screen.getByText('High Temperature Alert'));
    fireEvent.click(screen.getByTestId('rule-match-match-1'));

    await waitFor(() => expect(screen.getByTestId('explanation-error')).toHaveTextContent('Too many explanation requests — try again in a moment.'));
  });

  it('surfaces the real backend error message for any other failure', async () => {
    vi.mocked(aiApi.getRuleMatchHistory).mockResolvedValue([match()]);
    vi.mocked(aiApi.explainRuleMatch).mockRejectedValue(new ApiError(500, 'Internal Server Error'));
    renderPage();

    await waitFor(() => screen.getByText('High Temperature Alert'));
    fireEvent.click(screen.getByTestId('rule-match-match-1'));

    await waitFor(() => expect(screen.getByTestId('explanation-error')).toHaveTextContent('Internal Server Error'));
  });

  it('shows an honest empty state when the match triggered no actions', async () => {
    vi.mocked(aiApi.getRuleMatchHistory).mockResolvedValue([match()]);
    vi.mocked(aiApi.explainRuleMatch).mockResolvedValue({
      matchId: 'match-1', summary: 'x', claims: [], actionNote: null, generatedBy: 'DETERMINISTIC_FALLBACK',
    });
    vi.mocked(aiApi.getActionsForMatch).mockResolvedValue([]);
    renderPage();

    await waitFor(() => screen.getByText('High Temperature Alert'));
    fireEvent.click(screen.getByTestId('rule-match-match-1'));

    await waitFor(() => expect(screen.getByText('No actions were recorded for this rule match.')).toBeInTheDocument());
  });

  it('renders real resulting actions with correct execution status and ack status', async () => {
    vi.mocked(aiApi.getRuleMatchHistory).mockResolvedValue([match()]);
    vi.mocked(aiApi.explainRuleMatch).mockResolvedValue({
      matchId: 'match-1', summary: 'x', claims: [], actionNote: null, generatedBy: 'DETERMINISTIC_FALLBACK',
    });
    vi.mocked(aiApi.getActionsForMatch).mockResolvedValue([matchAction({ ackStatus: 'ACCEPTED' })]);
    renderPage();

    await waitFor(() => screen.getByText('High Temperature Alert'));
    fireEvent.click(screen.getByTestId('rule-match-match-1'));

    await waitFor(() => expect(screen.getByText('SEND_COMMAND')).toBeInTheDocument());
    expect(screen.getByText('ACCEPTED')).toBeInTheDocument();
  });

  it('shows NO ACK YET (never FAILURE) when a resulting action has no ack yet', async () => {
    vi.mocked(aiApi.getRuleMatchHistory).mockResolvedValue([match()]);
    vi.mocked(aiApi.explainRuleMatch).mockResolvedValue({
      matchId: 'match-1', summary: 'x', claims: [], actionNote: null, generatedBy: 'DETERMINISTIC_FALLBACK',
    });
    vi.mocked(aiApi.getActionsForMatch).mockResolvedValue([matchAction({ ackStatus: null })]);
    renderPage();

    await waitFor(() => screen.getByText('High Temperature Alert'));
    fireEvent.click(screen.getByTestId('rule-match-match-1'));

    await waitFor(() => expect(screen.getByText('NO ACK YET')).toBeInTheDocument());
    expect(screen.queryByText('FAILURE')).not.toBeInTheDocument();
  });

  it('still shows resulting actions when the AI explanation itself errors - investigation UI stays usable', async () => {
    vi.mocked(aiApi.getRuleMatchHistory).mockResolvedValue([match()]);
    vi.mocked(aiApi.explainRuleMatch).mockRejectedValue(new ApiError(500, 'NIM unavailable'));
    vi.mocked(aiApi.getActionsForMatch).mockResolvedValue([matchAction({ ackStatus: 'REJECTED' })]);
    renderPage();

    await waitFor(() => screen.getByText('High Temperature Alert'));
    fireEvent.click(screen.getByTestId('rule-match-match-1'));

    await waitFor(() => expect(screen.getByTestId('explanation-error')).toBeInTheDocument());
    expect(screen.getByText('REJECTED')).toBeInTheDocument();
  });

  it('deep-links directly to a match via ?matchId= in the URL (from RulesTab "Recent Matches")', async () => {
    vi.mocked(aiApi.getRuleMatchHistory).mockResolvedValue([match()]);
    vi.mocked(aiApi.explainRuleMatch).mockResolvedValue({
      matchId: 'match-1', summary: 'Deep-linked explanation.', claims: [], actionNote: null, generatedBy: 'DETERMINISTIC_FALLBACK',
    });
    renderPage('/ai?matchId=match-1');

    await waitFor(() => expect(aiApi.explainRuleMatch).toHaveBeenCalledWith('match-1'));
    await waitFor(() => expect(screen.getByText('Deep-linked explanation.')).toBeInTheDocument());
  });
});
