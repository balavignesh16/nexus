import { MousePointer2 } from 'lucide-react';
import { EmptyState } from '../foundation/EmptyState';

export function EmptyWorkspace() {
  return (
    <div className="h-full flex items-center justify-center p-8">
      <EmptyState
        icon={<MousePointer2 size={28} />}
        title="Select an item"
        description="Choose a site, building, space, or device from the hierarchy explorer to view details."
      />
    </div>
  );
}
