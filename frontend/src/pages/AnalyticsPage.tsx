import { useState } from 'react';
import { TrendingUp, Calendar } from 'lucide-react';
import { usePolling } from '../hooks/usePolling';
import { analyticsApi, type AnalyticsRangeParam } from '../api/analyticsApi';
import { TimeRangeSelector } from '../components/analytics/TimeRangeSelector';
import { AnalyticsSection } from '../components/analytics/AnalyticsSection';
import { DistributionBar } from '../components/analytics/DistributionBar';
import { TimeSeriesChart } from '../components/analytics/TimeSeriesChart';

const CONNECTIVITY_COLORS: Record<string, string> = {
  ONLINE: 'var(--data-live)',
  OFFLINE: 'var(--danger)',
  UNKNOWN: 'var(--data-unavailable)',
};

const ACTION_STATUS_COLORS: Record<string, string> = {
  SUCCESS: 'var(--success)',
  FAILURE: 'var(--danger)',
  IGNORED: 'var(--warning)',
  UNSUPPORTED: 'var(--data-unavailable)',
};

// NO_ACK_YET is deliberately NOT a failure color - it means "not yet
// resolved" (or a device that never acknowledges), never "went wrong".
const ACK_COLORS: Record<string, string> = {
  ACCEPTED: 'var(--success)',
  REJECTED: 'var(--danger)',
  NO_ACK_YET: 'var(--data-unavailable)',
};

const DEVICE_TYPE_PALETTE = ['var(--primary)', 'var(--info)', 'var(--warning)', 'var(--success)', 'var(--data-simulated)', 'var(--data-ai)'];

function paletteColor(index: number, palette: string[]): string {
  return palette[index % palette.length];
}

