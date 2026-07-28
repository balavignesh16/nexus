import React from 'react';
import { DeviceStatus } from '../api/deviceApi';

interface DeviceStatusBadgeProps {
  status: DeviceStatus;
}

export const DeviceStatusBadge: React.FC<DeviceStatusBadgeProps> = ({ status }) => {
  let bgColor = 'bg-gray-100';
  let textColor = 'text-gray-800';

  switch (status) {
    case DeviceStatus.REGISTERED:
      bgColor = 'bg-blue-100';
      textColor = 'text-blue-800';
      break;
    case DeviceStatus.ACTIVE:
      bgColor = 'bg-green-100';
      textColor = 'text-green-800';
      break;
    case DeviceStatus.OFFLINE:
      bgColor = 'bg-red-100';
      textColor = 'text-red-800';
      break;
    case DeviceStatus.MAINTENANCE:
      bgColor = 'bg-yellow-100';
      textColor = 'text-yellow-800';
      break;
  }

  return (
    <span className={`inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium ${bgColor} ${textColor}`}>
      {status}
    </span>
  );
};
