export type DevicePersonalityType = 'Temperature Sensor' | 'Motion Sensor' | 'Gas Sensor' | 'Door Sensor' | 'Energy Meter' | 'HVAC Controller' | 'Generic';

export interface DevicePersonality {
  type: DevicePersonalityType;
  baseTemperature: number;
  baseHumidity: number;
  generateTelemetry: (tick: number, scenario: string) => any;
}

export const Personalities: Record<DevicePersonalityType, DevicePersonality> = {
  'Temperature Sensor': {
    type: 'Temperature Sensor',
    baseTemperature: 24.8,
    baseHumidity: 52.1,
    generateTelemetry: (tick, scenario) => {
      let temp = 24.8 + Math.sin(tick / 10) * 0.1;
      let hum = 52.1 - Math.cos(tick / 5) * 0.2;
      let press = 1013.2 + Math.sin(tick / 20) * 0.05;
      let batt = 87 - Math.floor(tick / 100);
      
      if (scenario === 'High Temperature') {
        temp += (tick % 60) * 0.2; // Steadily rises
      }
      return { 
        temperature: temp.toFixed(1),
        humidity: hum.toFixed(1),
        pressure: press.toFixed(1),
        battery: Math.max(0, batt).toString()
      };
    }
  },
  'Motion Sensor': {
    type: 'Motion Sensor',
    baseTemperature: 21.0,
    baseHumidity: 40,
    generateTelemetry: (_tick, scenario) => {
      let motion = 'Idle';
      if (scenario === 'Unauthorized Access') {
        motion = 'Motion Detected';
      } else {
        motion = Math.random() > 0.8 ? 'Motion Detected' : 'Idle';
      }
      return { motion };
    }
  },
  'Gas Sensor': {
    type: 'Gas Sensor',
    baseTemperature: 22.0,
    baseHumidity: 42,
    generateTelemetry: (tick, scenario) => {
      let ppm = 98 + Math.sin(tick / 5) * 2;
      if (scenario === 'Gas Leak') {
        ppm = 98 + (tick % 30) * 5;
      }
      return { gas_ppm: ppm.toFixed(0) };
    }
  },
  'Door Sensor': {
    type: 'Door Sensor',
    baseTemperature: 20.0,
    baseHumidity: 40,
    generateTelemetry: (_tick, scenario) => {
      let state = 'Closed';
      if (scenario === 'Unauthorized Access') {
        state = 'Opened';
      }
      return { door_state: state };
    }
  },
  'Energy Meter': {
    type: 'Energy Meter',
    baseTemperature: 30.0,
    baseHumidity: 30,
    generateTelemetry: (tick, scenario) => {
      let kw = 3.2 + Math.sin(tick / 8) * 0.3;
      if (scenario === 'Power Failure') {
        kw = 0;
      }
      return { power_kw: kw.toFixed(1) };
    }
  },
  'HVAC Controller': {
    type: 'HVAC Controller',
    baseTemperature: 25.0,
    baseHumidity: 50,
    generateTelemetry: (_tick, scenario) => {
      let state = 'Cooling';
      if (scenario === 'HVAC Failure') {
        state = 'Fault';
      }
      return { hvac_mode: state };
    }
  },
  'Generic': {
    type: 'Generic',
    baseTemperature: 24.0,
    baseHumidity: 50,
    generateTelemetry: (_tick, _scenario) => ({ value: (Math.random() * 100).toFixed(1) })
  }
};
