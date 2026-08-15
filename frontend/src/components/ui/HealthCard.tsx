import { StatusBadge } from './StatusBadge';
import type { LucideIcon } from 'lucide-react';

interface HealthCardProps {
  icon: LucideIcon;
  title: string;
  status: 'online' | 'offline' | 'warning' | 'healthy' | 'critical' | 'degraded' | 'connected';
  caption: string;
}

export function HealthCard({ icon: Icon, title, status, caption }: HealthCardProps) {
  return (
    <div className="bg-[#12121a] border border-[#1f1f28] rounded-xl p-5 flex flex-col gap-4 shadow-sm h-full">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 rounded-lg bg-[#0a0a0f] border border-[#1f1f28] flex items-center justify-center">
            <Icon size={20} className="text-blue-400" />
          </div>
          <span className="text-sm font-semibold text-foreground">{title}</span>
        </div>
        <StatusBadge status={status} />
      </div>
      <span className="text-xs text-muted-foreground">{caption}</span>
    </div>
  );
}
