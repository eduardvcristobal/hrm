import { useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import './timekeeping.css';

const TOKEN_KEY='apexHrm.accessToken';
type Shift={id:string;companyId:string;code:string;name:string;start:string;end:string;timezone:string;active:boolean};
type Schedule={id:string;shiftId:string;from:string;until:string|null;weekdaysMask:number};
type Session={id:string;clockInAt:string;clockOutAt:string|null;source:string};
type DailySummary={day:string;completedMinutes:number;openSessions:number};
async function api<T>(path:string,options:RequestInit={}):Promise<T>{
  const token=localStorage.getItem(TOKEN_KEY);
  if(!token)throw new Error('Sign in and verify MFA before accessing timekeeping.');
  const response=await fetch('/api/v1/timekeeping'+path,{
    ...options,headers:{'Authorization':`Bearer ${token}`,...(options.body instanceof FormData?{}:{'Content-Type':'application/json'}),...options.headers}
  });
  if(!response.ok)throw new Error(`Timekeeping request rejected (HTTP ${response.status}). Check permissions or the form values.`);
  return response.json() as Promise<T>;
}
export default function TimekeepingPage(){
  const [companyId,setCompanyId]=useState('');
  const [employeeId,setEmployeeId]=useState('');
  const [code,setCode]=useState('');
  const [name,setName]=useState('');
  const [start,setStart]=useState('09:00');
  const [end,setEnd]=useState('18:00');
  const [timezone,setTimezone]=useState('Asia/Manila');
  const [shiftId,setShiftId]=useState('');
  const [from,setFrom]=useState('2026-11-01');
  const [until,setUntil]=useState('');
  const [weekdaysMask,setWeekdaysMask]=useState(31);
  const [shifts,setShifts]=useState<Shift[]>([]);
  const [schedules,setSchedules]=useState<Schedule[]>([]);
  const [sessions,setSessions]=useState<Session[]>([]);
  const [summaryFrom,setSummaryFrom]=useState('2026-10-01');
  const [summaryTo,setSummaryTo]=useState('2026-10-31');
  const [summaries,setSummaries]=useState<DailySummary[]>([]);
  const [notice,setNotice]=useState('');
  const [busy,setBusy]=useState(false);
  async function run(work:()=>Promise<void>){
    setBusy(true);setNotice('');
    try{await work();}catch(error){setNotice(error instanceof Error?error.message:'Timekeeping request failed');}
    finally{setBusy(false);}
  }
  async function loadShifts(){
    if(!companyId)throw new Error('Company ID is required');
    const rows=await api<Shift[]>(`/shifts?companyId=${encodeURIComponent(companyId)}`);
    setShifts(rows);
  }
  async function loadEmployee(){
    if(!employeeId)throw new Error('Employee ID is required');
    const key=encodeURIComponent(employeeId);
    const [scheduleRows,sessionRows]=await Promise.all([
      api<Schedule[]>(`/employees/${key}/schedules`),
      api<Session[]>(`/employees/${key}/attendance`)
    ]);
    setSchedules(scheduleRows);setSessions(sessionRows);
  }
  async function loadSummary(){
    if(!employeeId)throw new Error('Employee ID required');
    const query=new URLSearchParams({from:summaryFrom,to:summaryTo,timezone});
    setSummaries(await api<DailySummary[]>(`/employees/${encodeURIComponent(employeeId)}/summaries?${query}`));
  }
  function submitShift(event:FormEvent){
    event.preventDefault();
    void run(async()=>{
      await api<Shift>('/shifts',{method:'POST',body:JSON.stringify({
        companyId,code,name,start,end,timezone,unpaidBreakMinutes:60,graceMinutes:5
      })});
      await loadShifts();setNotice('Shift created.');
    });
  }
  function submitSchedule(event:FormEvent){
    event.preventDefault();
    void run(async()=>{
      await api<Schedule>(`/employees/${encodeURIComponent(employeeId)}/schedules`,{
        method:'POST',body:JSON.stringify({
          shiftId,effectiveFrom:from,effectiveUntil:until||null,weekdaysMask
        })
      });
      await loadEmployee();setNotice('Schedule assigned.');
    });
  }
  function clockIn(){
    void run(async()=>{
      await api<Session>(`/employees/${encodeURIComponent(employeeId)}/attendance/clock-in`,{method:'POST'});
      await loadEmployee();setNotice('Clock-in recorded on the server.');
    });
  }
  function clockOut(sessionId:string){
    void run(async()=>{
      await api<Session>(`/employees/${encodeURIComponent(employeeId)}/attendance/${sessionId}/clock-out`,{method:'POST'});
      await loadEmployee();setNotice('Clock-out recorded on the server.');
    });
  }
  return <main className="timekeeping-root">
    <header className="timekeeping-top"><div><p className="timekeeping-eyebrow">Apex People / M2</p><h1>Timekeeping & Attendance</h1><p>Shift templates, employee schedules, and controlled attendance capture.</p></div><div><Link to="/leave">Leave management</Link> &nbsp; <Link to="/calendar">Holidays & rest days</Link> &nbsp; <Link to="/dashboard">Back to dashboard</Link></div></header>
    <div className="timekeeping-hint">MFA-verified tenant administrators only. Clock times come from the server. Employee punch devices and payroll calculations are not active.</div>
    {notice&&<p role="status" className="timekeeping-alert">{notice}</p>}
    <section className="timekeeping-card"><h2>Workspace context</h2><div className="timekeeping-grid">
      <label>Company UUID<input value={companyId} onChange={e=>setCompanyId(e.target.value)} placeholder="Company ID"/></label>
      <label>Employee UUID<input value={employeeId} onChange={e=>setEmployeeId(e.target.value)} placeholder="Employee ID"/></label>
    </div><div className="timekeeping-actions"><button disabled={busy||!companyId} onClick={()=>void run(loadShifts)}>Load shifts</button><button disabled={busy||!employeeId} onClick={()=>void run(loadEmployee)}>Load employee</button></div></section>
    <div className="timekeeping-columns">
      <section className="timekeeping-card"><h2>Create shift</h2><form onSubmit={submitShift}><label>Code<input required maxLength={40} value={code} onChange={e=>setCode(e.target.value)}/></label><label>Name<input required maxLength={160} value={name} onChange={e=>setName(e.target.value)}/></label><div className="timekeeping-grid"><label>Start<input required type="time" value={start} onChange={e=>setStart(e.target.value)}/></label><label>End<input required type="time" value={end} onChange={e=>setEnd(e.target.value)}/></label></div><label>Timezone<input required value={timezone} onChange={e=>setTimezone(e.target.value)}/></label><button disabled={busy||!companyId}>Create shift</button></form><ul>{shifts.map(s=><li key={s.id}><strong>{s.name}</strong> — {s.code} ({s.start}–{s.end})</li>)}</ul></section>
      <section className="timekeeping-card"><h2>Assign schedule</h2><form onSubmit={submitSchedule}><label>Shift<select required value={shiftId} onChange={e=>setShiftId(e.target.value)}><option value="">Choose a shift</option>{shifts.map(s=><option key={s.id} value={s.id}>{s.name} ({s.code})</option>)}</select></label><div className="timekeeping-grid"><label>Effective from<input type="date" required value={from} onChange={e=>setFrom(e.target.value)}/></label><label>Effective until<input type="date" value={until} onChange={e=>setUntil(e.target.value)}/></label></div><label>Working days<select value={weekdaysMask} onChange={e=>setWeekdaysMask(Number(e.target.value))}><option value={31}>Monday–Friday</option><option value={63}>Monday–Saturday</option><option value={127}>Every day</option></select></label><button disabled={busy||!employeeId||!shiftId}>Assign schedule</button></form><ul>{schedules.map(s=><li key={s.id}>{s.from} to {s.until||'ongoing'} — {s.weekdaysMask===31?'Weekdays':s.weekdaysMask===63?'Monday–Saturday':'Selected days'}</li>)}</ul></section>
    </div>
    <section className="timekeeping-card"><h2>Attendance sessions</h2><button disabled={busy||!employeeId} onClick={clockIn}>Clock in employee</button><div className="timekeeping-table-wrap"><table><thead><tr><th>Clock in</th><th>Clock out</th><th>Source</th><th>Action</th></tr></thead><tbody>{sessions.map(s=><tr key={s.id}><td>{new Date(s.clockInAt).toLocaleString()}</td><td>{s.clockOutAt?new Date(s.clockOutAt).toLocaleString():'Open'}</td><td>{s.source}</td><td>{!s.clockOutAt&&<button disabled={busy} onClick={()=>clockOut(s.id)}>Clock out</button>}</td></tr>)}</tbody></table></div></section>
    <section className="timekeeping-card"><h2>Daily attendance totals</h2><p>Raw completed elapsed minutes, grouped by the local clock-in date. Not a paid-time or overtime calculation.</p><div className="timekeeping-grid"><label>From<input type="date" value={summaryFrom} onChange={e=>setSummaryFrom(e.target.value)}/></label><label>To<input type="date" value={summaryTo} onChange={e=>setSummaryTo(e.target.value)}/></label></div><button disabled={busy||!employeeId} onClick={()=>void run(loadSummary)}>Load daily totals</button><div className="timekeeping-table-wrap"><table><thead><tr><th>Date</th><th>Completed elapsed minutes</th><th>Open sessions</th></tr></thead><tbody>{summaries.map(d=><tr key={d.day}><td>{d.day}</td><td>{d.completedMinutes}</td><td>{d.openSessions}</td></tr>)}</tbody></table></div></section>
  </main>;
}
