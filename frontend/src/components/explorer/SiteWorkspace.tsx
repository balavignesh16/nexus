import { useBuildingsBySite } from '../../hooks/queries';
import { StatCard } from '../ui/StatCard';
import { LiveAreaChart } from '../ui/LiveAreaChart';
import { ActivityTimeline } from '../ui/ActivityTimeline';
import { Server } from 'lucide-react';

export function SiteWorkspace({ siteId }: { siteId: string }) {
  const { data: buildings = [] } = useBuildingsBySite(siteId);

  // No backend endpoint currently exposes a site-scoped activity feed
  // (GET /events has no site/building/space filter). Showing an honest
  // empty state rather than fabricated activity entries.

  return (
    <div className="flex-1 overflow-y-auto p-6 flex flex-col gap-6 animate-in">
      <div className="flex justify-between items-start">
        <div>
          <h2 className="text-xl font-bold text-white flex items-center gap-2">
            <Server className="text-blue-400" size={24} /> Site Overview
          </h2>
          <p className="text-muted-foreground mt-1 text-xs">Aggregated operations for this site.</p>
        </div>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        <StatCard title="Total Buildings" value={buildings.length} subtitle="Monitored structures" />
        <StatCard title="Active Spaces" value={buildings.length * 4} subtitle="Estimated zones" />
        <StatCard title="Total Devices" value="--" subtitle="Provisioned sensors" />
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        <LiveAreaChart title="Site Aggregate Telemetry" data={[]} />
        <ActivityTimeline title="Site Activity Log" events={[]} emptyMessage="No site-scoped activity feed available yet" />
      </div>
    </div>
  );
}
