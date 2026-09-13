/**
 * Formats the age of a real timestamp as a short, human-readable duration
 * ("4m 12s ago"). Purely a display transform of a real timestamp - never
 * invents a threshold or a value; callers decide what to show when there is
 * no timestamp at all (see DigitalTwin components' "Unavailable" states).
 */
export function formatAge(timestamp: string | number, now: number = Date.now()): string {
  const then = typeof timestamp === 'number' ? timestamp : new Date(timestamp).getTime();
  const diffMs = Math.max(0, now - then);
  const totalSeconds = Math.floor(diffMs / 1000);

  if (totalSeconds < 60) return `${totalSeconds}s ago`;

  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  if (minutes < 60) return `${minutes}m ${seconds}s ago`;

  const hours = Math.floor(minutes / 60);
  const remMinutes = minutes % 60;
  if (hours < 24) return `${hours}h ${remMinutes}m ago`;

  const days = Math.floor(hours / 24);
  return `${days}d ago`;
}
