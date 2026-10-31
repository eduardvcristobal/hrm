import {useState} from 'react';
type Device={id:string;employeeId:string;displayName:string;status:string;credentialExpiresAt:string|null};
const key='apexHrm.accessToken';
async function call<T>(path:string,options:RequestInit={}):Promise<T>{
  const token=sessionStorage.getItem(key);
  if(!token)throw new Error('An MFA-verified tenant administrator session is required.');
  const result=await fetch('/api/v1/employee-self-service/admin'+path,{
    ...options,headers:{Authorization:`Bearer ${token}`,...options.headers}
  });
  if(!result.ok)throw new Error(`Device decision refused (HTTP ${result.status}).`);
  return await result.json() as T;
}
export default function EmployeeDeviceAdminPanel({employeeId}:{employeeId:string}){
  const [devices,setDevices]=useState<Device[]>([]);
  const [error,setError]=useState('');
  const [notice,setNotice]=useState('');
  const [busy,setBusy]=useState(false);
  async function run(work:()=>Promise<void>){
    setBusy(true);setError('');setNotice('');
    try{await work();}catch(e){setError(e instanceof Error?e.message:'Device review failed');}
    finally{setBusy(false);}
  }
  async function refresh(){
    if(!employeeId)throw new Error('Select an employee UUID.');
    setDevices(await call<Device[]>(`/devices?employeeId=${encodeURIComponent(employeeId)}`));
  }
  function review(id:string,decision:'APPROVED'|'REJECTED'){
    if(!window.confirm(`${decision==='APPROVED'?'Approve':'Reject'} enrollment after independent verification? No device clock-in will be enabled.`))return;
    void run(async()=>{
      await call<Device>(`/devices/${encodeURIComponent(id)}/review?decision=${decision}`,{method:'POST'});
      await refresh();setNotice('Device review recorded and audited.');
    });
  }
  function revoke(id:string){
    if(!window.confirm('Immediately revoke this device credential?'))return;
    void run(async()=>{
      await call<Device>(`/devices/${encodeURIComponent(id)}/revoke`,{method:'POST'});
      await refresh();setNotice('Device revoked and audited.');
    });
  }
  return <section className="timekeeping-card">
    <h2>Independent device enrollment review</h2>
    <p>Device approvals are separate from employee identity linking and do not authorize punches, GPS or biometrics. All actions require an MFA-verified tenant administrator.</p>
    {error&&<p role="alert">{error}</p>}
    {notice&&<p role="status">{notice}</p>}
    <button type="button" disabled={busy||!employeeId} onClick={()=>void run(refresh)}>Load employee device requests</button>
    {devices.length===0?<p>No device requests loaded.</p>:<ul>{devices.map(d=><li key={d.id}>
      {d.displayName} — <strong>{d.status}</strong>
      {d.status==='PENDING'&&<><button type="button" disabled={busy} onClick={()=>review(d.id,'APPROVED')}>Approve device</button>
        <button type="button" disabled={busy} onClick={()=>review(d.id,'REJECTED')}>Reject device</button></>}
      {['PENDING','APPROVED','ACTIVE'].includes(d.status)&&
        <button type="button" disabled={busy} onClick={()=>revoke(d.id)}>Revoke device</button>}
    </li>)}</ul>}
  </section>;
}
