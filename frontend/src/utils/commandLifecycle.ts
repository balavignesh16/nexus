/**
 * Command lifecycle presentation (contract R37, ledger P4-102, P4-104).
 *
 * Four facts are kept SEPARATE and are never derived from one another: QUEUED (NEXUS accepted the command for delivery), PUBLISHED (the
 * MQTT broker confirmed the publication), ACKNOWLEDGED (the device said it accepted or rejected it) and VERIFIED (a qualified readback
 * matched the desired state). None of the labels below, and no text on the Actions page, states that anything physically happened.
 * Values the frontend does not recognise render a neutral label and never throw.
 */

export type LabelTone = 'neutral' | 'pending' | 'success' | 'warning' | 'danger';

export interface Label {
  label: string;
  tone: LabelTone;
}

/** Sentence shown wherever the four facts are listed: the labels are about software evidence only. */
export const NO_PHYSICAL_CLAIM = 'These labels describe what NEXUS recorded. They do not establish that the device physically acted.';

const MAX_CODE_LENGTH = 40;
const SAFE_CODE = /^[A-Z0-9_]+$/;

function safeCode(code: unknown): string | null {
  if (typeof code !== 'string') return null;
  const trimmed = code.trim();
  return trimmed.length > 0 && trimmed.length <= MAX_CODE_LENGTH && SAFE_CODE.test(trimmed) ? trimmed : null;
}

const REASON_TEXT: Record<string, string> = {
  RETRY_EXHAUSTED: 'every attempt failed before the broker confirmed it',
  EXPIRED: 'the delivery window ended',
  PAYLOAD_INTEGRITY: 'the stored command failed its integrity check',
  PERMANENT_FAILURE: 'delivery failed permanently',
  UNCERTAIN_EXPIRED: 'a publication may have reached the broker; outcome uncertain',
};

/** The delivery label of the additive `deliveryState` / `deliveryReason` fields. Absent means the command has no delivery record. */
export function deliveryLabel(state: unknown, reason?: unknown): Label | null {
  if (state === undefined || state === null || state === '') return null;
  switch (state) {
    case 'QUEUED': return { label: 'Queued for delivery', tone: 'pending' };
    case 'IN_FLIGHT': return { label: 'Being sent', tone: 'pending' };
    case 'RETRY_WAIT': return { label: 'Waiting to retry', tone: 'pending' };
    case 'PUBLISHED': return { label: 'Published to MQTT', tone: 'success' };
    case 'DELIVERY_UNKNOWN': return { label: 'Delivery unknown', tone: 'warning' };
    case 'DEAD_LETTER': {
      const code = safeCode(reason);
      if (code === 'UNCERTAIN_EXPIRED') return { label: 'Dead-lettered: outcome uncertain', tone: 'warning' };
      const text = code ? REASON_TEXT[code] : undefined;
      return { label: text ? `Dead-lettered: ${text}` : 'Dead-lettered', tone: 'danger' };
    }
    default:
      // A state added by a newer backend: say so neutrally; never echo it, never throw.
      return { label: 'Delivery state not recognised', tone: 'neutral' };
  }
}

/** The acknowledgement label. A missing ack is "no acknowledgement recorded", never a failure. */
export function ackLabel(ackStatus: unknown, timedOut = false): Label {
  if (ackStatus === 'ACCEPTED') return { label: 'Device accepted', tone: 'success' };
  if (ackStatus === 'REJECTED') return { label: 'Device rejected', tone: 'danger' };
  if (timedOut) return { label: 'No acknowledgement before the deadline', tone: 'warning' };
  if (ackStatus === undefined || ackStatus === null || ackStatus === 'NONE') return { label: 'No acknowledgement recorded', tone: 'neutral' };
  return { label: 'Acknowledgement status not recognised', tone: 'neutral' };
}

/** The verification label of a qualified readback. Absent means no verification record applies. */
export function verificationLabel(status: unknown): Label | null {
  if (status === undefined || status === null || status === '') return null;
  switch (status) {
    case 'CONVERGED': return { label: 'Verified by readback', tone: 'success' };
    case 'PENDING': return { label: 'Verification pending', tone: 'pending' };
    case 'DIVERGED': return { label: 'Readback did not match', tone: 'danger' };
    case 'TIMED_OUT': return { label: 'No matching readback in time', tone: 'warning' };
    case 'UNVERIFIED': return { label: 'Cannot be verified', tone: 'neutral' };
    default: return { label: 'Verification status not recognised', tone: 'neutral' };
  }
}

/**
 * A timestamp as shown to a person. Accepts an ISO string, epoch milliseconds or epoch seconds; anything else, including a value that
 * parses to an invalid date, is shown as the neutral placeholder instead of "Invalid Date".
 */
export function formatInstant(value: unknown, placeholder = '—'): string {
  const ms = toEpochMillis(value);
  return ms === null ? placeholder : new Date(ms).toLocaleString();
}

export function toEpochMillis(value: unknown): number | null {
  if (value === undefined || value === null || value === '') return null;
  if (typeof value === 'number') {
    if (!Number.isFinite(value) || value <= 0) return null;
    return value < 1e11 ? value * 1000 : value;       // epoch seconds vs milliseconds
  }
  if (typeof value === 'string') {
    const parsed = Date.parse(value);
    return Number.isNaN(parsed) ? null : parsed;
  }
  return null;
}

/** Milliseconds as "123 ms" / "4.2 s"; null for a missing or negative figure. */
export function formatDurationMs(value: unknown): string | null {
  if (typeof value !== 'number' || !Number.isFinite(value) || value < 0) return null;
  return value < 1000 ? `${Math.round(value)} ms` : `${(value / 1000).toFixed(1)} s`;
}

/** The message shown for a refused command (HTTP 429). It says that NOTHING was queued or sent; a refusal is never described as a failure to deliver. */
export function queueFullMessage(scope: unknown): string {
  if (scope === 'DEVICE') return 'This device already has too many commands waiting. The command was not queued and nothing was sent. Try again shortly.';
  if (scope === 'GLOBAL') return 'NEXUS is holding too many commands right now. The command was not queued and nothing was sent. Try again shortly.';
  return 'Too many commands are waiting. The command was not queued and nothing was sent. Try again shortly.';
}
