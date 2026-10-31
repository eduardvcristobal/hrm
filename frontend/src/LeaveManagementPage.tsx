import { useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import './timekeeping.css';

const TOKEN_KEY='apexHrm.accessToken';
type LeaveType={id:string;companyId:string;code:string;name:string;active:boolean};
type LeaveRequest={id:string;employeeId:string;leaveTypeId:string;from:string;to:string;status:string;requestedBy:string;reviewedBy:string|null};
async function api<T>(path:string,options:RequestInit={}):Promise<T>{
  const token=sessionStorage.getItem(TOKEN_KEY) || localStorage.getItem(TOKEN_KEY);
  if(!token)throw new Error('Sign in and complete MFA verification before accessing leave management.');
  const response=await fetch('/api/v1/timekeeping'+path,{
    ...options,headers:{Authorization:`Bearer ${token}`,...(options.body?{'Content-Type':'application/json'}:{}),...options.headers}
  });
  if(!response.ok){
    let detail='';
    try{const body=await response.json() as {detail?:string;message?:string};detail=body.detail||body.message||'';}catch{/* no response body */}
    throw new Error(detail||`Leave management rejected the request (HTTP ${response.status}).`);
  }
  return response.json() as Promise<T>;
}
export default function LeaveManagementPage(){
  const [companyId,setCompanyId]=useState('');
  const [employeeId,setEmployeeId]=useState('');
  const [types,setTypes]=useState<LeaveType[]>([]);
  const [requests,setRequests]=useState<LeaveRequest[]>([]);
  const [code,setCode]=useState('');
  const [name,setName]=useState('');
  const [leaveTypeId,setLeaveTypeId]=useState('');
  const [from,setFrom]=useState('');
  const [to,setTo]=useState('');
  const [reason,setReason]=useState('');
  const [notice,setNotice]=useState('');
  const [error,setError]=useState('');
  const [busy,setBusy]=useState(false);
  async function run(task:()=>Promise<void>){
    setBusy(true);setError('');setNotice('');
    try{await task();}catch(e){setError(e instanceof Error?e.message:'Leave management request failed');}
    finally{setBusy(false);}
  }
  async function refreshTypes(){
    if(!companyId)throw new Error('Enter your company UUID.');
    setTypes(await api<LeaveType[]>(`/leave-types?companyId=${encodeURIComponent(companyId)}`));
  }
  async function refreshRequests(){
    if(!employeeId)throw new Error('Enter the employee UUID.');
    setRequests(await api<LeaveRequest[]>(`/employees/${encodeURIComponent(employeeId)}/leave-requests`));
  }
  function createType(event:FormEvent){
    event.preventDefault();
    void run(async()=>{
      await api('/leave-types',{method:'POST',body:JSON.stringify({companyId,code,name})});
      await refreshTypes();setCode('');setName('');setNotice('Leave category created.');
    });
  }
  function createRequest(event:FormEvent){
    event.preventDefault();
    void run(async()=>{
      await api(`/employees/${encodeURIComponent(employeeId)}/leave-requests`,{
        method:'POST',body:JSON.stringify({leaveTypeId,from,to,reason})
      });
      await refreshRequests();setReason('');setNotice('Leave request submitted for independent review.');
    });
  }
  function review(id:string,decision:'APPROVED'|'REJECTED'){
    if(!window.confirm(`${decision==='APPROVED'?'Approve':'Reject'} this pending leave request? A different administrator must review it.`))return;
    void run(async()=>{
      await api(`/employees/${encodeURIComponent(employeeId)}/leave-requests/${id}/decision?decision=${decision}`,{method:'POST'});
      await refreshRequests();setNotice(`Leave request ${decision.toLowerCase()}.`);
    });
  }
  function cancel(id:string){
    if(!window.confirm('Cancel this pending leave request?'))return;
    void run(async()=>{
      await api(`/employees/${encodeURIComponent(employeeId)}/leave-requests/${id}/cancel`,{method:'POST'});
      await refreshRequests();setNotice('Pending leave request cancelled.');
    });
  }
  return <main className="timekeeping-root">
    <header className="timekeeping-top"><div><p className="timekeeping-eyebrow">Apex People / M2.5</p><h1>Leave Management</h1><p>Leave categories, employee requests and independent approval.</p></div><div><Link to="/timekeeping">Timekeeping</Link> &nbsp; <Link to="/dashboard">Dashboard</Link></div></header>
    <div className="timekeeping-hint">M2.5 foundation: MFA-verified tenant administrators only. Entitlement balances, accrual, paid leave and payroll effects are not implemented; do not use with real employee data before acceptance.</div>
    {error&&<p className="timekeeping-alert" role="alert">{error}</p>}
    {notice&&<p className="timekeeping-alert" role="status">{notice}</p>}
    <section className="timekeeping-card"><h2>Workspace context</h2><div className="timekeeping-grid"><label>Company UUID<input aria-label="Company UUID" value={companyId} onChange={e=>setCompanyId(e.target.value)} placeholder="Company ID"/></label><label>Employee UUID<input aria-label="Employee UUID" value={employeeId} onChange={e=>setEmployeeId(e.target.value)} placeholder="Employee ID"/></label></div><div className="timekeeping-actions"><button type="button" disabled={busy||!companyId} onClick={()=>void run(refreshTypes)}>Load leave types</button><button type="button" disabled={busy||!employeeId} onClick={()=>void run(refreshRequests)}>Load employee requests</button></div></section>
    <div className="timekeeping-columns">
      <section className="timekeeping-card"><h2>Create leave category</h2><form onSubmit={createType}><label>Code<input required maxLength={40} pattern="[A-Za-z0-9_-]+" value={code} onChange={e=>setCode(e.target.value)} placeholder="PERSONAL"/></label><label>Name<input required maxLength={120} value={name} onChange={e=>setName(e.target.value)} placeholder="Personal leave"/></label><button disabled={busy||!companyId}>Create category</button></form><h3>Available categories</h3><ul>{types.map(t=><li key={t.id}>{t.name} ({t.code}) {t.active?'':'— Inactive'}</li>)}</ul>{types.length===0&&<p>No leave categories loaded.</p>}</section>
      <section className="timekeeping-card"><h2>Submit leave request</h2><form onSubmit={createRequest}><label>Leave category<select required value={leaveTypeId} onChange={e=>setLeaveTypeId(e.target.value)}><option value="">Select a category</option>{types.filter(t=>t.active).map(t=><option key={t.id} value={t.id}>{t.name}</option>)}</select></label><div className="timekeeping-grid"><label>From<input type="date" required value={from} onChange={e=>setFrom(e.target.value)}/></label><label>To<input type="date" required min={from||undefined} value={to} onChange={e=>setTo(e.target.value)}/></label></div><label>Reason<input required minLength={8} maxLength={500} value={reason} onChange={e=>setReason(e.target.value)} placeholder="Reason for leave request"/></label><button disabled={busy||!employeeId||!leaveTypeId}>Submit for review</button></form></section>
    </div>
    <section className="timekeeping-card"><h2>Employee leave requests</h2>{requests.length===0?<p>No requests loaded.</p>:<div style={{overflowX:'auto'}}><table style={{width:'100%',borderSpacing:12,textAlign:'left'}}><thead><tr><th>Period</th><th>Category</th><th>Status</th><th>Requested by</th><th>Review actions</th></tr></thead><tbody>{requests.map(r=><tr key={r.id}><td>{r.from} to {r.to}</td><td>{types.find(t=>t.id===r.leaveTypeId)?.name||r.leaveTypeId}</td><td>{r.status}</td><td>{r.requestedBy.slice(0,8)}…</td><td>{r.status==='PENDING'?<div className="timekeeping-actions"><button disabled={busy} onClick={()=>review(r.id,'APPROVED')}>Approve</button><button disabled={busy} onClick={()=>review(r.id,'REJECTED')}>Reject</button><button disabled={busy} onClick={()=>cancel(r.id)}>Cancel</button></div>:'Reviewed / closed'}</td></tr>)}</tbody></table></div>}</section>
  </main>;
}
