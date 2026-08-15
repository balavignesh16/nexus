import { useState, useEffect } from 'react';
import { ChevronDown, AlertTriangle, Play, Settings2 } from 'lucide-react';
import { FEATURES } from '../../config/features';
import { ScenarioEngine } from '../../simulation/ScenarioEngine';
import type { ScenarioType } from '../../simulation/ScenarioEngine';

const scenarios: { type: ScenarioType; label: string; icon: any; color: string }[] = [
  { type: 'Normal Operations', label: 'Normal Operations', icon: Play, color: 'text-success' },
  { type: 'High Temperature', label: 'High Temperature', icon: AlertTriangle, color: 'text-warning' },
  { type: 'Gas Leak', label: 'Gas Leak', icon: AlertTriangle, color: 'text-destructive' },
  { type: 'Power Failure', label: 'Power Failure', icon: AlertTriangle, color: 'text-destructive' },
  { type: 'Unauthorized Access', label: 'Unauthorized Access', icon: AlertTriangle, color: 'text-warning' },
  { type: 'HVAC Failure', label: 'HVAC Failure', icon: AlertTriangle, color: 'text-warning' },
  { type: 'Water Leakage', label: 'Water Leakage', icon: AlertTriangle, color: 'text-blue-500' },
  { type: 'Sensor Offline', label: 'Sensor Offline', icon: AlertTriangle, color: 'text-muted-foreground' },
];

export function DemoScenarioSelector() {
  const [isOpen, setIsOpen] = useState(false);
  const [current, setCurrent] = useState<ScenarioType>('Normal Operations');

  useEffect(() => {
    setCurrent(ScenarioEngine.getScenario());
    const unsubscribe = ScenarioEngine.subscribe(() => {
      setCurrent(ScenarioEngine.getScenario());
    });
    return () => { unsubscribe(); };
  }, []);

  if (!FEATURES.DEMO_MODE) return null;

  return (
    <div className="relative">
      <button 
        onClick={() => setIsOpen(!isOpen)}
        className="flex items-center gap-2 px-3 py-1.5 bg-[#12121a] hover:bg-[#1a1a24] border border-[#1f1f28] rounded-md text-xs font-medium transition-colors"
      >
        <Settings2 size={14} className="text-muted-foreground" />
        <span className="text-foreground">Scenario:</span>
        <span className="text-blue-400 font-semibold">{current}</span>
        <ChevronDown size={14} className="text-muted-foreground ml-1" />
      </button>

      {isOpen && (
        <div className="absolute right-0 top-full mt-2 w-56 bg-[#0a0f1d] border border-[#1f1f28] rounded-md shadow-2xl z-50 overflow-hidden">
          <div className="px-3 py-2 bg-[#13131a] border-b border-[#1f1f28] text-xs font-semibold text-muted-foreground">
            Demo Simulator
          </div>
          <div className="max-h-64 overflow-y-auto py-1">
            {scenarios.map(sc => (
              <button
                key={sc.type}
                onClick={() => {
                  ScenarioEngine.setScenario(sc.type);
                  setIsOpen(false);
                }}
                className="w-full flex items-center gap-3 px-3 py-2 hover:bg-[#1a1a24] text-left text-[13px] transition-colors"
              >
                <sc.icon size={14} className={sc.color} />
                <span className={current === sc.type ? 'text-white font-medium' : 'text-muted-foreground'}>
                  {sc.label}
                </span>
              </button>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
