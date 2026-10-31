import { useState } from 'react';
import { Link } from 'react-router-dom';

type Config={id:string;companyId:string;payFrequency:string;currencyCode:string;timezone:string;effectiveFrom:string};
type Period={id:string;companyId:string;configurationId:string;startsOn:string;endsOn:string;payOn:string;status:string;classification:string};
type Component={id:string;companyId:string;componentCode:string;componentName:string;componentKind:string;effectiveFrom:string;treatment:string;classification:string};
const tokenKey='apexHrm.accessToken';
async function payroll<T>(path:string,options:RequestInit={}):Promise<T>{
  const token=sessionStorage.getItem(tokenKey);
  if(!token)throw new Error('Sign in as an MFA-verified tenant administrator first.');
  const response=await fetch('/api/v1/payroll'+path,{
    ...options,
    headers:{Authorization:`Bearer ${token}`,...(options.body?{'Content-Type':'application/json'}:{}),...options.headers}
  });
  if(!response.ok)throw new Error(`Payroll draft operation denied (HTTP ${response.status}).`);
  return await response.json() as T;
}
const field:React.CSSProperties={display:'grid',gap:6,marginBottom:14};
const panel:React.CSSProperties={border:'1px solid #cbd5e1',borderRadius:14,padding:24,background:'white'};
export default function PayrollFoundationPage(){
  const [companyId,setCompanyId]=useState('');
  const [configurations,setConfigurations]=useState<Config[]>([]);
  const [periods,setPeriods]=useState<Period[]>([]);
  const [components,setComponents]=useState<Component[]>([]);
  const [componentCode,setComponentCode]=useState('');
  const [componentName,setComponentName]=useState('');
  const [componentKind,setComponentKind]=useState('EARNING');
  const [componentEffectiveFrom,setComponentEffectiveFrom]=useState('2026-01-01');
  const [frequency,setFrequency]=useState('SEMI_MONTHLY');
  const [currency,setCurrency]=useState('PHP');
  const [timezone,setTimezone]=useState('Asia/Manila');
  const [effectiveFrom,setEffectiveFrom]=useState('2026-01-01');
  const [configurationId,setConfigurationId]=useState('');
  const [startsOn,setStartsOn]=useState('');
  const [endsOn,setEndsOn]=useState('');
  const [payOn,setPayOn]=useState('');
  const [busy,setBusy]=useState(false);
  const [message,setMessage]=useState('');
  const [error,setError]=useState('');
  async function load(){
    if(!companyId)throw new Error('Enter a valid company UUID.');
    const query=`?companyId=${encodeURIComponent(companyId)}`;
    const [configs,payPeriods,catalogue]=await Promise.all([
      payroll<Config[]>('/configurations'+query),payroll<Period[]>('/periods'+query),payroll<Component[]>('/components'+query)
    ]);
    setConfigurations(configs);setPeriods(payPeriods);setComponents(catalogue);
  }
  async function run(job:()=>Promise<void>){
    setBusy(true);setError('');setMessage('');
    try{await job();}catch(e){setError(e instanceof Error?e.message:'Payroll operation failed');}
    finally{setBusy(false);}
  }
  function createConfig(){
    if(!window.confirm('Create a payroll configuration draft? This does not calculate or authorize wages.'))return;
    void run(async()=>{
      await payroll<Config>('/configurations',{method:'POST',body:JSON.stringify({
        companyId,payFrequency:frequency,currencyCode:currency,timezone,effectiveFrom
      })});
      await load();setMessage('Payroll configuration recorded. No payroll calculation or release enabled.');
    });
  }
  function createPeriod(){
    if(!window.confirm('Create a draft-only payroll period? No payslip, payroll approval or payment will occur.'))return;
    void run(async()=>{
      await payroll<Period>('/periods',{method:'POST',body:JSON.stringify({
        companyId,configurationId,startsOn,endsOn,payOn
      })});
      await load();setMessage('Draft payroll period created. Independent payroll acceptance remains required.');
    });
  }
  function createComponent(){
    if(!window.confirm('Register an earning or deduction component definition? No tax or payroll calculation is activated.'))return;
    void run(async()=>{
      await payroll<Component>('/components',{method:'POST',body:JSON.stringify({
        companyId,componentCode,componentName,componentKind,effectiveFrom:componentEffectiveFrom
      })});
      await load();setMessage('Component definition registered as UNREVIEWED. No payroll amount calculated.');
    });
  }
  return <main style={{fontFamily:'system-ui, sans-serif',background:'#f8fafc',color:'#0f172a',minHeight:'100vh',padding:'clamp(16px,3vw,36px)'}}>
    <header style={{maxWidth:1120,margin:'auto'}}>
      <p style={{textTransform:'uppercase',letterSpacing:2,color:'#475569',fontSize:12}}>Apex People · M3.1</p>
      <h1>Payroll Management</h1>
      <p>Company payroll configuration and pay-period drafts. Attendance, earnings, deductions, tax, net pay and release remain unavailable.</p>
      <nav><Link to="/dashboard">Back to dashboard</Link></nav>
    </header>
    <div style={{maxWidth:1120,margin:'24px auto',display:'grid',gap:20}}>
      <aside role="note" style={{...panel,background:'#fff7ed',borderColor:'#fdba74'}}>
        <strong>NO-GO — configuration only.</strong> Periods are permanently DRAFT in M3.1. No wages are computed, no regulatory tables are applied and no payouts are authorized. M2 attendance is not merged into this main-based branch.
      </aside>
      {error&&<p role="alert" style={{color:'#b91c1c'}}>{error}</p>}
      {message&&<p role="status">{message}</p>}
      <section style={panel}><h2>Company</h2>
        <label style={field}>Company UUID<input aria-label="Payroll company UUID" value={companyId} onChange={e=>setCompanyId(e.target.value)} placeholder="Company UUID"/></label>
        <button type="button" disabled={busy||!companyId} onClick={()=>void run(load)}>Load payroll drafts</button>
      </section>
      <div style={{display:'grid',gridTemplateColumns:'repeat(auto-fit,minmax(280px,1fr))',gap:20}}>
        <section style={panel}><h2>Payroll configuration</h2>
          <label style={field}>Pay frequency<select aria-label="Pay frequency" value={frequency} onChange={e=>setFrequency(e.target.value)}><option>SEMI_MONTHLY</option><option>MONTHLY</option><option>WEEKLY</option><option>BIWEEKLY</option></select></label>
          <label style={field}>Currency<input aria-label="Currency" maxLength={3} value={currency} onChange={e=>setCurrency(e.target.value.toUpperCase())}/></label>
          <label style={field}>Time zone<input aria-label="Time zone" value={timezone} onChange={e=>setTimezone(e.target.value)}/></label>
          <label style={field}>Effective from<input aria-label="Configuration effective from" type="date" value={effectiveFrom} onChange={e=>setEffectiveFrom(e.target.value)}/></label>
          <button type="button" disabled={busy||!companyId||!effectiveFrom} onClick={createConfig}>Create configuration</button>
          <h3>Available configurations</h3><ul>{configurations.map(c=><li key={c.id}>{c.payFrequency} · {c.currencyCode} · {c.effectiveFrom}<div><code>{c.id}</code></div></li>)}</ul>
        </section>
        <section style={panel}><h2>Draft pay period</h2>
          <label style={field}>Configuration<select aria-label="Payroll configuration" value={configurationId} onChange={e=>setConfigurationId(e.target.value)}><option value="">Choose configuration</option>{configurations.map(c=><option key={c.id} value={c.id}>{c.payFrequency} from {c.effectiveFrom}</option>)}</select></label>
          <label style={field}>Period start<input aria-label="Payroll period start" type="date" value={startsOn} onChange={e=>setStartsOn(e.target.value)}/></label>
          <label style={field}>Period end<input aria-label="Payroll period end" type="date" value={endsOn} min={startsOn} onChange={e=>setEndsOn(e.target.value)}/></label>
          <label style={field}>Planned pay date<input aria-label="Planned pay date" type="date" value={payOn} onChange={e=>setPayOn(e.target.value)}/></label>
          <button type="button" disabled={busy||!companyId||!configurationId||!startsOn||!endsOn||!payOn} onClick={createPeriod}>Create draft period</button>
          <h3>Recorded draft periods</h3><ul>{periods.map(p=><li key={p.id}>{p.startsOn} – {p.endsOn} · pay {p.payOn} · <strong>{p.status}</strong></li>)}</ul>
        </section>
      </div>
      <section style={panel}><h2>Earnings and deductions catalogue</h2>
        <p>Versioned component definitions only. Tax treatment is UNREVIEWED; no formulas, automatic rates or employee monetary allocations are available.</p>
        <div style={{display:'grid',gridTemplateColumns:'repeat(auto-fit,minmax(220px,1fr))',gap:12}}>
          <label style={field}>Component code<input aria-label="Payroll component code" value={componentCode} onChange={e=>setComponentCode(e.target.value.toUpperCase())} placeholder="BASIC_PAY"/></label>
          <label style={field}>Component name<input aria-label="Payroll component name" value={componentName} onChange={e=>setComponentName(e.target.value)} placeholder="Basic pay"/></label>
          <label style={field}>Kind<select aria-label="Payroll component kind" value={componentKind} onChange={e=>setComponentKind(e.target.value)}><option>EARNING</option><option>DEDUCTION</option></select></label>
          <label style={field}>Effective from<input aria-label="Payroll component effective from" type="date" value={componentEffectiveFrom} onChange={e=>setComponentEffectiveFrom(e.target.value)}/></label>
        </div>
        <button type="button" disabled={busy||!companyId||componentCode.length<2||componentName.trim().length<3} onClick={createComponent}>Register component definition</button>
        <h3>Registered definitions</h3>
        {components.length===0?<p>No component definitions loaded.</p>:<ul>{components.map(c=><li key={c.id}><strong>{c.componentCode}</strong> — {c.componentName} ({c.componentKind}) · {c.effectiveFrom} · {c.treatment}</li>)}</ul>}
      </section>
    </div>
  </main>;
}
