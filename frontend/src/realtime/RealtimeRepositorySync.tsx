import { useEffect } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { RealtimeManager, TOPICS } from './RealtimeManager';
import type { TelemetryPayload, TwinUpdatedPayload, DomainEventPayload, ActionExecutedPayload, PipelineTracePayload, RuleMatchedPayload } from './RealtimeEvents';
import { normalizeRealtimePipelineTrace } from './normalizePipelineTrace';
import type { PipelineTracePayload as CanonicalPipelineTracePayload } from '../repositories/types';

export function RealtimeRepositorySync() {
  const queryClient = useQueryClient();

  useEffect(() => {
    // 1. Sync Telemetry -> Append
    const unsubTelemetry = RealtimeManager.subscribe<TelemetryPayload>(TOPICS.TELEMETRY, (msg) => {
      const payload = msg.payload;
      if (!payload) return;
      
      // Update historical cache (limit 100 typically)
      queryClient.setQueryData<any[]>(['telemetry', 100], (old = []) => {
        return [...old, { timestamp: payload.timestamp, payload: { [payload.sensorType]: payload.value }, deviceId: payload.deviceId }].slice(-100);
      });

      // Update specific device telemetry history (for TelemetryPage)
      queryClient.setQueryData<any[]>(['telemetry-page', payload.deviceId, payload.sensorType], (old = []) => {
        return [...old, { timestamp: payload.timestamp, payload: { [payload.sensorType]: payload.value }, deviceId: payload.deviceId }];
      });
    });

    // 2. Sync Twins -> Merge Properties
    const unsubTwins = RealtimeManager.subscribe<TwinUpdatedPayload>(TOPICS.TWINS, (msg) => {
      const payload = msg.payload;
      if (!payload || !payload.deltas) return;
      
      const deltaObj = Object.fromEntries(payload.deltas.map(d => [d.property, d.newValue]));

      // Update Active Devices query
      queryClient.setQueryData<any[]>(['activeDevices'], (old = []) => {
        return old.map(dev => dev.id === payload.deviceId ? { ...dev, ...deltaObj } : dev);
      });
      // Update Devices list query
      queryClient.setQueryData<any[]>(['all-devices'], (old = []) => {
        return old.map(dev => dev.id === payload.deviceId ? { ...dev, ...deltaObj } : dev);
      });
      // Update individual device query
      queryClient.setQueryData<any>(['device', payload.deviceId], (old: any) => {
        if (!old) return old;
        return { ...old, ...deltaObj };
      });

      // Twin-shaped caches (DigitalTwinResponse: latestSensorType,
      // latestValue, latestUnit, lastSeen, quality, ...) cannot be safely
      // hand-merged from this delta: the backend's TwinUpdated payload
      // only ever reports "sensorType" and "value" changes - it never
      // includes "unit", "lastSeen", or "latestTimestamp", even though
      // those genuinely change too (a device cycling between capabilities
      // has a different unit per capability). Patching only
      // sensorType/value onto the cached row - the previous approach here
      // - produces a torn/inconsistent record (e.g. sensorType:
      // "TEMPERATURE" with the previous capability's leftover value and
      // unit, like "1013.43 CELSIUS"). A full refetch is the only way to
      // get a row where every field agrees with every other field.
      queryClient.invalidateQueries({ queryKey: ['twins', 12] });
      queryClient.invalidateQueries({ queryKey: ['twins', 100] });
    });

    // 3. Sync Events -> Prepend
    const unsubEvents = RealtimeManager.subscribe<DomainEventPayload>(TOPICS.EVENTS, (msg) => {
      const payload = msg.payload;
      if (!payload) return;
      
      const newEvent = {
        id: payload.eventId || msg.eventId,
        type: payload.eventType,
        deviceId: payload.deviceId || msg.deviceId,
        severity: payload.severity,
        timestamp: payload.timestamp,
        message: `${payload.eventType} from ${payload.source}`,
        correlationId: msg.correlationId
      };

      // Update global events query
      queryClient.setQueryData<any[]>(['global-events-history'], (old = []) => {
        return [newEvent, ...old].slice(0, 100);
      });
      
      // Update local events query
      queryClient.setQueryData<any[]>(['events', 20], (old = []) => {
        return [newEvent, ...old].slice(0, 20);
      });
    });

    // 4. Sync Actions -> Prepend
    const unsubActions = RealtimeManager.subscribe<ActionExecutedPayload>(TOPICS.ACTIONS, (msg) => {
      const payload = msg.payload;
      if (!payload) return;
      
      const newAction = {
        id: payload.executionId,
        type: payload.status === 'FAILURE' ? 'ACTION_FAILED' : 'ACTION_COMPLETED',
        actionType: payload.actionType,
        deviceId: msg.deviceId,
        timestamp: payload.executedAt,
        message: payload.message,
        correlationId: msg.correlationId
      };

      // Update global actions query
      queryClient.setQueryData<any[]>(['global-actions-history'], (old = []) => {
        return [newAction, ...old].slice(0, 100);
      });
      
      // Update local actions query
      queryClient.setQueryData<any[]>(['actions', 20], (old = []) => {
        return [newAction, ...old].slice(0, 20);
      });
    });

    // 5b. Sync Rule Matches -> Annotate matching rule in every cached
    // rules list, regardless of its device-filter param. RulesPage
    // queries ['global-rules', deviceFilter] while DiagnosticsTab queries
    // the bare ['global-rules'] - both are real, independently-cached
    // views of the same real rule set, so `exact: false` annotates
    // whichever of them is currently in the cache rather than only the
    // one exact key, without duplicating or inventing any rule data.
    const unsubRules = RealtimeManager.subscribe<RuleMatchedPayload>(TOPICS.RULES, (msg) => {
      const payload = msg.payload;
      if (!payload || !payload.ruleId) return;

      queryClient.setQueriesData<any[]>({ queryKey: ['global-rules'], exact: false }, (old) => {
        if (!old) return old;
        return old.map(rule =>
          (rule.ruleId === payload.ruleId || rule.id === payload.ruleId)
            ? { ...rule, lastMatchedAt: msg.serverTimestamp, lastMatchReason: payload.reason }
            : rule
        );
      });
    });

    // 5. Sync Pipeline Traces -> Prepend
    // The realtime PipelineTrace payload and the REST-seeded canonical
    // shape are different DTOs for the same real concept - normalize
    // before writing into the shared cache so every consumer of
    // ['global-pipeline-traces'] only ever sees one coherent shape.
    const unsubPipeline = RealtimeManager.subscribe<PipelineTracePayload>(TOPICS.PIPELINE, (msg) => {
      const payload = msg.payload;
      if (!payload) return;

      const trace = normalizeRealtimePipelineTrace(payload);
      queryClient.setQueryData<CanonicalPipelineTracePayload[]>(['global-pipeline-traces'], (old = []) => {
        return [trace, ...old].slice(0, 10000); // UI buffers up to 10k traces
      });
    });

    return () => {
      unsubTelemetry();
      unsubTwins();
      unsubEvents();
      unsubActions();
      unsubRules();
      unsubPipeline();
    };
  }, [queryClient]);

  return null;
}
