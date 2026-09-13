import { Personalities } from './personalities';
import type { DevicePersonalityType } from './personalities';
import { DeviceStatus } from '../api/deviceApi';
import type { DeviceState } from '../repositories/types';
import { v4 as uuidv4 } from 'uuid';

export type ScenarioType = 'Normal Operations' | 'High Temperature' | 'Gas Leak' | 'Power Failure' | 'Unauthorized Access' | 'HVAC Failure' | 'Water Leakage' | 'Sensor Offline';

interface EngineState {
  currentScenario: ScenarioType;
  tick: number;
  devices: Map<string, DeviceState>;
  events: any[];
}

class ScenarioEngineClass {
  private state: EngineState = {
    currentScenario: 'Normal Operations',
    tick: 0,
    devices: new Map(),
    events: []
  };

  private listeners: Set<() => void> = new Set();
  private intervalId: any = null;

  constructor() {
    this.initializeMockDevices();
  }

  private initializeMockDevices() {
    const defaultDevices: { id: string, name: string, type: DevicePersonalityType, space: string }[] = [
      { id: 'dev-temp-001', name: 'Temperature Sensor 1', type: 'Temperature Sensor', space: 'Floor 1' },
      { id: 'dev-hum-001', name: 'Humidity Sensor 1', type: 'Generic', space: 'Floor 1' },
      { id: 'dev-mot-001', name: 'Motion Sensor 1', type: 'Motion Sensor', space: 'Floor 1' },
      { id: 'dev-gas-001', name: 'Gas Sensor 1', type: 'Gas Sensor', space: 'Floor 2' },
      { id: 'dev-pwr-001', name: 'Energy Meter 1', type: 'Energy Meter', space: 'Floor 2' },
      { id: 'dev-hvac-001', name: 'HVAC Controller 1', type: 'HVAC Controller', space: 'Floor 2' },
    ];

    defaultDevices.forEach(d => {
      this.state.devices.set(d.id, {
        id: d.id,
        name: d.name,
        status: DeviceStatus.ACTIVE,
        deviceType: d.type,
        mqttTopic: `sensors/${d.space.toLowerCase().replace(' ', '-')}/${d.id}`,
        lastSeen: 'Just now',
        signalStrength: -50,
        battery: 100,
        temperature: Personalities[d.type]?.baseTemperature || 24,
        humidity: Personalities[d.type]?.baseHumidity || 50,
        metrics: []
      });
    });
  }

  start() {
    if (this.intervalId) return;
    this.intervalId = setInterval(() => {
      this.tick();
    }, 2000);
  }

  stop() {
    if (this.intervalId) {
      clearInterval(this.intervalId);
      this.intervalId = null;
    }
  }

  setScenario(scenario: ScenarioType) {
    this.state.currentScenario = scenario;
    this.state.tick = 0; // reset tick for scenario
    this.emitEvent('Scenario Changed', `Scenario changed to ${scenario}`);
    this.notifyListeners();
  }

  getScenario() {
    return this.state.currentScenario;
  }

  private emitEvent(type: string, message: string, deviceId?: string) {
    const correlationId = uuidv4();
    const event = {
      id: uuidv4(),
      correlationId,
      timestamp: Date.now(),
      type,
      message,
      deviceId
    };
    this.state.events.unshift(event);
    if (this.state.events.length > 100) this.state.events.pop();
  }

  private tick() {
    this.state.tick++;
    const scenario = this.state.currentScenario;
    const tick = this.state.tick;

    this.state.devices.forEach((device, id) => {
      const personality = Personalities[device.deviceType as DevicePersonalityType] || Personalities['Generic'];
      const metrics = personality.generateTelemetry(tick, scenario);
      
      // Update basic state based on scenario
      if (scenario === 'Sensor Offline' && id === 'dev-temp-001') {
        if (device.status === DeviceStatus.ACTIVE) {
          device.status = DeviceStatus.OFFLINE;
          this.emitEvent('Connection Lost', 'Sensor dropped off the network', id);
        }
      } else if (device.status === DeviceStatus.OFFLINE && scenario !== 'Sensor Offline') {
        device.status = DeviceStatus.ACTIVE;
        this.emitEvent('Sensor Online', 'Sensor reconnected', id);
      }

      // Update specific scenario side effects
      if (scenario === 'Gas Leak' && id === 'dev-gas-001' && tick === 5) {
        this.emitEvent('Rule Matched', 'High Gas Concentration Detected', id);
        this.emitEvent('Alert Raised', 'Evacuation protocol suggested', id);
      }

      if (scenario === 'High Temperature' && id === 'dev-temp-001' && tick === 8) {
        this.emitEvent('Rule Matched', 'Temperature exceeded threshold', id);
      }

      // Fluctuations
      device.signalStrength = -50 - Math.floor(Math.random() * 15);
      if (tick % 10 === 0 && (device.battery ?? 0) > 0) {
        device.battery = (device.battery ?? 0) - 1;
      }

      // Update metrics array for UI mapping
      device.metrics = Object.keys(metrics).map(k => {
        let unit = '';
        let trend = '';
        let trendLabel = 'vs 1m ago';
        
        if (k.includes('temp')) { unit = '°C'; trend = '↑ 0.6°C'; }
        else if (k.includes('hum')) { unit = '%'; trend = '↓ 1.2%'; }
        else if (k.includes('press')) { unit = 'hPa'; trend = '→ 0.0 hPa'; }
        else if (k.includes('batt')) { unit = '%'; trend = '↑ 1%'; trendLabel = 'vs 10m ago'; }
        else if (k.includes('ppm')) { unit = 'ppm'; trend = '↑ 2 ppm'; }
        else if (k.includes('kw')) { unit = 'kW'; trend = '↑ 0.1 kW'; }

        return {
          name: k,
          value: metrics[k],
          unit,
          trend,
          trendLabel
        };
      });
    });

    this.notifyListeners();
  }

  subscribe(listener: () => void) {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  private notifyListeners() {
    this.listeners.forEach(l => l());
  }

  getDevice(id: string): DeviceState | undefined {
    return this.state.devices.get(id);
  }

  getDevices(): DeviceState[] {
    return Array.from(this.state.devices.values());
  }
  
  getEvents(deviceId?: string) {
    if (deviceId) {
      return this.state.events.filter(e => e.deviceId === deviceId || !e.deviceId);
    }
    return this.state.events;
  }
}

export const ScenarioEngine = new ScenarioEngineClass();
