import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { DeviceCapabilitiesList } from './DeviceCapabilitiesList';
import type { DeviceCapability } from '../api/deviceApi';

function capability(overrides: Partial<DeviceCapability> = {}): DeviceCapability {
  return {
    id: 'cap-1',
    deviceId: 'dev-1',
    capabilityType: 'TEMPERATURE',
    direction: 'READ',
    unit: 'CELSIUS',
    metadata: undefined,
    ...overrides,
  };
}

describe('DeviceCapabilitiesList - truthful capability rendering', () => {
  it('renders each capability with type, direction, and unit', () => {
    render(
      <DeviceCapabilitiesList
        capabilities={[
          capability({ id: 'cap-1', capabilityType: 'TEMPERATURE', direction: 'READ', unit: 'CELSIUS' }),
          capability({ id: 'cap-2', capabilityType: 'LED', direction: 'WRITE', unit: undefined }),
        ]}
      />
    );

    expect(screen.getByTestId('capabilities-list')).toBeInTheDocument();
    expect(screen.getByText('TEMPERATURE')).toBeInTheDocument();
    expect(screen.getByText('CELSIUS', { exact: false })).toBeInTheDocument();
    expect(screen.getByText('LED')).toBeInTheDocument();
    expect(screen.getByText('WRITE')).toBeInTheDocument();
  });

  it('renders the honest empty state when no capabilities are configured - no fabricated rows', () => {
    render(<DeviceCapabilitiesList capabilities={[]} />);

    expect(screen.getByTestId('capabilities-empty')).toHaveTextContent('No capabilities configured');
    expect(screen.queryByTestId('capabilities-list')).not.toBeInTheDocument();
  });

  it('treats a null/undefined capabilities prop as empty rather than throwing', () => {
    // @ts-expect-error - deliberately exercising a defensive runtime path
    render(<DeviceCapabilitiesList capabilities={undefined} />);

    expect(screen.getByTestId('capabilities-empty')).toBeInTheDocument();
  });
});
