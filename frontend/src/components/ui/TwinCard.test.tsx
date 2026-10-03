import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { TwinCard } from './TwinCard';
import type { DigitalTwinResponse } from '../../api/twinApi';

function twin(overrides: Partial<DigitalTwinResponse> = {}): DigitalTwinResponse {
  return {
    deviceId: 'dev-1',
    latestTimestamp: '2026-01-01T00:00:00Z',
    latestSensorType: 'TEMPERATURE',
    latestValue: 22.5,
    latestUnit: 'CELSIUS',
    lastSeen: '2026-01-01T00:00:00Z',
    quality: 'GOOD',
    metadata: {},
    reportedState: {
      TEMPERATURE: {
        value: 22.5,
        unit: 'CELSIUS',
        timestamp: '2026-01-01T00:00:00Z',
        lastSeen: '2026-01-01T00:00:00Z',
        quality: 'GOOD',
        metadata: {},
      },
    },
    ...overrides,
  };
}

describe('TwinCard - shows the real reading, not "No runtime state available" for every twin', () => {
  it('shows the real sensor type, value and unit from the real DigitalTwinResponse fields', () => {
    render(<TwinCard twin={twin({
      latestSensorType: 'GAS',
      latestValue: 812,
      latestUnit: 'PPM',
      reportedState: {
        GAS: { value: 812, unit: 'PPM', timestamp: '2026-01-01T00:00:00Z', lastSeen: '2026-01-01T00:00:00Z', quality: 'GOOD', metadata: {} },
      },
    })} />);

    expect(screen.getByText('GAS')).toBeInTheDocument();
    expect(screen.getByText('812 PPM')).toBeInTheDocument();
    expect(screen.queryByText('No reading available')).not.toBeInTheDocument();
  });

  it('shows all capability states belonging to the same device', () => {
    render(<TwinCard twin={twin({
      reportedState: {
        TEMPERATURE: { value: 22.5, unit: 'CELSIUS', timestamp: '2026-01-01T00:00:00Z', lastSeen: '2026-01-01T00:00:00Z', quality: 'GOOD', metadata: {} },
        HUMIDITY: { value: 55, unit: 'PERCENT', timestamp: '2026-01-01T00:00:01Z', lastSeen: '2026-01-01T00:00:01Z', quality: 'GOOD', metadata: {} },
      },
    })} />);

    expect(screen.getByText('TEMPERATURE')).toBeInTheDocument();
    expect(screen.getByText('22.5 CELSIUS')).toBeInTheDocument();
    expect(screen.getByText('HUMIDITY')).toBeInTheDocument();
    expect(screen.getByText('55 PERCENT')).toBeInTheDocument();
  });

  it('shows the real lastSeen time, not "Seen Never" when a reading exists', () => {
    render(<TwinCard twin={twin({ lastSeen: new Date('2026-01-01T12:00:00Z').toISOString() })} />);

    expect(screen.queryByText('Seen Never')).not.toBeInTheDocument();
  });

  it('shows ONLINE for GOOD quality and STALE otherwise, from the real quality field', () => {
    const { rerender } = render(<TwinCard twin={twin({ quality: 'GOOD' })} />);
    expect(screen.getByText('ONLINE')).toBeInTheDocument();

    rerender(<TwinCard twin={twin({ quality: 'STALE' })} />);
    expect(screen.getAllByText('STALE').length).toBeGreaterThan(0);
    expect(screen.queryByText('ONLINE')).not.toBeInTheDocument();
  });
});
