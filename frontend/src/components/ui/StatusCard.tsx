import type { LucideIcon } from 'lucide-react';
import { cn } from '../../lib/utils';

interface StatusCardProps {
  title: string;
  status: 'Healthy' | 'Warning' | 'Offline';
  icon: LucideIcon;
  lastUpdated: string;
}

export function StatusCard({ title, status, icon: Icon, lastUpdated }: StatusCardProps) {
  return (
    <div className="bg-card border border-border rounded-lg p-4 flex items-center justify-between hover:border-primary/50 transition-colors">
      <div className="flex items-center gap-4">
        <div className={cn(
          "p-2 rounded-md",
          status === 'Healthy' ? "bg-success/10 text-success" : 
          status === 'Warning' ? "bg-warning/10 text-warning" : 
          "bg-danger/10 text-danger"
        )}>
          <Icon size={20} />
        </div>
        <div>
          <h4 className="text-sm font-medium text-foreground">{title}</h4>
          <p className="text-xs text-muted-foreground">Updated {lastUpdated}</p>
        </div>
      </div>
      <div className={cn(
        "px-2 py-1 text-xs font-semibold rounded-full",
        status === 'Healthy' ? "bg-success/20 text-success" : 
        status === 'Warning' ? "bg-warning/20 text-warning" : 
        "bg-danger/20 text-danger"
      )}>
        {status}
      </div>
    </div>
  );
}
