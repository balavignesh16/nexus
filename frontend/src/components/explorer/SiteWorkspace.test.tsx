import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';

const mockUseBuildingsBySite = vi.fn();
vi.mock('../../hooks/queries', () => ({
  useBuildingsBySite: (...args: any[]) => mockUseBuildingsBySite(...args),
}));

import { SiteWorkspace } from './SiteWorkspace';

describe('SiteWorkspace - real counts only, no fabricated metrics', () => {
  it('shows the real building count, never a multiplied "Estimated" figure or a "--" placeholder', () => {
    mockUseBuildingsBySite.mockReturnValue({ data: [{ id: 'b1', name: 'Building 1' }, { id: 'b2', name: 'Building 2' }] });

    render(<SiteWorkspace siteId="site-1" />);

    expect(screen.getByText('2')).toBeInTheDocument();
    expect(screen.queryByText('8')).not.toBeInTheDocument();
    expect(screen.queryByText('--')).not.toBeInTheDocument();
    expect(screen.queryByText(/Estimated/i)).not.toBeInTheDocument();
  });

  it('shows an honest empty state instead of a fabricated chart or activity feed', () => {
    mockUseBuildingsBySite.mockReturnValue({ data: [] });

    render(<SiteWorkspace siteId="site-1" />);

    expect(screen.getByText('No site-scoped activity feed available yet.')).toBeInTheDocument();
  });
});
