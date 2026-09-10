import { Cpu, Box, Sparkles, Hash, Signal, Zap, CheckCircle2, ChevronRight, Send, Construction } from 'lucide-react';
import { useState } from 'react';

// PREVIEW ONLY: this page shows what the planned AI Operations Copilot will look like.
// No AI/ML model, inference, or backend AI service exists yet - the conversation below
// is a fixed sample, not a live chat. See README.md "Current Limitations" for status.
const SAMPLE_MESSAGE = {
  content: `Based on my analysis of the recent incidents, the root cause appears to be a sudden temperature spike in **HVAC Unit 1**, which triggered the **Temperature Correction Rule**.

This action successfully updated the twin's desired state, but the physical device took 450ms to acknowledge the command due to degraded signal strength. I recommend inspecting the network gateway on **Floor 2** and reviewing the threshold limits in the rule configuration.`,
  context: {
    twins: ['hvac-1', 'gateway-floor2'],
    telemetry: ['hvac-1:temperature', 'hvac-1:signalStrength'],
    events: ['Temperature High Alert', 'Action Dispatched'],
    rules: ['Rule-TempCorrection'],
    correlationId: 'corr-8f92-42a1-b38d-19283746 (sample)'
  }
};

export default function AiInsightsPage() {
  const [showSample, setShowSample] = useState(false);

  return (
    <div className="flex-1 flex h-full bg-[#060b14] overflow-hidden text-foreground animate-in fade-in duration-300">

      {/* LEFT: CONVERSATION PANE */}
      <div className="flex-1 flex flex-col h-full relative z-10">

        {/* Header */}
        <div className="shrink-0 h-16 border-b border-[#1f1f28] flex items-center justify-between px-6 bg-[#0a0a0f]">
          <div className="flex items-center gap-3">
            <div className="p-2 bg-purple-500/10 rounded-lg border border-purple-500/20">
              <Sparkles size={20} className="text-purple-400" />
            </div>
            <div>
              <h1 className="text-lg font-bold">Operations Copilot</h1>
              <p className="text-xs text-muted-foreground">AI-assisted diagnostics & reasoning</p>
            </div>
          </div>
        </div>

        {/* Preview banner */}
        <div className="shrink-0 mx-6 mt-6 flex items-start gap-3 bg-amber-500/10 border border-amber-500/30 rounded-xl p-4">
          <Construction size={18} className="text-amber-400 shrink-0 mt-0.5" />
          <div className="text-sm text-amber-200">
            <p className="font-semibold">Preview — Coming Soon</p>
            <p className="text-amber-200/80 text-xs mt-1">
              No AI model is connected yet. Decisions in NEXUS today come from the deterministic rule engine.
              This page previews what an AI-assisted copilot could look like once that layer is built. The
              conversation below is a fixed sample, not a live response.
            </p>
          </div>
        </div>

        {/* Sample conversation */}
        <div className="flex-1 overflow-y-auto custom-scrollbar p-6 space-y-6">
          <div className="flex gap-4 max-w-4xl mr-auto">
            <div className="shrink-0 w-8 h-8 rounded-lg bg-purple-500/10 border border-purple-500/30 text-purple-400 flex items-center justify-center">
              <Sparkles size={16} />
            </div>
            <div className="flex flex-col gap-2 items-start">
              <div className="p-4 rounded-2xl text-sm leading-relaxed bg-card/80 text-foreground border border-border/50 rounded-tl-sm shadow-sm">
                I am the Nexus Operations Copilot. Once connected to a real model, I'll help you analyze telemetry,
                trace correlation IDs across events, and suggest rule optimizations. Here's a sample of what that
                could look like:
              </div>
            </div>
          </div>

          {!showSample ? (
            <button
              onClick={() => setShowSample(true)}
              className="flex items-center gap-2 px-4 py-2 bg-[#13131a] hover:bg-muted/50 border border-[#1f1f28] rounded-full text-xs text-muted-foreground hover:text-foreground transition-colors mx-auto"
            >
              <Sparkles size={12} className="text-purple-400" /> Show sample response
            </button>
          ) : (
            <div className="flex gap-4 max-w-4xl mr-auto">
              <div className="shrink-0 w-8 h-8 rounded-lg bg-purple-500/10 border border-purple-500/30 text-purple-400 flex items-center justify-center">
                <Sparkles size={16} />
              </div>
              <div className="flex flex-col gap-2 items-start">
                <div className="p-4 rounded-2xl text-sm leading-relaxed whitespace-pre-wrap shadow-sm border bg-card/80 text-foreground border-border/50 rounded-tl-sm">
                  {SAMPLE_MESSAGE.content}
                </div>
                <span className="text-[10px] text-muted-foreground/70 px-1">Sample output — illustrative, not a real analysis</span>
                <button
                  className="flex items-center gap-2 px-3 py-1.5 bg-[#13131a] border border-[#1f1f28] rounded-full text-xs text-muted-foreground"
                >
                  <Hash size={12} className="text-purple-400" />
                  View sample references
                  <ChevronRight size={12} />
                </button>
              </div>
            </div>
          )}
        </div>

        {/* Disabled input area */}
        <div className="shrink-0 p-6 bg-[#060b14] border-t border-[#1f1f28]">
          <div className="max-w-4xl mx-auto relative group">
            <textarea
              value=""
              disabled
              placeholder="AI Copilot is not yet connected — this is a preview of planned functionality"
              className="w-full bg-[#0d0d14] border border-[#1f1f28] rounded-xl pl-4 pr-14 py-4 text-sm text-muted-foreground/60 resize-none overflow-hidden h-14 cursor-not-allowed"
              rows={1}
            />
            <button
              disabled
              className="absolute right-2 top-1/2 -translate-y-1/2 p-2 bg-purple-600/30 text-white/50 rounded-lg cursor-not-allowed"
            >
              <Send size={16} />
            </button>
          </div>
        </div>

      </div>

      {/* RIGHT: SAMPLE CONTEXT PANEL */}
      {showSample && (
        <div className="shrink-0 w-[450px] border-l border-[#1f1f28] bg-[#0a0a0f] flex flex-col z-20 shadow-2xl overflow-hidden">
          <div className="p-4 border-b border-[#1f1f28] flex items-center justify-between shrink-0 whitespace-nowrap">
            <div className="flex items-center gap-2">
              <Hash size={16} className="text-purple-400" />
              <h3 className="font-semibold text-sm">Sample References</h3>
            </div>
            <button
              onClick={() => setShowSample(false)}
              className="text-muted-foreground hover:text-foreground text-sm"
            >
              Close
            </button>
          </div>

          <div className="flex-1 overflow-y-auto custom-scrollbar p-6 space-y-8">
            <div className="bg-amber-500/10 border border-amber-500/30 rounded-lg p-3 text-[11px] text-amber-200">
              Everything below is sample/illustrative data, not derived from a real analysis.
            </div>

            <div className="space-y-4">
              <div className="flex items-center gap-2">
                <div className="w-1.5 h-1.5 rounded-full bg-success"></div>
                <h4 className="text-xs font-bold uppercase tracking-wider text-muted-foreground">Sample Correlation Trace</h4>
              </div>
              <div className="bg-[#13131a] rounded-lg p-4 border border-[#1f1f28] space-y-3">
                <div className="text-xs font-mono text-muted-foreground bg-[#060b14] border border-[#1f1f28] px-3 py-2 rounded">
                  ID: {SAMPLE_MESSAGE.context.correlationId}
                </div>
                <div className="relative pl-3 space-y-3 before:absolute before:inset-y-0 before:left-1 before:w-px before:bg-[#1f1f28] mt-3">
                  <div className="relative">
                    <div className="absolute -left-[5px] top-1.5 w-1.5 h-1.5 rounded-full bg-blue-500"></div>
                    <p className="text-xs text-muted-foreground">Event: {SAMPLE_MESSAGE.context.events[0]}</p>
                  </div>
                  <div className="relative">
                    <div className="absolute -left-[5px] top-1.5 w-1.5 h-1.5 rounded-full bg-purple-500"></div>
                    <p className="text-xs text-muted-foreground">Rule: {SAMPLE_MESSAGE.context.rules[0]}</p>
                  </div>
                  <div className="relative">
                    <div className="absolute -left-[5px] top-1.5 w-1.5 h-1.5 rounded-full bg-success"></div>
                    <p className="text-xs text-muted-foreground">Action: {SAMPLE_MESSAGE.context.events[1]}</p>
                  </div>
                </div>
              </div>
            </div>

            <div className="space-y-4">
              <h4 className="text-xs font-bold uppercase tracking-wider text-muted-foreground flex items-center gap-2">
                <Box size={14} /> Referenced Twins (sample)
              </h4>
              <div className="space-y-2">
                {SAMPLE_MESSAGE.context.twins.map((t, idx) => (
                  <div key={idx} className="flex items-center justify-between bg-[#13131a] rounded-lg p-3 border border-[#1f1f28]">
                    <div className="flex items-center gap-2">
                      <Cpu size={14} className="text-blue-400" />
                      <span className="text-sm font-medium">{t}</span>
                    </div>
                    <CheckCircle2 size={14} className="text-success" />
                  </div>
                ))}
              </div>
            </div>

            <div className="space-y-4">
              <h4 className="text-xs font-bold uppercase tracking-wider text-muted-foreground flex items-center gap-2">
                <Signal size={14} /> Affected Telemetry (sample)
              </h4>
              <div className="space-y-2">
                {SAMPLE_MESSAGE.context.telemetry.map((t, idx) => (
                  <div key={idx} className="flex items-center justify-between bg-[#13131a] rounded-lg p-3 border border-[#1f1f28]">
                    <span className="text-xs font-mono text-muted-foreground">{t}</span>
                    <span className="text-xs font-bold text-muted-foreground">Sample flag</span>
                  </div>
                ))}
              </div>
            </div>

            <div className="space-y-4">
              <h4 className="text-xs font-bold uppercase tracking-wider text-muted-foreground flex items-center gap-2">
                <Zap size={14} /> Referenced Rule (sample)
              </h4>
              <div className="bg-[#13131a] rounded-lg p-3 border border-[#1f1f28]">
                <span className="text-sm font-medium">{SAMPLE_MESSAGE.context.rules[0]}</span>
              </div>
            </div>
          </div>
        </div>
      )}

    </div>
  );
}
