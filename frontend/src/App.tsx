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
import Phase6Page from './pages/Phase6Page';
import SimulationPage from './pages/SimulationPage';
import InfrastructureManagePage from './pages/InfrastructureManagePage';
import SettingsPage from './pages/SettingsPage';
import CapabilityDefinitionsPage from './pages/framework/CapabilityDefinitionsPage';
import DeviceTemplatesPage from './pages/framework/DeviceTemplatesPage';
import DeviceOnboardingPage from './pages/framework/DeviceOnboardingPage';
import OnboardingSessionPage from './pages/framework/OnboardingSessionPage';
import DeviceContractPage from './pages/framework/DeviceContractPage';
import GenericRulePage from './pages/framework/GenericRulePage';
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
            <Route path="/simulation" element={<AppShell><SimulationPage /></AppShell>} />
            <Route path="/infrastructure" element={<AppShell><InfrastructureManagePage /></AppShell>} />
            <Route path="/design" element={<AppShell><DesignSystemPage /></AppShell>} />
            <Route path="/twins" element={<AppShell><TwinsPage /></AppShell>} />
            <Route path="/telemetry" element={<AppShell><TelemetryPage /></AppShell>} />
            <Route path="/pipeline" element={<AppShell><PipelinePage /></AppShell>} />
            <Route path="/analytics" element={<AppShell><AnalyticsPage /></AppShell>} />
            <Route path="/ai" element={<AppShell><AiInsightsPage /></AppShell>} />
            <Route path="/assistant" element={<AppShell><Phase6Page key="assistant" screen="assistant" /></AppShell>} />
            <Route path="/intelligence" element={<AppShell><Phase6Page key="insights" screen="insights" /></AppShell>} />
            <Route path="/trends" element={<AppShell><Phase6Page key="trends" screen="trends" /></AppShell>} />
            <Route path="/ai/drafts" element={<AppShell><Phase6Page key="drafts" screen="drafts" /></AppShell>} />
            <Route path="/energy" element={<AppShell><Phase6Page key="energy" screen="energy" /></AppShell>} />
            <Route path="/actions" element={<AppShell><ActionsPage /></AppShell>} />
            <Route path="/settings" element={<AppShell><SettingsPage /></AppShell>} />
            <Route path="/system" element={<AppShell><SystemPage /></AppShell>} />
            <Route path="/framework/capabilities" element={<AppShell><CapabilityDefinitionsPage /></AppShell>} />
            <Route path="/framework/templates" element={<AppShell><DeviceTemplatesPage /></AppShell>} />
            <Route path="/framework/templates/:templateId" element={<AppShell><DeviceTemplatesPage /></AppShell>} />
            <Route path="/framework/onboarding" element={<AppShell><DeviceOnboardingPage /></AppShell>} />
            <Route path="/framework/onboarding/:sessionId" element={<AppShell><OnboardingSessionPage /></AppShell>} />
            <Route path="/framework/rules" element={<AppShell><GenericRulePage /></AppShell>} />
            <Route path="/devices/:deviceId/contract" element={<AppShell><DeviceContractPage /></AppShell>} />
            <Route path="*" element={<AppShell><main className="p-8 flex items-center justify-center h-full"><h1 className="text-2xl font-bold text-muted-foreground">404 - Page Not Found</h1></main></AppShell>} />
          </Routes>
        </BrowserRouter>
      </InspectorProvider>
      </RealtimeProvider>
    </ThemeProvider>
  );
}

export default App;
