import { useDevice } from '../../../../hooks/queries';
import { useRepositories } from '../../../../repositories/RepositoryProvider';
import { usePolling } from '../../../../hooks/usePolling';
import { useConnectionStatus } from '../../../../services/ConnectionManager';
import { ShieldAlert, Server, Database, Wifi, Cpu, Activity, CheckCircle2, AlertTriangle, XCircle, HelpCircle } from 'lucide-react';
import { DebugOverlay } from '../DebugOverlay';
import { Card } from '../../../foundation/Card';
import { LoadingBlock } from '../../../foundation/LoadingState';

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

  if (isLoading || !device) return <LoadingBlock className="h-64 mt-6" />;

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

  const statusColor = (status: CheckStatus) =>
    status === 'PASS' ? 'var(--success)' :
    status === 'WARN' ? 'var(--warning)' :
    status === 'UNAVAILABLE' ? 'var(--foundation-muted-fg)' :
    'var(--danger)';

  const getStatusIcon = (status: CheckStatus) => {
    const color = statusColor(status);
    if (status === 'PASS') return <CheckCircle2 size={22} style={{ color }} />;
    if (status === 'WARN') return <AlertTriangle size={22} style={{ color }} />;
    if (status === 'UNAVAILABLE') return <HelpCircle size={22} style={{ color }} />;
    return <XCircle size={22} style={{ color }} />;
  };

  return (
    <div className="py-6 flex flex-col gap-4">

      {/* Header */}
      <Card level="glass" padding="lg" className="shrink-0 relative">
        <DebugOverlay
          title="System Diagnostics"
          data={checks}
          repository="ConnectionManager + RuleRepository"
        />
        <div className="flex items-center gap-3">
          <div className="p-2 rounded-[var(--radius-panel)]" style={{ backgroundColor: 'color-mix(in srgb, var(--danger) 14%, transparent)', color: 'var(--danger)' }}>
            <ShieldAlert size={20} />
          </div>
          <div>
            <h3 className="font-semibold text-foundation-fg">System Diagnostics</h3>
            <p className="text-xs text-foundation-muted-fg">End-to-end health verification</p>
          </div>
        </div>
      </Card>

      {/* Diagnostic Checks */}
      <div className="flex flex-col gap-3">
        {checks.map((check, idx) => {
          const Icon = check.icon;
          return (
            <Card key={idx} level="glass" padding="lg">
              <div className="flex items-start gap-5">
                <div className="shrink-0 p-3 rounded-[var(--radius-panel)]" style={{ backgroundColor: 'var(--foundation-bg)', border: '1px solid var(--foundation-border)' }}>
                  <Icon size={20} className="text-foundation-muted-fg" />
                </div>

                <div className="flex-1">
                  <div className="flex items-center justify-between mb-1">
                    <h4 className="font-semibold text-foundation-fg">{check.name}</h4>
                    <div className="flex items-center gap-2">
                      <span className="text-[10px] font-bold uppercase tracking-wider" style={{ color: statusColor(check.status) }}>
                        {check.status}
                      </span>
                      {getStatusIcon(check.status)}
                    </div>
                  </div>
                  <p className="text-xs text-foundation-muted-fg mb-2.5">{check.description}</p>
                  <div className="rounded p-2.5 text-xs font-mono text-foundation-muted-fg" style={{ backgroundColor: 'var(--foundation-bg)', border: '1px solid var(--foundation-border)' }}>
                    &gt; {check.details}
                  </div>
                </div>
              </div>
            </Card>
          );
        })}
      </div>

    </div>
  );
}
