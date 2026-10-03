import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import type { DeviceContract, DeviceTemplate, OnboardingSession } from '../../api/frameworkTypes';

const mock = vi.hoisted(() => ({
  definitions: vi.fn(), definitionCreate: vi.fn(), definitionUpdate: vi.fn(),
  templates: vi.fn(), templateCreate: vi.fn(), templateUpdate: vi.fn(), members: vi.fn(), memberAdd: vi.fn(),
  sessions: vi.fn(), session: vi.fn(),
  sites: vi.fn(), buildings: vi.fn(), spaces: vi.fn(), provision: vi.fn(), retry: vi.fn(), disable: vi.fn(),
  contract: vi.fn(), bindings: vi.fn(), twin: vi.fn(), devices: vi.fn(), createRule: vi.fn(),
}));
vi.mock('../../api/capabilityDefinitionApi', () => ({ capabilityDefinitionApi: {
  all: mock.definitions, create: mock.definitionCreate, update: mock.definitionUpdate,
} }));
vi.mock('../../api/deviceTemplateApi', () => ({ deviceTemplateApi: {
  all: mock.templates, create: mock.templateCreate, update: mock.templateUpdate,
  capabilities: mock.members, addCapability: mock.memberAdd,
} }));
vi.mock('../../api/onboardingApi', () => ({ onboardingApi: { all: mock.sessions, get: mock.session,
  provision: mock.provision, retry: mock.retry, disable: mock.disable } }));
vi.mock('../../api/deviceContractApi', () => ({ deviceContractApi: { get: mock.contract } }));
vi.mock('../../api/deviceBindingApi', () => ({ deviceBindingApi: { all: mock.bindings } }));
vi.mock('../../api/twinApi', () => ({ twinApi: { getDeviceTwin: mock.twin } }));
vi.mock('../../api/siteApi', () => ({ siteApi: { getSites: mock.sites } }));
vi.mock('../../api/buildingApi', () => ({ buildingApi: { getBuildingsBySiteId: mock.buildings } }));
vi.mock('../../api/spaceApi', () => ({ spaceApi: { getSpacesByBuildingId: mock.spaces } }));
vi.mock('../../api/deviceApi', () => ({ deviceApi: { getActiveDevices: mock.devices } }));
vi.mock('../../api/ruleApi', () => ({ ruleApi: { createRule: mock.createRule } }));

import DeviceOnboardingPage from './DeviceOnboardingPage';
import OnboardingSessionPage from './OnboardingSessionPage';
import GenericRulePage from './GenericRulePage';
import CapabilityDefinitionsPage from './CapabilityDefinitionsPage';
import DeviceTemplatesPage from './DeviceTemplatesPage';

const template = { id: 'template-1', key: 'sample', displayName: 'Sample', description: null,
  manufacturer: null, model: null, protocolType: 'MQTT', enabled: true, version: 1,
  compositionLocked: false, metadata: {} } as DeviceTemplate;
const members = [{ id: 'member-1', capabilityDefinitionId: 'definition-1', capabilityKey: 'temperature',
  required: true, defaultUnit: 'C', displayOrder: 1, freshnessThresholdSeconds: null, configuration: {} }];
const definitions = [{ id: 'definition-1', key: 'temperature', displayName: 'Temperature',
  description: null, direction: 'READ', valueType: 'NUMBER', unit: 'C', allowedUnits: ['C'],
  minimum: -20, maximum: 100, enumValues: [], supportedCommands: [], semanticCategory: null,
  objectConstraints: {}, metadata: {}, version: 1 }];
const session = { id: 'session-1', deviceId: 'device-1', templateId: template.id,
  state: 'WAITING_FOR_TELEMETRY', contractVersion: '1.0', statusDeadline: null,
  telemetryDeadline: null, firstStatusAt: null, firstTelemetryAt: null, failureCode: null,
  failureMessage: null, createdAt: '', updatedAt: '' } as OnboardingSession;
