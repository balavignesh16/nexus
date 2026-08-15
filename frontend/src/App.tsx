import { BrowserRouter, Routes, Route } from 'react-router-dom';
import { AppShell } from './components/AppShell';
import HomePage from './pages/HomePage';
import AboutPage from './pages/AboutPage';
import EventsPage from './pages/EventsPage';
import ActionsPage from './pages/ActionsPage';

import { RulesPage } from './pages/RulesPage';
import SystemPage from './pages/SystemPage';
import ExplorerPage from './pages/ExplorerPage';
import { DesignSystemPage } from './pages/DesignSystemPage';
import { ThemeProvider } from './components/ThemeProvider';
import TwinsPage from './pages/TwinsPage';
import TelemetryPage from './pages/TelemetryPage';
import PipelinePage from './pages/PipelinePage';
import AnalyticsPage from './pages/AnalyticsPage';
import AiInsightsPage from './pages/AiInsightsPage';
import SettingsPage from './pages/SettingsPage';
import { InspectorProvider } from './components/ui/InspectorProvider';
import { RealtimeProvider } from './realtime/RealtimeProvider';
import './App.css';

function App() {
  return (
    <ThemeProvider defaultTheme="dark" storageKey="nexus-theme">
      <RealtimeProvider>
      <InspectorProvider>
        <BrowserRouter>
          <Routes>
            <Route path="/" element={<AppShell><HomePage /></AppShell>} />
            <Route path="/about" element={<AppShell><AboutPage /></AppShell>} />
            <Route path="/events" element={<AppShell><EventsPage /></AppShell>} />
            <Route path="/rules" element={<AppShell><RulesPage /></AppShell>} />
            <Route path="/explorer" element={<AppShell><ExplorerPage /></AppShell>} />
            <Route path="/design" element={<AppShell><DesignSystemPage /></AppShell>} />
            <Route path="/twins" element={<AppShell><TwinsPage /></AppShell>} />
            <Route path="/telemetry" element={<AppShell><TelemetryPage /></AppShell>} />
            <Route path="/pipeline" element={<AppShell><PipelinePage /></AppShell>} />
            <Route path="/analytics" element={<AppShell><AnalyticsPage /></AppShell>} />
            <Route path="/ai" element={<AppShell><AiInsightsPage /></AppShell>} />
            <Route path="/actions" element={<AppShell><ActionsPage /></AppShell>} />
            <Route path="/settings" element={<AppShell><SettingsPage /></AppShell>} />
            <Route path="/system" element={<AppShell><SystemPage /></AppShell>} />
            <Route path="*" element={<AppShell><main className="p-8 flex items-center justify-center h-full"><h1 className="text-2xl font-bold text-muted-foreground">404 - Page Not Found</h1></main></AppShell>} />
          </Routes>
        </BrowserRouter>
      </InspectorProvider>
      </RealtimeProvider>
    </ThemeProvider>
  );
}

export default App;
