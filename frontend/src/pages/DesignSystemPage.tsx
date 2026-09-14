import { useState } from 'react';
import { Thermometer, Zap, Radio, AlertTriangle, CheckCircle2, Search, Layers } from 'lucide-react';
import { GlassPanel } from '../components/foundation/GlassPanel';
import { Card } from '../components/foundation/Card';
import { Button } from '../components/foundation/Button';
import { IconButton } from '../components/foundation/IconButton';
import { Badge } from '../components/foundation/Badge';
import { StatusIndicator } from '../components/foundation/StatusIndicator';
import { Pill } from '../components/foundation/Pill';
import { Toggle } from '../components/foundation/Toggle';
import { Tabs } from '../components/foundation/Tabs';
import { Input, Select } from '../components/foundation/Input';
import { Table, type TableColumn } from '../components/foundation/Table';
import { SectionHeader } from '../components/foundation/SectionHeader';
import { EmptyState } from '../components/foundation/EmptyState';
import { LoadingBlock } from '../components/foundation/LoadingState';
import { ErrorState } from '../components/foundation/ErrorState';
import { MetricCard } from '../components/foundation/MetricCard';
import { Gauge } from '../components/foundation/Gauge';
import { DataSourceBadge } from '../components/ui/DataSourceBadge';
import { Skeleton } from '../components/ui/Skeleton';
import { DeviceStatusBadge } from '../components/DeviceStatusBadge';
import { DeviceStatus } from '../api/deviceApi';
import { LiveTelemetryChart } from '../components/widgets/LiveTelemetryChart';
import { MiniSparkline } from '../components/widgets/MiniSparkline';

/**
 * NEXUS Foundation Showcase (Frontend Redesign - Step 1).
 *
 * Everything on this page is a COMPONENT STATE, not live NEXUS data - every
 * value here is a static illustration ("Sample reading"), never a real
 * telemetry/device/event value. This page demonstrates the new
 * reference-driven foundation primitives (src/components/foundation/) -
 * a light, cool lavender/blue-gray glassmorphic system - that later
 * phases will use to redesign the real, data-backed pages. DataSourceBadge's
 * classification LOGIC (which label/color means what) remains completely
 * unchanged and is the sole authority for LIVE/CALCULATED/SIMULATED/
 * UNAVAILABLE/AI labeling everywhere - only its visual chrome is
 * switchable via variant="foundation" (used here) vs. the default dark
 * chip every existing production page still renders unchanged.
 *
 * The legacy showcase content below the new sections is preserved as-is
 * (still functional, still real components) rather than deleted.
 */
