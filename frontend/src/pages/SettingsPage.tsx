import { useState } from 'react';
import { Settings, Sliders, Palette, Users, Shield, Database, Zap, Bell, Server, Key, Activity, Box, Search } from 'lucide-react';
import { motion, AnimatePresence } from 'framer-motion';

const SETTINGS_TABS = [
  { id: 'general', label: 'General', icon: Sliders, group: 'Configuration' },
  { id: 'appearance', label: 'Appearance', icon: Palette, group: 'Configuration' },
  { id: 'simulator', label: 'Simulator', icon: Zap, group: 'Configuration' },
  { id: 'feature_flags', label: 'Feature Flags', icon: Box, group: 'Configuration' },
  
  { id: 'users', label: 'Users & Roles', icon: Users, group: 'Access Management' },
  { id: 'permissions', label: 'Permissions', icon: Shield, group: 'Access Management' },
  { id: 'api_keys', label: 'API Keys', icon: Key, group: 'Access Management' },
  
  { id: 'mqtt', label: 'MQTT Broker', icon: Server, group: 'Infrastructure' },
  { id: 'database', label: 'Database', icon: Database, group: 'Infrastructure' },
  { id: 'integrations', label: 'Integrations', icon: Box, group: 'Infrastructure' },
  
  { id: 'notifications', label: 'Notifications', icon: Bell, group: 'System' },
  { id: 'health', label: 'Health Checks', icon: Activity, group: 'System' },
  { id: 'audit', label: 'Audit Logs', icon: Settings, group: 'System' },
];

