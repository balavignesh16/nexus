import { useDevicesBySpace } from '../../hooks/queries';
import { Layers3, Radio } from 'lucide-react';
import { Card } from '../foundation/Card';
import { MetricCard } from '../foundation/MetricCard';
import { SectionHeader } from '../foundation/SectionHeader';
import { EmptyState } from '../foundation/EmptyState';

export function SpaceWorkspace({ spaceId }: { spaceId: string }) {
  const { data: devices = [] } = useDevicesBySpace(spaceId);

  // No backend endpoint currently exposes a space-scoped activity feed.
  // Showing an honest empty state rather than fabricated activity entries.

  return (
    <div className="flex flex-col gap-6 p-4 sm:p-6">
      <SectionHeader
        icon={<Layers3 size={18} />}
        title="Space Overview"
        subtitle="Aggregated metrics for this space and its devices"
      />

      <div className="grid grid-cols-1 sm:grid-cols-2 gap-3 sm:gap-4 max-w-xl">
        <MetricCard label="Devices" value={devices.length} icon={<Radio size={18} />} />
      </div>

      <Card level="glass" title="Space Activity" padding="lg">
        <EmptyState
          icon={<Layers3 size={28} />}
          title="No space-scoped activity feed available yet."
          description="The backend does not currently expose an activity endpoint scoped to a space."
        />
      </Card>
    </div>
  );
}
