import { Settings } from 'lucide-react';
import { EmptyState } from '../components/ui/EmptyState';

export default function SystemPage() {
  return (
    <div className="h-full bg-[#060b14] flex flex-col">
      <div className="shrink-0 p-6 border-b border-[#1f1f28]">
        <h1 className="text-2xl font-bold text-white tracking-tight">System Settings</h1>
        <p className="text-muted-foreground text-sm mt-1">Global platform configuration.</p>
      </div>
      <EmptyState 
        icon={Settings} 
        title="Settings Not Available" 
        description="You do not have permission to view or edit system settings."
      />
    </div>
  );
}
