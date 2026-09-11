import { useEffect } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { RealtimeManager, TOPICS } from './RealtimeManager';
import type { TelemetryPayload, TwinUpdatedPayload, DomainEventPayload, ActionExecutedPayload, PipelineTracePayload, RuleMatchedPayload } from './RealtimeEvents';

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

      // Twin-shaped caches (DigitalTwinResponse) use different field names
      // (latestSensorType/latestValue) than the raw delta property names
      // (sensorType/value), and identify twins by `deviceId`, not `id`.
      // Map explicitly so no stray "sensorType"/"value" fields are added.
      const TWIN_DELTA_FIELD_MAP: Record<string, string> = {
        sensorType: 'latestSensorType',
        value: 'latestValue',
      };
      const twinDeltaObj = Object.fromEntries(
        payload.deltas
          .filter(d => TWIN_DELTA_FIELD_MAP[d.property])
          .map(d => [TWIN_DELTA_FIELD_MAP[d.property], d.newValue])
      );

      // Update latest twins query
      queryClient.setQueryData<any[]>(['twins', 12], (old = []) => {
        return old.map(dev => dev.deviceId === payload.deviceId ? { ...dev, ...twinDeltaObj } : dev);
      });
      // Update the higher-capacity twins query (DigitalTwinTab)
      queryClient.setQueryData<any[]>(['twins', 100], (old = []) => {
        return old.map(dev => dev.deviceId === payload.deviceId ? { ...dev, ...twinDeltaObj } : dev);
      });
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

    // 5b. Sync Rule Matches -> Annotate matching rule in the rules list
    const unsubRules = RealtimeManager.subscribe<RuleMatchedPayload>(TOPICS.RULES, (msg) => {
      const payload = msg.payload;
      if (!payload || !payload.ruleId) return;

      queryClient.setQueryData<any[]>(['global-rules'], (old = []) => {
        return old.map(rule =>
          (rule.ruleId === payload.ruleId || rule.id === payload.ruleId)
            ? { ...rule, lastMatchedAt: msg.serverTimestamp, lastMatchReason: payload.reason }
            : rule
        );
      });
    });

    // 5. Sync Pipeline Traces -> Prepend
    const unsubPipeline = RealtimeManager.subscribe<PipelineTracePayload>(TOPICS.PIPELINE, (msg) => {
      const payload = msg.payload;
      if (!payload) return;
      
      queryClient.setQueryData<PipelineTracePayload[]>(['global-pipeline-traces'], (old = []) => {
        return [payload, ...old].slice(0, 10000); // UI buffers up to 10k traces
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
