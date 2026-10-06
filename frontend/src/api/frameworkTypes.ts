export type ValueType = 'NUMBER' | 'BOOLEAN' | 'STRING' | 'ENUM' | 'OBJECT';
export type Direction = 'READ' | 'WRITE';
export type OnboardingState = 'WAITING_FOR_STATUS' | 'WAITING_FOR_TELEMETRY' | 'OPERATIONAL' |
  'STATUS_TIMEOUT' | 'TELEMETRY_REJECTED' | 'DISABLED';

export type CapabilityValue =
  | { valueType: 'NUMBER'; value: number; unit?: string | null }
  | { valueType: 'BOOLEAN'; value: boolean }
  | { valueType: 'STRING'; value: string }
  | { valueType: 'ENUM'; value: string }
  | { valueType: 'OBJECT'; value: Record<string, unknown> };

export interface CapabilityDefinition {
  id: string; key: string; displayName: string; description: string | null;
  direction: Direction; valueType: ValueType; unit: string | null;
  allowedUnits: string[]; minimum: number | null; maximum: number | null;
  enumValues: string[]; supportedCommands: string[]; semanticCategory: string | null;
  objectConstraints: Record<string, unknown>; metadata: Record<string, unknown>; version: number;
}

export type CapabilityDefinitionCreate = Omit<CapabilityDefinition, 'id' | 'version'>;
export type CapabilityDefinitionUpdate = Omit<CapabilityDefinitionCreate, 'key' | 'direction' | 'valueType'>;

export interface DeviceTemplate {
  id: string; key: string; displayName: string; description: string | null;
  manufacturer: string | null; model: string | null; protocolType: 'MQTT';
  enabled: boolean; version: number; compositionLocked: boolean; metadata: Record<string, unknown>;
}

export interface TemplateCapability {
  id: string; capabilityDefinitionId: string; capabilityKey: string; required: boolean;
  defaultUnit: string | null; displayOrder: number; freshnessThresholdSeconds: number | null;
  configuration: Record<string, unknown>;
}

export interface BindingOverride {
  enabled?: boolean; unit?: string | null; configuration?: Record<string, unknown>;
  externalIdentifiers?: Record<string, string>;
  freshnessThresholdSeconds?: number | null;
}

export interface DeviceBinding {
  id: string; deviceId: string; capabilityDefinitionId: string; capabilityKey: string;
  templateCapabilityId: string | null; enabled: boolean; unit: string | null;
  configuration: Record<string, unknown>; externalIdentifiers: Record<string, string>;
  freshnessThresholdSeconds: number | null;
}

export interface ProvisionDeviceRequest {
  requestKey: string; templateId: string; spaceId: string; name: string;
  serialNumber: string; mode: 'SIMULATED' | 'REAL';
  bindings?: Record<string, BindingOverride>;
}

export interface OnboardingSession {
  id: string; deviceId: string; templateId: string; state: OnboardingState;
  contractVersion: string; statusDeadline: string | null; telemetryDeadline: string | null;
  firstStatusAt: string | null; firstTelemetryAt: string | null;
  failureCode: string | null; failureMessage: string | null;
  createdAt: string; updatedAt: string;
}

export interface ContractCapability {
  bindingId: string; definitionId: string; key: string; displayName: string;
  direction: Direction; valueType: ValueType; enabled: boolean; unit: string | null;
  allowedUnits: string[]; minimum: number | null; maximum: number | null;
  enumValues: string[]; supportedCommands: string[];
  externalTelemetryIdentifier: string | null; externalCommandIdentifier: string | null;
  effectiveConstraints: Record<string, unknown>;
}

export interface DeviceContract {
  contractVersion: string; deviceId: string; templateId: string | null;
  templateVersion: number | null; mode: 'SIMULATED' | 'REAL'; protocol: 'MQTT';
  topics: { telemetry: string; status: string; commands: string; commandAck: string };
  capabilities: ContractCapability[]; payloadExamples: Record<string, unknown>;
}
