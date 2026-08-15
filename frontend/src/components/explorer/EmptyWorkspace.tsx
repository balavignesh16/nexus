import { MousePointer2 } from 'lucide-react';
import { EmptyState } from '../ui/EmptyState';

export function EmptyWorkspace() {
  return (
    <EmptyState 
      icon={MousePointer2} 
      title="Select an Item" 
      description="Choose a site, building, space, or device from the hierarchy explorer to view details."
    />
  );
}
