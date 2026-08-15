import { useEffect, useState } from 'react';
import { Command } from 'cmdk';
import { useNavigate } from 'react-router-dom';
import { Search, Server, Cpu, Zap, FileJson, TrendingUp, Settings, Activity, LogOut } from 'lucide-react';
import { motion, AnimatePresence } from 'framer-motion';

export function CommandPalette({ open, setOpen }: { open: boolean, setOpen: (open: boolean) => void }) {
  const navigate = useNavigate();
  const [search, setSearch] = useState('');

  // Toggle the menu when ⌘K is pressed
  useEffect(() => {
    const down = (e: KeyboardEvent) => {
      if (e.key === 'k' && (e.metaKey || e.ctrlKey)) {
        e.preventDefault();
        setOpen(true);
      }
    };

    document.addEventListener('keydown', down);
    return () => document.removeEventListener('keydown', down);
  }, [setOpen]);

  const handleSelect = (action: () => void) => {
    action();
    setOpen(false);
    setSearch('');
  };

  if (!open) return null;

  return (
    <AnimatePresence>
      <div className="fixed inset-0 z-50 flex items-start justify-center pt-[15vh]">
        {/* Backdrop */}
        <motion.div
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          exit={{ opacity: 0 }}
          onClick={() => setOpen(false)}
          className="fixed inset-0 bg-background/80 backdrop-blur-sm"
        />

        {/* Command Menu */}
        <motion.div
          initial={{ opacity: 0, scale: 0.95 }}
          animate={{ opacity: 1, scale: 1 }}
          exit={{ opacity: 0, scale: 0.95 }}
          transition={{ duration: 0.15 }}
          className="relative z-50 w-full max-w-2xl overflow-hidden rounded-xl border border-[#1f1f28] bg-[#0a0a0f] shadow-2xl"
        >
          <Command 
            className="flex h-full w-full flex-col overflow-hidden bg-transparent"
            shouldFilter={false} // Custom filtering handled internally or pass everything
          >
            <div className="flex items-center border-b border-[#1f1f28] px-3">
              <Search className="mr-2 h-4 w-4 shrink-0 text-muted-foreground" />
              <Command.Input 
                autoFocus
                placeholder="Type a command or search..."
                value={search}
                onValueChange={setSearch}
                className="flex h-12 w-full rounded-md bg-transparent py-3 text-sm outline-none placeholder:text-muted-foreground text-foreground"
              />
            </div>

            <Command.List className="max-h-[400px] overflow-y-auto overflow-x-hidden custom-scrollbar p-2">
              <Command.Empty className="py-6 text-center text-sm text-muted-foreground">
                No results found.
              </Command.Empty>

              <Command.Group heading="Navigation" className="text-xs font-medium text-muted-foreground [&_[cmdk-group-heading]]:px-2 [&_[cmdk-group-heading]]:py-1.5 [&_[cmdk-group-heading]]:mb-1">
                <Command.Item 
                  onSelect={() => handleSelect(() => navigate('/'))}
                  className="relative flex cursor-pointer select-none items-center rounded-sm px-2 py-2 text-sm outline-none aria-selected:bg-blue-500/10 aria-selected:text-blue-400 hover:bg-[#1f1f28]"
                >
                  <Activity className="mr-2 h-4 w-4" />
                  Dashboard
                </Command.Item>
                <Command.Item 
                  onSelect={() => handleSelect(() => navigate('/twins'))}
                  className="relative flex cursor-pointer select-none items-center rounded-sm px-2 py-2 text-sm outline-none aria-selected:bg-blue-500/10 aria-selected:text-blue-400 hover:bg-[#1f1f28]"
                >
                  <Cpu className="mr-2 h-4 w-4" />
                  Digital Twins
                </Command.Item>
                <Command.Item 
                  onSelect={() => handleSelect(() => navigate('/telemetry'))}
                  className="relative flex cursor-pointer select-none items-center rounded-sm px-2 py-2 text-sm outline-none aria-selected:bg-blue-500/10 aria-selected:text-blue-400 hover:bg-[#1f1f28]"
                >
                  <TrendingUp className="mr-2 h-4 w-4" />
                  Telemetry
                </Command.Item>
                <Command.Item 
                  onSelect={() => handleSelect(() => navigate('/events'))}
                  className="relative flex cursor-pointer select-none items-center rounded-sm px-2 py-2 text-sm outline-none aria-selected:bg-blue-500/10 aria-selected:text-blue-400 hover:bg-[#1f1f28]"
                >
                  <Zap className="mr-2 h-4 w-4" />
                  Events
                </Command.Item>
                <Command.Item 
                  onSelect={() => handleSelect(() => navigate('/rules'))}
                  className="relative flex cursor-pointer select-none items-center rounded-sm px-2 py-2 text-sm outline-none aria-selected:bg-blue-500/10 aria-selected:text-blue-400 hover:bg-[#1f1f28]"
                >
                  <FileJson className="mr-2 h-4 w-4" />
                  Rules
                </Command.Item>
              </Command.Group>

              <Command.Group heading="Recent Entities" className="mt-4 text-xs font-medium text-muted-foreground [&_[cmdk-group-heading]]:px-2 [&_[cmdk-group-heading]]:py-1.5 [&_[cmdk-group-heading]]:mb-1">
                <Command.Item 
                  onSelect={() => handleSelect(() => console.log('hvac-1'))}
                  className="relative flex cursor-pointer select-none items-center rounded-sm px-2 py-2 text-sm outline-none aria-selected:bg-blue-500/10 aria-selected:text-blue-400 hover:bg-[#1f1f28]"
                >
                  <Server className="mr-2 h-4 w-4" />
                  HVAC Controller 1 (dev-hvac-001)
                  <span className="ml-auto text-xs text-muted-foreground">Floor 2</span>
                </Command.Item>
                <Command.Item 
                  onSelect={() => handleSelect(() => console.log('temp-1'))}
                  className="relative flex cursor-pointer select-none items-center rounded-sm px-2 py-2 text-sm outline-none aria-selected:bg-blue-500/10 aria-selected:text-blue-400 hover:bg-[#1f1f28]"
                >
                  <Server className="mr-2 h-4 w-4" />
                  Temperature Sensor 1 (dev-temp-001)
                  <span className="ml-auto text-xs text-muted-foreground">Floor 1</span>
                </Command.Item>
              </Command.Group>

              <Command.Group heading="System" className="mt-4 text-xs font-medium text-muted-foreground [&_[cmdk-group-heading]]:px-2 [&_[cmdk-group-heading]]:py-1.5 [&_[cmdk-group-heading]]:mb-1">
                <Command.Item 
                  onSelect={() => handleSelect(() => navigate('/settings'))}
                  className="relative flex cursor-pointer select-none items-center rounded-sm px-2 py-2 text-sm outline-none aria-selected:bg-blue-500/10 aria-selected:text-blue-400 hover:bg-[#1f1f28]"
                >
                  <Settings className="mr-2 h-4 w-4" />
                  Settings
                </Command.Item>
                <Command.Item 
                  onSelect={() => handleSelect(() => console.log('Logout'))}
                  className="relative flex cursor-pointer select-none items-center rounded-sm px-2 py-2 text-sm outline-none aria-selected:bg-destructive/10 aria-selected:text-destructive text-destructive hover:bg-[#1f1f28]"
                >
                  <LogOut className="mr-2 h-4 w-4" />
                  Log out
                </Command.Item>
              </Command.Group>
            </Command.List>
          </Command>
        </motion.div>
      </div>
    </AnimatePresence>
  );
}
