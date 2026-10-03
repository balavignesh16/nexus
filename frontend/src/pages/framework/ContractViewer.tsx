import type { DeviceContract } from '../../api/frameworkTypes';
import { Button } from '../../components/foundation/Button';

export function ContractViewer({ contract }: { contract: DeviceContract }) {
  const serialized = JSON.stringify(contract, null, 2);
  const download = () => {
    const url = URL.createObjectURL(new Blob([serialized], { type: 'application/json' }));
    const link = document.createElement('a');
    link.href = url; link.download = `nexus-contract-${contract.deviceId}.json`; link.click();
    URL.revokeObjectURL(url);
  };
  return <section className="space-y-3">
    <div className="flex flex-wrap justify-between gap-2">
      <div><h2 className="font-semibold">Backend-generated MQTT contract</h2>
        <p className="text-xs text-foundation-muted-fg">Device {contract.deviceId} · v{contract.contractVersion} · {contract.mode}</p></div>
      <div className="flex gap-2"><Button variant="outline" size="sm" onClick={() => void navigator.clipboard.writeText(serialized)}>Copy JSON</Button>
        <Button variant="outline" size="sm" onClick={download}>Download JSON</Button></div>
    </div>
    <div className="grid sm:grid-cols-2 gap-2 text-xs">
      {Object.entries(contract.topics).map(([name, topic]) => <div key={name} className="rounded-lg border border-foundation-border p-2">
        <span className="text-foundation-muted-fg">{name}</span><code className="block break-all">{topic}</code></div>)}
    </div>
    <div className="space-y-2">{contract.capabilities.map(cap => <div key={cap.bindingId} className="rounded-lg border border-foundation-border p-3 text-sm">
      <span className="font-medium">{cap.displayName}</span> <code className="text-xs">{cap.key}</code>
      <p className="text-xs text-foundation-muted-fg">{cap.direction} · {cap.valueType} · {cap.unit ?? 'unitless'} · {cap.enabled ? 'enabled' : 'disabled'}</p>
      <p className="text-xs text-foundation-muted-fg">{cap.externalTelemetryIdentifier && `Telemetry: ${cap.externalTelemetryIdentifier} `}
        {cap.externalCommandIdentifier && `Command: ${cap.externalCommandIdentifier}`}</p>
      <pre className="mt-1 text-xs overflow-auto">{JSON.stringify({ minimum: cap.minimum, maximum: cap.maximum,
        enumValues: cap.enumValues, supportedCommands: cap.supportedCommands,
        effectiveConstraints: cap.effectiveConstraints }, null, 2)}</pre>
    </div>)}</div>
    <details><summary className="cursor-pointer text-sm">Raw contract and payload examples</summary>
      <pre className="mt-2 rounded-lg border border-foundation-border p-3 overflow-auto text-xs">{serialized}</pre></details>
    <p className="text-xs text-foundation-muted-fg">Contract generation is not credential management. No broker credentials are included.</p>
  </section>;
}
