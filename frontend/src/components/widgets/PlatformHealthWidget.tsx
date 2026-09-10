import { Server, Database, Network, Cpu, ListFilter, Zap } from 'lucide-react';
import { HealthCard } from '../ui/HealthCard';
import { useConnectionStatus, type ServiceStatus } from '../../services/ConnectionManager';

type HealthCardStatus = 'online' | 'offline' | 'warning' | 'healthy' | 'critical' | 'degraded' | 'connected';

function toHealthCardStatus(status: ServiceStatus): HealthCardStatus {
  switch (status) {
    case 'Online': return 'healthy';
    case 'Degraded': return 'degraded';
    case 'Offline':
    default: return 'offline';
  }
}

export function PlatformHealthWidget() {
  const connection = useConnectionStatus();

  return (
    <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 xl:grid-cols-6 gap-4">
      <HealthCard
        title="Backend API"
        status={toHealthCardStatus(connection.backend)}
        icon={Server}
        caption={connection.backend === 'Online' ? `Latency ${connection.latencyMs}ms` : 'Unreachable'}
      />
      <HealthCard
        title="Database"
        status={toHealthCardStatus(connection.database)}
        icon={Database}
        caption={connection.database === 'Online' ? 'Reachable via backend health check' : 'Unreachable'}
      />
      <HealthCard
        title="Realtime Link"
        status={connection.realtimeStatus === 'connected' ? 'connected' : connection.realtimeStatus === 'demo' ? 'warning' : 'offline'}
        icon={Network}
        caption={`${connection.realtimeStatus} • ${connection.messagesPerSec.toFixed(1)} msg/s`}
      />
      <HealthCard
        title="Digital Twin"
        status={toHealthCardStatus(connection.twinEngine)}
        icon={Cpu}
        caption={connection.twinEngine === 'Online' ? 'Reachable via backend health check' : 'Unreachable'}
      />
      <HealthCard
        title="Rules Engine"
        status={toHealthCardStatus(connection.ruleEngine)}
        icon={ListFilter}
        caption={connection.ruleEngine === 'Online' ? 'Reachable via backend health check' : 'Unreachable'}
      />
      <HealthCard
        title="Action Engine"
        status={toHealthCardStatus(connection.actionEngine)}
        icon={Zap}
        caption={connection.actionEngine === 'Online' ? 'Reachable via backend health check' : 'Unreachable'}
      />
    </div>
  );
}
