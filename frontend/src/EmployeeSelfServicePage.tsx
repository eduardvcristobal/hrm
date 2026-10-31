import { useState } from 'react';
import { Link } from 'react-router-dom';
import './timekeeping.css';
import EmployeeDevicesPanel from './EmployeeDevicesPanel';

type Profile={employeeId:string;companyId:string;employeeNumber:string;firstName:string;lastName:string;employmentStatus:string};
type Schedule={id:string;shiftId:string;effectiveFrom:string;effectiveUntil:string|null;weekdaysMask:number};
type Leave={id:string;leaveTypeId:string;startsOn:string;endsOn:string;status:string};
type Attendance={id:string;clockInAt:string;clockOutAt:string|null};
type LeaveCategory={id:string;code:string;name:string};
type SelfService={employee:Profile;schedules:Schedule[];leaveRequests:Leave[];attendanceSessions:Attendance[];availableLeaveCategories:LeaveCategory[];clockInEnabled:boolean;locationCaptureEnabled:boolean;biometricCaptureEnabled:boolean;classification:string};
const tokenKey='apexHrm.accessToken';
export default function EmployeeSelfServicePage(){
  const [data,setData]=useState<SelfService|null>(null);
  const [busy,setBusy]=useState(false);
  const [error,setError]=useState('');
  const [notice,setNotice]=useState('');
  const [leaveType,setLeaveType]=useState('');
  const [from,setFrom]=useState('');
  const [to,setTo]=useState('');
  const [reason,setReason]=useState('');
  async function load(){
    setBusy(true);setError('');setData(null);
    try{
      const token=sessionStorage.getItem(tokenKey)||localStorage.getItem(tokenKey);
      if(!token)throw new Error('Sign in with your employee account and complete MFA verification.');
      const res=await fetch('/api/v1/employee-self-service/me',{headers:{Authorization:`Bearer ${token}`}});
      if(!res.ok){
        if(res.status===403)throw new Error('Your employee identity is not linked or self-service access is inactive. Contact HR.');
        throw new Error(`Self-service request denied (HTTP ${res.status}).`);
      }
      setData(await res.json() as SelfService);
    }catch(e){setError(e instanceof Error?e.message:'Failed to load self-service');}
    finally{setBusy(false);}
  }
  async function submitLeave(){
    if(!data||!leaveType||!from||!to||reason.trim().length<8){setError('Select a leave category, date range and reason of at least eight characters.');return;}
    if(!window.confirm('Submit this leave request for independent HR review? This does not authorize paid leave.'))return;
    setBusy(true);setError('');setNotice('');
    try{
      const token=sessionStorage.getItem(tokenKey)||localStorage.getItem(tokenKey);
      if(!token)throw new Error('Your authenticated employee session is required.');
      const res=await fetch('/api/v1/employee-self-service/leave-requests',{
        method:'POST',
        headers:{Authorization:`Bearer ${token}`,'Content-Type':'application/json'},
        body:JSON.stringify({leaveTypeId:leaveType,from,to,reason})
      });
      if(!res.ok)throw new Error(`Leave request rejected (HTTP ${res.status}). Check the date range or overlap with another request.`);
      const fresh=await fetch('/api/v1/employee-self-service/me',{headers:{Authorization:`Bearer ${token}`}});
      if(!fresh.ok)throw new Error('Leave request submitted, but the updated history could not be loaded.');
      setData(await fresh.json() as SelfService);setReason('');setNotice('Leave request submitted for independent HR review.');
    }catch(e){setError(e instanceof Error?e.message:'Failed to submit leave request');}
    finally{setBusy(false);}
  }
  return <main className="timekeeping-root">
    <header className="timekeeping-top"><div><p className="timekeeping-eyebrow">Apex People / M2.9</p><h1>My Timekeeping</h1><p>Secure employee schedules, attendance history and leave requests.</p></div><div><Link to="/dashboard">Dashboard</Link></div></header>
    <div className="timekeeping-hint">Self-service preview: verified employee identity and MFA required. No employee clock-in, GPS/location tracking, biometrics, attendance edits, or payroll access are enabled. Only your linked record can be viewed.</div>
    {error&&<p className="timekeeping-alert" role="alert">{error}</p>}
    {notice&&<p className="timekeeping-alert" role="status">{notice}</p>}
    <section className="timekeeping-card"><h2>My employee record</h2><button type="button" disabled={busy} onClick={()=>void load()}>{busy?'Loading…':'Load my timekeeping'}</button>
      {data&&<><p><strong>{data.employee.firstName} {data.employee.lastName}</strong> — {data.employee.employeeNumber}</p><p>Status: {data.employee.employmentStatus}</p><p>Device attendance: <strong>Disabled until acceptance</strong></p></>}
    </section>
    {data&&<div className="timekeeping-columns">
      <section className="timekeeping-card"><h2>My shift assignments</h2>{data.schedules.length===0?<p>No assignments recorded.</p>:<ul>{data.schedules.map(s=><li key={s.id}>{s.effectiveFrom} to {s.effectiveUntil||'open-ended'} · Shift {s.shiftId.slice(0,8)} · Weekday mask {s.weekdaysMask}</li>)}</ul>}</section>
      <section className="timekeeping-card"><h2>My leave requests</h2>{data.leaveRequests.length===0?<p>No leave requests recorded.</p>:<ul>{data.leaveRequests.map(l=><li key={l.id}>{l.startsOn} to {l.endsOn} — <strong>{l.status}</strong></li>)}</ul>}</section>
    </div>}
    {data&&<section className="timekeeping-card"><h2>Submit my leave request</h2>
      <p>Only your verified employee record can submit this request. Independent HR approval is required; no paid leave entitlement is granted by submission.</p>
      <div className="timekeeping-grid">
        <label>Leave category<select aria-label="My leave category" value={leaveType} onChange={e=>setLeaveType(e.target.value)}><option value="">Choose a leave category</option>{data.availableLeaveCategories.map(c=><option key={c.id} value={c.id}>{c.name}</option>)}</select></label>
        <label>From<input aria-label="My leave from" type="date" value={from} onChange={e=>setFrom(e.target.value)}/></label>
        <label>To<input aria-label="My leave to" type="date" min={from} value={to} onChange={e=>setTo(e.target.value)}/></label>
        <label>Reason<input aria-label="My leave reason" minLength={8} maxLength={500} value={reason} onChange={e=>setReason(e.target.value)}/></label>
      </div>
      <button type="button" disabled={busy||!leaveType||!from||!to||reason.trim().length<8} onClick={()=>void submitLeave()}>Submit my leave request</button>
    </section>}
    {data&&<EmployeeDevicesPanel/>}
    {data&&<section className="timekeeping-card"><h2>My recent attendance (raw, not payable)</h2>
      {data.attendanceSessions.length===0?<p>No attendance sessions recorded.</p>:<div style={{overflowX:'auto'}}><table style={{width:'100%',borderSpacing:12,textAlign:'left'}}><thead><tr><th>Clock in (UTC)</th><th>Clock out (UTC)</th><th>Status</th></tr></thead><tbody>
        {data.attendanceSessions.map(a=><tr key={a.id}><td>{a.clockInAt}</td><td>{a.clockOutAt||'—'}</td><td>{a.clockOutAt?'Closed':'Open'}</td></tr>)}
      </tbody></table></div>}
      <p>These timestamps are source events, not confirmed hours, overtime or payroll.</p>
    </section>}
  </main>;
}
