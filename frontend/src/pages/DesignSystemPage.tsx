import { Skeleton } from '../components/ui/Skeleton';
import { LiveTelemetryChart } from '../components/widgets/LiveTelemetryChart';
import { MiniSparkline } from '../components/widgets/MiniSparkline';
import { DeviceStatusBadge } from '../components/DeviceStatusBadge';
import { DeviceStatus } from '../api/deviceApi';
export function DesignSystemPage() {
  return (
    <div className="min-h-screen bg-background text-foreground overflow-y-auto">
      <div className="p-8 max-w-6xl mx-auto space-y-12 pb-24">
        
        <div>
          <h1 className="text-3xl font-bold mb-2">Design System Showcase</h1>
          <p className="text-muted-foreground">Premium components used across the NEXUS Platform.</p>
        </div>

        <section className="space-y-4">
          <h2 className="text-xl font-semibold border-b border-border/50 pb-2">Status Badges</h2>
          <div className="flex flex-wrap gap-4">
            <DeviceStatusBadge status={DeviceStatus.ACTIVE} />
            <DeviceStatusBadge status={DeviceStatus.OFFLINE} />
            <DeviceStatusBadge status={DeviceStatus.MAINTENANCE} />
            <DeviceStatusBadge status={DeviceStatus.REGISTERED} />
          </div>
        </section>

        <section className="space-y-4">
          <h2 className="text-xl font-semibold border-b border-border/50 pb-2">Skeletons & Loaders</h2>
          <div className="grid grid-cols-3 gap-4">
            <div className="p-4 border border-border/50 rounded-xl space-y-4">
              <Skeleton className="h-10 w-10 rounded-full" />
              <div className="space-y-2">
                <Skeleton className="h-4 w-[200px]" />
                <Skeleton className="h-4 w-[160px]" />
              </div>
            </div>
            <div className="p-4 border border-border/50 rounded-xl">
              <Skeleton className="h-[120px] w-full rounded-lg" />
            </div>
          </div>
        </section>

        <section className="space-y-4">
          <h2 className="text-xl font-semibold border-b border-border/50 pb-2">Data Visualizations</h2>
          <div className="grid grid-cols-2 gap-8">
            <div className="p-6 border border-border/50 rounded-xl bg-card">
              <h3 className="mb-4 font-medium">Mini Sparkline</h3>
              <div className="h-12 border-b border-border/50 mb-2 pb-2 flex items-end">
                <MiniSparkline data={[10, 15, 8, 20, 25, 18, 30, 22]} color="#10b981" height={40} />
              </div>
            </div>
            <div className="p-6 border border-border/50 rounded-xl bg-card">
              <h3 className="mb-4 font-medium">Live Telemetry Chart (Mock)</h3>
              <div className="h-48">
                <LiveTelemetryChart 
                  title=""
                  data={Array.from({ length: 20 }).map((_, i) => ({ timestamp: Date.now() - (20 - i) * 1000, value: Math.random() * 100 }))}
                  dataKey="value"
                  timeKey="timestamp"
                />
              </div>
            </div>
          </div>
        </section>

      </div>
    </div>
  );
}