export default function AnalyticsPage() {
  const [range, setRange] = useState<AnalyticsRangeParam>('24h');

  const summaryQuery = usePolling(
    ['analytics-summary', range],
    () => analyticsApi.getSummary(range),
    { interval: 0 }
  );
  const telemetrySeries = usePolling(
    ['analytics-timeseries', 'telemetry', range],
    () => analyticsApi.getTimeSeries('telemetry', range),
    { interval: 0 }
  );
  const eventsSeries = usePolling(
    ['analytics-timeseries', 'events', range],
    () => analyticsApi.getTimeSeries('events', range),
    { interval: 0 }
  );
  const ruleMatchesSeries = usePolling(
    ['analytics-timeseries', 'rule-matches', range],
    () => analyticsApi.getTimeSeries('rule-matches', range),
    { interval: 0 }
  );
  const actionsSeries = usePolling(
    ['analytics-timeseries', 'actions', range],
    () => analyticsApi.getTimeSeries('actions', range),
    { interval: 0 }
  );
  const pipelineSeries = usePolling(
    ['analytics-timeseries', 'pipeline', range],
    () => analyticsApi.getTimeSeries('pipeline', range),
    { interval: 0 }
  );

  const summary = summaryQuery.data;

  return (
    <div className="flex-1 flex flex-col h-full bg-canvas overflow-hidden text-foreground animate-in fade-in duration-300">

      {/* TOOLBAR */}
      <div className="shrink-0 h-16 border-b border-surface-border flex items-center justify-between px-6 bg-sunken z-10 relative">
        <div className="flex items-center gap-3">
          <div className="p-2 bg-primary/10 rounded-lg">
            <TrendingUp size={20} className="text-primary" />
          </div>
          <div>
            <h1 className="text-lg font-bold">Operational Analytics</h1>
            <p className="text-xs text-muted-foreground">
              {summary ? `${new Date(summary.from).toLocaleString()} — ${new Date(summary.to).toLocaleString()}` : 'What is happening across the physical system'}
            </p>
          </div>
        </div>

        <div className="flex items-center gap-3">
          <Calendar size={14} className="text-muted-foreground" />
          <TimeRangeSelector value={range} onChange={setRange} />
        </div>
      </div>

      <div className="flex-1 overflow-y-auto custom-scrollbar p-6 grid grid-cols-1 lg:grid-cols-2 gap-4 content-start">

        {/* SYSTEM ACTIVITY */}
        <AnalyticsSection
          title="System Activity"
          subtitle="Telemetry volume over time"
          isLoading={telemetrySeries.isLoading}
          error={telemetrySeries.error}
          isEmpty={!telemetrySeries.data || telemetrySeries.data.points.length === 0}
          emptyMessage="No telemetry recorded in selected period."
          drillDownTo="/telemetry"
        >
          {telemetrySeries.data && <TimeSeriesChart data={telemetrySeries.data} color="#3b82f6" />}
        </AnalyticsSection>

        {/* DEVICE ACTIVITY */}
        <AnalyticsSection
          title="Device Activity"
          subtitle={summary ? `${summary.totalDeviceCount} device(s) registered` : undefined}
          isLoading={summaryQuery.isLoading}
          error={summaryQuery.error}
          isEmpty={!summary || summary.totalDeviceCount === 0}
          emptyMessage="No devices registered."
          drillDownTo="/explorer"
        >
          {summary && (
            <div className="flex flex-col gap-4">
              <div>
                <span className="text-[10px] uppercase tracking-wider text-muted-foreground/70 mb-1 block">By type</span>
                <DistributionBar
                  data={summary.deviceCountByType}
                  colorFor={(key) => paletteColor(Object.keys(summary.deviceCountByType).indexOf(key), DEVICE_TYPE_PALETTE)}
                />
              </div>
              <div>
                <span className="text-[10px] uppercase tracking-wider text-muted-foreground/70 mb-1 block">Connectivity (current)</span>
                <DistributionBar
                  data={summary.connectivityDistribution}
                  colorFor={(key) => CONNECTIVITY_COLORS[key] ?? 'var(--data-unavailable)'}
                />
              </div>
            </div>
          )}
        </AnalyticsSection>

        {/* EVENT ACTIVITY */}
        <AnalyticsSection
          title="Event Activity"
          subtitle={summary ? `${summary.eventCount} event(s) in selected period` : undefined}
          isLoading={eventsSeries.isLoading}
          error={eventsSeries.error}
          isEmpty={!eventsSeries.data || eventsSeries.data.points.length === 0}
          emptyMessage="No events recorded in selected period."
          drillDownTo="/events"
        >
          {eventsSeries.data && <TimeSeriesChart data={eventsSeries.data} color="var(--info)" />}
        </AnalyticsSection>

        {/* RULE ACTIVITY */}
        <AnalyticsSection
          title="Rule Activity"
          subtitle={summary ? `${summary.ruleMatchCount} rule match(es) in selected period` : undefined}
          isLoading={ruleMatchesSeries.isLoading}
          error={ruleMatchesSeries.error}
          isEmpty={!ruleMatchesSeries.data || ruleMatchesSeries.data.points.length === 0}
          emptyMessage="No rule matches recorded in selected period."
          drillDownTo="/rules"
        >
          {ruleMatchesSeries.data && <TimeSeriesChart data={ruleMatchesSeries.data} color="var(--data-simulated)" />}
        </AnalyticsSection>

        {/* ACTION ACTIVITY */}
        <AnalyticsSection
          title="Action Activity"
          subtitle="Status distribution in selected period"
          isLoading={summaryQuery.isLoading}
          error={summaryQuery.error}
          isEmpty={!summary || Object.keys(summary.actionStatusDistribution).length === 0}
          emptyMessage="No actions executed in selected period."
          drillDownTo="/actions"
        >
          {summary && (
            <div className="flex flex-col gap-4">
              <DistributionBar
                data={summary.actionStatusDistribution}
                colorFor={(key) => ACTION_STATUS_COLORS[key] ?? 'var(--data-unavailable)'}
              />
              {actionsSeries.data && actionsSeries.data.points.length > 0 && (
                <TimeSeriesChart data={actionsSeries.data} color="var(--success)" />
              )}
            </div>
          )}
        </AnalyticsSection>

        {/* COMMAND RELIABILITY */}
        <AnalyticsSection
          title="Command Reliability"
          subtitle="Physical command acknowledgement outcome (SEND_COMMAND only)"
          isLoading={summaryQuery.isLoading}
          error={summaryQuery.error}
          isEmpty={!summary || Object.keys(summary.commandAckDistribution).length === 0}
          emptyMessage="No commands dispatched in selected period."
          drillDownTo="/actions"
        >
          {summary && (
            <DistributionBar
              data={summary.commandAckDistribution}
              colorFor={(key) => ACK_COLORS[key] ?? 'var(--data-unavailable)'}
              labelFor={(key) => (key === 'NO_ACK_YET' ? 'No ack yet' : key)}
            />
          )}
        </AnalyticsSection>

        {/* PIPELINE ACTIVITY */}
        <AnalyticsSection
          title="Pipeline Activity"
          subtitle={
            pipelineSeries.data
              ? `Retained window: ${new Date(pipelineSeries.data.from).toLocaleDateString()} — ${new Date(pipelineSeries.data.to).toLocaleDateString()} (pipeline traces retain only 7 days, regardless of the selected range)`
              : undefined
          }
          isLoading={pipelineSeries.isLoading}
          error={pipelineSeries.error}
          isEmpty={!pipelineSeries.data || pipelineSeries.data.points.length === 0}
          emptyMessage="No pipeline traces recorded in the retained 7-day window."
          drillDownTo="/pipeline"
        >
          {pipelineSeries.data && <TimeSeriesChart data={pipelineSeries.data} color="var(--data-ai)" />}
        </AnalyticsSection>

      </div>
    </div>
  );
}
