import { useState } from 'react';
import { Link } from 'react-router-dom';
import './timekeeping.css';

type Receipt={id:string;exportType:string;from:string;to:string;recordCount:number;contentSha256:string};
type RetentionPlan={id:string;companyId:string;attendanceMinDays:number;auditMinDays:number;legalHold:boolean};
type RetentionPreview={legalHold:boolean;attendanceBefore:string;auditBefore:string;attendanceCandidates:number;auditCandidates:number;deletionEnabled:boolean;classification:string};
const tokenKey='apexHrm.accessToken';
function authToken(){
  const token=sessionStorage.getItem(tokenKey)||localStorage.getItem(tokenKey);
  if(!token)throw new Error('Sign in and verify MFA to view internal timekeeping reports.');
  return token;
}
async function api<T>(path:string,options:RequestInit={}):Promise<T>{
  const r=await fetch('/api/v1/timekeeping'+path,{
    ...options,headers:{Authorization:`Bearer ${authToken()}`,...(options.body?{'Content-Type':'application/json'}:{}),...options.headers}
  });
  if(!r.ok)throw new Error(`Timekeeping report action rejected (HTTP ${r.status}).`);
  return r.json() as Promise<T>;
}
export default function TimekeepingReportsPage(){
  const [companyId,setCompanyId]=useState('');
  const [from,setFrom]=useState('2026-10-01');
  const [to,setTo]=useState('2026-10-31');
  const [timezone,setTimezone]=useState('Asia/Manila');
  const [attendanceDays,setAttendanceDays]=useState('730');
  const [auditDays,setAuditDays]=useState('2555');
  const [receipts,setReceipts]=useState<Receipt[]>([]);
  const [plans,setPlans]=useState<RetentionPlan[]>([]);
  const [retention,setRetention]=useState<RetentionPreview|null>(null);
  const [busy,setBusy]=useState(false);
  const [error,setError]=useState('');
  const [notice,setNotice]=useState('');
  async function run(task:()=>Promise<void>){
    setBusy(true);setError('');setNotice('');
    try{await task();}catch(e){setError(e instanceof Error?e.message:'Report operation failed');}
    finally{setBusy(false);}
  }
  function query(plusCompany:boolean){
    const args=new URLSearchParams({from,to});
    if(plusCompany){args.set('companyId',companyId);args.set('timezone',timezone);}
    return args;
  }
  async function refreshReceipts(){setReceipts(await api<Receipt[]>('/reports/export-receipts'));}
  async function refreshRetention(){
    if(!companyId)throw new Error('Enter a company UUID.');
    const q=encodeURIComponent(companyId);
    setPlans(await api<RetentionPlan[]>(`/retention/plans?companyId=${q}`));
    setRetention(await api<RetentionPreview>(`/retention/preview?companyId=${q}`));
  }
  function download(kind:'attendance'|'audit'){
    const file=kind==='attendance'?'attendance.csv':'audit.csv';
    if(kind==='attendance'&&!companyId){setError('Company UUID is required for attendance export.');return;}
    void run(async()=>{
      const q=query(kind==='attendance');
      const res=await fetch(`/api/v1/timekeeping/reports/${file}?${q}`,{
        headers:{Authorization:`Bearer ${authToken()}`}
      });
      if(!res.ok)throw new Error(`CSV export blocked (HTTP ${res.status}). Narrow the range if needed.`);
      const csv=await res.blob();
      const url=URL.createObjectURL(csv);
      try{
        const element=document.createElement('a');element.href=url;
        element.download=kind==='attendance'?'timekeeping-attendance.csv':'timekeeping-audit.csv';
        document.body.appendChild(element);element.click();element.remove();
      }finally{URL.revokeObjectURL(url);}
      await refreshReceipts();setNotice('Internal-only CSV exported and recorded in audit receipts.');
    });
  }
  function createRetention(){
    if(!window.confirm('Configure a non-destructive retention plan? Legal hold cannot be disabled and this will not delete records.'))return;
    void run(async()=>{
      await api('/retention/plans',{method:'POST',body:JSON.stringify({
        companyId,attendanceMinDays:Number(attendanceDays),auditMinDays:Number(auditDays)
      })});
      await refreshRetention();setNotice('Retention plan created with mandatory legal hold.');
    });
  }
  return <main className="timekeeping-root">
    <header className="timekeeping-top"><div><p className="timekeeping-eyebrow">Apex People / M2.8</p><h1>Timekeeping Reports & Retention</h1><p>Controlled internal CSV exports, audit receipts and legal-hold planning.</p></div><div><Link to="/timekeeping">Timekeeping</Link> &nbsp; <Link to="/reconciliation">Reconciliation</Link> &nbsp; <Link to="/dashboard">Dashboard</Link></div></header>
    <div className="timekeeping-hint">MFA-verified tenant administrators only. Exports contain raw, non-payroll-approved evidence; names and free-text personnel notes are excluded. Retention preview is advisory only; deletion is disabled.</div>
    {error&&<p role="alert" className="timekeeping-alert">{error}</p>}
    {notice&&<p role="status" className="timekeeping-alert">{notice}</p>}
    <section className="timekeeping-card"><h2>Reporting context</h2><div className="timekeeping-grid">
      <label>Company UUID<input placeholder="Company ID" value={companyId} onChange={e=>setCompanyId(e.target.value)}/></label>
      <label>From<input type="date" value={from} onChange={e=>setFrom(e.target.value)}/></label>
      <label>To<input type="date" value={to} min={from} onChange={e=>setTo(e.target.value)}/></label>
      <label>Timezone<input value={timezone} onChange={e=>setTimezone(e.target.value)}/></label>
    </div><p>Maximum 31 calendar days per export; maximum 500 records. Report exports are recorded with a content fingerprint.</p>
      <div className="timekeeping-actions"><button type="button" disabled={busy||!companyId} onClick={()=>download('attendance')}>Export attendance CSV</button><button type="button" disabled={busy} onClick={()=>download('audit')}>Export timekeeping audit CSV</button><button type="button" disabled={busy} onClick={()=>void run(refreshReceipts)}>View export receipts</button></div>
      <h3>Recent export receipts</h3>
      {receipts.length===0?<p>No receipts loaded.</p>:<div style={{overflowX:'auto'}}><table style={{width:'100%',borderSpacing:12,textAlign:'left'}}><thead><tr><th>Type</th><th>Date range</th><th>Rows</th><th>SHA-256</th></tr></thead><tbody>{receipts.map(r=><tr key={r.id}><td>{r.exportType}</td><td>{r.from} – {r.to}</td><td>{r.recordCount}</td><td><code>{r.contentSha256.slice(0,16)}…</code></td></tr>)}</tbody></table></div>}
    </section>
    <section className="timekeeping-card"><h2>Retention planning — no deletion</h2><p>All plans enforce legal hold. This is not permission to purge HR records; legal, privacy and operations sign-off is still required.</p>
      <div className="timekeeping-grid"><label>Minimum attendance retention (days)<input type="number" min="365" max="36500" value={attendanceDays} onChange={e=>setAttendanceDays(e.target.value)}/></label><label>Minimum audit retention (days)<input type="number" min="365" max="36500" value={auditDays} onChange={e=>setAuditDays(e.target.value)}/></label></div>
      <div className="timekeeping-actions"><button type="button" disabled={busy||!companyId} onClick={createRetention}>Create protected retention plan</button><button type="button" disabled={busy||!companyId} onClick={()=>void run(refreshRetention)}>Preview retention only</button></div>
      {plans.map(p=><p key={p.id}>Configured: attendance {p.attendanceMinDays} days · audit {p.auditMinDays} days · Legal hold: <strong>on</strong></p>)}
      {retention&&<p role="status">Advisory attendance cutoff: {retention.attendanceBefore}. Candidate closed sessions: {retention.attendanceCandidates}. Audit records remain on hold. <strong>Deletion enabled: no.</strong></p>}
    </section>
  </main>;
}
