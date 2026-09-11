import { Server, Database, Network, Radio } from 'lucide-react';
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
    <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
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
        caption={connection.database === 'Online' ? 'Datasource validity check passed' : 'Unreachable'}
      />
      <HealthCard
        title="MQTT Broker"
        status={toHealthCardStatus(connection.mqtt)}
        icon={Radio}
        caption={connection.mqtt === 'Online' ? 'Backend connected to broker' : 'Backend not connected to broker'}
      />
      <HealthCard
        title="Realtime Link"
        status={connection.realtimeStatus === 'connected' ? 'connected' : connection.realtimeStatus === 'demo' ? 'warning' : 'offline'}
        icon={Network}
        caption={`${connection.realtimeStatus} • ${connection.messagesPerSec.toFixed(1)} msg/s`}
      />
    </div>
  );
}