export function DesignSystemPage() {
  const [toggleA, setToggleA] = useState(true);
  const [toggleB, setToggleB] = useState(false);
  const [activeTab, setActiveTab] = useState('overview');
  const [activePill, setActivePill] = useState('24h');

  const sampleRows = [
    { id: '1', name: 'Sample Row A', status: 'ONLINE', value: '34.7°C' },
    { id: '2', name: 'Sample Row B', status: 'UNKNOWN', value: '—' },
    { id: '3', name: 'Sample Row C', status: 'OFFLINE', value: '12.1°C' },
  ];
  const columns: TableColumn<typeof sampleRows[number]>[] = [
    { key: 'name', header: 'Name', render: (r) => r.name },
    { key: 'status', header: 'Status', render: (r) => (
      <StatusIndicator tone={r.status === 'ONLINE' ? 'online' : r.status === 'OFFLINE' ? 'offline' : 'unknown'} label={r.status} />
    ) },
    { key: 'value', header: 'Value', render: (r) => r.value, align: 'right' },
  ];

  return (
    <div className="min-h-screen text-foundation-fg overflow-y-auto" style={{ backgroundColor: 'var(--foundation-bg)' }}>
      <div className="p-8 max-w-6xl mx-auto space-y-14 pb-24">

        <div>
          <Badge tone="accent">Step 1 · Foundation</Badge>
          <h1 className="text-3xl font-bold mt-3 mb-2">Premium Glass Control Plane</h1>
          <p className="text-foundation-muted-fg max-w-2xl">
            The new visual foundation for NEXUS. Every value below is a labeled component state for
            demonstration only — nothing on this page is live operational data.
          </p>
        </div>

        {/* ---------- Glass surface ladder ---------- */}
        <section className="space-y-4">
          <SectionHeader icon={<Layers size={18} />} title="Surface Levels" subtitle="Blur, opacity, border, and shadow travel together as one level - never applied piecemeal." />
          <div className="grid grid-cols-1 md:grid-cols-5 gap-4 p-6 rounded-[var(--radius-panel-lg)]" style={{ backgroundImage: 'radial-gradient(circle at 30% 20%, rgba(139,127,201,0.08), transparent 60%), radial-gradient(circle at 80% 80%, rgba(35,32,58,0.03), transparent 60%)', backgroundColor: 'var(--foundation-bg)' }}>
            <GlassPanel level="base" className="h-28 flex items-center justify-center text-xs text-foundation-muted-fg border border-dashed border-foundation-border">1 · base</GlassPanel>
            <GlassPanel level="elevated" className="h-28 flex items-center justify-center text-xs text-foundation-muted-fg">2 · elevated</GlassPanel>
            <GlassPanel level="glass" className="h-28 flex items-center justify-center text-xs text-foundation-fg/80">3 · glass</GlassPanel>
            <GlassPanel level="glass-strong" className="h-28 flex items-center justify-center text-xs text-foundation-fg/80">4 · glass-strong</GlassPanel>
            <GlassPanel level="float" className="h-28 flex items-center justify-center text-xs text-foundation-fg/80">5 · float</GlassPanel>
          </div>
        </section>

        {/* ---------- Color / typography reference ---------- */}
        <section className="space-y-4">
          <SectionHeader title="Color & Typography" subtitle="Restrained lavender accent - not blue, not cyan, not indigo. A small pastel family (green/yellow/orange) for category use only. Semantic status colors stay separate from the brand accent." />
          <Card padding="lg">
            <div className="grid grid-cols-2 md:grid-cols-6 gap-4">
              {[
                { name: 'accent (new)', var: 'var(--accent)' },
                { name: 'accent-green', var: 'var(--accent-green)' },
                { name: 'accent-yellow', var: 'var(--accent-yellow)' },
                { name: 'accent-orange', var: 'var(--accent-orange)' },
                { name: 'success', var: 'var(--success)' },
                { name: 'warning', var: 'var(--warning)' },
                { name: 'danger', var: 'var(--danger)' },
                { name: 'info', var: 'var(--info)' },
                { name: 'primary (legacy)', var: 'var(--primary)' },
              ].map((c) => (
                <div key={c.name} className="flex flex-col gap-2">
                  <div className="h-12 rounded-[var(--radius-panel)]" style={{ backgroundColor: c.var }} />
                  <span className="text-[11px] text-foundation-muted-fg">{c.name}</span>
                </div>
              ))}
            </div>
            <p className="mt-3 text-[11px] text-foundation-muted-fg/70">
              &quot;primary (legacy)&quot; is the existing blue token, shown only for reference - the new foundation does not build its identity around it.
              accent-green/yellow/orange are for category/type distinction only, never a replacement for the semantic success/warning/danger colors above.
            </p>
            <div className="mt-6 space-y-1.5 border-t border-foundation-border pt-5">
              <div className="text-2xl font-bold">Section Title — text-2xl font-bold</div>
              <div className="text-lg font-semibold">Panel Heading — text-lg font-semibold</div>
              <div className="text-sm font-medium">Label / Control — text-sm font-medium</div>
              <div className="text-sm text-foundation-fg/90">Body copy — text-sm</div>
              <div className="text-xs text-foundation-muted-fg">Meta / caption — text-xs text-muted-foreground</div>
              <div className="text-[10px] font-semibold uppercase tracking-wider text-foundation-muted-fg">Eyebrow / tag — text-[10px] uppercase tracking-wider</div>
              <div className="text-[10px] text-foundation-tertiary-fg">Restrained tertiary — text-foundation-tertiary-fg</div>
            </div>
          </Card>
        </section>

        {/* ---------- Cards ---------- */}
        <section className="space-y-4">
          <SectionHeader icon={<Thermometer size={18} />} title="Cards" subtitle="Icon-chip header over a glass body." />
          <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
            <Card icon={<Thermometer size={16} />} title="Sample Reading" subtitle="Component state" action={<DataSourceBadge classification="CALCULATED" variant="foundation" />}>
              <p className="text-xs text-foundation-muted-fg">Card body content goes here. This card demonstrates composition only.</p>
            </Card>
            <Card level="elevated" icon={<Zap size={16} />} title="Elevated Variant" subtitle="Opaque, no blur">
              <p className="text-xs text-foundation-muted-fg">Same primitive, level=&quot;elevated&quot; - matches the existing production surface.</p>
            </Card>
            <Card level="glass-strong" icon={<Radio size={16} />} title="Strong Glass" subtitle="Modal/overlay weight">
              <p className="text-xs text-foundation-muted-fg">Heavier blur and lower transparency, for overlays and modals.</p>
            </Card>
          </div>
        </section>

        {/* ---------- Buttons / IconButtons ---------- */}
        <section className="space-y-4">
          <SectionHeader title="Buttons" />
          <Card padding="lg" className="flex flex-wrap items-center gap-3">
            <Button variant="primary">Primary</Button>
            <Button variant="secondary">Secondary</Button>
            <Button variant="outline">Outline</Button>
            <Button variant="ghost">Ghost</Button>
            <Button variant="destructive">Destructive</Button>
            <Button variant="primary" disabled>Disabled</Button>
            <div className="w-px h-6 bg-foundation-border mx-1" />
            <IconButton variant="ghost" aria-label="Search"><Search size={16} /></IconButton>
            <IconButton variant="glass" aria-label="Search glass"><Search size={16} /></IconButton>
            <IconButton variant="solid" aria-label="Search solid"><Search size={16} /></IconButton>
          </Card>
        </section>

        {/* ---------- Toggles / Pills / Tabs ---------- */}
        <section className="space-y-4">
          <SectionHeader title="Toggles, Pills & Tabs" />
          <Card padding="lg" className="space-y-5">
            <div className="flex items-center gap-6">
              <div className="flex items-center gap-2">
                <Toggle checked={toggleA} onChange={setToggleA} label="Sample toggle A" />
                <span className="text-xs text-foundation-muted-fg">{toggleA ? 'On' : 'Off'}</span>
              </div>
              <div className="flex items-center gap-2">
                <Toggle checked={toggleB} onChange={setToggleB} label="Sample toggle B" />
                <span className="text-xs text-foundation-muted-fg">{toggleB ? 'On' : 'Off'}</span>
              </div>
            </div>
            <div className="flex items-center gap-2">
              {['1h', '6h', '24h', '7d'].map((r) => (
                <Pill key={r} active={activePill === r} onClick={() => setActivePill(r)}>{r}</Pill>
              ))}
            </div>
            <Tabs
              value={activeTab}
              onChange={setActiveTab}
              items={[
                { value: 'overview', label: 'Overview' },
                { value: 'telemetry', label: 'Telemetry' },
                { value: 'rules', label: 'Rules' },
              ]}
            />
          </Card>
        </section>

        {/* ---------- Badges / Status ---------- */}
        <section className="space-y-4">
          <SectionHeader title="Badges & Status Indicators" />
          <Card padding="lg" className="flex flex-wrap items-center gap-3">
            <Badge tone="neutral">Neutral</Badge>
            <Badge tone="accent">Accent</Badge>
            <Badge tone="success">Success</Badge>
            <Badge tone="warning">Warning</Badge>
            <Badge tone="danger">Danger</Badge>
            <div className="w-px h-6 bg-foundation-border mx-1" />
            <StatusIndicator tone="online" label="ONLINE" pulse />
            <StatusIndicator tone="offline" label="OFFLINE" />
            <StatusIndicator tone="warning" label="DEGRADED" />
            <StatusIndicator tone="unknown" label="UNKNOWN" />
            <div className="w-px h-6 bg-foundation-border mx-1" />
            <DataSourceBadge classification="LIVE" variant="foundation" />
            <DataSourceBadge classification="CALCULATED" variant="foundation" />
            <DataSourceBadge classification="SIMULATED" variant="foundation" />
            <DataSourceBadge classification="UNAVAILABLE" variant="foundation" />
            <DataSourceBadge classification="AI" variant="foundation" />
          </Card>
        </section>

        {/* ---------- Inputs ---------- */}
        <section className="space-y-4">
          <SectionHeader title="Inputs" />
          <Card padding="lg" className="grid grid-cols-1 md:grid-cols-3 gap-4">
            <Input placeholder="Search devices…" />
            <Select defaultValue="a">
              <option value="a">Option A</option>
              <option value="b">Option B</option>
            </Select>
            <Input type="number" placeholder="Numeric input" />
          </Card>
        </section>

        {/* ---------- Table ---------- */}
        <section className="space-y-4">
          <SectionHeader title="Table" subtitle="Sample rows - component state only" />
          <Table columns={columns} rows={sampleRows} rowKey={(r) => r.id} />
        </section>

        {/* ---------- Metric cards + Gauge ---------- */}
        <section className="space-y-4">
          <SectionHeader title="Metric Presentation & Gauge" />
          <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
            <MetricCard label="Sample Metric" value={128} unit="events" icon={<Zap size={16} />} badge={<DataSourceBadge classification="CALCULATED" variant="foundation" />} trend={{ direction: 'up', text: '+12 vs prior window (illustrative)' }} />
            <MetricCard label="Sample Count" value={3} icon={<Radio size={16} />} />
            <Card padding="md" className="items-center flex flex-col justify-center">
              <Gauge value={22} min={10} max={40} unit="°" label="Sample dial (illustrative)" />
            </Card>
            <Card padding="md" className="items-center flex flex-col justify-center">
              <Gauge value={340} min={0} max={500} unit="ppm" label="Sample dial (illustrative)" color="var(--warning)" sweep={360} />
            </Card>
          </div>
        </section>

        {/* ---------- Loading / Empty / Error ---------- */}
        <section className="space-y-4">
          <SectionHeader title="Loading, Empty & Error States" />
          <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
            <Card padding="md" title="Loading">
              <div className="space-y-2">
                <LoadingBlock className="h-4 w-3/4" />
                <LoadingBlock className="h-4 w-1/2" />
                <LoadingBlock className="h-20 w-full" />
              </div>
            </Card>
            <Card padding="md" title="Empty">
              <EmptyState icon={<CheckCircle2 size={32} />} title="No records in this window" description="Real destinations show the actual honest message here." />
            </Card>
            <Card padding="md" title="Error">
              <ErrorState icon={<AlertTriangle size={28} />} message="Sample error message (illustrative)." action={<Button variant="outline" size="sm">Retry</Button>} />
            </Card>
          </div>
        </section>

        {/* ---------- Legacy showcase (preserved, unchanged) ----------
            These are the app's EXISTING dark-themed production components
            (DeviceStatusBadge, Skeleton, chart widgets), preserved as-is
            rather than deleted. They are deliberately wrapped in their own
            dark surface here (not restyled) so they stay legible and
            honestly presented as "the old dark palette" next to the new
            light foundation above, rather than floating unstyled on a
            white page. */}
        <section className="space-y-4 border-t border-foundation-border pt-10">
          <h2 className="text-xl font-semibold pb-2">Legacy Showcase (preserved)</h2>
          <div className="space-y-8 p-6 rounded-[var(--radius-panel-lg)]" style={{ backgroundColor: '#0a0a0f' }}>

            <div className="space-y-4">
              <h3 className="text-sm font-semibold text-muted-foreground">Status Badges</h3>
              <div className="flex flex-wrap gap-4">
                <DeviceStatusBadge status={DeviceStatus.ACTIVE} />
                <DeviceStatusBadge status={DeviceStatus.OFFLINE} />
                <DeviceStatusBadge status={DeviceStatus.MAINTENANCE} />
                <DeviceStatusBadge status={DeviceStatus.REGISTERED} />
              </div>
            </div>

            <div className="space-y-4">
              <h3 className="text-sm font-semibold text-muted-foreground">Skeletons &amp; Loaders</h3>
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
            </div>

            <div className="space-y-4">
              <h3 className="text-sm font-semibold text-muted-foreground">Data Visualizations</h3>
              <div className="grid grid-cols-2 gap-8">
                <div className="p-6 border border-border/50 rounded-xl bg-card">
                  <h4 className="mb-4 font-medium text-foreground">Mini Sparkline</h4>
                  <div className="h-12 border-b border-border/50 mb-2 pb-2 flex items-end">
                    <MiniSparkline data={[10, 15, 8, 20, 25, 18, 30, 22]} color="#10b981" height={40} />
                  </div>
                </div>
                <div className="p-6 border border-border/50 rounded-xl bg-card">
                  <h4 className="mb-4 font-medium text-foreground">Live Telemetry Chart (Mock)</h4>
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
            </div>

          </div>
        </section>

      </div>
    </div>
  );
}
