import type { DeviceState } from '../repositories/types';
import { DeviceStatus } from '../api/deviceApi';

export class MetricEngine {
  
  /**
   * Calculates the overall health score (0-100) of a device.
   * Derives from status, latency, and telemetry staleness.
   */
  static calculateHealthScore(device: DeviceState, currentLatencyMs?: number): number {
    if (device.status === DeviceStatus.OFFLINE) return 0;
    if (device.status === DeviceStatus.MAINTENANCE) return 50;
    
    let score = 100;

    // Deduct for high latency
    if (currentLatencyMs) {
      if (currentLatencyMs > 500) score -= 15;
      else if (currentLatencyMs > 200) score -= 5;
    }

    // Deduct for missing metrics
    if (!device.metrics || device.metrics.length === 0) {
      score -= 20;
    }

    return Math.max(0, Math.min(100, score));
  }

  /**
   * Calculates Twin Quality (0-100) based on how fully populated the twin schema is.
   * If twin data is mostly populated, quality is high.
   */
  static calculateTwinQuality(device: DeviceState): number {
    // In a real scenario, this checks the Twin payload against the JSON schema.
    // For now, we derive it from the basic properties we know.
    let quality = 50; // Base quality
    
    if (device.deviceType) quality += 10;
    if (device.mqttTopic) quality += 10;
    if (device.metrics && device.metrics.length > 0) quality += 30;

    return Math.max(0, Math.min(100, quality));
  }

  /**
   * Infers average latency from network request metrics or Simulator constraints.
   */
  static inferLatency(latencyLog: number[]): number {
    if (!latencyLog || latencyLog.length === 0) return 0;
    const sum = latencyLog.reduce((a, b) => a + b, 0);
    return Math.round(sum / latencyLog.length);
  }

  /**
   * Calculates trend labels relative to previous values for metrics.
   */
  static generateTrend(current: number, previous: number, unit: string): { trend: string, trendLabel: string } {
    if (previous === undefined || previous === null) return { trend: '→ 0 ' + unit, trendLabel: 'No history' };
    
    const diff = current - previous;
    const absDiff = Math.abs(diff).toFixed(1);
    
    let trend = '';
    if (diff > 0) trend = `↑ ${absDiff} ${unit}`;
    else if (diff < 0) trend = `↓ ${absDiff} ${unit}`;
    else trend = `→ 0.0 ${unit}`;

    return {
      trend,
      trendLabel: 'vs last reading'
    };
  }
}
