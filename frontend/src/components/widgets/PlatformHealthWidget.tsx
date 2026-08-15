import { Server, Database, Network, Cpu, ListFilter, Zap } from 'lucide-react';
import { HealthCard } from '../ui/HealthCard';

export function PlatformHealthWidget() {
  return (
    <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 xl:grid-cols-6 gap-4">
      <HealthCard title="Backend API" status="healthy" icon={Server} caption="All systems operational" />
      <HealthCard title="PostgreSQL" status="healthy" icon={Database} caption="Query latency < 5ms" />
      <HealthCard title="MQTT Broker" status="connected" icon={Network} caption="0 dropped messages" />
      <HealthCard title="Digital Twin" status="healthy" icon={Cpu} caption="Engine synced" />
      <HealthCard title="Rules Engine" status="healthy" icon={ListFilter} caption="Processing 500 ops/s" />
      <HealthCard title="Action Engine" status="healthy" icon={Zap} caption="No pending actions" />
    </div>
  );
}
