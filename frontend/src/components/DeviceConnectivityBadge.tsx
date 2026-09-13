import React from 'react';

export type DeviceConnectivity = 'ONLINE' | 'OFFLINE' | 'UNKNOWN';

interface DeviceConnectivityBadgeProps {
  connectivity: DeviceConnectivity;
}

/**
 * Displays physical MQTT connectivity (Tier 5B) - deliberately a separate
 * badge from DeviceStatusBadge (lifecycle: REGISTERED/ACTIVE/MAINTENANCE/
 * OFFLINE). A caller renders both side by side, e.g.:
 *
 *   <DeviceStatusBadge status={device.status} />
 *   <DeviceConnectivityBadge connectivity={connectivity} />
 *
 * 'UNKNOWN' means no connectivity observation has been received yet for
 * this device in the current session (no realtime event has arrived) - it
 * is never defaulted to OFFLINE.
 */
export const DeviceConnectivityBadge: React.FC<DeviceConnectivityBadgeProps> = ({ connectivity }) => {
  let className = 'bg-[var(--data-unavailable)]/10 text-[var(--data-unavailable)] border-[var(--data-unavailable)]/30';
  let label: string = connectivity;

  switch (connectivity) {
    case 'ONLINE':
      className = 'bg-success/10 text-success border-success/30';
      break;
    case 'OFFLINE':
      className = 'bg-destructive/10 text-destructive border-destructive/30';
      break;
    case 'UNKNOWN':
      className = 'bg-[var(--data-unavailable)]/10 text-[var(--data-unavailable)] border-[var(--data-unavailable)]/30';
      break;
  }

  return (
    <span className={`inline-flex items-center px-2.5 py-0.5 rounded-full border text-xs font-medium ${className}`}>
      {label}
    </span>
  );
};
