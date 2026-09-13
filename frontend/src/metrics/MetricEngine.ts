export class MetricEngine {

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
