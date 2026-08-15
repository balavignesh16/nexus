import { useDevicesBySpace } from '../../hooks/queries';
import { StatCard } from '../ui/StatCard';
import { LiveAreaChart } from '../ui/LiveAreaChart';
import { ActivityTimeline } from '../ui/ActivityTimeline';
import { Layers3, Activity } from 'lucide-react';

export function SpaceWorkspace({ spaceId }: { spaceId: string }) {
  const { data: devices = [] } = useDevicesBySpace(spaceId);

  const mockEvents = [
    { id: '1', time: '12:05 PM', title: 'Occupancy limit reached', description: 'Zone A exceeded 50 people', icon: Activity, type: 'telemetry' as const },
  ];

  return (
    <div className="flex-1 overflow-y-auto p-6 flex flex-col gap-6 animate-in">
      <div className="flex justify-between items-start">
        <div>
          <h2 className="text-xl font-bold text-white flex items-center gap-2">
            <Layers3 className="text-blue-400" size={24} /> Space Overview
          </h2>
          <p className="text-muted-foreground mt-1 text-xs">Aggregated metrics for this space and its devices.</p>
        </div>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        <StatCard title="Total Devices" value={devices.length} subtitle="Provisioned sensors" />
        <StatCard title="Air Quality Index" value="95" subtitle="Excellent" />
        <StatCard title="Active Alerts" value="0" subtitle="Requires attention" />
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        <LiveAreaChart title="Space Occupancy Trend" data={[]} color="#10b981" />
        <ActivityTimeline title="Space Activity Log" events={mockEvents} />
      </div>
    </div>
  );
}
