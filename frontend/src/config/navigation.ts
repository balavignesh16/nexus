import { 
  LayoutDashboard, 
  Server, 
  Cpu, 
  Activity, 
  AlertTriangle, 
  ListFilter, 
  Zap, 
  BarChart3, 
  Settings,
  BrainCircuit,
  type LucideIcon 
} from 'lucide-react';

export interface NavItem {
  title: string;
  path: string;
  icon: LucideIcon;
  requiresAuth?: boolean;
}

export const navigationConfig: NavItem[] = [
  { title: 'Dashboard', path: '/', icon: LayoutDashboard },
  { title: 'Infrastructure', path: '/explorer', icon: Server },
  { title: 'Digital Twins', path: '/twins', icon: Cpu },
  { title: 'Telemetry', path: '/telemetry', icon: Activity },
  { title: 'Events', path: '/events', icon: AlertTriangle },
  { title: 'Rules', path: '/rules', icon: ListFilter },
  { title: 'Actions', path: '/actions', icon: Zap },
  { title: 'Analytics', path: '/analytics', icon: BarChart3 },
  { title: 'AI Insights', path: '/ai', icon: BrainCircuit },
  { title: 'Settings', path: '/settings', icon: Settings },
];
