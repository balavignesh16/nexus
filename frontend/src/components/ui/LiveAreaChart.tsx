import { Maximize2 } from 'lucide-react';

interface LiveAreaChartProps {
  title: string;
  data: any[]; // In a real implementation this would map to standard D3/recharts format
  color?: string; // Hex color for the main line and gradient
}

// eslint-disable-next-line @typescript-eslint/no-unused-vars
export function LiveAreaChart({ title, color = '#3b82f6' }: LiveAreaChartProps) {
  return (
    <div className="bg-[#12121a] border border-[#1f1f28] rounded-xl p-4 flex flex-col shadow-sm relative group overflow-hidden h-full min-h-[300px]">
      <div className="flex items-center justify-between mb-4 z-10">
        <h3 className="text-sm font-semibold text-foreground">{title}</h3>
        <div className="flex items-center gap-3">
          <select className="bg-muted/30 border border-border/30 rounded text-xs px-2 py-1 text-muted-foreground focus:outline-none focus:border-primary cursor-pointer">
            <option>5 Minutes</option>
            <option>15 Minutes</option>
            <option>1 Hour</option>
          </select>
          <div className="flex rounded border border-[#1f1f28] overflow-hidden">
            <button className="bg-blue-500/20 text-blue-400 px-3 py-1 text-xs font-medium cursor-pointer">Live</button>
            <button className="bg-transparent hover:bg-muted/50 text-muted-foreground px-3 py-1 text-xs font-medium transition-colors cursor-pointer">Pause</button>
          </div>
          <button className="p-1.5 text-muted-foreground hover:text-foreground hover:bg-muted/50 rounded transition-colors cursor-pointer">
            <Maximize2 size={14} />
          </button>
        </div>
      </div>
      
      <div className="flex-1 w-full relative bg-[#0a0a0f]/50 rounded-lg border border-[#1f1f28] overflow-hidden">
        {/* Ambient Background Glow */}
        <div className="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 w-[80%] h-[80%] bg-blue-500/10 blur-[100px] rounded-full pointer-events-none" />
        
        {/* Mock Detailed Chart */}
        <div className="absolute inset-0 pt-4 px-2 pb-6">
          <svg viewBox="0 0 1000 300" className="w-full h-full preserve-3d" preserveAspectRatio="none">
            <defs>
              <linearGradient id={`chartGradient-${title.replace(/\s+/g, '')}`} x1="0" y1="0" x2="0" y2="1">
                <stop offset="0%" stopColor={color} stopOpacity="0.25" />
                <stop offset="100%" stopColor={color} stopOpacity="0" />
              </linearGradient>
              <pattern id="grid" width="40" height="40" patternUnits="userSpaceOnUse">
                <path d="M 40 0 L 0 0 0 40" fill="none" stroke="rgba(255,255,255,0.02)" strokeWidth="1"/>
              </pattern>
            </defs>
            <rect width="1000" height="300" fill="url(#grid)" />
            <path 
              d="M0,150 Q50,130 100,160 T200,120 T300,140 T400,110 T500,130 T600,160 T700,140 T800,170 T900,150 T1000,140" 
              fill="none" 
              stroke={color} 
              strokeWidth="3" 
              strokeLinecap="round" 
              className="drop-shadow-[0_0_8px_rgba(59,130,246,0.6)]"
            />
            <path 
              d="M0,300 L0,150 Q50,130 100,160 T200,120 T300,140 T400,110 T500,130 T600,160 T700,140 T800,170 T900,150 T1000,140 L1000,300 Z" 
              fill={`url(#chartGradient-${title.replace(/\s+/g, '')})`} 
            />
          </svg>
        </div>
        
        {/* Axis Mock */}
        <div className="absolute inset-y-0 left-0 w-12 border-r border-[#1f1f28]/50 bg-background/30 backdrop-blur-sm flex flex-col justify-between py-4 px-2 text-[9px] text-muted-foreground items-end z-10 pointer-events-none">
          <span>100</span>
          <span>75</span>
          <span>50</span>
          <span>25</span>
          <span>0</span>
        </div>
        <div className="absolute bottom-0 inset-x-0 h-6 border-t border-[#1f1f28]/50 bg-background/30 backdrop-blur-sm flex justify-between px-16 items-center text-[9px] text-muted-foreground z-10 pointer-events-none">
          <span>T-5</span>
          <span>T-4</span>
          <span>T-3</span>
          <span>T-2</span>
          <span>T-1</span>
          <span>Now</span>
        </div>
      </div>
    </div>
  );
}
