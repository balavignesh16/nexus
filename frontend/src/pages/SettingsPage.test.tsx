import { describe, it, expect } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import SettingsPage from './SettingsPage';

function renderPage() {
  return render(<SettingsPage />);
}

describe('SettingsPage - Step 10C: light/glass migration, no new functionality', () => {
  it('renders with Appearance selected by default', () => {
    renderPage();
    expect(screen.getByText('Customize the UI density, theme, and animations across the workspace.')).toBeInTheDocument();
  });

  it('sidebar tab switching works - the one real behavior on this page', async () => {
    renderPage();

    fireEvent.click(screen.getByRole('button', { name: /Users & Roles/i }));

    await waitFor(() => expect(screen.getByText('Configuration section for users.')).toBeInTheDocument());
  });

  it('switching to Simulator renders real Simulator content', async () => {
    renderPage();

    fireEvent.click(screen.getByRole('button', { name: /Simulator/i }));

    await waitFor(() => expect(screen.getByText('Configure the behavior of the DemoRepository and ScenarioEngine.')).toBeInTheDocument());
    expect(screen.getByText('Telemetry Generation Rate (ms)')).toBeInTheDocument();
  });

  it('switching to any other tab renders the honest "Module Not Configured" stub, never fabricated content', async () => {
    renderPage();

    fireEvent.click(screen.getByRole('button', { name: /MQTT Broker/i }));

    await waitFor(() => expect(screen.getByText('Module Not Configured')).toBeInTheDocument());
    expect(screen.getByText('This enterprise module requires backend capabilities that are currently inactive in Hybrid Mode.')).toBeInTheDocument();
  });

  it('all 13 real settings tabs are present, grouped correctly', () => {
    renderPage();

    const labels = [
      'General', 'Appearance', 'Simulator', 'Feature Flags',
      'Users & Roles', 'Permissions', 'API Keys',
      'MQTT Broker', 'Database', 'Integrations',
      'Notifications', 'Health Checks', 'Audit Logs',
    ];
    labels.forEach((label) => {
      expect(screen.getByRole('button', { name: new RegExp(label, 'i') })).toBeInTheDocument();
    });

    expect(screen.getByText('Configuration')).toBeInTheDocument();
    expect(screen.getByText('Access Management')).toBeInTheDocument();
    expect(screen.getByText('Infrastructure')).toBeInTheDocument();
    expect(screen.getByText('System')).toBeInTheDocument();
  });

  it('the search input exists with a real accessible name, honestly not wired to any filtering', () => {
    renderPage();

    const search = screen.getByRole('textbox', { name: 'Search settings' });
    expect(search).toBeInTheDocument();

    // Typing is real (controlled input), but never removes/filters any tab.
    fireEvent.change(search, { target: { value: 'zzz-no-match-zzz' } });
    expect(screen.getByRole('button', { name: /Appearance/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Simulator/i })).toBeInTheDocument();
  });

  it('the theme cards remain non-functional - clicking either never changes activeTab or any application state', () => {
    renderPage();

    fireEvent.click(screen.getByText('NEXUS Dark (Default)'));
    fireEvent.click(screen.getByText('Light Mode'));

    // Still on Appearance - no navigation, no theme-switch side effect.
    expect(screen.getByText('Customize the UI density, theme, and animations across the workspace.')).toBeInTheDocument();
    expect(screen.getByText('Selected')).toBeInTheDocument();
    expect(screen.getByText('Unavailable')).toBeInTheDocument();
  });

  it('Apply Changes remains non-functional - no success/persistence effect appears', async () => {
    renderPage();
    fireEvent.click(screen.getByRole('button', { name: /Simulator/i }));
    const applyButton = await waitFor(() => screen.getByRole('button', { name: 'Apply Changes' }));

    fireEvent.click(applyButton);

    expect(screen.queryByText(/saved/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/applied/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/success/i)).not.toBeInTheDocument();
  });

  it('Simulator input changes are real but locally uncontrolled - never trigger external behavior', async () => {
    renderPage();
    fireEvent.click(screen.getByRole('button', { name: /Simulator/i }));
    await waitFor(() => expect(screen.getByText('Telemetry Generation Rate (ms)')).toBeInTheDocument());

    const rateInput = screen.getByDisplayValue('2000');
    fireEvent.change(rateInput, { target: { value: '9999' } });

    // No confirmation/side-effect text appears anywhere as a result.
    expect(screen.queryByText(/updated/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/applied/i)).not.toBeInTheDocument();
  });

  it('the Reduced Motion toggle flips its own visual state but configures no runtime motion behavior', () => {
    renderPage();

    const toggle = screen.getByRole('switch', { name: 'Reduced Motion' });
    expect(toggle).toHaveAttribute('aria-checked', 'false');

    fireEvent.click(toggle);

    expect(toggle).toHaveAttribute('aria-checked', 'true');
    // No document-level class, no localStorage write, no motion-config text.
    expect(document.documentElement.className).not.toMatch(/reduced-motion/);
    expect(window.localStorage.getItem('nexus-reduced-motion')).toBeNull();
  });
});
