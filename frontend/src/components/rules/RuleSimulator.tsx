import { useState, useRef } from 'react';
import type { Node, Edge } from 'reactflow';
import { Play, Code2, CheckCircle, XCircle, RotateCcw, Clock } from 'lucide-react';

interface RuleSimulatorProps {
  nodes: Node[];
  edges: Edge[];
}

export function RuleSimulator({ nodes }: RuleSimulatorProps) {
  const [payload, setPayload] = useState('{\n  "value": 55,\n  "severity": "CRITICAL"\n}');
  const [result, setResult] = useState<{ status: 'IDLE' | 'MATCHED' | 'FAILED', log: string[], execTime?: number }>({ status: 'IDLE', log: [] });
  const logEndRef = useRef<HTMLDivElement>(null);

  const runSimulation = () => {
    const startTime = performance.now();
    let parsed: any;
    try {
      parsed = JSON.parse(payload);
    } catch (e) {
      setResult({ status: 'FAILED', log: ['Invalid JSON payload.'], execTime: 0 });
      return;
    }

    const log: string[] = ['Starting simulation...'];
    
    const triggers = nodes.filter(n => n.type === 'trigger');
    if (triggers.length === 0) {
      const execTime = performance.now() - startTime;
      setResult({ status: 'FAILED', log: [...log, 'No trigger node found.'], execTime });
      return;
    }

    const conditions = nodes.filter(n => n.type === 'condition');
    const actions = nodes.filter(n => n.type === 'action');
    
    let allPassed = true;

    for (const c of conditions) {
      const field = c.data.field as string;
      const operator = c.data.operator as string;
      const expected = c.data.expectedValue as string;

      if (!field || !operator || !expected) {
        log.push(`[SKIP] Incomplete condition node: ${c.data.label || 'Unnamed'}`);
        continue;
      }

      const val = parsed[field.toLowerCase()] || parsed[field];
      
      log.push(`Evaluating [${field}]: payload value = ${val}, expected = ${expected}`);
      
      let passed = false;
      const numVal = parseFloat(val);
      const numExpected = parseFloat(expected);

      switch (operator) {
        case 'EQ': passed = (val == expected); break;
        case 'NE': passed = (val != expected); break;
        case 'GT': passed = (!isNaN(numVal) && !isNaN(numExpected)) ? numVal > numExpected : val > expected; break;
        case 'LT': passed = (!isNaN(numVal) && !isNaN(numExpected)) ? numVal < numExpected : val < expected; break;
        case 'GTE': passed = (!isNaN(numVal) && !isNaN(numExpected)) ? numVal >= numExpected : val >= expected; break;
        case 'LTE': passed = (!isNaN(numVal) && !isNaN(numExpected)) ? numVal <= numExpected : val <= expected; break;
      }

      if (passed) {
        log.push(`✅ Condition passed.`);
      } else {
        log.push(`❌ Condition failed.`);
        allPassed = false;
        break;
      }
    }

    const execTime = performance.now() - startTime;

    if (allPassed) {
      log.push(`All conditions met! Triggering ${actions.length} action(s).`);
      actions.forEach(a => {
        log.push(`⚡ Action triggered: ${a.data.actionType || 'Unnamed Action'}`);
      });
      setResult({ status: 'MATCHED', log, execTime });
    } else {
      log.push('Rule execution stopped.');
      setResult({ status: 'FAILED', log, execTime });
    }
    
    // Auto-scroll
    setTimeout(() => logEndRef.current?.scrollIntoView({ behavior: 'smooth' }), 50);
  };

  const handleReset = () => {
    setResult({ status: 'IDLE', log: [] });
  };

  return (
    <div className="flex h-full animate-in slide-in-from-bottom-2">
      {/* Input */}
      <div className="flex-1 border-r border-border p-4 flex flex-col max-w-sm">
        <div className="flex items-center gap-2 mb-3">
          <Code2 size={16} className="text-muted-foreground" />
          <h3 className="font-semibold text-sm text-foreground">Mock Event Payload</h3>
        </div>
        <textarea 
          value={payload}
          onChange={e => setPayload(e.target.value)}
          className="flex-1 w-full bg-background border border-border rounded-md p-3 font-mono text-xs text-foreground focus:outline-none focus:border-primary resize-none"
        />
        <div className="mt-3 flex justify-between items-center">
          <button 
            onClick={handleReset}
            className="flex items-center gap-2 px-3 py-1.5 rounded bg-muted hover:bg-muted/80 text-foreground text-xs font-medium transition-colors"
          >
            <RotateCcw size={12} /> Reset
          </button>
          <button 
            onClick={runSimulation}
            className="flex items-center gap-2 px-4 py-1.5 rounded bg-blue-500 hover:bg-blue-600 text-white text-xs font-medium transition-colors"
          >
            <Play size={12} /> Run Evaluation
          </button>
        </div>
      </div>

      {/* Output */}
      <div className="flex-1 p-4 flex flex-col bg-muted/10">
        <div className="flex items-center justify-between mb-3">
          <div className="flex items-center gap-4">
            <h3 className="font-semibold text-sm text-foreground">Execution Trace</h3>
            {result.execTime !== undefined && (
              <div className="flex items-center gap-1 text-xs text-muted-foreground">
                <Clock size={12} /> {result.execTime.toFixed(2)}ms
              </div>
            )}
          </div>
          {result.status === 'MATCHED' && (
            <div className="flex items-center gap-1.5 text-xs text-success bg-success/10 px-2 py-0.5 rounded font-semibold">
              <CheckCircle size={12} /> MATCHED
            </div>
          )}
          {result.status === 'FAILED' && (
            <div className="flex items-center gap-1.5 text-xs text-destructive bg-destructive/10 px-2 py-0.5 rounded font-semibold">
              <XCircle size={12} /> FAILED
            </div>
          )}
        </div>
        <div className="flex-1 bg-background border border-border rounded-md p-3 overflow-y-auto font-mono text-xs space-y-1.5 text-muted-foreground">
          {result.log.length === 0 ? (
            <div className="opacity-50 h-full flex items-center justify-center">Run evaluation to see trace.</div>
          ) : (
            <>
              {result.log.map((line, idx) => (
                <div key={idx} className={
                  line.includes('✅') || line.includes('MATCHED') || line.includes('⚡') ? 'text-success' :
                  line.includes('❌') || line.includes('FAILED') ? 'text-destructive' : ''
                }>
                  {line}
                </div>
              ))}
              <div ref={logEndRef} />
            </>
          )}
        </div>
      </div>
    </div>
  );
}
