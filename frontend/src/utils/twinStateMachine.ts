export type TwinState = 'UNKNOWN' | 'CONNECTING' | 'ONLINE' | 'WARNING' | 'CRITICAL' | 'OFFLINE' | 'RECOVERING';

/**
 * State machine for Twin lifecycles. 
 * Resolves the final state based on health inputs and latency.
 */
export function getTwinState(
  healthScore: number, 
  lastSeenMs: number, 
  isConnected: boolean, 
  isRecovering: boolean = false
): TwinState {
  if (!isConnected) {
    return 'OFFLINE';
  }

  if (lastSeenMs > 30000) { // 30 seconds
    return 'OFFLINE';
  }

  if (isRecovering) {
    return 'RECOVERING';
  }

  if (lastSeenMs === 0) {
    return 'UNKNOWN';
  }

  if (healthScore >= 90) {
    return 'ONLINE';
  } else if (healthScore >= 70) {
    return 'WARNING';
  } else {
    return 'CRITICAL';
  }
}
