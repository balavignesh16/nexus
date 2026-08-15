import { useSpacesByBuilding } from '../../hooks/queries';
import { StatCard } from '../ui/StatCard';
import { LiveAreaChart } from '../ui/LiveAreaChart';
import { ActivityTimeline } from '../ui/ActivityTimeline';
import { Building2, Activity } from 'lucide-react';

export function BuildingWorkspace({ buildingId }: { buildingId: string }) {
  const { data: spaces = [] } = useSpacesByBuilding(buildingId);

  const mockEvents = [
    { id: '1', time: '11:15 AM', title: 'HVAC setpoint adjusted', description: 'Rule engine triggered temperature optimization', icon: Activity, type: 'rule' as const },
  ];

  return (
    <div className="flex-1 overflow-y-auto p-6 flex flex-col gap-6 animate-in">
      <div className="flex justify-between items-start">
        <div>
          <h2 className="text-xl font-bold text-white flex items-center gap-2">
            <Building2 className="text-blue-400" size={24} /> Building Overview
          </h2>
          <p className="text-muted-foreground mt-1 text-xs">Aggregated metrics for this building and its spaces.</p>
        </div>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        <StatCard title="Total Spaces" value={spaces.length} subtitle="Active zones" />
        <StatCard title="Health Score" value="98%" subtitle="Overall system health" />
        <StatCard title="Active Alerts" value="0" subtitle="Requires attention" />
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        <LiveAreaChart title="Building Energy Consumption" data={[]} color="#8b5cf6" />
        <ActivityTimeline title="Building Activity Log" events={mockEvents} />
      </div>
    </div>
  );
}
