import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { ReactFlowProvider } from 'reactflow';
import { RuleCanvas } from './RuleCanvas';
import { ruleApi } from '../../api/ruleApi';

vi.mock('../../api/ruleApi', () => ({ ruleApi: { createRule: vi.fn(), updateRule: vi.fn(), saveDraft: vi.fn() } }));
vi.mock('./RulePalette', () => ({ RulePalette: () => null }));
vi.mock('./RuleInspector', () => ({ RuleInspector: () => null }));
vi.mock('./RuleSimulator', () => ({ RuleSimulator: () => null }));
describe('RuleCanvas persistence honesty', () => {
  afterEach(() => vi.unstubAllGlobals());
  it('disables saving and cannot claim success or submit a fake graph', () => {
    vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} });
    render(<ReactFlowProvider><RuleCanvas /></ReactFlowProvider>);
    const save = screen.getByRole('button', { name: 'Save unavailable' });
    expect(save).toBeDisabled(); fireEvent.click(save);
    expect(ruleApi.createRule).not.toHaveBeenCalled(); expect(ruleApi.updateRule).not.toHaveBeenCalled(); expect(ruleApi.saveDraft).not.toHaveBeenCalled();
    expect(screen.queryByText(/saved successfully/i)).not.toBeInTheDocument();
  });
});
