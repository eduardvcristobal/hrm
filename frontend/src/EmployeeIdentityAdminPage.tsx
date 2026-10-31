import { useState } from 'react';
import { Link } from 'react-router-dom';
import './timekeeping.css';

type LinkRecord={id:string;employeeId:string;identityId:string};
const tokenKey='apexHrm.accessToken';
async function api<T>(path:string,options:RequestInit={}):Promise<T>{
  const token=sessionStorage.getItem(tokenKey)||localStorage.getItem(tokenKey);
  if(!token)throw new Error('Sign in as a tenant administrator and complete MFA verification.');
  const response=await fetch('/api/v1/employee-self-service/admin'+path,{
    ...options,headers:{Authorization:`Bearer ${token}`,...(options.body?{'Content-Type':'application/json'}:{}),...options.headers}
  });
  if(!response.ok)throw new Error(`Employee access request denied (HTTP ${response.status}).`);
  return response.json() as Promise<T>;
}
export default function EmployeeIdentityAdminPage(){
  const [employeeId,setEmployeeId]=useState('');
  const [identityId,setIdentityId]=useState('');
  const [links,setLinks]=useState<LinkRecord[]>([]);
  const [error,setError]=useState('');
  const [notice,setNotice]=useState('');
  const [busy,setBusy]=useState(false);
  async function run(task:()=>Promise<void>){
    setBusy(true);setError('');setNotice('');
    try{await task();}catch(e){setError(e instanceof Error?e.message:'Employee access request failed');}
    finally{setBusy(false);}
  }
  async function refresh(){
    if(!employeeId)throw new Error('Enter an employee UUID.');
    setLinks(await api<LinkRecord[]>(`/identity-links?employeeId=${encodeURIComponent(employeeId)}`));
  }
  function linkEmployee(){
    if(!employeeId||!identityId){setError('Employee and identity UUIDs are required.');return;}
    if(!window.confirm('Confirm the employee identity was verified through HR onboarding. This one-to-one link controls sensitive employee self-service access.'))return;
    void run(async()=>{
      await api<LinkRecord>('/identity-links',{method:'POST',body:JSON.stringify({employeeId,identityId})});
      await refresh();setNotice('Verified employee identity linked and audited.');
    });
  }
  function revoke(linkId:string){
    if(!window.confirm('Immediately revoke this employee self-service link? The account will lose employee-record access, and automatic reassignment will remain blocked.'))return;
    void run(async()=>{
      await api<LinkRecord>(`/identity-links/${encodeURIComponent(linkId)}/revoke`,{method:'POST'});
      await refresh();setNotice('Employee self-service access revoked and audited.');
    });
  }
  return <main className="timekeeping-root">
    <header className="timekeeping-top"><div><p className="timekeeping-eyebrow">Apex People / M2.9</p><h1>Employee Access Linking</h1><p>HR-controlled verified identity binding and revocation.</p></div><div><Link to="/my-timekeeping">My Timekeeping</Link> &nbsp; <Link to="/timekeeping">Timekeeping</Link> &nbsp; <Link to="/dashboard">Dashboard</Link></div></header>
    <div className="timekeeping-hint">Only MFA-verified tenant administrators can link or revoke identities. Verify the employee in your HR onboarding process first. Device clock-in, geolocation and biometrics remain disabled; no automatic link reassignment is supported.</div>
    {error&&<p role="alert" className="timekeeping-alert">{error}</p>}
    {notice&&<p role="status" className="timekeeping-alert">{notice}</p>}
    <section className="timekeeping-card"><h2>Verified employee account</h2><div className="timekeeping-grid">
      <label>Employee UUID<input aria-label="Employee UUID" placeholder="Employee ID" value={employeeId} onChange={e=>setEmployeeId(e.target.value)}/></label>
      <label>Enabled employee-role identity UUID<input aria-label="Identity UUID" placeholder="Identity ID" value={identityId} onChange={e=>setIdentityId(e.target.value)}/></label>
    </div><div className="timekeeping-actions"><button type="button" disabled={busy||!employeeId||!identityId} onClick={linkEmployee}>Link verified identity</button><button type="button" disabled={busy||!employeeId} onClick={()=>void run(refresh)}>Find active link</button></div></section>
    <section className="timekeeping-card"><h2>Active self-service access</h2>
      {links.length===0?<p>No active link loaded for this employee.</p>:<ul>{links.map(link=><li key={link.id}>Identity: <code>{link.identityId}</code> <button type="button" disabled={busy} onClick={()=>revoke(link.id)}>Revoke access</button></li>)}</ul>}
      <p>Revoked bindings remain preserved for audit and cannot be silently reused.</p>
    </section>
  </main>;
}
