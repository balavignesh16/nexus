import React from 'react';
import type { DeviceCapability } from '../api/deviceApi';

interface DeviceCapabilitiesListProps {
  capabilities: DeviceCapability[];
}

/**
 * Displays NEXUS-authoritative device capability metadata (Tier 5C) -
 * deliberately separate from DeviceStatusBadge (lifecycle) and
 * DeviceConnectivityBadge (physical MQTT connectivity, Tier 5B). Renders
 * only what the backend actually returned - an empty list shows an
 * honest "No capabilities configured" state, never fabricated or demo
 * placeholder rows.
 */
export const DeviceCapabilitiesList: React.FC<DeviceCapabilitiesListProps> = ({ capabilities }) => {
  if (!capabilities || capabilities.length === 0) {
    return (
      <div className="text-xs text-muted-foreground italic" data-testid="capabilities-empty">
        No capabilities configured
      </div>
    );
  }

  return (
    <ul className="flex flex-col gap-1" data-testid="capabilities-list">
      {capabilities.map((capability) => (
        <li key={capability.id} className="flex items-center gap-2 text-xs font-mono">
          <span className="text-foreground/90">{capability.capabilityType}</span>
          <span className="text-muted-foreground">{capability.direction}</span>
          {capability.unit && <span className="text-muted-foreground/70">({capability.unit})</span>}
        </li>
      ))}
    </ul>
  );
};
