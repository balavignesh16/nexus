import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { siteApi } from '../api/siteApi';
import { buildingApi } from '../api/buildingApi';
import { spaceApi } from '../api/spaceApi';
import type { Site, Building, Space } from '../types/models';
import { Button } from '../components/foundation/Button';
import { Input, Select } from '../components/foundation/Input';

export default function InfrastructureManagePage() {
  const cache=useQueryClient();
  const [sites,setSites]=useState<Site[]>([]), [buildings,setBuildings]=useState<Building[]>([]), [spaces,setSpaces]=useState<Space[]>([]);
  const [site,setSite]=useState(''), [building,setBuilding]=useState('');
  const [siteName,setSiteName]=useState(''), [buildingName,setBuildingName]=useState(''), [spaceName,setSpaceName]=useState('');
  const [error,setError]=useState(''), [busy,setBusy]=useState(false), [notice,setNotice]=useState('');
  useEffect(()=>{siteApi.getSites().then(setSites).catch(e=>setError(String(e)));},[]);
  useEffect(()=>{let active=true; if(site) buildingApi.getBuildingsBySiteId(site).then(v=>{if(active)setBuildings(v);}).catch(e=>setError(String(e))); return()=>{active=false;};},[site]);
  useEffect(()=>{let active=true; if(building) spaceApi.getSpacesByBuildingId(building).then(v=>{if(active)setSpaces(v);}).catch(e=>setError(String(e))); return()=>{active=false;};},[building]);
  async function create(kind:'site'|'building'|'space') {
    setBusy(true);setError('');setNotice('');
    try {
      if(kind==='site') {const s=await siteApi.createSite({name:siteName.trim()});setSites(await siteApi.getSites());setSite(s.id);setBuilding('');setBuildings([]);setSpaces([]);setSiteName('');}
      if(kind==='building') {const b=await buildingApi.createBuilding(site,{name:buildingName.trim()});setBuildings(await buildingApi.getBuildingsBySiteId(site));setBuilding(b.id);setSpaces([]);setBuildingName('');}
      if(kind==='space') {await spaceApi.createSpace(building,{name:spaceName.trim(),spaceType:'ROOM'});setSpaces(await spaceApi.getSpacesByBuildingId(building));setSpaceName('');}
      await cache.invalidateQueries();setNotice(`${kind} created and saved to NEXUS.`);
    } catch(e){setError(e instanceof Error?e.message:String(e));} finally{setBusy(false);}
  }
  return <main className="h-full overflow-auto p-8 text-foundation-fg" style={{backgroundColor:'var(--foundation-bg)'}}><div className="max-w-4xl mx-auto space-y-6">
    <h1 className="text-2xl font-semibold">Manage Infrastructure</h1>
    <p>Create Site → Building → Space. Devices belong to a space within the selected building.</p>
    <div className="flex gap-4"><Link to="/explorer">Open Explorer</Link><Link to="/framework/onboarding">Onboard device into a building</Link><Link to="/simulation">Three ESP simulation</Link></div>
    <section className="rounded-2xl border border-foundation-border p-5 space-y-3"><h2>1. Site</h2>
      <label>Selected site<Select value={site} onChange={e=>{setSite(e.target.value);setBuilding('');setBuildings([]);setSpaces([]);}}><option value="">Choose site</option>{sites.map(s=><option key={s.id} value={s.id}>{s.name}</option>)}</Select></label>
      <label>New site name<Input value={siteName} onChange={e=>setSiteName(e.target.value)} /></label><Button disabled={busy||!siteName.trim()} onClick={()=>void create('site')}>Create site</Button>
    </section>
    <section className="rounded-2xl border border-foundation-border p-5 space-y-3"><h2>2. Building</h2>
      <label>Selected building<Select value={building} onChange={e=>{setBuilding(e.target.value);setSpaces([]);}}><option value="">Choose building</option>{buildings.map(b=><option key={b.id} value={b.id}>{b.name}</option>)}</Select></label>
      <label>New building name<Input value={buildingName} onChange={e=>setBuildingName(e.target.value)} /></label><Button disabled={busy||!site||!buildingName.trim()} onClick={()=>void create('building')}>Create building</Button>
    </section>
    <section className="rounded-2xl border border-foundation-border p-5 space-y-3"><h2>3. Space / room</h2>
      <ul>{spaces.map(s=><li key={s.id}>{s.name}</li>)}</ul>
      <label>New room name<Input value={spaceName} onChange={e=>setSpaceName(e.target.value)} /></label><Button disabled={busy||!building||!spaceName.trim()} onClick={()=>void create('space')}>Create room</Button>
    </section>
    {notice&&<p role="status">{notice}</p>}{error&&<p role="alert" className="text-red-500">{error}</p>}
  </div></main>;
}
