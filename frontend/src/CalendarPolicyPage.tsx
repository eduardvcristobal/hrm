import { useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import './timekeeping.css';

type CalendarDay={id:string;companyId:string;date:string;kind:string;title:string};
type DayOverride={id:string;employeeId:string;date:string;kind:string;reason:string};
type DayPreview={date:string;shiftId:string|null;timezone:string|null;scheduled:boolean;restDay:boolean;scheduleStatus:string;companyDayKind:string|null;companyDayTitle:string|null;employeeOverride:string|null;provisional:boolean;classification:string};
const tokenKey='apexHrm.accessToken';
async function api<T>(path:string,options:RequestInit={}):Promise<T>{
  const token=sessionStorage.getItem(tokenKey)||localStorage.getItem(tokenKey);
  if(!token)throw new Error('Sign in and verify MFA to manage timekeeping policies.');
  const response=await fetch('/api/v1/timekeeping'+path,{
    ...options,headers:{Authorization:`Bearer ${token}`,...(options.body?{'Content-Type':'application/json'}:{}),...options.headers}
  });
  if(!response.ok)throw new Error(`Calendar request rejected (HTTP ${response.status}). Check permissions and input values.`);
  return response.json() as Promise<T>;
}
export default function CalendarPolicyPage(){
  const [companyId,setCompanyId]=useState('');
  const [employeeId,setEmployeeId]=useState('');
  const [date,setDate]=useState('2026-11-02');
  const [title,setTitle]=useState('Company holiday');
  const [kind,setKind]=useState('HOLIDAY');
  const [overrideDate,setOverrideDate]=useState('2026-11-02');
  const [overrideKind,setOverrideKind]=useState('REST_DAY');
  const [reason,setReason]=useState('Approved rest-day exception');
  const [from,setFrom]=useState('2026-11-01');
  const [to,setTo]=useState('2026-11-07');
  const [days,setDays]=useState<CalendarDay[]>([]);
  const [overrides,setOverrides]=useState<DayOverride[]>([]);
  const [preview,setPreview]=useState<DayPreview[]>([]);
  const [notice,setNotice]=useState('');
  const [error,setError]=useState('');
  const [busy,setBusy]=useState(false);
  async function run(task:()=>Promise<void>){
    setBusy(true);setError('');setNotice('');
    try{await task();}catch(e){setError(e instanceof Error?e.message:'Request failed');}
    finally{setBusy(false);}
  }
  async function refresh(){
    if(!companyId||!employeeId)throw new Error('Enter company and employee UUIDs.');
    const params=new URLSearchParams({from,to});
    const [calendar,employeeOverrides,classification]=await Promise.all([
      api<CalendarDay[]>(`/calendar-days?companyId=${encodeURIComponent(companyId)}&${params}`),
      api<DayOverride[]>(`/employees/${encodeURIComponent(employeeId)}/day-overrides?${params}`),
      api<DayPreview[]>(`/employees/${encodeURIComponent(employeeId)}/calendar-preview?${params}`)
    ]);
    setDays(calendar);setOverrides(employeeOverrides);setPreview(classification);
  }
  function createDay(event:FormEvent){
    event.preventDefault();
    if(!window.confirm('Save this company-defined date classification? It will not calculate payroll premiums.'))return;
    void run(async()=>{
      await api('/calendar-days',{method:'POST',body:JSON.stringify({companyId,date,kind,title})});
      await refresh();setNotice('Company calendar entry saved and audited.');
    });
  }
  function createOverride(event:FormEvent){
    event.preventDefault();
    if(!window.confirm('Add an employee day override? Existing shifts and attendance will not be modified.'))return;
    void run(async()=>{
      await api(`/employees/${encodeURIComponent(employeeId)}/day-overrides`,{
        method:'POST',body:JSON.stringify({date:overrideDate,kind:overrideKind,reason})
      });
      await refresh();setNotice('Employee day override saved and audited.');
    });
  }
  return <main className="timekeeping-root">
    <header className="timekeeping-top"><div><p className="timekeeping-eyebrow">Apex People / M2.6</p><h1>Holidays & Rest Days</h1><p>Review company calendar labels and employee schedule exceptions.</p></div><div><Link to="/timekeeping">Timekeeping</Link> &nbsp; <Link to="/leave">Leave</Link> &nbsp; <Link to="/dashboard">Dashboard</Link></div></header>
    <div className="timekeeping-hint">Provisional classification only. No statutory holiday catalog, payable premiums, automatically chargeable leave days, or payroll effects. MFA-verified tenant administrators only.</div>
    {error&&<p role="alert" className="timekeeping-alert">{error}</p>}
    {notice&&<p role="status" className="timekeeping-alert">{notice}</p>}
    <section className="timekeeping-card"><h2>Company & employee</h2><div className="timekeeping-grid"><label>Company UUID<input value={companyId} onChange={e=>setCompanyId(e.target.value)} placeholder="Company ID"/></label><label>Employee UUID<input value={employeeId} onChange={e=>setEmployeeId(e.target.value)} placeholder="Employee ID"/></label><label>Preview from<input type="date" value={from} onChange={e=>setFrom(e.target.value)}/></label><label>Preview to<input type="date" min={from} value={to} onChange={e=>setTo(e.target.value)}/></label></div><button type="button" disabled={busy||!companyId||!employeeId} onClick={()=>void run(refresh)}>Load calendar preview</button></section>
    <div className="timekeeping-columns">
      <section className="timekeeping-card"><h2>Add company calendar day</h2><form onSubmit={createDay}><label>Date<input type="date" required value={date} onChange={e=>setDate(e.target.value)}/></label><label>Classification<select value={kind} onChange={e=>setKind(e.target.value)}><option value="HOLIDAY">Holiday</option><option value="SPECIAL_NON_WORKING">Special non-working</option><option value="COMPANY_CLOSURE">Company closure</option></select></label><label>Label<input required minLength={3} maxLength={160} value={title} onChange={e=>setTitle(e.target.value)}/></label><button disabled={busy||!companyId||!employeeId}>Save calendar day</button></form><h3>Configured company days</h3><ul>{days.map(day=><li key={day.id}>{day.date} — {day.title} ({day.kind})</li>)}</ul></section>
      <section className="timekeeping-card"><h2>Employee day override</h2><form onSubmit={createOverride}><label>Date<input type="date" required value={overrideDate} onChange={e=>setOverrideDate(e.target.value)}/></label><label>Day type<select value={overrideKind} onChange={e=>setOverrideKind(e.target.value)}><option value="REST_DAY">Rest day</option><option value="WORK_DAY">Work day</option></select></label><label>Reason<input required minLength={8} maxLength={500} value={reason} onChange={e=>setReason(e.target.value)}/></label><button disabled={busy||!employeeId}>Save employee override</button></form><h3>Configured overrides</h3><ul>{overrides.map(o=><li key={o.id}>{o.date} — {o.kind}: {o.reason}</li>)}</ul></section>
    </div>
    <section className="timekeeping-card"><h2>Employee calendar classification preview</h2><p>Holidays and rest-day overrides can coexist. A missing schedule is displayed as UNSCHEDULED, never assumed to be a rest day.</p><div style={{overflowX:'auto'}}><table style={{width:'100%',borderSpacing:12,textAlign:'left'}}><thead><tr><th>Date</th><th>Schedule</th><th>Company calendar</th><th>Employee override</th><th>Shift ID</th></tr></thead><tbody>{preview.map(p=><tr key={p.date}><td>{p.date}</td><td>{p.scheduleStatus}</td><td>{p.companyDayTitle||'—'}</td><td>{p.employeeOverride||'—'}</td><td>{p.shiftId?.slice(0,8)||'—'}</td></tr>)}</tbody></table></div>{preview.length===0&&<p>No classification loaded.</p>}</section>
  </main>;
}
