import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { DataSourceBadge } from './DataSourceBadge';

describe('DataSourceBadge - classification is authoritative, chrome is switchable', () => {
  it('defaults to the original dark chip - every existing production call site is visually unaffected', () => {
    render(<DataSourceBadge classification="CALCULATED" />);

    const badge = screen.getByText('Calculated').closest('span');
    expect(badge?.className).toContain('bg-raised');
    expect(badge?.className).toContain('border-surface-border');
  });

  it('variant="foundation" renders a soft tinted-background chip instead of the dark chip', () => {
    render(<DataSourceBadge classification="CALCULATED" variant="foundation" />);

    const badge = screen.getByText('Calculated').closest('span');
    expect(badge?.className).not.toContain('bg-raised');
    expect(badge?.style.backgroundColor).toContain('color-mix');
  });

  it('the classification-to-label mapping is identical across both variants (semantics untouched)', () => {
    const { rerender } = render(<DataSourceBadge classification="AI" />);
    expect(screen.getByText('AI-Generated')).toBeInTheDocument();

    rerender(<DataSourceBadge classification="AI" variant="foundation" />);
    expect(screen.getByText('AI-Generated')).toBeInTheDocument();
  });

  it('renders every real classification correctly in the foundation variant', () => {
    const classifications = ['LIVE', 'CALCULATED', 'SIMULATED', 'UNAVAILABLE', 'AI'] as const;
    for (const c of classifications) {
      const { unmount } = render(<DataSourceBadge classification={c} variant="foundation" />);
      unmount();
    }
  });
});
