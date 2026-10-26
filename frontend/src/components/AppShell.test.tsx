import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';

const mockUseConnectionStatus = vi.fn();
vi.mock('../services/ConnectionManager', () => ({
  useConnectionStatus: () => mockUseConnectionStatus(),
}));

vi.mock('./ui/DemoScenarioSelector', () => ({ DemoScenarioSelector: () => null }));

vi.mock('./ui/CommandPalette', () => ({
  CommandPalette: ({ open }: { open: boolean }) => (open ? <div data-testid="command-palette">palette open</div> : null),
}));

vi.mock('./ui/NotificationCenter', () => ({
  NotificationCenter: ({ open }: { open: boolean }) => (open ? <div data-testid="notification-center">notifications open</div> : null),
}));

import { AppShell } from './AppShell';

function renderShell(initialPath = '/') {
  return render(
    <MemoryRouter initialEntries={[initialPath]}>
      <Routes>
        <Route path="*" element={<AppShell><div data-testid="page-content">content</div></AppShell>} />
      </Routes>
    </MemoryRouter>
  );
}

describe('AppShell - navigation, connection state, real overlays', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockUseConnectionStatus.mockReturnValue({
      realtimeStatus: 'connected',
      backend: 'Online',
      mqtt: 'Online',
      database: 'Online',
      messagesPerSec: 4.2,
      latencyMs: 18,
      droppedMessages: 0,
      reconnectAttempts: 0,
      clientOffset: 2,
    });
  });

  it('keeps the current destinations accessible in labelled navigation groups', () => {
    renderShell();
    for(const label of ['Intelligence','Decide & Act','Framework'])fireEvent.click(screen.getByRole('button',{name:label}));

    const expected: [string, string][] = [
      ['Overview', '/'],
      ['Infrastructure', '/explorer'],
      ['Digital Twin', '/twins'],
      ['Telemetry', '/telemetry'],
      ['Events', '/events'],
      ['Pipeline', '/pipeline'],
      ['Analytics', '/analytics'],
      ['AI Insights', '/intelligence'],
      ['Three ESP Simulation', '/simulation'],
      ['Manage Infrastructure', '/infrastructure'],
      ['Device Onboarding', '/framework/onboarding'],
      ['Device Templates', '/framework/templates'],
      ['Capability Definitions', '/framework/capabilities'],
      ['Generic Rules', '/framework/rules'],
      ['AI Rule Drafts', '/ai/drafts'],
      ['Trends', '/trends'],
      ['Energy Intelligence', '/energy'],
      ['Rules', '/rules'],
      ['Actions', '/actions'],
      ['Settings', '/settings'],
    ];

    for (const [label, path] of expected) {
      const link = screen.getByRole('link', { name: label });
      expect(link).toHaveAttribute('href', path);
    }
  });

  it('marks the current route active via aria-current', () => {
    renderShell('/rules');

    expect(screen.getByRole('link', { name: 'Rules' })).toHaveAttribute('aria-current', 'page');
    expect(screen.getByRole('link', { name: 'Overview' })).not.toHaveAttribute('aria-current');
  });

  it('treats the root path as exact-match only (does not also mark Overview active on nested routes)', () => {
    renderShell('/explorer');

    expect(screen.getByRole('link', { name: 'Overview' })).not.toHaveAttribute('aria-current');
    expect(screen.getByRole('link', { name: 'Infrastructure' })).toHaveAttribute('aria-current', 'page');
  });

  it('reflects real connection status text, not a hardcoded value', () => {
    mockUseConnectionStatus.mockReturnValue({
      realtimeStatus: 'recovering',
      backend: 'Degraded',
      mqtt: 'Offline',
      database: 'Online',
      messagesPerSec: 0,
      latencyMs: 999,
      droppedMessages: 3,
      reconnectAttempts: 2,
      clientOffset: 40,
    });

    renderShell();

    expect(screen.getAllByText(/recovering/).length).toBeGreaterThan(0);
  });

  it('opens the real CommandPalette from the search trigger', () => {
    renderShell();

    fireEvent.click(screen.getAllByRole('button', { name: 'Open search' })[0]);

    expect(screen.getByTestId('command-palette')).toBeInTheDocument();
  });

  it('opens the real NotificationCenter from the notification trigger, with no decorative unread dot', () => {
    renderShell();

    const bell = screen.getByRole('button', { name: 'Notifications' });
    // No unconditional "unread" indicator - NotificationCenter has no real unread concept.
    expect(bell.querySelector('span[class*="bg-"]')).toBeNull();

    fireEvent.click(bell);

    expect(screen.getByTestId('notification-center')).toBeInTheDocument();
  });

  it('renders the routed page content', () => {
    renderShell();

    expect(screen.getByTestId('page-content')).toBeInTheDocument();
  });

  it('collapses explicitly instead of expanding on hover, and restores a selected group', () => {
    renderShell();
    fireEvent.click(screen.getByRole('button',{name:'Collapse navigation'}));
    expect(screen.queryByRole('link',{name:'Infrastructure'})).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button',{name:'Intelligence'}));
    expect(screen.getByRole('link',{name:'AI Insights'})).toHaveAttribute('href','/intelligence');
    expect(screen.getByRole('button',{name:'Collapse navigation'})).toBeInTheDocument();
  });

  it('shows labelled real service states on demand rather than anonymous dots', () => {
    renderShell();
    fireEvent.click(screen.getByRole('button',{name:'Connection details'}));
    expect(screen.getByText('MQTT')).toBeInTheDocument();
    expect(screen.getByText('Database')).toBeInTheDocument();
    expect(screen.getByText('Realtime')).toBeInTheDocument();
    expect(screen.getByText('4/4')).toBeInTheDocument();
  });

  it('opens mobile navigation and closes on Escape', () => {
    renderShell();
    fireEvent.click(screen.getByRole('button',{name:'Open navigation'}));
    expect(screen.getByRole('dialog',{name:'Application navigation'})).toBeInTheDocument();
    fireEvent.keyDown(document,{key:'Escape'});
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('the content slot is not display:flex, so a narrower page cannot leave dark bg-canvas exposed as a right-side gap (regression)', () => {
    renderShell();

    const contentSlot = screen.getByTestId('page-content').parentElement;
    const classes = (contentSlot?.className ?? '').split(/\s+/);
    expect(classes).not.toContain('flex');
    expect(classes).toContain('bg-canvas');
  });

  it('the content slot forces its single page child to fill 100% width and height (regression: a page using bare flex-1 without h-full, e.g. ExplorerPage, must not collapse to its own content height)', () => {
    renderShell();

    const contentSlot = screen.getByTestId('page-content').parentElement;
    const classes = (contentSlot?.className ?? '').split(/\s+/);
    expect(classes).toContain('[&>*]:w-full');
    expect(classes).toContain('[&>*]:h-full');
  });
});
