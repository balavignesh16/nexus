import { useSpacesByBuilding } from '../../hooks/queries';
import { Building2, Layers3 } from 'lucide-react';
import { Card } from '../foundation/Card';
import { MetricCard } from '../foundation/MetricCard';
import { SectionHeader } from '../foundation/SectionHeader';
import { EmptyState } from '../foundation/EmptyState';

export function BuildingWorkspace({ buildingId }: { buildingId: string }) {
  const { data: spaces = [] } = useSpacesByBuilding(buildingId);

  // No backend endpoint currently exposes a building-scoped activity feed.
  // Showing an honest empty state rather than fabricated activity entries.

  return (
    <div className="flex flex-col gap-6 p-4 sm:p-6">
      <SectionHeader
        icon={<Building2 size={18} />}
        title="Building Overview"
        subtitle="Aggregated metrics for this building and its spaces"
      />

      <div className="grid grid-cols-1 sm:grid-cols-2 gap-3 sm:gap-4 max-w-xl">
        <MetricCard label="Spaces" value={spaces.length} icon={<Layers3 size={18} />} />
      </div>

      <Card level="glass" title="Building Activity" padding="lg">
        <EmptyState
          icon={<Building2 size={28} />}
          title="No building-scoped activity feed available yet."
          description="The backend does not currently expose an activity endpoint scoped to a building."
        />
      </Card>
    </div>
  );
}
