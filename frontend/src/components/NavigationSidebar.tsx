import { useEffect, useRef, useState } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { ChevronDown, ChevronLeft, ChevronRight, Activity, Settings, X, type LucideIcon } from 'lucide-react';
import { GlassPanel } from './foundation/GlassPanel';
import './NavigationSidebar.css';

type NavGroup={label:string;items:{label:string;path:string;icon:LucideIcon}[]};
type Connection={realtimeStatus:string;backend:string;mqtt:string;database:string};
export function NavigationSidebar({groups,connection,mobileOpen,onMobileClose}:{groups:NavGroup[];connection:Connection;mobileOpen:boolean;onMobileClose:()=>void}){
  const {pathname}=useLocation();
  const active=(path:string)=>path==='/'?pathname==='/':pathname===path||pathname.startsWith(path+'/');
  const [compact,setCompact]=useState(false);
  const [open,setOpen]=useState(()=>new Set([groups[0].label,...groups.filter(g=>g.items.some(i=>active(i.path))).map(g=>g.label)]));
  const [healthOpen,setHealthOpen]=useState(false);
  const aside=useRef<HTMLElement>(null);
  const previousPath=useRef(pathname);
  useEffect(()=>{
    if(previousPath.current!==pathname){
      previousPath.current=pathname;
      setOpen(current=>new Set([...current,...groups.filter(g=>g.items.some(i=>i.path==='/'?pathname==='/':pathname===i.path||pathname.startsWith(i.path+'/'))).map(g=>g.label)]));
    }
  },[pathname,groups]);
  useEffect(()=>{
    if(!mobileOpen)return;
    setCompact(false);
    const previous=document.activeElement as HTMLElement|null;
    aside.current?.querySelector<HTMLButtonElement>('[aria-label="Close navigation"]')?.focus();
    const keyboard=(e:KeyboardEvent)=>{
      if(e.key==='Escape'){e.preventDefault();onMobileClose();}
      if(e.key==='Tab'){
        const focusable=Array.from(aside.current?.querySelectorAll<HTMLElement>('button:not([disabled]),a[href]')??[]).filter(el=>el.getClientRects().length>0);
        const first=focusable[0],last=focusable[focusable.length-1];
        if(e.shiftKey&&document.activeElement===first){e.preventDefault();last?.focus();}
        else if(!e.shiftKey&&document.activeElement===last){e.preventDefault();first?.focus();}
      }
    };
    document.addEventListener('keydown',keyboard);
    return()=>{document.removeEventListener('keydown',keyboard);previous?.focus();};
  },[mobileOpen,onMobileClose]);
  function toggle(label:string){setOpen(current=>{const next=new Set(current);if(next.has(label))next.delete(label);else next.add(label);return next;});}
  const services=[{label:'Backend',value:connection.backend},{label:'MQTT',value:connection.mqtt},{label:'Database',value:connection.database},{label:'Realtime',value:connection.realtimeStatus}];
  const healthy=(value:string)=>['online','connected'].includes(value.toLowerCase());
  const online=services.filter(s=>healthy(s.value)).length;
  return <>
    {mobileOpen&&<button className="nexus-nav-backdrop" aria-label="Close navigation overlay" onClick={onMobileClose}/>}
    <aside ref={aside} className={`nexus-sidebar ${compact?'is-compact':''} ${mobileOpen?'is-mobile-open':''}`} aria-label="Application navigation" role={mobileOpen?'dialog':undefined} aria-modal={mobileOpen?true:undefined}>
      <GlassPanel level="elevated" radius="panel-lg" className="nexus-sidebar-panel">
        <div className="nexus-nav-heading"><div className="nexus-nav-label"><span className="nexus-nav-eyebrow">CONTROL CENTRE</span><strong>Navigation</strong></div>
          <button type="button" className="nexus-nav-collapse" aria-label={compact?'Expand navigation':'Collapse navigation'} onClick={()=>setCompact(!compact)}>{compact?<ChevronRight size={17}/>:<ChevronLeft size={17}/>}</button>
          <button type="button" className="nexus-nav-mobile-close" aria-label="Close navigation" onClick={onMobileClose}><X size={19}/></button>
        </div>
        <nav aria-label="Main navigation" className="nexus-nav-scroll">
          {groups.filter(g=>g.label!=='System').map((group,index)=>{
            const expanded=open.has(group.label),selected=group.items.some(i=>active(i.path)),GroupIcon=group.items[0].icon;
            return <div className="nexus-nav-group" key={group.label}>
              <button type="button" className={`nexus-nav-group-toggle ${selected?'has-active-route':''}`} aria-expanded={!compact&&expanded} aria-controls={!compact&&expanded?`navigation-group-${index}`:undefined} title={compact?group.label:undefined}
                onClick={()=>{if(compact){setCompact(false);setOpen(new Set([group.label]));}else toggle(group.label);}}>
                <GroupIcon size={16}/><span className="nexus-nav-label">{group.label}</span><ChevronDown size={14} className={`nexus-nav-chevron ${expanded?'is-open':''}`}/>
              </button>
              {!compact&&expanded&&<div id={`navigation-group-${index}`} className="nexus-nav-links">{group.items.map(item=><Link key={item.path} to={item.path} aria-current={active(item.path)?'page':undefined} onClick={onMobileClose} className="nexus-nav-link"><item.icon size={17}/><span>{item.label}</span>{active(item.path)&&<span className="nexus-nav-active-dot" aria-hidden="true"/>}</Link>)}</div>}
            </div>;
          })}
        </nav>
        <footer className="nexus-nav-footer">
          <Link to="/settings" className="nexus-nav-link" title={compact?'Settings':undefined} aria-label="Settings" aria-current={active('/settings')?'page':undefined} onClick={onMobileClose}><Settings size={17}/><span className="nexus-nav-label">Settings</span></Link>
          <button type="button" className="nexus-nav-health" aria-label="Connection details" aria-expanded={!compact&&healthOpen} onClick={()=>{if(compact){setCompact(false);setHealthOpen(true);}else setHealthOpen(!healthOpen);}}>
            <Activity size={16} style={{color:online===4?'var(--success)':'var(--warning)'}}/><span className="nexus-nav-label">Connections <span className="nexus-nav-count">{online}/4</span></span><ChevronDown size={13} className="nexus-nav-chevron"/>
          </button>
          {!compact&&healthOpen&&<dl className="nexus-nav-services">{services.map(s=><div key={s.label}><dt>{s.label}</dt><dd style={{color:healthy(s.value)?'var(--success)':'var(--warning)'}}>{s.value}</dd></div>)}</dl>}
        </footer>
      </GlassPanel>
    </aside>
  </>;
}
