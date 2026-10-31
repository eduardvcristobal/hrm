import { useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import './timekeeping.css';

const TOKEN_KEY='apexHrm.accessToken';
type LeaveType={id:string;companyId:string;code:string;name:string;active:boolean};
type CreditBalance={employeeId:string;leaveTypeId:string;accruedCreditDays:number;spendable:boolean;classification:string};
type JournalBalance={leaveTypeId:string;accruedDays:number;consumedDays:number;remainingDays:number;payable:boolean;policyCertified:boolean;classification:string};
type JournalEvent={id:string;leaveRequestId:string;leaveTypeId:string;eventType:string;days:number;reversesEventId:string|null};
type CarryoverPreview={proposedCarryoverDays:number;policyCap:number;posted:boolean;classification:string};
type LeavePolicy={id:string;leaveTypeId:string;monthlyCreditDays:number;effectiveFrom:string;active:boolean};
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
  const [credits,setCredits]=useState<CreditBalance[]>([]);
  const [policies,setPolicies]=useState<LeavePolicy[]>([]);
  const [monthlyDays,setMonthlyDays]=useState('1.250');
  const [policyEffective,setPolicyEffective]=useState('2026-01-01');
  const [creditMonth,setCreditMonth]=useState('2026-10');
  const [balances,setBalances]=useState<JournalBalance[]>([]);
  const [journal,setJournal]=useState<JournalEvent[]>([]);
  const [debitRequest,setDebitRequest]=useState('');
  const [debitDays,setDebitDays]=useState('1.000');
  const [debitNote,setDebitNote]=useState('Reviewed chargeable leave days');
  const [carryYear,setCarryYear]=useState(String(new Date().getUTCFullYear()+1));
  const [carryCap,setCarryCap]=useState('1.000');
  const [carryPreview,setCarryPreview]=useState<CarryoverPreview|null>(null);
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
  async function refreshCredits(){
    if(!employeeId)throw new Error('Enter the employee UUID.');
    setCredits(await api<CreditBalance[]>(`/employees/${encodeURIComponent(employeeId)}/leave-credit-balances`));
  }
  async function refreshPolicies(){
    if(!leaveTypeId)throw new Error('Choose a leave category.');
    setPolicies(await api<LeavePolicy[]>(`/leave-credit-policies?leaveTypeId=${encodeURIComponent(leaveTypeId)}`));
  }
  function createPolicy(event:FormEvent){
    event.preventDefault();
    if(!window.confirm('Create a new employer-configured monthly credit rule? Existing monthly postings will not change.'))return;
    void run(async()=>{
      await api('/leave-credit-policies',{method:'POST',body:JSON.stringify({
        leaveTypeId,monthlyCreditDays:Number(monthlyDays),effectiveFrom:policyEffective
      })});
      await refreshPolicies();setNotice('Credit policy saved. No employee balances were changed.');
    });
  }
  function accrueCredit(){
    if(!window.confirm('Post an informational monthly credit for this employee? This is not a payable or spendable leave balance.'))return;
    void run(async()=>{
      await api(`/employees/${encodeURIComponent(employeeId)}/leave-credits/accrue?leaveTypeId=${encodeURIComponent(leaveTypeId)}&month=${encodeURIComponent(creditMonth)}`,{method:'POST'});
      await refreshCredits();setNotice('Monthly informational credit posted once and audited.');
    });
  }
  async function refreshBalances(){
    if(!employeeId)throw new Error('Enter the employee UUID.');
    setBalances(await api<JournalBalance[]>(`/employees/${encodeURIComponent(employeeId)}/leave-balances`));
  }
  async function refreshJournal(){
    if(!employeeId||!leaveTypeId)throw new Error('Select an employee and leave category.');
    setJournal(await api<JournalEvent[]>(`/employees/${encodeURIComponent(employeeId)}/leave-balances/events?leaveTypeId=${encodeURIComponent(leaveTypeId)}`));
  }
  function debitLeave(event:FormEvent){
    event.preventDefault();
    if(!window.confirm('Record this explicitly reviewed debit? It does not certify paid leave, holidays or legal entitlements.'))return;
    void run(async()=>{
      await api(`/employees/${encodeURIComponent(employeeId)}/leave-balances/requests/${encodeURIComponent(debitRequest)}/consume`,{method:'POST',body:JSON.stringify({days:Number(debitDays),note:debitNote})});
      await Promise.all([refreshBalances(),refreshJournal()]);
      setNotice('Manual leave debit recorded and audited. No payroll changes made.');
    });
  }
  function reverseDebit(eventId:string){
    const note=window.prompt('Reason for reversing this debit (at least eight characters):');
    if(!note)return;
    if(!window.confirm('Post a compensating reversal? The original debit will remain in audit history.'))return;
    void run(async()=>{
      await api(`/employees/${encodeURIComponent(employeeId)}/leave-balances/events/${encodeURIComponent(eventId)}/reverse`,{method:'POST',body:JSON.stringify({note})});
      await Promise.all([refreshBalances(),refreshJournal()]);
      setNotice('Compensating reversal recorded and audited.');
    });
  }
  function configureCarryover(event:FormEvent){
    event.preventDefault();
    if(!window.confirm('Save a non-posting carryover preview cap? This does not award leave.'))return;
    void run(async()=>{
      await api('/leave-carryover-preview-policies',{method:'POST',body:JSON.stringify({
        leaveTypeId,destinationYear:Number(carryYear),maximumDays:Number(carryCap)
      })});
      setNotice('Preview-only carryover cap configured. No leave transferred.');
    });
  }
  function showCarryover(){
    void run(async()=>{
      const params=new URLSearchParams({leaveTypeId,destinationYear:carryYear});
      setCarryPreview(await api<CarryoverPreview>(`/employees/${encodeURIComponent(employeeId)}/leave-carryover-preview?${params}`));
      setNotice('Carryover preview calculated without posting any credit.');
    });
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
    <div className="timekeeping-hint">M2.5 foundation: MFA-verified tenant administrators only. Informational monthly credit rules and accruals are available. Manual approved-leave debit and compensating reversal are available. Spendable legal entitlements, working-day attribution and payroll effects remain unimplemented. Do not use with real employee data before acceptance.</div>
    {error&&<p className="timekeeping-alert" role="alert">{error}</p>}
    {notice&&<p className="timekeeping-alert" role="status">{notice}</p>}
    <section className="timekeeping-card"><h2>Workspace context</h2><div className="timekeeping-grid"><label>Company UUID<input aria-label="Company UUID" value={companyId} onChange={e=>setCompanyId(e.target.value)} placeholder="Company ID"/></label><label>Employee UUID<input aria-label="Employee UUID" value={employeeId} onChange={e=>setEmployeeId(e.target.value)} placeholder="Employee ID"/></label></div><div className="timekeeping-actions"><button type="button" disabled={busy||!companyId} onClick={()=>void run(refreshTypes)}>Load leave types</button><button type="button" disabled={busy||!employeeId} onClick={()=>void run(refreshRequests)}>Load employee requests</button></div></section>
    <div className="timekeeping-columns">
      <section className="timekeeping-card"><h2>Create leave category</h2><form onSubmit={createType}><label>Code<input required maxLength={40} pattern="[A-Za-z0-9_-]+" value={code} onChange={e=>setCode(e.target.value)} placeholder="PERSONAL"/></label><label>Name<input required maxLength={120} value={name} onChange={e=>setName(e.target.value)} placeholder="Personal leave"/></label><button disabled={busy||!companyId}>Create category</button></form><h3>Available categories</h3><ul>{types.map(t=><li key={t.id}>{t.name} ({t.code}) {t.active?'':'— Inactive'}</li>)}</ul>{types.length===0&&<p>No leave categories loaded.</p>}</section>
      <section className="timekeeping-card"><h2>Submit leave request</h2><form onSubmit={createRequest}><label>Leave category<select required value={leaveTypeId} onChange={e=>setLeaveTypeId(e.target.value)}><option value="">Select a category</option>{types.filter(t=>t.active).map(t=><option key={t.id} value={t.id}>{t.name}</option>)}</select></label><div className="timekeeping-grid"><label>From<input type="date" required value={from} onChange={e=>setFrom(e.target.value)}/></label><label>To<input type="date" required min={from||undefined} value={to} onChange={e=>setTo(e.target.value)}/></label></div><label>Reason<input required minLength={8} maxLength={500} value={reason} onChange={e=>setReason(e.target.value)} placeholder="Reason for leave request"/></label><button disabled={busy||!employeeId||!leaveTypeId}>Submit for review</button></form></section>
    </div>
    <section className="timekeeping-card"><h2>Monthly leave credits (M2.5.1 preview)</h2>
      <p>Employer-configured informational credits only. These are not available-to-take days or statutory entitlements, and approved leave is not subtracted.</p>
      <form onSubmit={createPolicy}>
        <div className="timekeeping-grid">
          <label>Leave category<select aria-label="Credit leave category" required value={leaveTypeId} onChange={e=>setLeaveTypeId(e.target.value)}><option value="">Select a category</option>{types.filter(t=>t.active).map(t=><option key={t.id} value={t.id}>{t.name}</option>)}</select></label>
          <label>Monthly credit days<input aria-label="Monthly credit days" type="number" min="0" max="31" step="0.001" required value={monthlyDays} onChange={e=>setMonthlyDays(e.target.value)} /></label>
          <label>Policy effective (month start)<input aria-label="Policy effective date" type="date" required value={policyEffective} onChange={e=>setPolicyEffective(e.target.value)}/></label>
          <label>Accrual month<input aria-label="Accrual month" type="month" value={creditMonth} onChange={e=>setCreditMonth(e.target.value)}/></label>
        </div>
        <div className="timekeeping-actions"><button type="submit" disabled={busy||!leaveTypeId}>Create credit policy</button><button type="button" disabled={busy||!leaveTypeId} onClick={()=>void run(refreshPolicies)}>Load policies</button><button type="button" disabled={busy||!employeeId||!leaveTypeId} onClick={accrueCredit}>Post monthly credit</button><button type="button" disabled={busy||!employeeId} onClick={()=>void run(refreshCredits)}>Load credit summary</button></div>
      </form>
      <h3>Effective-dated policies</h3>
      {policies.length===0?<p>No policies loaded.</p>:<ul>{policies.map(p=><li key={p.id}>{p.effectiveFrom} — {p.monthlyCreditDays} informational days/month {p.active?'':'(inactive)'}</li>)}</ul>}
      <h3>Employee accrued credits (not spendable)</h3>
      {credits.length===0?<p>No credit records loaded.</p>:<ul>{credits.map(c=><li key={c.leaveTypeId}><strong>{types.find(t=>t.id===c.leaveTypeId)?.name||c.leaveTypeId}</strong>: {c.accruedCreditDays} credited days — not spendable</li>)}</ul>}
    </section>
    <section className="timekeeping-card"><h2>Manual leave reconciliation (M2.5.2)</h2>
      <p>Only approved leave requests may be debited against informational credits. The administrator must separately confirm actual chargeable days. This is not statutory, payable, or payroll-certified.</p>
      <div className="timekeeping-actions"><button type="button" disabled={busy||!employeeId} onClick={()=>void run(refreshBalances)}>Refresh reconciled balances</button><button type="button" disabled={busy||!employeeId||!leaveTypeId} onClick={()=>void run(refreshJournal)}>Load debit and reversal journal</button></div>
      <ul>{balances.map(b=><li key={b.leaveTypeId}><strong>{types.find(t=>t.id===b.leaveTypeId)?.name||b.leaveTypeId}</strong> — {b.accruedDays} credited, {b.consumedDays} consumed, <strong>{b.remainingDays} remaining (not payable)</strong></li>)}</ul>
      <form onSubmit={debitLeave}><div className="timekeeping-grid"><label>Approved leave request<select aria-label="Approved leave request" required value={debitRequest} onChange={e=>setDebitRequest(e.target.value)}><option value="">Select an approved request</option>{requests.filter(r=>r.status==='APPROVED' && r.leaveTypeId===leaveTypeId).map(r=><option key={r.id} value={r.id}>{r.from} to {r.to} — {r.id.slice(0,8)}</option>)}</select></label><label>HR-confirmed debit days<input aria-label="HR-confirmed debit days" required type="number" step="0.001" min="0.001" max="366" value={debitDays} onChange={e=>setDebitDays(e.target.value)}/></label></div><label>Reconciliation reason<input aria-label="Reconciliation reason" required minLength={8} maxLength={500} value={debitNote} onChange={e=>setDebitNote(e.target.value)}/></label><button disabled={busy||!employeeId||!debitRequest}>Post reviewed debit</button></form>
      <h3>Immutable journal</h3>{journal.length===0?<p>No journal events loaded for selected category.</p>:<ul>{journal.map(e=><li key={e.id}>{e.eventType} {e.days} days — request {e.leaveRequestId.slice(0,8)} {e.eventType==='DEBIT'&&!journal.some(r=>r.reversesEventId===e.id)?<button type="button" disabled={busy} onClick={()=>reverseDebit(e.id)}>Reverse debit</button>:null}</li>)}</ul>}
    </section>
    <section className="timekeeping-card"><h2>Carryover planning (preview only)</h2><p>The cap applies to a snapshot of unsettled informational credits. It does not award, transfer, or expire leave.</p><form onSubmit={configureCarryover}><div className="timekeeping-grid"><label>Destination year<input aria-label="Carryover destination year" type="number" min={new Date().getUTCFullYear()+1} max="2200" required value={carryYear} onChange={e=>setCarryYear(e.target.value)}/></label><label>Maximum preview days<input aria-label="Maximum carryover days" type="number" step="0.001" min="0" max="366" required value={carryCap} onChange={e=>setCarryCap(e.target.value)}/></label></div><div className="timekeeping-actions"><button type="submit" disabled={busy||!leaveTypeId}>Save preview policy</button><button type="button" disabled={busy||!leaveTypeId||!employeeId} onClick={showCarryover}>Preview carryover</button></div></form>{carryPreview&&<p role="status">Proposed only: {carryPreview.proposedCarryoverDays} days (cap: {carryPreview.policyCap}). Posted: no. No legal or payroll entitlement created.</p>}</section>
    <section className="timekeeping-card"><h2>Employee leave requests</h2>{requests.length===0?<p>No requests loaded.</p>:<div style={{overflowX:'auto'}}><table style={{width:'100%',borderSpacing:12,textAlign:'left'}}><thead><tr><th>Period</th><th>Category</th><th>Status</th><th>Requested by</th><th>Review actions</th></tr></thead><tbody>{requests.map(r=><tr key={r.id}><td>{r.from} to {r.to}</td><td>{types.find(t=>t.id===r.leaveTypeId)?.name||r.leaveTypeId}</td><td>{r.status}</td><td>{r.requestedBy.slice(0,8)}…</td><td>{r.status==='PENDING'?<div className="timekeeping-actions"><button disabled={busy} onClick={()=>review(r.id,'APPROVED')}>Approve</button><button disabled={busy} onClick={()=>review(r.id,'REJECTED')}>Reject</button><button disabled={busy} onClick={()=>cancel(r.id)}>Cancel</button></div>:'Reviewed / closed'}</td></tr>)}</tbody></table></div>}</section>
  </main>;
}
