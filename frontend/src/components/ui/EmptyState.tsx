import type { LucideIcon } from 'lucide-react';

interface EmptyStateProps {
  icon: LucideIcon;
  title: string;
  description: string;
}

export function EmptyState({ icon: Icon, title, description }: EmptyStateProps) {
  return (
    <div className="flex-1 flex flex-col items-center justify-center p-8 text-center bg-[#0a0a0f] h-full">
      <div className="w-16 h-16 rounded-2xl bg-[#12121a] border border-[#1f1f28] flex items-center justify-center mb-4 shadow-sm">
        <Icon size={32} className="text-muted-foreground/50" />
      </div>
      <h3 className="text-lg font-semibold text-foreground mb-2">{title}</h3>
      <p className="text-sm text-muted-foreground max-w-sm">
        {description}
      </p>
    </div>
  );
}
