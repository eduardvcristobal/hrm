import { useState } from 'react';
import { Link } from 'react-router-dom';
import './timekeeping.css';

type Summary={
  attendanceSessions:number;rawClosedMinutes:number;openSessions:number;
  pendingCorrections:number;pendingLeaveRequests:number;approvedLeaveRequests:number;
  unconsumedApprovedLeave:number;calendarEvents:number;blockerCodes:string[];
  sourceFingerprint:string
};
type Preview={employeeId:string;from:string;to:string;timezone:string;summary:Summary;payrollReady:boolean;classification:string};
type Package={id:string;status:string;from:string;to:string;timezone:string;summary:Summary;createdBy:string;reviewedBy:string|null;payrollReady:boolean;classification:string};
const tokenKey='apexHrm.accessToken';
async function api<T>(path:string,options:RequestInit={}):Promise<T>{
  const token=sessionStorage.getItem(tokenKey)||localStorage.getItem(tokenKey);
  if(!token)throw new Error('Sign in and verify MFA to access payroll reconciliation.');
  const response=await fetch('/api/v1/timekeeping'+path,{
    ...options,headers:{Authorization:`Bearer ${token}`,...options.headers}
  });
  if(!response.ok){
    let detail='';
    try{const x=await response.json() as {detail?:string;message?:string};detail=x.detail||x.message||'';}catch{/* handled below */}
    throw new Error(detail||`Reconciliation rejected (HTTP ${response.status}).`);
  }
  return response.json() as Promise<T>;
}
export default function PayrollReconciliationPage(){
  const [employeeId,setEmployeeId]=useState('');
  const [from,setFrom]=useState('2026-10-15');
  const [to,setTo]=useState('2026-11-13');
  const [timezone,setTimezone]=useState('Asia/Manila');
  const [preview,setPreview]=useState<Preview|null>(null);
  const [packages,setPackages]=useState<Package[]>([]);
  const [busy,setBusy]=useState(false);
  const [error,setError]=useState('');
  const [notice,setNotice]=useState('');
  function base(){
    if(!employeeId)throw new Error('Enter an employee UUID first.');
    return `/employees/${encodeURIComponent(employeeId)}/payroll-reconciliation`;
  }
  async function run(callback:()=>Promise<void>){
    setBusy(true);setError('');setNotice('');
    try{await callback();}catch(err){setError(err instanceof Error?err.message:'Reconciliation request failed');}
    finally{setBusy(false);}
  }
  function params(){return new URLSearchParams({from,to,timezone}).toString();}
  async function refreshPackages(){setPackages(await api<Package[]>(base()+'/packages'));}
  function getPreview(){void run(async()=>{
    setPreview(await api<Preview>(base()+'/preview?'+params()));
    setNotice('Source preview loaded. These figures do not authorize payroll.');
  });}
  function prepare(){
    if(!window.confirm('Create an internal reconciliation snapshot? No payroll or wage calculation will occur.'))return;
    void run(async()=>{
      await api<Package>(base()+'/packages?'+params(),{method:'POST'});
      await refreshPackages();
      setNotice('Internal snapshot saved with source fingerprint and blocker list.');
    });
  }
  function review(id:string){
    if(!window.confirm('A distinct MFA-verified reviewer must confirm unchanged source evidence. This does not authorize payroll.'))return;
    void run(async()=>{
      await api<Package>(base()+`/packages/${encodeURIComponent(id)}/review`,{method:'POST'});
      await refreshPackages();
      setNotice('Internal-only review recorded. This is not payroll approval.');
    });
  }
  return <main className="timekeeping-root">
    <header className="timekeeping-top"><div><p className="timekeeping-eyebrow">Apex People / M2.7</p><h1>Attendance Reconciliation</h1><p>Internal evidence snapshots for future payroll integration.</p></div><div><Link to="/timekeeping">Timekeeping</Link> &nbsp; <Link to="/leave">Leave</Link> &nbsp; <Link to="/calendar">Calendar</Link> &nbsp; <Link to="/dashboard">Dashboard</Link></div></header>
    <div className="timekeeping-hint"><strong>Not a payroll run.</strong> Raw attendance minutes are not payable hours. Internal reviews cannot authorize wages, holiday premiums, statutory deductions or export to payroll. Use synthetic data only until acceptance.</div>
    {error&&<p role="alert" className="timekeeping-alert">{error}</p>}
    {notice&&<p role="status" className="timekeeping-alert">{notice}</p>}
    <section className="timekeeping-card"><h2>Reconciliation window</h2><div className="timekeeping-grid">
      <label>Employee UUID<input placeholder="Employee ID" aria-label="Employee UUID" value={employeeId} onChange={e=>setEmployeeId(e.target.value)}/></label>
      <label>From<input type="date" aria-label="From date" value={from} onChange={e=>setFrom(e.target.value)}/></label>
      <label>To<input type="date" aria-label="To date" min={from} value={to} onChange={e=>setTo(e.target.value)}/></label>
      <label>Timezone<input aria-label="Timezone" value={timezone} onChange={e=>setTimezone(e.target.value)}/></label>
      </div><div className="timekeeping-actions"><button type="button" disabled={busy||!employeeId} onClick={getPreview}>Preview source evidence</button><button type="button" disabled={busy||!employeeId} onClick={prepare}>Save internal snapshot</button><button type="button" disabled={busy||!employeeId} onClick={()=>void run(refreshPackages)}>Load existing packages</button></div></section>
    {preview&&<section className="timekeeping-card"><h2>Source evidence preview</h2>
      <p>Raw closed attendance: <strong>{preview.summary.rawClosedMinutes} minutes</strong> across {preview.summary.attendanceSessions} sessions. This is not a payable-hours calculation.</p>
      <p>Open sessions: {preview.summary.openSessions} · Pending corrections: {preview.summary.pendingCorrections} · Pending leave: {preview.summary.pendingLeaveRequests} · Unconsumed approved leave: {preview.summary.unconsumedApprovedLeave} · Company calendar events: {preview.summary.calendarEvents}</p>
      <h3>Blocking conditions</h3>{preview.summary.blockerCodes.length?<ul>{preview.summary.blockerCodes.map(code=><li key={code}>{code.replaceAll('_',' ')}</li>)}</ul>:<p>No source blockers detected. Still not approved for payroll.</p>}
      <p>Fingerprint: <code>{preview.summary.sourceFingerprint}</code></p>
    </section>}
    <section className="timekeeping-card"><h2>Internal reconciliation packages</h2>
      {packages.length===0?<p>No snapshots loaded.</p>:<div style={{overflowX:'auto'}}><table style={{width:'100%',borderSpacing:12,textAlign:'left'}}><thead><tr><th>Period</th><th>Status</th><th>Blockers</th><th>Raw minutes</th><th>Action</th></tr></thead><tbody>
      {packages.map(p=><tr key={p.id}><td>{p.from} to {p.to}</td><td>{p.status} — never payroll-approved</td><td>{p.summary.blockerCodes.join(', ')||'No source blockers'}</td><td>{p.summary.rawClosedMinutes}</td><td>{p.status==='DRAFT'?<button type="button" disabled={busy||p.summary.blockerCodes.length>0} onClick={()=>review(p.id)}>Independent internal review</button>:'Review complete'}</td></tr>)}
      </tbody></table></div>}
    </section>
  </main>;
}
