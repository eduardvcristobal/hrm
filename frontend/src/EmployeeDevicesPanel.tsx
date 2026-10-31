import {useState} from 'react';
type Device={id:string;employeeId:string;displayName:string;status:string;credentialExpiresAt:string|null};
type Activation={deviceId:string;credential:string;credentialExpiresAt:string;classification:string};
const key='apexHrm.accessToken';
async function call<T>(path:string,options:RequestInit={}):Promise<T>{
  const token=sessionStorage.getItem(key);
  if(!token)throw new Error('Sign in as a verified employee with MFA.');
  const res=await fetch('/api/v1/employee-self-service'+path,{
    ...options,headers:{Authorization:`Bearer ${token}`,...(options.body?{'Content-Type':'application/json'}:{}),...options.headers}
  });
  if(!res.ok)throw new Error(`Device action refused (HTTP ${res.status}).`);
  return await res.json() as T;
}
export default function EmployeeDevicesPanel(){
  const [devices,setDevices]=useState<Device[]>([]);
  const [displayName,setDisplayName]=useState('');
  const [secret,setSecret]=useState<string|null>(null);
  const [error,setError]=useState('');
  const [notice,setNotice]=useState('');
  const [busy,setBusy]=useState(false);
  async function run(work:()=>Promise<void>){
    setBusy(true);setError('');setNotice('');setSecret(null);
    try{await work();}catch(err){setError(err instanceof Error?err.message:'Device operation failed');}
    finally{setBusy(false);}
  }
  async function refresh(){setDevices(await call<Device[]>('/devices'));}
  function requestDevice(){
    if(!window.confirm('Request a device record for future attendance certification? This does not turn on clock-in.'))return;
    void run(async()=>{
      await call<Device>('/devices',{method:'POST',body:JSON.stringify({displayName})});
      setDisplayName('');await refresh();setNotice('Device requested. Independent HR approval is required.');
    });
  }
  function activate(id:string){
    if(!window.confirm('Show a one-time device secret? Never share or store it in an unsecured note. Device clock-in remains disabled.'))return;
    void run(async()=>{
      const result=await call<Activation>(`/devices/${encodeURIComponent(id)}/activate`,{method:'POST'});
      setSecret(result.credential);
      await refresh();
      setNotice('Device secret issued once. This does not enable attendance capture.');
    });
  }
  function revoke(id:string){
    if(!window.confirm('Revoke this device and invalidate its credential immediately?'))return;
    void run(async()=>{
      await call<Device>(`/devices/${encodeURIComponent(id)}/revoke`,{method:'POST'});
      await refresh();setNotice('Device revoked.');
    });
  }
  return <section className="timekeeping-card"><h2>My devices — enrollment only</h2>
    <p>This is a security-registration preview. Every device requires independent HR approval. Clock-in/out, biometrics and location capture remain disabled.</p>
    {error&&<p role="alert">{error}</p>}{notice&&<p role="status">{notice}</p>}
    <label>Device description<input aria-label="My device description" value={displayName} maxLength={80} onChange={e=>setDisplayName(e.target.value)} placeholder="My work laptop"/></label>
    <div className="timekeeping-actions">
      <button type="button" disabled={busy||displayName.trim().length<3} onClick={requestDevice}>Request device enrollment</button>
      <button type="button" disabled={busy} onClick={()=>void run(refresh)}>Refresh my devices</button>
    </div>
    {secret&&<div><p><strong>One-time device credential</strong> (shown once; do not reuse on other devices):</p>
      <code style={{overflowWrap:'anywhere'}}>{secret}</code>
      <p>Store it only in an approved secure credential store. It cannot be retrieved later.</p>
      <button type="button" onClick={()=>setSecret(null)}>Dismiss secret</button>
    </div>}
    {devices.length>0&&<ul>{devices.map(d=><li key={d.id}>
      {d.displayName} — <strong>{d.status}</strong>
      {d.credentialExpiresAt&&<span> (credential expires {d.credentialExpiresAt})</span>}
      {d.status==='APPROVED'&&<button type="button" disabled={busy} onClick={()=>activate(d.id)}>Activate once</button>}
      {['PENDING','APPROVED','ACTIVE'].includes(d.status)&&<button type="button" disabled={busy} onClick={()=>revoke(d.id)}>Revoke</button>}
    </li>)}</ul>}
  </section>;
}
