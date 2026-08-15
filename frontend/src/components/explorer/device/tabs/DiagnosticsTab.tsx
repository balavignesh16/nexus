import { useDevice } from '../../../../hooks/queries';
import { Skeleton } from '../../../ui/Skeleton';
import { ShieldAlert, Server, Database, Wifi, Cpu, Activity, RefreshCw, CheckCircle2, AlertTriangle, XCircle } from 'lucide-react';
import { DebugOverlay } from '../DebugOverlay';

export function DiagnosticsTab({ deviceId }: { deviceId: string }) {
  const { data: device, isLoading } = useDevice(deviceId);

  if (isLoading || !device) return <Skeleton className="w-full h-full" />;

  // Mocking diagnostic checks based on device state
  const isOnline = device.status === 'ACTIVE';
  
  const checks = [
    {
      name: 'Device Connection',
      description: 'Last seen status and latency',
      status: isOnline ? 'PASS' : 'FAIL',
      icon: Wifi,
      details: isOnline ? 'Device responded 2 seconds ago. Latency 45ms.' : 'Device has been offline for 4 hours.',
    },
    {
      name: 'MQTT Broker',
      description: 'Pub/Sub topic authorization',
      status: 'PASS',
      icon: Server,
      details: 'Authorized on telemetry, twins, and commands topics.',
    },
    {
      name: 'Digital Twin Sync',
      description: 'State synchronization with cloud',
      status: isOnline ? 'PASS' : 'WARN',
      icon: Cpu,
      details: isOnline ? 'Twin fully synced. Last update 10 seconds ago.' : 'Twin is stale. Awaiting next connection.',
    },
    {
      name: 'Database Persistence',
      description: 'Telemetry and event storage',
      status: 'PASS',
      icon: Database,
      details: 'Connected to TimescaleDB. Inserts successful.',
    },
    {
      name: 'Rule Engine Evaluation',
      description: 'Real-time monitoring health',
      status: 'PASS',
      icon: Activity,
      details: '2 rules actively evaluating telemetry stream.',
    }
  ];

  const getStatusIcon = (status: string) => {
    if (status === 'PASS') return <CheckCircle2 size={24} className="text-success" />;
    if (status === 'WARN') return <AlertTriangle size={24} className="text-warning" />;
    return <XCircle size={24} className="text-destructive" />;
  };

  return (
    <div className="p-6 h-full flex flex-col space-y-6 animate-in slide-in-from-bottom-2 fade-in duration-300">
      
      {/* Header */}
      <div className="flex items-center justify-between bg-card/50 backdrop-blur border border-border/50 rounded-xl p-4 shadow-sm shrink-0 relative group">
        <DebugOverlay 
          title="System Diagnostics" 
          data={checks}
          repository="MockData"
        />
        <div className="flex items-center gap-3">
          <div className="p-2 bg-destructive/10 rounded-lg">
            <ShieldAlert size={20} className="text-destructive" />
          </div>
          <div>
            <h3 className="font-semibold text-foreground">System Diagnostics</h3>
            <p className="text-xs text-muted-foreground">End-to-end health verification</p>
          </div>
        </div>
        
        <button className="flex items-center gap-2 px-4 py-2 bg-primary/10 hover:bg-primary/20 text-primary text-xs font-medium rounded transition-colors">
          <RefreshCw size={14} /> Run Full Diagnostic
        </button>
      </div>

      {/* Diagnostic Checks */}
      <div className="flex-1 min-h-0 overflow-y-auto custom-scrollbar pb-6 space-y-4">
        {checks.map((check, idx) => {
          const Icon = check.icon;
          return (
            <div key={idx} className="bg-card border border-border/50 rounded-xl p-5 shadow-sm hover:shadow-md transition-shadow flex items-start gap-5">
              <div className="shrink-0 p-3 bg-muted/30 rounded-lg border border-border/30">
                <Icon size={20} className="text-muted-foreground" />
              </div>
              
              <div className="flex-1">
                <div className="flex items-center justify-between mb-1">
                  <h4 className="font-semibold text-foreground">{check.name}</h4>
                  <div className="flex items-center gap-2">
                    <span className={`text-[10px] font-bold uppercase tracking-wider ${
                      check.status === 'PASS' ? 'text-success' :
                      check.status === 'WARN' ? 'text-warning' :
                      'text-destructive'
                    }`}>
                      {check.status}
                    </span>
                    {getStatusIcon(check.status)}
                  </div>
                </div>
                <p className="text-xs text-muted-foreground mb-3">{check.description}</p>
                <div className="bg-muted/20 border border-border/30 rounded p-3 text-xs font-mono text-muted-foreground">
                  &gt; {check.details}
                </div>
              </div>
            </div>
          );
        })}
      </div>

    </div>
  );
}
