export interface HealthConfig {
  weights: {
    telemetryFreshness: number;
    twinQuality: number;
    connectionStatus: number;
    recentFailures: number;
    latency: number;
    ruleActivity: number;
    actionSuccess: number;
  };
  thresholds: {
    freshnessWarningMs: number;
    freshnessCriticalMs: number;
    latencyWarningMs: number;
    latencyCriticalMs: number;
  };
}

const DEFAULT_CONFIG: HealthConfig = {
  weights: {
    telemetryFreshness: 25,
    twinQuality: 15,
    connectionStatus: 10,
    recentFailures: 15,
    latency: 10,
    ruleActivity: 10,
    actionSuccess: 15
  },
  thresholds: {
    freshnessWarningMs: 10000,
    freshnessCriticalMs: 30000,
    latencyWarningMs: 200,
    latencyCriticalMs: 1000
  }
};

export interface HealthResult {
  score: number; // 0-100
  state: "HEALTHY" | "WARNING" | "CRITICAL" | "OFFLINE" | "UNKNOWN";
  reasons: string[];
}

export function calculateTwinHealth(
  lastSeenMs: number,
  twinQuality: string,
  isConnected: boolean,
  actionFailures: number,
  actionSuccesses: number,
  ruleMatches: number,
  latencyMs: number,
  config: HealthConfig = DEFAULT_CONFIG
): HealthResult {
  const reasons: string[] = [];
  let score = 100;
  let isCritical = false;

  // 1. Connection Status
  if (!isConnected) {
    return { score: 0, state: 'OFFLINE', reasons: ['Device is offline'] };
  } else {
    reasons.push('Connection active');
  }

  // 2. Telemetry Freshness
  if (lastSeenMs === 0) {
    return { score: 0, state: 'UNKNOWN', reasons: ['Waiting for first telemetry'] };
  } else if (lastSeenMs > config.thresholds.freshnessCriticalMs) {
    score -= config.weights.telemetryFreshness;
    reasons.push('Telemetry is stale (Critical)');
    isCritical = true;
  } else if (lastSeenMs > config.thresholds.freshnessWarningMs) {
    score -= (config.weights.telemetryFreshness / 2);
    reasons.push('Telemetry is delayed (Warning)');
  } else {
    reasons.push('Telemetry fresh');
  }

  // 3. Twin Quality
  if (twinQuality === 'BAD') {
    score -= config.weights.twinQuality;
    reasons.push('Twin quality is bad');
    isCritical = true;
  } else if (twinQuality === 'UNCERTAIN') {
    score -= (config.weights.twinQuality / 2);
    reasons.push('Twin quality is uncertain');
  } else {
    reasons.push('Twin quality is good');
  }

  // 4. Latency
  if (latencyMs > config.thresholds.latencyCriticalMs) {
    score -= config.weights.latency;
    reasons.push('High latency (Critical)');
  } else if (latencyMs > config.thresholds.latencyWarningMs) {
    score -= (config.weights.latency / 2);
    reasons.push('Moderate latency (Warning)');
  } else {
    reasons.push('Low latency');
  }

  // 5. Recent Failures & Action Success
  if (actionFailures > 0) {
    const penalty = Math.min(config.weights.recentFailures, actionFailures * (config.weights.recentFailures / 3));
    score -= penalty;
    reasons.push(`${actionFailures} recent action failure(s)`);
  } else if (actionSuccesses > 0) {
    reasons.push('No failed actions');
  } else {
    reasons.push('No recent actions');
  }

  // 6. Rule Activity
  if (ruleMatches > 10) {
    score -= config.weights.ruleActivity;
    reasons.push('High rule match activity');
  } else {
    reasons.push('Normal rule activity');
  }

  score = Math.max(0, Math.round(score));

  let state: HealthResult['state'] = 'HEALTHY';
  if (score < 70 || isCritical) state = 'CRITICAL';
  else if (score < 90) state = 'WARNING';

  // Sort reasons: negative reasons first for visibility
  const negativeKeywords = ['stale', 'delayed', 'bad', 'uncertain', 'High', 'Moderate', 'failure'];
  reasons.sort((a, b) => {
    const aNeg = negativeKeywords.some(k => a.includes(k));
    const bNeg = negativeKeywords.some(k => b.includes(k));
    if (aNeg && !bNeg) return -1;
    if (!aNeg && bNeg) return 1;
    return 0;
  });

  return {
    score,
    state,
    reasons: reasons.slice(0, 5) // Return top 5 reasons
  };
}
