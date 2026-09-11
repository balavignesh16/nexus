import { useDevice } from '../../../../hooks/queries';
import { useRepositories } from '../../../../repositories/RepositoryProvider';
import { usePolling } from '../../../../hooks/usePolling';
import { useConnectionStatus } from '../../../../services/ConnectionManager';
import { Skeleton } from '../../../ui/Skeleton';
import { ShieldAlert, Server, Database, Wifi, Cpu, Activity, RefreshCw, CheckCircle2, AlertTriangle, XCircle, HelpCircle } from 'lucide-react';
import { DebugOverlay } from '../DebugOverlay';

type CheckStatus = 'PASS' | 'WARN' | 'FAIL' | 'UNAVAILABLE';

export function DiagnosticsTab({ deviceId }: { deviceId: string }) {
  const { data: device, isLoading } = useDevice(deviceId);
  const { rules } = useRepositories();
  const connection = useConnectionStatus();
  // Shares the same cache key RulesPage uses - real registered-rule count,
  // not a fabricated "actively evaluating" claim.
  const { data: globalRules = [], isError: rulesUnavailable } = usePolling(
    ['global-rules'],
    () => rules.getRules(),
    { interval: 5000 }
  );

  if (isLoading || !device) return <Skeleton className="w-full h-full" />;

  const isOnline = device.status === 'ACTIVE';

  // Every check below is backed by a real, existing signal, or is honestly
  // marked UNAVAILABLE when no real signal exists yet. None of these are
  // generated, inferred from unrelated state, or hardcoded.
  const checks: { name: string; description: string; status: CheckStatus; icon: typeof Wifi; details: string }[] = [
    {
      name: 'Device Connection',
      description: 'Reported device status',
      status: isOnline ? 'PASS' : 'FAIL',
      icon: Wifi,
      details: `Device status is ${device.status}.`,
    },
    {
      name: 'MQTT Broker',
      description: "Backend's connection to the broker",
      status: connection.mqtt === 'Online' ? 'PASS' : connection.mqtt === 'Offline' ? 'FAIL' : 'UNAVAILABLE',
      icon: Server,
      details:
        connection.mqtt === 'Online' ? 'Backend is connected to the MQTT broker.' :
        connection.mqtt === 'Offline' ? 'Backend is not connected to the MQTT broker.' :
        'Broker connection status unavailable.',
    },
    {
      name: 'Digital Twin Sync',
      description: 'State synchronization freshness',
      status: 'UNAVAILABLE',
      icon: Cpu,
      details: 'Twin sync freshness is not currently exposed by the backend.',
    },
    {
      name: 'Database Persistence',
      description: "Backend's datasource health",
      status: connection.database === 'Online' ? 'PASS' : connection.database === 'Offline' ? 'FAIL' : 'UNAVAILABLE',
      icon: Database,
      details:
        connection.database === 'Online' ? 'Datasource validity check passed.' :
        connection.database === 'Offline' ? 'Datasource is unreachable.' :
        'Database status unavailable.',
    },
    {
      name: 'Rule Engine',
      description: 'Registered automation rules',
      status: rulesUnavailable ? 'UNAVAILABLE' : 'PASS',
      icon: Activity,
      details: rulesUnavailable
        ? 'Rule count unavailable.'
        : `${globalRules.length} rule(s) registered.`,
    }
  ];

  const getStatusIcon = (status: CheckStatus) => {
    if (status === 'PASS') return <CheckCircle2 size={24} className="text-success" />;
    if (status === 'WARN') return <AlertTriangle size={24} className="text-warning" />;
    if (status === 'UNAVAILABLE') return <HelpCircle size={24} className="text-muted-foreground" />;
    return <XCircle size={24} className="text-destructive" />;
  };

  return (
    <div className="p-6 h-full flex flex-col space-y-6 animate-in slide-in-from-bottom-2 fade-in duration-300">
      
      {/* Header */}
      <div className="flex items-center justify-between bg-card/50 backdrop-blur border border-border/50 rounded-xl p-4 shadow-sm shrink-0 relative group">
        <DebugOverlay
          title="System Diagnostics"
          data={checks}
          repository="ConnectionManager + RuleRepository"
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
                      check.status === 'UNAVAILABLE' ? 'text-muted-foreground' :
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
