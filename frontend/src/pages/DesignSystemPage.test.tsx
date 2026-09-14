import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { DesignSystemPage } from './DesignSystemPage';

describe('DesignSystemPage - foundation showcase, no fabricated operational data', () => {
  it('renders without crashing and shows the foundation heading', () => {
    render(<DesignSystemPage />);

    expect(screen.getByText('Premium Glass Control Plane')).toBeInTheDocument();
  });

  it('demonstrates every requested primitive category', () => {
    render(<DesignSystemPage />);

    expect(screen.getByText('Surface Levels')).toBeInTheDocument();
    expect(screen.getByText('Cards')).toBeInTheDocument();
    expect(screen.getByText('Buttons')).toBeInTheDocument();
    expect(screen.getByText('Toggles, Pills & Tabs')).toBeInTheDocument();
    expect(screen.getByText('Badges & Status Indicators')).toBeInTheDocument();
    expect(screen.getByText('Inputs')).toBeInTheDocument();
    expect(screen.getByText('Table')).toBeInTheDocument();
    expect(screen.getByText('Metric Presentation & Gauge')).toBeInTheDocument();
    expect(screen.getByText('Loading, Empty & Error States')).toBeInTheDocument();
  });

  it('explicitly labels itself as component states, not live data', () => {
    render(<DesignSystemPage />);

    expect(screen.getByText(/nothing on this page is live operational data/i)).toBeInTheDocument();
  });

  it('never claims a fabricated value is LIVE - only labeled illustrative states', () => {
    render(<DesignSystemPage />);

    expect(screen.getAllByText('Live').length).toBeGreaterThan(0); // DataSourceBadge showcase label itself
    expect(screen.getAllByText(/illustrative/i).length).toBeGreaterThan(0);
  });

  it('no longer presents cyan/blue as the primary visual accent (palette correction)', () => {
    const { container } = render(<DesignSystemPage />);

    // The legacy blue --primary is still shown, but only as one reference
    // swatch explicitly labeled "legacy" - never as the page's dominant
    // active/selected color.
    expect(screen.getByText('primary (legacy)')).toBeInTheDocument();
    expect(screen.getByText('accent (new)')).toBeInTheDocument();
    expect(container.innerHTML).not.toContain('22d3ee');
    expect(container.innerHTML).not.toContain('Primary (blue) + accent (cyan)');
  });

  it('uses the new light-theme foundation text tokens on its own root, not the dark-tuned global ones (regression)', () => {
    const { container } = render(<DesignSystemPage />);

    // The page root previously used the global text-foreground/
    // text-muted-foreground classes (near-white, tuned for the app's dark
    // production pages) - on the new white/light-neutral background that
    // rendered as invisible near-white-on-white text. The root and its
    // direct new-foundation content must use the foundation-scoped
    // tokens instead. The preserved dark "Legacy Showcase" box is the one
    // deliberate, wrapped exception.
    const root = container.firstChild as HTMLElement;
    expect(root.className).toContain('text-foundation-fg');
    expect(root.className).not.toContain('text-foreground ');
    expect(root.className).not.toMatch(/\btext-foreground\b(?!-)/);
  });

  it('never uses a blue/cyan/indigo hex literal outside the explicitly-labeled legacy reference swatch', () => {
    const { container } = render(<DesignSystemPage />);

    // #3b82f6 (legacy --primary, blue) may still appear once, inside the
    // reference swatch labeled "primary (legacy)" - but the page must not
    // introduce any new blue/cyan/indigo color literal.
    expect(container.innerHTML).not.toContain('#22d3ee');
    expect(container.innerHTML).not.toContain('#6366f1');
    expect(container.innerHTML).not.toContain('rgba(34, 211, 238');
    expect(container.innerHTML).not.toContain('rgba(59, 130, 246');
  });
});