const contract = { contractVersion: '1.0', deviceId: 'device-1', templateId: template.id,
  templateVersion: 1, mode: 'SIMULATED', protocol: 'MQTT', topics: {
    telemetry: 'nexus/devices/device-1/telemetry', status: 'nexus/devices/device-1/status',
    commands: 'nexus/devices/device-1/commands', commandAck: 'nexus/devices/device-1/commands/ack',
  }, capabilities: [
    { bindingId: 'b1', definitionId: 'd1', key: 'temperature', displayName: 'Temperature',
      direction: 'READ', valueType: 'NUMBER', enabled: true, unit: 'C', allowedUnits: ['C'],
      minimum: -20, maximum: 100, enumValues: [], supportedCommands: [],
      externalTelemetryIdentifier: 'temperature', externalCommandIdentifier: null, effectiveConstraints: {} },
    { bindingId: 'b2', definitionId: 'd2', key: 'relay', displayName: 'Relay', direction: 'WRITE',
      valueType: 'BOOLEAN', enabled: true, unit: null, allowedUnits: [], minimum: null, maximum: null,
      enumValues: [], supportedCommands: ['SET'], externalTelemetryIdentifier: null,
      externalCommandIdentifier: 'RELAY', effectiveConstraints: { channels: [1] } },
  ], payloadExamples: {} } as DeviceContract;

function renderAt(path: string) {
  return render(<MemoryRouter initialEntries={[path]}><Routes>
    <Route path="/framework/capabilities" element={<CapabilityDefinitionsPage />} />
    <Route path="/framework/templates" element={<DeviceTemplatesPage />} />
    <Route path="/framework/templates/:templateId" element={<DeviceTemplatesPage />} />
    <Route path="/framework/onboarding" element={<DeviceOnboardingPage />} />
    <Route path="/framework/onboarding/:sessionId" element={<OnboardingSessionPage />} />
    <Route path="/framework/rules" element={<GenericRulePage />} />
  </Routes></MemoryRouter>);
}

beforeEach(() => {
  vi.clearAllMocks();
  mock.definitions.mockResolvedValue(definitions);
  mock.templates.mockResolvedValue([template]); mock.members.mockResolvedValue(members);
  mock.sessions.mockResolvedValue([]); mock.session.mockResolvedValue(session);
  mock.sites.mockResolvedValue([{ id: 'site-1', name: 'Site' }]);
  mock.buildings.mockResolvedValue([{ id: 'building-1', name: 'Building' }]);
  mock.spaces.mockResolvedValue([{ id: 'space-1', name: 'Space' }]);
  mock.contract.mockResolvedValue(contract); mock.bindings.mockResolvedValue([]);
  mock.twin.mockRejectedValue(new Error('No twin yet'));
  mock.devices.mockResolvedValue([{ id: 'device-1', name: 'Sensor', mode: 'SIMULATED' }]);
  mock.createRule.mockResolvedValue({ ruleId: 'rule-1' });
  mock.definitionCreate.mockResolvedValue(definitions[0]);
  mock.definitionUpdate.mockResolvedValue(definitions[0]);
  mock.templateCreate.mockResolvedValue(template);
  mock.templateUpdate.mockResolvedValue(template);
  mock.memberAdd.mockResolvedValue(members[0]);
});