export default function SettingsPage() {
  const [activeTab, setActiveTab] = useState('appearance');
  const [search, setSearch] = useState('');

  const groups = Array.from(new Set(SETTINGS_TABS.map(t => t.group)));

  return (
    <div className="flex-1 flex h-full bg-[#060b14] overflow-hidden text-foreground animate-in fade-in duration-300">
      
      {/* LEFT: SETTINGS SIDEBAR */}
      <div className="w-[280px] shrink-0 border-r border-[#1f1f28] bg-[#0a0a0f] flex flex-col z-10">
        <div className="shrink-0 h-16 border-b border-[#1f1f28] flex items-center px-6">
          <div className="flex items-center gap-3">
            <div className="p-2 bg-blue-500/10 rounded-lg border border-blue-500/20">
              <Settings size={20} className="text-blue-400" />
            </div>
            <h1 className="text-lg font-bold">Settings</h1>
          </div>
        </div>

        <div className="p-4 border-b border-[#1f1f28]">
          <div className="relative">
            <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground" />
            <input 
              type="text" 
              placeholder="Search settings..."
              value={search}
              onChange={e => setSearch(e.target.value)}
              className="w-full pl-9 pr-4 py-1.5 bg-[#13131a] border border-[#1f1f28] rounded-md text-sm focus:outline-none focus:border-blue-500 transition-colors"
            />
          </div>
        </div>

        <div className="flex-1 overflow-y-auto custom-scrollbar p-4 space-y-6">
          {groups.map(group => (
            <div key={group} className="space-y-1">
              <h4 className="text-[10px] font-bold uppercase tracking-wider text-muted-foreground px-3 mb-2">{group}</h4>
              {SETTINGS_TABS.filter(t => t.group === group).map(tab => (
                <button
                  key={tab.id}
                  onClick={() => setActiveTab(tab.id)}
                  className={`w-full flex items-center gap-3 px-3 py-2 rounded-lg text-sm font-medium transition-colors ${
                    activeTab === tab.id 
                      ? 'bg-blue-500/10 text-blue-400' 
                      : 'text-muted-foreground hover:bg-[#13131a] hover:text-foreground'
                  }`}
                >
                  <tab.icon size={16} />
                  {tab.label}
                </button>
              ))}
            </div>
          ))}
        </div>
      </div>

      {/* RIGHT: SETTINGS CONTENT */}
      <div className="flex-1 overflow-y-auto custom-scrollbar p-10 bg-[#060b14]">
        <div className="max-w-4xl mx-auto">
          
          <AnimatePresence mode="wait">
            <motion.div
              key={activeTab}
              initial={{ opacity: 0, y: 10 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0, y: -10 }}
              transition={{ duration: 0.2 }}
            >
              {activeTab === 'appearance' && (
                <div className="space-y-8">
                  <div>
                    <h2 className="text-2xl font-bold mb-1">Appearance</h2>
                    <p className="text-muted-foreground text-sm">Customize the UI density, theme, and animations across the workspace.</p>
                  </div>
                  
                  <div className="space-y-6">
                    <div className="bg-card/50 backdrop-blur border border-border/50 rounded-xl p-6">
                      <h3 className="font-semibold mb-4 text-sm">Color Theme</h3>
                      <div className="grid grid-cols-3 gap-4">
                        <div className="border border-blue-500/50 ring-1 ring-blue-500/30 bg-[#0a0a0f] rounded-lg p-4 cursor-pointer">
                          <div className="flex items-center gap-2 mb-3">
                            <div className="w-4 h-4 rounded-full bg-[#0a0a0f] border border-border/50"></div>
                            <span className="text-sm font-medium">NEXUS Dark (Default)</span>
                          </div>
                          <div className="flex gap-2">
                            <div className="h-2 w-full bg-[#13131a] rounded"></div>
                            <div className="h-2 w-1/2 bg-blue-500/20 rounded"></div>
                          </div>
                        </div>
                        <div className="border border-border/50 bg-white rounded-lg p-4 cursor-not-allowed opacity-50">
                          <div className="flex items-center gap-2 mb-3">
                            <div className="w-4 h-4 rounded-full bg-white border border-gray-300"></div>
                            <span className="text-sm font-medium text-gray-900">Light Mode</span>
                          </div>
                          <div className="flex gap-2">
                            <div className="h-2 w-full bg-gray-100 rounded"></div>
                            <div className="h-2 w-1/2 bg-blue-100 rounded"></div>
                          </div>
                        </div>
                      </div>
                    </div>

                    <div className="bg-card/50 backdrop-blur border border-border/50 rounded-xl p-6">
                      <h3 className="font-semibold mb-4 text-sm">UI Density</h3>
                      <div className="space-y-4">
                        <label className="flex items-center gap-3 cursor-pointer">
                          <input type="radio" name="density" className="w-4 h-4 text-blue-500 bg-[#13131a] border-[#1f1f28] focus:ring-blue-500" onChange={() => document.body.classList.add('ui-density-comfortable')} />
                          <div>
                            <p className="text-sm font-medium">Comfortable</p>
                            <p className="text-xs text-muted-foreground">More whitespace, easier to read.</p>
                          </div>
                        </label>
                        <label className="flex items-center gap-3 cursor-pointer">
                          <input type="radio" name="density" defaultChecked className="w-4 h-4 text-blue-500 bg-[#13131a] border-[#1f1f28] focus:ring-blue-500" onChange={() => document.body.classList.remove('ui-density-comfortable')} />
                          <div>
                            <p className="text-sm font-medium">Compact (Enterprise Default)</p>
                            <p className="text-xs text-muted-foreground">Information-dense layout for maximum data visibility.</p>
                          </div>
                        </label>
                      </div>
                    </div>

                    <div className="bg-card/50 backdrop-blur border border-border/50 rounded-xl p-6 flex items-center justify-between">
                      <div>
                        <h3 className="font-semibold text-sm">Reduced Motion</h3>
                        <p className="text-xs text-muted-foreground mt-1">Disable complex animations and transitions.</p>
                      </div>
                      <div className="relative inline-block w-10 h-5">
                        <input type="checkbox" className="peer sr-only" id="motion-toggle" />
                        <label htmlFor="motion-toggle" className="block w-full h-full bg-[#1f1f28] peer-checked:bg-blue-500 rounded-full cursor-pointer transition-colors"></label>
                        <div className="absolute left-1 top-1 w-3 h-3 bg-white rounded-full transition-transform peer-checked:translate-x-5 pointer-events-none"></div>
                      </div>
                    </div>
                  </div>
                </div>
              )}

              {activeTab === 'simulator' && (
                <div className="space-y-8">
                  <div>
                    <h2 className="text-2xl font-bold mb-1">Simulator Settings</h2>
                    <p className="text-muted-foreground text-sm">Configure the behavior of the DemoRepository and ScenarioEngine.</p>
                  </div>
                  
                  <div className="bg-card/50 backdrop-blur border border-border/50 rounded-xl p-6">
                    <h3 className="font-semibold mb-4 text-sm">Scenario Engine Polling</h3>
                    <div className="space-y-4">
                      <div>
                        <label className="block text-xs font-medium text-muted-foreground mb-1">Telemetry Generation Rate (ms)</label>
                        <input type="number" defaultValue={2000} className="w-full bg-[#13131a] border border-[#1f1f28] rounded-md px-3 py-2 text-sm focus:outline-none focus:border-blue-500" />
                      </div>
                      <div>
                        <label className="block text-xs font-medium text-muted-foreground mb-1">Event Flap Probability (%)</label>
                        <input type="number" defaultValue={5} className="w-full bg-[#13131a] border border-[#1f1f28] rounded-md px-3 py-2 text-sm focus:outline-none focus:border-blue-500" />
                      </div>
                    </div>
                    <div className="mt-6 pt-4 border-t border-[#1f1f28] flex justify-end">
                      <button className="px-4 py-2 bg-blue-600 hover:bg-blue-700 text-white rounded-md text-sm font-medium transition-colors">
                        Apply Changes
                      </button>
                    </div>
                  </div>
                </div>
              )}

              {/* STUBBED SECTIONS */}
              {activeTab !== 'appearance' && activeTab !== 'simulator' && (
                <div className="space-y-8">
                  <div>
                    <h2 className="text-2xl font-bold mb-1">{SETTINGS_TABS.find(t => t.id === activeTab)?.label}</h2>
                    <p className="text-muted-foreground text-sm">Configuration section for {activeTab}.</p>
                  </div>
                  
                  <div className="bg-card/50 backdrop-blur border border-border/50 rounded-xl p-12 flex flex-col items-center justify-center text-center text-muted-foreground">
                    <Settings size={48} className="opacity-20 mb-4" />
                    <h3 className="text-foreground font-semibold mb-2">Module Not Configured</h3>
                    <p className="text-sm max-w-sm">This enterprise module requires backend capabilities that are currently inactive in Hybrid Mode.</p>
                  </div>
                </div>
              )}
            </motion.div>
          </AnimatePresence>

        </div>
      </div>

    </div>
  );
}
