import { Play, Filter, Zap, MousePointer2 } from 'lucide-react';

export function RulePalette({onAdd}:{onAdd?:(type:string)=>void}) {
  const blocks=[
    {type:'trigger',label:'Event trigger',description:'Start when an event arrives',icon:Play,tone:'#7e73be'},
    {type:'condition',label:'Condition',description:'Check a value or device',icon:Filter,tone:'#bd8130'},
    {type:'action',label:'Action',description:'Choose what happens next',icon:Zap,tone:'#358c6f'},
  ];
  return <aside className="studio-palette bg-card border-r border-border flex flex-col min-h-0 shrink-0" aria-label="Rule building blocks">
    <div className="p-5 border-b border-border"><p className="text-xs font-semibold uppercase tracking-widest text-muted-foreground">Building blocks</p><h2 className="font-semibold text-foreground mt-2">Create your flow</h2><p className="text-xs text-muted-foreground mt-2 leading-relaxed">Click to add, or drag a block onto the canvas.</p></div>
    <div className="flex-1 overflow-y-auto p-3 space-y-3">{blocks.map(({type,label,description,icon:Icon,tone},index)=><button key={type} type="button" draggable onClick={()=>onAdd?.(type)}
      onDragStart={e=>{e.dataTransfer.setData('application/reactflow',type);e.dataTransfer.effectAllowed='move';}}
      className="studio-block w-full text-left p-3 rounded-2xl border border-border bg-background transition-colors" aria-label={`Add ${label.toLowerCase()}`}>
      <span className="flex items-center justify-between mb-3"><span className="w-9 h-9 rounded-xl flex items-center justify-center" style={{background:`${tone}18`,color:tone}}><Icon size={17}/></span><span className="text-xs text-muted-foreground">0{index+1}</span></span>
      <span className="block font-semibold text-sm text-foreground">{label}</span><span className="block text-xs text-muted-foreground mt-1 leading-relaxed">{description}</span>
    </button>)}</div>
    <div className="m-3 rounded-xl border border-border p-3 text-xs text-muted-foreground leading-relaxed"><MousePointer2 size={15} className="mb-2"/>Connect the dots from left to right. Conditions use AND. Branches, timers and AI blocks are not supported.</div>
  </aside>;
}
