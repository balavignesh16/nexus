import { useState } from 'react';
import { Cpu, Send, Box, Sparkles, Hash, Signal, Zap, AlertTriangle, FileJson, CheckCircle2, ChevronRight } from 'lucide-react';
import { motion, AnimatePresence } from 'framer-motion';

type Message = {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  context?: {
    twins?: string[];
    telemetry?: string[];
    events?: string[];
    rules?: string[];
    correlationId?: string;
  };
};

export default function AiInsightsPage() {
  const [input, setInput] = useState('');
  const [messages, setMessages] = useState<Message[]>([
    {
      id: '1',
      role: 'assistant',
      content: 'I am the Nexus Operations Copilot. I can help you analyze telemetry, trace correlation IDs across events, predict failures, and suggest rule optimizations. What would you like to investigate?'
    }
  ]);
  const [isTyping, setIsTyping] = useState(false);
  const [selectedContext, setSelectedContext] = useState<Message['context'] | null>(null);

  const handleSend = () => {
    if (!input.trim()) return;

    const userMsg: Message = { id: Date.now().toString(), role: 'user', content: input };
    setMessages(prev => [...prev, userMsg]);
    setInput('');
    setIsTyping(true);

    // Simulate AI response with context
    setTimeout(() => {
      const aiMsg: Message = {
        id: (Date.now() + 1).toString(),
        role: 'assistant',
        content: `Based on my analysis of the recent incidents, the root cause appears to be a sudden temperature spike in **HVAC Unit 1**, which triggered the **Temperature Correction Rule**. 

This action successfully updated the twin's desired state, but the physical device took 450ms to acknowledge the command due to degraded signal strength. I recommend inspecting the network gateway on **Floor 2** and reviewing the threshold limits in the rule configuration.`,
        context: {
          twins: ['hvac-1', 'gateway-floor2'],
          telemetry: ['hvac-1:temperature', 'hvac-1:signalStrength'],
          events: ['Temperature High Alert', 'Action Dispatched'],
          rules: ['Rule-TempCorrection'],
          correlationId: 'corr-8f92-42a1-b38d-19283746'
        }
      };
      setMessages(prev => [...prev, aiMsg]);
      setSelectedContext(aiMsg.context!);
      setIsTyping(false);
    }, 1500);
  };

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

        {/* Chat History */}
        <div className="flex-1 overflow-y-auto custom-scrollbar p-6 space-y-6">
          <AnimatePresence>
            {messages.map(msg => (
              <motion.div 
                key={msg.id}
                initial={{ opacity: 0, y: 10 }}
                animate={{ opacity: 1, y: 0 }}
                className={`flex gap-4 max-w-4xl ${msg.role === 'user' ? 'ml-auto flex-row-reverse' : 'mr-auto'}`}
              >
                <div className={`shrink-0 w-8 h-8 rounded-lg flex items-center justify-center border ${
                  msg.role === 'user' 
                    ? 'bg-blue-500/10 border-blue-500/30 text-blue-400' 
                    : 'bg-purple-500/10 border-purple-500/30 text-purple-400'
                }`}>
                  {msg.role === 'user' ? <div className="text-xs font-bold">AD</div> : <Sparkles size={16} />}
                </div>
                
                <div className={`flex flex-col gap-2 ${msg.role === 'user' ? 'items-end' : 'items-start'}`}>
                  <div className={`p-4 rounded-2xl text-sm leading-relaxed whitespace-pre-wrap shadow-sm border ${
                    msg.role === 'user' 
                      ? 'bg-blue-500/5 text-blue-100 border-blue-500/20 rounded-tr-sm' 
                      : 'bg-card/80 text-foreground border-border/50 rounded-tl-sm'
                  }`}>
                    {msg.content}
                  </div>
                  
                  {msg.context && (
                    <button 
                      onClick={() => setSelectedContext(msg.context!)}
                      className="flex items-center gap-2 px-3 py-1.5 bg-[#13131a] hover:bg-muted/50 border border-[#1f1f28] rounded-full text-xs text-muted-foreground hover:text-foreground transition-colors"
                    >
                      <Hash size={12} className="text-purple-400" />
                      View 7 Linked References
                      <ChevronRight size={12} />
                    </button>
                  )}
                </div>
              </motion.div>
            ))}
          </AnimatePresence>

          {isTyping && (
            <motion.div 
              initial={{ opacity: 0, y: 10 }}
              animate={{ opacity: 1, y: 0 }}
              className="flex gap-4 max-w-3xl mr-auto"
            >
              <div className="shrink-0 w-8 h-8 rounded-lg bg-purple-500/10 border border-purple-500/30 flex items-center justify-center">
                <Sparkles size={16} className="text-purple-400" />
              </div>
              <div className="p-4 rounded-2xl bg-card/80 border border-border/50 rounded-tl-sm flex items-center gap-2">
                <div className="w-1.5 h-1.5 bg-purple-400 rounded-full animate-bounce" style={{ animationDelay: '0ms' }} />
                <div className="w-1.5 h-1.5 bg-purple-400 rounded-full animate-bounce" style={{ animationDelay: '150ms' }} />
                <div className="w-1.5 h-1.5 bg-purple-400 rounded-full animate-bounce" style={{ animationDelay: '300ms' }} />
              </div>
            </motion.div>
          )}
        </div>

        {/* Input Area */}
        <div className="shrink-0 p-6 bg-[#060b14] border-t border-[#1f1f28]">
          <div className="max-w-4xl mx-auto relative group">
            <textarea
              value={input}
              onChange={e => setInput(e.target.value)}
              onKeyDown={e => {
                if (e.key === 'Enter' && !e.shiftKey) {
                  e.preventDefault();
                  handleSend();
                }
              }}
              placeholder="Ask Copilot about telemetry anomalies, rule failures, or correlation traces..."
              className="w-full bg-[#13131a] border border-[#1f1f28] rounded-xl pl-4 pr-14 py-4 text-sm text-foreground focus:outline-none focus:border-purple-500/50 focus:ring-1 focus:ring-purple-500/50 resize-none overflow-hidden h-14 group-hover:border-[#2d2d3d] transition-colors"
              rows={1}
            />
            <button 
              onClick={handleSend}
              disabled={!input.trim() || isTyping}
              className="absolute right-2 top-1/2 -translate-y-1/2 p-2 bg-purple-600 hover:bg-purple-700 disabled:bg-purple-600/50 disabled:cursor-not-allowed text-white rounded-lg transition-colors"
            >
              <Send size={16} />
            </button>
          </div>
          <div className="max-w-4xl mx-auto flex items-center gap-4 mt-3 text-xs text-muted-foreground">
            <span className="flex items-center gap-1.5 px-2 py-1 bg-[#13131a] border border-[#1f1f28] rounded cursor-pointer hover:text-foreground">
              <Sparkles size={12} className="text-purple-400" /> Explain correlation {`{id}`}
            </span>
            <span className="flex items-center gap-1.5 px-2 py-1 bg-[#13131a] border border-[#1f1f28] rounded cursor-pointer hover:text-foreground">
              <Box size={12} className="text-blue-400" /> Compare twins
            </span>
            <span className="flex items-center gap-1.5 px-2 py-1 bg-[#13131a] border border-[#1f1f28] rounded cursor-pointer hover:text-foreground">
              <AlertTriangle size={12} className="text-warning" /> Predict failures
            </span>
          </div>
        </div>

      </div>

      {/* RIGHT: CONTEXT PANEL */}
      <AnimatePresence>
        {selectedContext && (
          <motion.div 
            initial={{ width: 0, opacity: 0 }}
            animate={{ width: 450, opacity: 1 }}
            exit={{ width: 0, opacity: 0 }}
            className="shrink-0 border-l border-[#1f1f28] bg-[#0a0a0f] flex flex-col z-20 shadow-2xl overflow-hidden"
          >
            <div className="p-4 border-b border-[#1f1f28] flex items-center justify-between shrink-0 whitespace-nowrap">
              <div className="flex items-center gap-2">
                <Hash size={16} className="text-purple-400" />
                <h3 className="font-semibold text-sm">Copilot References</h3>
              </div>
              <button 
                onClick={() => setSelectedContext(null)}
                className="text-muted-foreground hover:text-foreground text-sm"
              >
                Close
              </button>
            </div>
            
            <div className="flex-1 overflow-y-auto custom-scrollbar p-6 space-y-8">
              
              <div className="space-y-4">
                <div className="flex items-center gap-2">
                  <div className="w-1.5 h-1.5 rounded-full bg-success"></div>
                  <h4 className="text-xs font-bold uppercase tracking-wider text-muted-foreground">High Confidence Evidence</h4>
                </div>
                
                {selectedContext.correlationId && (
                  <div className="bg-[#13131a] rounded-lg p-4 border border-[#1f1f28] space-y-3">
                    <div className="flex items-center gap-2 mb-2 text-xs font-bold text-foreground">
                      <Hash size={14} className="text-blue-400" /> Correlation Trace
                    </div>
                    <div className="text-xs font-mono text-muted-foreground bg-[#060b14] border border-[#1f1f28] px-3 py-2 rounded">
                      ID: {selectedContext.correlationId}
                    </div>
                    
                    <div className="relative pl-3 space-y-3 before:absolute before:inset-y-0 before:left-1 before:w-px before:bg-[#1f1f28] mt-3">
                      <div className="relative">
                        <div className="absolute -left-[5px] top-1.5 w-1.5 h-1.5 rounded-full bg-blue-500"></div>
                        <p className="text-xs text-muted-foreground">Event: {selectedContext.events?.[0]}</p>
                      </div>
                      <div className="relative">
                        <div className="absolute -left-[5px] top-1.5 w-1.5 h-1.5 rounded-full bg-purple-500"></div>
                        <p className="text-xs text-muted-foreground">Rule: {selectedContext.rules?.[0]}</p>
                      </div>
                      <div className="relative">
                        <div className="absolute -left-[5px] top-1.5 w-1.5 h-1.5 rounded-full bg-success"></div>
                        <p className="text-xs text-muted-foreground">Action: {selectedContext.events?.[1]}</p>
                      </div>
                    </div>
                  </div>
                )}
              </div>

              <div className="space-y-4">
                <h4 className="text-xs font-bold uppercase tracking-wider text-muted-foreground flex items-center gap-2">
                  <Box size={14} /> Referenced Twins
                </h4>
                <div className="space-y-2">
                  {selectedContext.twins?.map((t, idx) => (
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
                  <Signal size={14} /> Affected Telemetry
                </h4>
                <div className="space-y-2">
                  {selectedContext.telemetry?.map((t, idx) => (
                    <div key={idx} className="flex items-center justify-between bg-[#13131a] rounded-lg p-3 border border-[#1f1f28]">
                      <span className="text-xs font-mono text-muted-foreground">{t}</span>
                      <span className="text-xs font-bold text-destructive">Anomaly Detected</span>
                    </div>
                  ))}
                </div>
              </div>

              <div className="space-y-4">
                <h4 className="text-xs font-bold uppercase tracking-wider text-muted-foreground flex items-center gap-2">
                  <FileJson size={14} /> Executed Rules
                </h4>
                <div className="bg-[#13131a] rounded-lg p-3 border border-[#1f1f28]">
                  <div className="flex items-center gap-2 mb-2">
                    <Zap size={14} className="text-purple-400" />
                    <span className="text-sm font-medium">{selectedContext.rules?.[0]}</span>
                  </div>
                  <div className="text-[10px] font-mono text-muted-foreground bg-[#060b14] border border-[#1f1f28] p-2 rounded">
                    IF temperature &gt; 25 THEN EXECUTE hvac-cool
                  </div>
                </div>
              </div>

            </div>
          </motion.div>
        )}
      </AnimatePresence>

    </div>
  );
}
