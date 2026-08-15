type StatusType = 'online' | 'offline' | 'warning' | 'healthy' | 'critical' | 'degraded' | 'connected';

interface StatusBadgeProps {
  status: StatusType;
}

export function StatusBadge({ status }: StatusBadgeProps) {
  const isPositive = status === 'online' || status === 'healthy' || status === 'connected';
  const isWarning = status === 'warning' || status === 'degraded';
  
  let colorClass = 'border-gray-500/20 bg-gray-500/10 text-gray-500';
  let dotClass = 'bg-gray-500';
  
  if (isPositive) {
    colorClass = 'border-[#22c55e]/20 bg-[#22c55e]/10 text-[#22c55e]';
    dotClass = 'bg-[#22c55e]';
  } else if (isWarning) {
    colorClass = 'border-amber-500/20 bg-amber-500/10 text-amber-500';
    dotClass = 'bg-amber-500';
  } else if (status === 'offline' || status === 'critical') {
    colorClass = 'border-red-500/20 bg-red-500/10 text-red-500';
    dotClass = 'bg-red-500';
  }

  const displayLabel = status.charAt(0).toUpperCase() + status.slice(1);

  return (
    <div className={`flex items-center gap-1.5 px-2.5 py-1 rounded-full border text-[11px] font-bold tracking-wider ${colorClass} w-max`}>
      <span className="relative flex h-2 w-2">
        {isPositive && <span className={`animate-ping absolute inline-flex h-full w-full rounded-full opacity-75 ${dotClass}`}></span>}
        <span className={`relative inline-flex rounded-full h-2 w-2 ${dotClass}`}></span>
      </span>
      {displayLabel}
    </div>
  );
}
