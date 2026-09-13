import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
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
    },
  };
});

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <AiInsightsPage />
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

describe('AiInsightsPage - evidence-grounded rule-match explanations, never a chatbot', () => {
  beforeEach(() => {
    vi.clearAllMocks();
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
});
