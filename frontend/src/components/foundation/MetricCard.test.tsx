import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MetricCard } from './MetricCard';

describe('MetricCard', () => {
  it('renders the real caller-supplied value and unit', () => {
    render(<MetricCard label="Events" value={42} unit="count" />);

    expect(screen.getByText('42')).toBeInTheDocument();
    expect(screen.getByText('count')).toBeInTheDocument();
  });

  it('never renders a trend unless the caller explicitly supplies one', () => {
    render(<MetricCard label="Events" value={42} />);

    expect(screen.queryByText(/↑|↓|→/)).not.toBeInTheDocument();
  });

  it('renders exactly the caller-supplied trend text, never a fabricated one', () => {
    render(<MetricCard label="Events" value={42} trend={{ direction: 'up', text: '+3 vs real prior window' }} />);

    expect(screen.getByText(/\+3 vs real prior window/)).toBeInTheDocument();
  });
});
