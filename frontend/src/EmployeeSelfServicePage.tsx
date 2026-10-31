import { useState } from 'react';
import { Link } from 'react-router-dom';
import './timekeeping.css';

type Profile={employeeId:string;companyId:string;employeeNumber:string;firstName:string;lastName:string;employmentStatus:string};
type Schedule={id:string;shiftId:string;effectiveFrom:string;effectiveUntil:string|null;weekdaysMask:number};
type Leave={id:string;leaveTypeId:string;startsOn:string;endsOn:string;status:string};
type SelfService={employee:Profile;schedules:Schedule[];leaveRequests:Leave[];clockInEnabled:boolean;locationCaptureEnabled:boolean;biometricCaptureEnabled:boolean;classification:string};
const tokenKey='apexHrm.accessToken';
export default function EmployeeSelfServicePage(){
  const [data,setData]=useState<SelfService|null>(null);
  const [busy,setBusy]=useState(false);
  const [error,setError]=useState('');
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
  return <main className="timekeeping-root">
    <header className="timekeeping-top"><div><p className="timekeeping-eyebrow">Apex People / M2.9</p><h1>My Timekeeping</h1><p>Secure, read-only employee schedule and leave history.</p></div><div><Link to="/dashboard">Dashboard</Link></div></header>
    <div className="timekeeping-hint">Self-service preview: verified employee identity and MFA required. No employee clock-in, GPS/location tracking, biometrics, attendance edits, or payroll access are enabled. Only your linked record can be viewed.</div>
    {error&&<p className="timekeeping-alert" role="alert">{error}</p>}
    <section className="timekeeping-card"><h2>My employee record</h2><button type="button" disabled={busy} onClick={()=>void load()}>{busy?'Loading…':'Load my timekeeping'}</button>
      {data&&<><p><strong>{data.employee.firstName} {data.employee.lastName}</strong> — {data.employee.employeeNumber}</p><p>Status: {data.employee.employmentStatus}</p><p>Device attendance: <strong>Disabled until acceptance</strong></p></>}
    </section>
    {data&&<div className="timekeeping-columns">
      <section className="timekeeping-card"><h2>My shift assignments</h2>{data.schedules.length===0?<p>No assignments recorded.</p>:<ul>{data.schedules.map(s=><li key={s.id}>{s.effectiveFrom} to {s.effectiveUntil||'open-ended'} · Shift {s.shiftId.slice(0,8)} · Weekday mask {s.weekdaysMask}</li>)}</ul>}</section>
      <section className="timekeeping-card"><h2>My leave requests</h2>{data.leaveRequests.length===0?<p>No leave requests recorded.</p>:<ul>{data.leaveRequests.map(l=><li key={l.id}>{l.startsOn} to {l.endsOn} — <strong>{l.status}</strong></li>)}</ul>}</section>
    </div>}
  </main>;
}