describe('framework management flow', () => {
  it('filters capability definitions and keeps identity fields locked on edit', async () => {
    renderAt('/framework/capabilities');
    fireEvent.change(await screen.findByLabelText('Search capabilities'), { target: { value: 'temp' } });
    fireEvent.click(screen.getByRole('button', { name: /Temperaturetemperature/i }));
    expect(screen.getByLabelText('Key')).toBeDisabled();
    expect(screen.getByLabelText('Direction')).toBeDisabled();
    expect(screen.getByLabelText('Value type')).toBeDisabled();
    fireEvent.change(screen.getByLabelText('Maximum'), { target: { value: '80' } });
    fireEvent.click(screen.getByText('Save definition'));
    await waitFor(() => expect(mock.definitionUpdate).toHaveBeenCalledWith('definition-1',
      expect.objectContaining({ maximum: 80 })));
    expect(mock.definitionUpdate.mock.calls[0][1]).not.toHaveProperty('id');
    expect(mock.definitionUpdate.mock.calls[0][1]).not.toHaveProperty('version');
  });

  it('locks template composition when the backend reports a referenced template', async () => {
    mock.templates.mockResolvedValue([{ ...template, compositionLocked: true }]);
    renderAt('/framework/templates/template-1');
    expect(await screen.findByText('temperature')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: /Capabilities.*locked/ })).toBeInTheDocument();
    expect(screen.queryByLabelText('Add capability')).not.toBeInTheDocument();
    expect(screen.getByLabelText('Key')).toBeDisabled();
  });

  it('provisions with a template, location, and a cleared numeric override omitted', async () => {
    mock.provision.mockResolvedValue(session);
    renderAt('/framework/onboarding');
    fireEvent.change(await screen.findByLabelText('Enabled MQTT template'), { target: { value: template.id } });
    await waitFor(() => expect(mock.members).toHaveBeenCalledWith(template.id));
    fireEvent.click(screen.getByText('Next'));
    fireEvent.change(screen.getByLabelText('Site'), { target: { value: 'site-1' } });
    fireEvent.change(await screen.findByLabelText('Building'), { target: { value: 'building-1' } });
    fireEvent.change(await screen.findByLabelText('Space'), { target: { value: 'space-1' } });
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Sensor' } });
    fireEvent.change(screen.getByLabelText('Serial number'), { target: { value: 'serial-1' } });
    fireEvent.click(screen.getByText('Next'));
    fireEvent.change(screen.getByLabelText('Minimum'), { target: { value: '10' } });
    fireEvent.change(screen.getByLabelText('Minimum'), { target: { value: '' } });
    fireEvent.click(screen.getByText('Review'));
    fireEvent.click(screen.getByText('Confirm provisioning'));
    await waitFor(() => expect(mock.provision).toHaveBeenCalledWith(expect.objectContaining({
      templateId: template.id, spaceId: 'space-1', name: 'Sensor', serialNumber: 'serial-1',
      bindings: { temperature: { configuration: {} } },
    })));
  });

  it('shows backend session state, exact topics, and an absent twin honestly', async () => {
    renderAt('/framework/onboarding/session-1');
    expect(await screen.findByRole('heading', { name: 'WAITING_FOR_TELEMETRY' })).toBeInTheDocument();
    expect(screen.getByText(contract.topics.telemetry)).toBeInTheDocument();
    expect(screen.getByText('No reported Twin state yet.')).toBeInTheDocument();
    expect(screen.queryByText('Retry')).not.toBeInTheDocument();
  });

  it('renders typed boolean and object Twin values without capability-name switches', async () => {
    mock.twin.mockResolvedValue({ deviceId: 'device-1', reportedState: {
      motion: { value: null, typedValue: false, valueType: 'BOOLEAN', capabilityKey: 'motion',
        unit: null, resolutionSource: 'GENERIC_BINDING' },
      pose: { value: null, typedValue: { x: 2, y: 3 }, valueType: 'OBJECT', capabilityKey: 'pose',
        unit: null, resolutionSource: 'GENERIC_BINDING' },
    } });
    renderAt('/framework/onboarding/session-1');
    expect(await screen.findByText('Off / false')).toBeInTheDocument();
    expect(screen.getByText(/"x": 2/)).toBeInTheDocument();
  });

  it('offers retry only for a backend failure state', async () => {
    mock.session.mockResolvedValue({ ...session, state: 'TELEMETRY_REJECTED',
      failureCode: 'INVALID_TELEMETRY', failureMessage: 'Out of range' });
    mock.retry.mockResolvedValue({ ...session, state: 'WAITING_FOR_STATUS' });
    renderAt('/framework/onboarding/session-1');
    expect(await screen.findByText('INVALID_TELEMETRY')).toBeInTheDocument();
    fireEvent.click(screen.getByText('Retry'));
    await waitFor(() => expect(mock.retry).toHaveBeenCalledWith('session-1'));
  });

  it('keeps a provisioning conflict visible instead of fabricating a session', async () => {
    mock.provision.mockRejectedValue(new Error('Serial number already belongs to another device'));
    renderAt('/framework/onboarding');
    fireEvent.change(await screen.findByLabelText('Enabled MQTT template'), { target: { value: template.id } });
    await waitFor(() => expect(mock.members).toHaveBeenCalledWith(template.id));
    fireEvent.click(screen.getByText('Next'));
    fireEvent.change(screen.getByLabelText('Site'), { target: { value: 'site-1' } });
    fireEvent.change(await screen.findByLabelText('Building'), { target: { value: 'building-1' } });
    fireEvent.change(await screen.findByLabelText('Space'), { target: { value: 'space-1' } });
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Sensor' } });
    fireEvent.change(screen.getByLabelText('Serial number'), { target: { value: 'serial-1' } });
    fireEvent.click(screen.getByText('Next'));
    fireEvent.click(screen.getByText('Review'));
    fireEvent.click(screen.getByText('Confirm provisioning'));
    expect(await screen.findByRole('alert')).toHaveTextContent('Serial number already belongs');
    expect(screen.queryByRole('heading', { name: 'OPERATIONAL' })).not.toBeInTheDocument();
  });

  it('builds a capability-keyed Rule with typed operator and command from the contract', async () => {
    renderAt('/framework/rules?deviceId=device-1');
    await screen.findByText('Temperature · NUMBER');
    fireEvent.change(screen.getByLabelText('Rule name'), { target: { value: 'Hot relay' } });
    fireEvent.change(screen.getByLabelText('Comparison value'), { target: { value: '42' } });
    fireEvent.click(screen.getByText('Create generic Rule'));
    await waitFor(() => expect(mock.createRule).toHaveBeenCalledWith(expect.objectContaining({
      conditions: expect.arrayContaining([expect.objectContaining({ capabilityKey: 'temperature', expectedValue: '42' })]),
      actions: [expect.objectContaining({ parameters: expect.objectContaining({
        capabilityKey: 'relay', command: 'SET', value: true,
      }) })],
    })));
  });

  it('preserves a valid user-selected numeric operator across capability selection effects', async () => {
    mock.contract.mockResolvedValue({ ...contract, capabilities: [
      contract.capabilities[0],
      { ...contract.capabilities[0], bindingId: 'b3', definitionId: 'd3',
        key: 'humidity', displayName: 'Humidity' },
      contract.capabilities[1],
    ] });
    renderAt('/framework/rules?deviceId=device-1');
    await waitFor(() => expect(screen.getByLabelText('Operator')).toHaveTextContent('GT'));
    fireEvent.change(screen.getByLabelText('Operator'), { target: { value: 'GT' } });
    expect(screen.getByLabelText('Operator')).toHaveValue('GT');
    fireEvent.change(screen.getByLabelText('Readable capability'), { target: { value: 'humidity' } });
    await waitFor(() => expect(screen.getByLabelText('Operator')).toHaveValue('GT'));
  });

  it('does not offer numeric operators for string input or actuation of a REAL target', async () => {
    mock.devices.mockResolvedValue([{ id: 'device-1', name: 'Sensor', mode: 'SIMULATED' },
      { id: 'device-2', name: 'Real relay', mode: 'REAL' }]);
    mock.contract.mockResolvedValue({ ...contract, capabilities: [
      { ...contract.capabilities[0], valueType: 'STRING', key: 'label', displayName: 'Label' },
      contract.capabilities[1],
    ] });
    renderAt('/framework/rules?deviceId=device-1');
    await screen.findByText('Label · STRING');
    await waitFor(() => expect(screen.getByLabelText('Operator')).toHaveTextContent('EQ'));
    expect(screen.getByLabelText('Operator')).not.toHaveTextContent('GT');
    fireEvent.change(screen.getByLabelText('Action target device'), { target: { value: 'device-2' } });
    expect(screen.getByText('Backend currently rejects actuation of REAL devices.')).toBeInTheDocument();
    expect(screen.getByText('Create generic Rule')).toBeDisabled();
  });
});
