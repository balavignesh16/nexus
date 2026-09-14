import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { UniversalInspector, type InspectorItem } from './UniversalInspector';

function item(overrides: Partial<InspectorItem> = {}): InspectorItem {
  return {
    id: 'action-1',
    type: 'Action',
    title: 'SEND_COMMAND',
    subtitle: 'Target: dev-1',
    status: 'SUCCESS',
    statusColor: 'success',
    properties: { 'Request ID': 'req-1', 'Target Device': 'dev-1' },
    rawJson: { executionId: 'exec-1', status: 'SUCCESS' },
    history: [{ time: '2026-01-01, 12:00:00 PM', action: 'SUCCESS', details: 'Command published to MQTT' }],
    correlationId: 'corr-1',
    ...overrides,
  };
}

describe('UniversalInspector - Step 10A: light/glass migration, no data changes', () => {
  it('renders nothing when there is no item', () => {
    const { container } = render(<UniversalInspector open={true} onClose={vi.fn()} item={null} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('renders when open with a real item', () => {
    render(<UniversalInspector open={true} onClose={vi.fn()} item={item()} />);
    expect(screen.getByText('SEND_COMMAND')).toBeInTheDocument();
  });

  it('renders the real title and subtitle unchanged', () => {
    render(<UniversalInspector open={true} onClose={vi.fn()} item={item({ title: 'GAS_SENSOR_1', subtitle: 'Target: dev-9' })} />);
    expect(screen.getByText('GAS_SENSOR_1')).toBeInTheDocument();
    expect(screen.getByText('Target: dev-9')).toBeInTheDocument();
  });

  it('renders the real type and status labels unchanged', () => {
    render(<UniversalInspector open={true} onClose={vi.fn()} item={item({ type: 'Event', status: 'FAILURE', statusColor: 'destructive' })} />);
    expect(screen.getByText('Event')).toBeInTheDocument();
    expect(screen.getByText('FAILURE')).toBeInTheDocument();
  });

  it('renders every real property key/value pair unchanged', () => {
    render(<UniversalInspector open={true} onClose={vi.fn()} item={item({ properties: { 'Rule Match ID': 'match-42', Status: 'ACCEPTED' } })} />);
    expect(screen.getByText('Rule Match ID')).toBeInTheDocument();
    expect(screen.getByText('match-42')).toBeInTheDocument();
    expect(screen.getByText('Status')).toBeInTheDocument();
    expect(screen.getByText('ACCEPTED')).toBeInTheDocument();
  });

  it('renders the exact real raw JSON content, never truncated or reformatted', () => {
    const rawJson = { executionId: 'exec-42', matchId: 'match-1', nested: { a: 1, b: [1, 2, 3] } };
    const { container } = render(<UniversalInspector open={true} onClose={vi.fn()} item={item({ rawJson })} />);
    expect(container.querySelector('pre')!.textContent).toBe(JSON.stringify(rawJson, null, 2));
  });

  it('omits the Raw JSON section entirely when rawJson is not provided - never a fabricated empty block', () => {
    render(<UniversalInspector open={true} onClose={vi.fn()} item={item({ rawJson: undefined })} />);
    expect(screen.queryByText('Raw JSON')).not.toBeInTheDocument();
  });

  it('renders the real history entries unchanged', () => {
    render(<UniversalInspector open={true} onClose={vi.fn()} item={item({
      history: [{ time: 'yesterday', action: 'ACKNOWLEDGED', details: 'Device accepted the command' }],
    })} />);
    expect(screen.getByText('ACKNOWLEDGED')).toBeInTheDocument();
    expect(screen.getByText('Device accepted the command')).toBeInTheDocument();
    expect(screen.getByText('yesterday')).toBeInTheDocument();
  });

  it('omits the History section entirely when history is not provided', () => {
    render(<UniversalInspector open={true} onClose={vi.fn()} item={item({ history: undefined })} />);
    expect(screen.queryByText('History')).not.toBeInTheDocument();
  });

  it('renders the real correlationId when present, and omits the chip entirely when absent', () => {
    const { rerender } = render(<UniversalInspector open={true} onClose={vi.fn()} item={item({ correlationId: 'corr-real-1' })} />);
    expect(screen.getByText('corr-real-1')).toBeInTheDocument();

    rerender(<UniversalInspector open={true} onClose={vi.fn()} item={item({ correlationId: undefined })} />);
    expect(screen.queryByText('corr-real-1')).not.toBeInTheDocument();
  });

  it('the close button calls the real onClose handler', () => {
    const onClose = vi.fn();
    render(<UniversalInspector open={true} onClose={onClose} item={item()} />);

    fireEvent.click(screen.getByRole('button', { name: 'Close inspector' }));

    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('the close button has an accessible name, fixing the prior icon-only gap', () => {
    render(<UniversalInspector open={true} onClose={vi.fn()} item={item()} />);
    const closeButton = screen.getByRole('button', { name: 'Close inspector' });
    expect(closeButton).toHaveAccessibleName('Close inspector');
  });

  it('clicking the backdrop also calls the real onClose handler', () => {
    const onClose = vi.fn();
    const { container } = render(<UniversalInspector open={true} onClose={onClose} item={item()} />);

    fireEvent.click(container.querySelector('.fixed.inset-0.z-40')!);

    expect(onClose).toHaveBeenCalledTimes(1);
  });
});
