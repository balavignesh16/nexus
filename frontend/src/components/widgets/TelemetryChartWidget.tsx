import { TelemetryChart } from '../charts/TelemetryChart';
import { WidgetWrapper } from './WidgetWrapper';

export function TelemetryChartWidget() {
  // Assuming useLatestTelemetry or useTelemetryRate here. 
  // For now, the chart manages its own simulated sliding window.
  
  return (
    <WidgetWrapper
      title="Live Telemetry Ingestion"
      headerAction={
        <div className="flex items-center gap-2 text-xs text-muted-foreground">
          <span className="w-2 h-2 rounded-full bg-primary animate-pulse"></span>
          Live Stream
        </div>
      }
      isLoading={false}
      isError={false}
      isEmpty={false}
      className="h-[300px]"
    >
      <TelemetryChart />
    </WidgetWrapper>
  );
}
