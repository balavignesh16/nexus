import { useBuildingsBySite } from '../../hooks/queries';
import { Server, Building2 } from 'lucide-react';
import { Card } from '../foundation/Card';
import { MetricCard } from '../foundation/MetricCard';
import { SectionHeader } from '../foundation/SectionHeader';
import { EmptyState } from '../foundation/EmptyState';

export function SiteWorkspace({ siteId }: { siteId: string }) {
  const { data: buildings = [] } = useBuildingsBySite(siteId);

  // No backend endpoint currently exposes a site-scoped activity feed
  // (GET /events has no site/building/space filter). Showing an honest
  // empty state rather than fabricated activity entries.

  return (
    <div className="flex flex-col gap-6 p-4 sm:p-6">
      <SectionHeader
        icon={<Server size={18} />}
        title="Site Overview"
        subtitle="Aggregated operations for this site"
      />

      <div className="grid grid-cols-1 sm:grid-cols-2 gap-3 sm:gap-4 max-w-xl">
        <MetricCard label="Buildings" value={buildings.length} icon={<Building2 size={18} />} />
      </div>

      <Card level="glass" title="Site Activity" padding="lg">
        <EmptyState
          icon={<Server size={28} />}
          title="No site-scoped activity feed available yet."
          description="The backend does not currently expose an activity endpoint scoped to a site."
        />
      </Card>
    </div>
  );
}
