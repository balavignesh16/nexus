import { Skeleton } from './Skeleton';

export function SkeletonCard() {
  return (
    <div className="bg-[#12121a] border border-[#1f1f28] rounded-xl p-4 flex flex-col shadow-sm">
      <div className="flex items-center justify-between mb-4">
        <div className="flex items-center gap-3">
          <Skeleton className="w-8 h-8 rounded-lg bg-[#1f1f28]" />
          <div className="space-y-2">
            <Skeleton className="h-3 w-20 bg-[#1f1f28]" />
            <Skeleton className="h-4 w-12 bg-[#1f1f28]" />
          </div>
        </div>
      </div>
      <Skeleton className="h-2 w-full mt-2 bg-[#1f1f28]" />
    </div>
  );
}

export function SkeletonChart() {
  return (
    <div className="bg-[#12121a] border border-[#1f1f28] rounded-xl p-4 flex flex-col shadow-sm h-full min-h-[300px]">
      <div className="flex items-center justify-between mb-4">
        <Skeleton className="h-5 w-32 bg-[#1f1f28]" />
        <Skeleton className="h-4 w-16 bg-[#1f1f28]" />
      </div>
      <div className="flex-1 w-full flex items-end gap-2 pb-2">
        {Array.from({ length: 12 }).map((_, i) => (
          <Skeleton key={i} className="w-full bg-[#1f1f28] rounded-t-sm" style={{ height: `${Math.max(10, Math.random() * 100)}%` }} />
        ))}
      </div>
      <div className="flex justify-between mt-2">
        <Skeleton className="h-3 w-10 bg-[#1f1f28]" />
        <Skeleton className="h-3 w-10 bg-[#1f1f28]" />
      </div>
    </div>
  );
}

export function SkeletonTimeline() {
  return (
    <div className="bg-[#12121a] border border-[#1f1f28] rounded-xl p-4 flex flex-col shadow-sm h-full overflow-hidden">
      <Skeleton className="h-5 w-40 mb-6 bg-[#1f1f28]" />
      <div className="space-y-6 relative before:absolute before:inset-0 before:ml-2.5 before:-translate-x-px md:before:mx-auto md:before:translate-x-0 before:h-full before:w-0.5 before:bg-gradient-to-b before:from-transparent before:via-[#1f1f28] before:to-transparent">
        {Array.from({ length: 4 }).map((_, i) => (
          <div key={i} className="relative flex items-center justify-between md:justify-normal md:odd:flex-row-reverse group is-active">
            <div className="flex items-center justify-center w-5 h-5 rounded-full border border-[#1f1f28] bg-[#0a0a0f] text-slate-500 shrink-0 md:order-1 md:group-odd:-translate-x-1/2 md:group-even:translate-x-1/2 shadow">
              <Skeleton className="w-2 h-2 rounded-full bg-[#1f1f28]" />
            </div>
            <div className="w-[calc(100%-2rem)] md:w-[calc(50%-1.5rem)] p-3 rounded-lg border border-[#1f1f28] bg-[#0a0a0f] shadow-sm">
              <div className="flex items-center justify-between space-x-2 mb-2">
                <Skeleton className="h-4 w-24 bg-[#1f1f28]" />
                <Skeleton className="h-3 w-12 bg-[#1f1f28]" />
              </div>
              <Skeleton className="h-3 w-full bg-[#1f1f28]" />
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
