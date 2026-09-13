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
  let bgColor = 'bg-gray-100';
  let textColor = 'text-gray-800';
  let label: string = connectivity;

  switch (connectivity) {
    case 'ONLINE':
      bgColor = 'bg-green-100';
      textColor = 'text-green-800';
      break;
    case 'OFFLINE':
      bgColor = 'bg-red-100';
      textColor = 'text-red-800';
      break;
    case 'UNKNOWN':
      bgColor = 'bg-gray-100';
      textColor = 'text-gray-600';
      break;
  }

  return (
    <span className={`inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium ${bgColor} ${textColor}`}>
      {label}
    </span>
  );
};
