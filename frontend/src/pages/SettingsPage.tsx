import { useState } from 'react';
import { Settings, Sliders, Palette, Users, Shield, Database, Zap, Bell, Server, Key, Activity, Box, Search } from 'lucide-react';
import { motion, AnimatePresence } from 'framer-motion';
import { GlassPanel } from '../components/foundation/GlassPanel';
import { Input } from '../components/foundation/Input';
import { Toggle } from '../components/foundation/Toggle';
import { Button } from '../components/foundation/Button';
import { EmptyState } from '../components/foundation/EmptyState';

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
  // Reduced Motion (Step 10C): this local state exists only to satisfy
  // foundation Toggle's controlled-component contract - it drives nothing
  // else. No CSS motion config, no localStorage, no runtime effect - the
  // exact same no-op the prior hand-rolled checkbox had.
  const [reducedMotion, setReducedMotion] = useState(false);

  const groups = Array.from(new Set(SETTINGS_TABS.map(t => t.group)));

  return (
    <div className="flex flex-col lg:flex-row h-full overflow-hidden" style={{ backgroundColor: 'var(--foundation-bg)' }}>

      {/* LEFT: SETTINGS SIDEBAR */}
      <div
        className="w-full lg:w-[280px] shrink-0 h-[40vh] lg:h-full flex flex-col border-b lg:border-b-0 lg:border-r"
        style={{ borderColor: 'var(--foundation-border)' }}
      >
        <div className="shrink-0 h-16 border-b flex items-center px-6" style={{ borderColor: 'var(--foundation-border)' }}>
          <div className="flex items-center gap-3">
            <div
              className="w-9 h-9 rounded-[var(--radius-panel)] flex items-center justify-center shrink-0"
              style={{ backgroundColor: 'color-mix(in srgb, var(--accent) 14%, transparent)', color: 'var(--accent)' }}
            >
              <Settings size={18} />
            </div>
            <h1 className="text-lg font-bold text-foundation-fg">Settings</h1>
          </div>
        </div>

        <div className="p-4 border-b" style={{ borderColor: 'var(--foundation-border)' }}>
          <div className="relative">
            <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2 text-foundation-muted-fg" />
            {/* Not wired to filtering (Step 10C audit): honestly present, not honestly functional yet. */}
            <Input
              type="text"
              placeholder="Search settings..."
              aria-label="Search settings"
              value={search}
              onChange={e => setSearch(e.target.value)}
              className="pl-9"
            />
          </div>
        </div>

        <div className="flex-1 overflow-y-auto custom-scrollbar p-4 space-y-6">
          {groups.map(group => (
            <div key={group} className="space-y-1">
              <h4 className="text-[10px] font-bold uppercase tracking-wider text-foundation-muted-fg px-3 mb-2">{group}</h4>
              {SETTINGS_TABS.filter(t => t.group === group).map(tab => (
                <button
                  key={tab.id}
                  onClick={() => setActiveTab(tab.id)}
                  className="w-full flex items-center gap-3 px-3 py-2 rounded-[var(--radius-panel)] text-sm font-medium transition-colors"
                  style={
                    activeTab === tab.id
                      ? { backgroundColor: 'color-mix(in srgb, var(--accent) 12%, transparent)', color: 'var(--accent)' }
                      : { color: 'var(--foundation-muted-fg)' }
                  }
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
      <div className="flex-1 overflow-y-auto custom-scrollbar p-6 sm:p-10">
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
                    <h2 className="text-2xl font-bold mb-1 text-foundation-fg">Appearance</h2>
                    <p className="text-foundation-muted-fg text-sm">Customize the UI density, theme, and animations across the workspace.</p>
                  </div>

                  <div className="space-y-6">
                    <GlassPanel level="elevated" radius="panel-lg" className="p-6">
                      <h3 className="font-semibold mb-4 text-sm text-foundation-fg">Color Theme</h3>
                      {/* Non-interactive by design (Step 10C audit): neither card
                          has ever changed the application theme. Rather than add
                          keyboard handlers that would activate nothing, both are
                          presented as static illustrations of theme options, not
                          live controls - preserving the exact current behavior
                          honestly instead of pretending they are buttons. */}
                      <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                        <div
                          className="rounded-[var(--radius-panel)] p-4"
                          style={{ backgroundColor: 'var(--foundation-bg)', border: '1px solid var(--accent)', boxShadow: '0 0 0 1px color-mix(in srgb, var(--accent) 30%, transparent)' }}
                        >
                          <div className="flex items-center justify-between gap-2 mb-3">
                            <div className="flex items-center gap-2">
                              <div className="w-4 h-4 rounded-full" style={{ backgroundColor: '#1c1b26', border: '1px solid var(--foundation-border)' }} />
                              <span className="text-sm font-medium text-foundation-fg">NEXUS Dark (Default)</span>
                            </div>
                            <span
                              className="text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-full"
                              style={{ backgroundColor: 'color-mix(in srgb, var(--accent) 14%, transparent)', color: 'var(--accent)' }}
                            >
                              Selected
                            </span>
                          </div>
                          <div className="flex gap-2">
                            <div className="h-2 w-full rounded" style={{ backgroundColor: '#13131a' }} />
                            <div className="h-2 w-1/2 rounded" style={{ backgroundColor: 'color-mix(in srgb, var(--accent) 30%, transparent)' }} />
                          </div>
                        </div>
                        <div className="rounded-[var(--radius-panel)] p-4 opacity-50" style={{ backgroundColor: 'var(--foundation-surface)', border: '1px solid var(--foundation-border)' }}>
                          <div className="flex items-center justify-between gap-2 mb-3">
                            <div className="flex items-center gap-2">
                              <div className="w-4 h-4 rounded-full bg-white" style={{ border: '1px solid var(--foundation-border)' }} />
                              <span className="text-sm font-medium text-foundation-fg">Light Mode</span>
                            </div>
                            <span className="text-[10px] font-bold uppercase tracking-wider text-foundation-muted-fg">Unavailable</span>
                          </div>
                          <div className="flex gap-2">
                            <div className="h-2 w-full rounded bg-gray-100" />
                            <div className="h-2 w-1/2 rounded" style={{ backgroundColor: 'color-mix(in srgb, var(--accent) 20%, transparent)' }} />
                          </div>
                        </div>
                      </div>
                    </GlassPanel>

                    <GlassPanel level="elevated" radius="panel-lg" className="p-6">
                      <h3 className="font-semibold mb-4 text-sm text-foundation-fg">UI Density</h3>
                      <div className="space-y-4">
                        <label className="flex items-center gap-3 cursor-pointer">
                          <input
                            type="radio"
                            name="density"
                            className="w-4 h-4"
                            style={{ accentColor: 'var(--accent)' }}
                            onChange={() => document.body.classList.add('ui-density-comfortable')}
                          />
                          <div>
                            <p className="text-sm font-medium text-foundation-fg">Comfortable</p>
                            <p className="text-xs text-foundation-muted-fg">More whitespace, easier to read.</p>
                          </div>
                        </label>
                        <label className="flex items-center gap-3 cursor-pointer">
                          <input
                            type="radio"
                            name="density"
                            defaultChecked
                            className="w-4 h-4"
                            style={{ accentColor: 'var(--accent)' }}
                            onChange={() => document.body.classList.remove('ui-density-comfortable')}
                          />
                          <div>
                            <p className="text-sm font-medium text-foundation-fg">Compact (Enterprise Default)</p>
                            <p className="text-xs text-foundation-muted-fg">Information-dense layout for maximum data visibility.</p>
                          </div>
                        </label>
                      </div>
                    </GlassPanel>

                    <GlassPanel level="elevated" radius="panel-lg" className="p-6 flex items-center justify-between gap-4">
                      <div>
                        <h3 className="font-semibold text-sm text-foundation-fg">Reduced Motion</h3>
                        <p className="text-xs text-foundation-muted-fg mt-1">Disable complex animations and transitions.</p>
                      </div>
                      <Toggle checked={reducedMotion} onChange={setReducedMotion} label="Reduced Motion" />
                    </GlassPanel>
                  </div>
                </div>
              )}

              {activeTab === 'simulator' && (
                <div className="space-y-8">
                  <div>
                    <h2 className="text-2xl font-bold mb-1 text-foundation-fg">Simulator Settings</h2>
                    <p className="text-foundation-muted-fg text-sm">Configure the behavior of the DemoRepository and ScenarioEngine.</p>
                  </div>

                  <GlassPanel level="elevated" radius="panel-lg" className="p-6">
                    <h3 className="font-semibold mb-4 text-sm text-foundation-fg">Scenario Engine Polling</h3>
                    <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                      <div>
                        <label className="block text-xs font-medium text-foundation-muted-fg mb-1">Telemetry Generation Rate (ms)</label>
                        <Input type="number" defaultValue={2000} />
                      </div>
                      <div>
                        <label className="block text-xs font-medium text-foundation-muted-fg mb-1">Event Flap Probability (%)</label>
                        <Input type="number" defaultValue={5} />
                      </div>
                    </div>
                    <div className="mt-6 pt-4 border-t flex justify-end" style={{ borderColor: 'var(--foundation-border)' }}>
                      <Button variant="primary" size="md">
                        Apply Changes
                      </Button>
                    </div>
                  </GlassPanel>
                </div>
              )}

              {/* STUBBED SECTIONS */}
              {activeTab !== 'appearance' && activeTab !== 'simulator' && (
                <div className="space-y-8">
                  <div>
                    <h2 className="text-2xl font-bold mb-1 text-foundation-fg">{SETTINGS_TABS.find(t => t.id === activeTab)?.label}</h2>
                    <p className="text-foundation-muted-fg text-sm">Configuration section for {activeTab}.</p>
                  </div>

                  <GlassPanel level="elevated" radius="panel-lg" className="p-12">
                    <EmptyState
                      icon={<Settings size={48} />}
                      title="Module Not Configured"
                      description="This enterprise module requires backend capabilities that are currently inactive in Hybrid Mode."
                    />
                  </GlassPanel>
                </div>
              )}
            </motion.div>
          </AnimatePresence>

        </div>
      </div>

    </div>
  );
}
