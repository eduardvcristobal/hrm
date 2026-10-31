import { useEffect, useMemo, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import './hrms-prototype.css';

type Module = { key: string; label: string; icon: string; group: string; description: string };
const modules: Module[] = [
  { key:'overview',label:'Overview',icon:'◫',group:'WORKSPACE',description:'Your people operations at a glance' },
  { key:'employees',label:'Employee Directory',icon:'♙',group:'PEOPLE',description:'Manage employee profiles and employment records' },
  { key:'onboarding',label:'Onboarding',icon:'✳',group:'PEOPLE',description:'Track new hires from offer to first day' },
  { key:'organization',label:'Organization',icon:'▦',group:'PEOPLE',description:'Explore departments, teams and reporting lines' },
  { key:'documents',label:'Documents',icon:'▤',group:'PEOPLE',description:'Employee records, requests and document lifecycle' },
  { key:'attendance',label:'Time & Attendance',icon:'◷',group:'TIME & PAY',description:'Monitor clock records, exceptions and timesheets' },
  { key:'schedules',label:'Shifts & Schedules',icon:'▦',group:'TIME & PAY',description:'Plan work patterns, rosters and shifts' },
  { key:'leave',label:'Leave Management',icon:'☼',group:'TIME & PAY',description:'Leave requests, calendars and balances' },
  { key:'payroll',label:'Payroll',icon:'▥',group:'TIME & PAY',description:'Review payroll runs and reconciliation' },
  { key:'benefits',label:'Benefits',icon:'♡',group:'TIME & PAY',description:'Benefits programs and enrollments' },
  { key:'recruitment',label:'Recruitment',icon:'⊕',group:'TALENT',description:'Open roles, applications and hiring pipeline' },
  { key:'performance',label:'Performance',icon:'◇',group:'TALENT',description:'Goals, reviews and feedback cycles' },
  { key:'training',label:'Learning',icon:'▧',group:'TALENT',description:'Training courses and learning progress' },
  { key:'analytics',label:'Reports & Analytics',icon:'▥',group:'INSIGHTS',description:'People metrics and operational reporting' },
  { key:'approvals',label:'Approval Center',icon:'✓',group:'INSIGHTS',description:'Review requests and pending decisions' },
  { key:'announcements',label:'Announcements',icon:'♧',group:'INSIGHTS',description:'Company news and internal updates' },
  { key:'audit',label:'Audit Logs',icon:'▣',group:'ADMINISTRATION',description:'Inspect activity and security events' },
  { key:'team',label:'Users & Roles',icon:'♧',group:'ADMINISTRATION',description:'Administrators, staff access and invitations' },
  { key:'settings',label:'Settings',icon:'⚙',group:'ADMINISTRATION',description:'Workspace, preferences and integrations' },
];
const employees = [
  { name:'Sofia Reyes',initials:'SR',role:'Senior Product Designer',team:'Product',status:'Active',code:'EMP-1001',email:'sofia.reyes@example.test',color:'lilac' },
  { name:'Liam Santos',initials:'LS',role:'Backend Engineer',team:'Engineering',status:'Active',code:'EMP-1002',email:'liam.santos@example.test',color:'mint' },
  { name:'Isabella Cruz',initials:'IC',role:'People Operations Lead',team:'Human Resources',status:'Active',code:'EMP-1003',email:'isabella.cruz@example.test',color:'peach' },
  { name:'Noah Garcia',initials:'NG',role:'Finance Analyst',team:'Finance',status:'On Leave',code:'EMP-1004',email:'noah.garcia@example.test',color:'sky' },
  { name:'Mia Mendoza',initials:'MM',role:'Customer Success Lead',team:'Operations',status:'Active',code:'EMP-1005',email:'mia.mendoza@example.test',color:'lilac' },
  { name:'Ethan Lim',initials:'EL',role:'UI Engineer',team:'Engineering',status:'Active',code:'EMP-1006',email:'ethan.lim@example.test',color:'mint' },
];
const peopleTasks = [
  { title:'Approve attendance correction',info:'Liam Santos · Yesterday, 9:42 AM',tag:'Attendance',urgent:true },
  { title:'Review leave application',info:'Noah Garcia · 3-day annual leave',tag:'Leave',urgent:false },
  { title:'Complete onboarding checklist',info:'Ava Torres · Starts November 3',tag:'Onboarding',urgent:false },
];
const calendar = [
  {day:'MON',date:'02',label:'Team stand-up',time:'9:00 AM'},
  {day:'TUE',date:'03',label:'New hire orientation',time:'10:30 AM'},
  {day:'WED',date:'04',label:'Payroll review',time:'2:00 PM'},
  {day:'THU',date:'05',label:'Performance check-in',time:'11:00 AM'},
];
const titles: Record<string,string> = {
  employees:'People directory', onboarding:'Employee onboarding', organization:'Organization chart',
  documents:'Documents & records', attendance:'Time & attendance', schedules:'Shifts & schedules',
  leave:'Leave management', payroll:'Payroll workspace', benefits:'Benefits & enrollment',
  recruitment:'Recruitment pipeline', performance:'Performance management', training:'Learning & development',
  analytics:'Reports & analytics', approvals:'Approval center', announcements:'Announcements',
  audit:'Audit activity', team:'Users & permissions', settings:'Workspace settings'
};
const rowsByModule: Record<string, { a:string;b:string;c:string;d:string }[]> = {
  onboarding:[
    {a:'Ava Torres',b:'Engineering',c:'Nov 03, 2026',d:'In progress'},
    {a:'Lucas Tan',b:'Finance',c:'Nov 09, 2026',d:'Pending'},
    {a:'Chloe Ramos',b:'Operations',c:'Nov 16, 2026',d:'In progress'}],
  documents:[
    {a:'Employment contract',b:'Sofia Reyes',c:'Oct 30, 2026',d:'Verified'},
    {a:'Government ID',b:'Liam Santos',c:'Oct 29, 2026',d:'In review'},
    {a:'Tax declaration',b:'Noah Garcia',c:'Oct 28, 2026',d:'Pending'}],
  attendance:[
    {a:'Sofia Reyes',b:'08:56 AM',c:'05:45 PM',d:'On time'},
    {a:'Liam Santos',b:'09:12 AM',c:'06:02 PM',d:'Exception'},
    {a:'Mia Mendoza',b:'08:47 AM',c:'05:30 PM',d:'On time'}],
  schedules:[
    {a:'Morning shift',b:'09:00 AM – 06:00 PM',c:'18 employees',d:'Published'},
    {a:'Early shift',b:'07:00 AM – 04:00 PM',c:'8 employees',d:'Published'},
    {a:'Night shift',b:'10:00 PM – 06:00 AM',c:'5 employees',d:'Draft'}],
  leave:[
    {a:'Noah Garcia',b:'Annual leave',c:'Nov 05 – Nov 07',d:'Pending'},
    {a:'Mia Mendoza',b:'Sick leave',c:'Oct 28',d:'Approved'},
    {a:'Sofia Reyes',b:'Personal leave',c:'Nov 13',d:'Pending'}],
  payroll:[
    {a:'October 2026',b:'Monthly payroll',c:'31 employees',d:'In review'},
    {a:'September 2026',b:'Monthly payroll',c:'30 employees',d:'Demo only'}],
  recruitment:[
    {a:'Frontend Engineer',b:'Engineering',c:'12 candidates',d:'Interviewing'},
    {a:'HR Generalist',b:'Human Resources',c:'8 candidates',d:'Screening'},
    {a:'Account Manager',b:'Operations',c:'6 candidates',d:'Open'}],
  approvals:[
    {a:'Attendance correction',b:'Liam Santos',c:'Oct 30, 2026',d:'Pending'},
    {a:'Annual leave',b:'Noah Garcia',c:'Nov 05, 2026',d:'Pending'},
    {a:'Document verification',b:'Sofia Reyes',c:'Oct 29, 2026',d:'In review'}],
};
function Badge({value}:{value:string}) {
  const kind = /active|approved|verified|published|on time/i.test(value) ? 'good' :
    /pending|review|progress|screening|interview/i.test(value) ? 'warn' :
    /exception/i.test(value) ? 'bad' : 'neutral';
  return <span className={'hp-badge hp-badge-'+kind}>{value}</span>;
}
function Avatar({initials,color='lilac',small=false}:{initials:string;color?:string;small?:boolean}) {
  return <span className={'hp-avatar hp-avatar-'+color+(small?' hp-avatar-small':'')}>{initials}</span>;
}
export default function HrmsPrototype(){
  const navigate = useNavigate();
  const location = useLocation();
  const active = location.pathname.split('/')[2] || 'overview';
  const current = modules.find(m=>m.key===active) || modules[0];
  const [sidebar,setSidebar]=useState(false);
  const [profile,setProfile]=useState(false);
  const [notices,setNotices]=useState(false);
  const [search,setSearch]=useState('');
  const [status,setStatus]=useState('All statuses');
  const [toast,setToast]=useState('');
  const [dark,setDark]=useState(false);
  const [selectedPerson,setSelectedPerson]=useState<string|null>(null);
  const [tab,setTab]=useState('All');
  const [date,setDate]=useState('This month');
  const go=(key:string)=>{navigate('/prototype/'+key);setSidebar(false);setSearch('');setSelectedPerson(null);setTab('All')};
  const notify=(text:string)=>{setToast(text);};
  const shownEmployees=useMemo(()=>employees.filter(e=>
    (status==='All statuses'||e.status===status)&&
    [e.name,e.role,e.team,e.code].some(v=>v.toLowerCase().includes(search.toLowerCase()))
  ),[search,status]);
  useEffect(()=>{setToast('')},[active]);
  useEffect(()=>{
    const listener=(event:KeyboardEvent)=>{if((event.metaKey||event.ctrlKey)&&event.key.toLowerCase()==='k'){event.preventDefault();document.getElementById('hp-search')?.focus()}if(event.key==='Escape'){setProfile(false);setNotices(false);setSidebar(false);setSelectedPerson(null)}};
    window.addEventListener('keydown',listener);return()=>window.removeEventListener('keydown',listener);
  },[]);
  return <div className={'hp-app'+(dark?' hp-dark':'')}>
    {sidebar&&<button className="hp-shade" aria-label="Close sidebar" onClick={()=>setSidebar(false)}/>}
    <aside className={'hp-sidebar'+(sidebar?' hp-sidebar-open':'')}>
      <button className="hp-brand" onClick={()=>go('overview')} aria-label="Apex People overview"><span className="hp-brand-mark">✳</span><span><strong>apex<span>people</span></strong><small>HR MANAGEMENT</small></span></button>
      <div className="hp-workspace"><div className="hp-workspace-icon">A</div><div><strong>Acme Studio</strong><small>Enterprise workspace</small></div><span className="hp-workspace-chevron">⌄</span></div>
      <div className="hp-nav-scroll">
        {['WORKSPACE','PEOPLE','TIME & PAY','TALENT','INSIGHTS','ADMINISTRATION'].map(group=><div className="hp-nav-group" key={group}>
          <p>{group}</p>
          {modules.filter(m=>m.group===group).map(m=><button key={m.key} className={'hp-nav-link'+(active===m.key?' active':'')} onClick={()=>go(m.key)} aria-current={active===m.key?'page':undefined}>
            <span className="hp-nav-icon">{m.icon}</span><span>{m.label}</span>{m.key==='approvals'&&<i>3</i>}
          </button>)}
        </div>)}
      </div>
      <div className="hp-side-bottom"><div className="hp-help-illustration">✦</div><strong>Need a hand?</strong><p>Explore the prototype and share feedback before we build further.</p><button onClick={()=>notify('Demo help center — no external service is connected.')}>Help center ↗</button></div>
    </aside>
    <div className="hp-body">
      <header className="hp-topbar">
        <div className="hp-top-left"><button className="hp-icon-button hp-mobile-toggle" onClick={()=>setSidebar(true)} aria-label="Open navigation">☰</button><div className="hp-crumb"><span>Workspace</span><span className="hp-crumb-divider">/</span><strong>{current.label}</strong></div></div>
        <div className="hp-top-right">
          <div className="hp-top-search"><span>⌕</span><input id="hp-search" aria-label="Search employees and navigation" value={search} onChange={e=>setSearch(e.target.value)} placeholder="Search anything..." /><kbd>⌘ K</kbd>
            {search&&active!=='employees'&&<div className="hp-search-results">{modules.filter(m=>m.label.toLowerCase().includes(search.toLowerCase())).slice(0,5).map(m=><button key={m.key} onClick={()=>go(m.key)}>{m.icon} &nbsp; {m.label}</button>)}{employees.filter(e=>e.name.toLowerCase().includes(search.toLowerCase())).slice(0,4).map(e=><button key={e.code} onClick={()=>{go('employees');setSelectedPerson(e.name)}}>♙ &nbsp; {e.name}</button>)}</div>}
          </div>
          <span className="hp-head-separator"/>
          <div className="hp-relative"><button className="hp-icon-button" aria-label="Notifications" aria-expanded={notices} onClick={()=>{setNotices(!notices);setProfile(false)}}>♧<span className="hp-notice-dot"/></button>
            {notices&&<div className="hp-menu hp-notifications"><div className="hp-menu-title"><strong>Notifications</strong><Badge value="3 new"/></div>{peopleTasks.map(t=><button key={t.title} onClick={()=>{setNotices(false);go('approvals')}}><span className="hp-notif-bullet">●</span><span><strong>{t.title}</strong><small>{t.info}</small></span></button>)}</div>}
          </div>
          <div className="hp-relative"><button className="hp-profile-trigger" onClick={()=>{setProfile(!profile);setNotices(false)}} aria-label="Open account menu" aria-expanded={profile}><Avatar initials="JD" small/><span className="hp-user-name"><strong>Jordan Davis</strong><small>HR Administrator</small></span><span className="hp-down">⌄</span></button>
            {profile&&<div className="hp-menu hp-profile-menu"><div className="hp-profile-card"><Avatar initials="JD"/><div><strong>Jordan Davis</strong><small>jordan.davis@example.test</small><Badge value="Demo account"/></div></div><button onClick={()=>{setProfile(false);go('settings')}}>⚙ &nbsp; Account settings</button><button onClick={()=>{setProfile(false);setDark(!dark)}}>◐ &nbsp; {dark?'Light appearance':'Dark appearance'}</button><div className="hp-menu-divider"/><button className="hp-logout" onClick={()=>navigate('/')}>↪ &nbsp; Exit prototype / Log out</button></div>}
          </div>
        </div>
      </header>
      <main className="hp-main">
        <div className="hp-preview-banner"><span>✦ &nbsp; INTERACTIVE PROTOTYPE</span><p>Sample data only. Pages and actions are for product review; no employee records or payroll are changed.</p><button onClick={()=>navigate('/dashboard')}>Open real workspace ↗</button></div>
        {active==='overview'?<>
          <div className="hp-page-head hp-overview-head"><div><div className="hp-date-eyebrow">SATURDAY, OCTOBER 31, 2026 <span className="hp-small-dot">•</span> PEOPLE OPERATIONS</div><h1>Good morning, Jordan <span className="hp-wave">✳</span></h1><p>Here's what's happening with your team today.</p></div><div className="hp-page-actions"><button className="hp-secondary" onClick={()=>notify('Demo report is not connected to a live dataset.')}>⇩ &nbsp; Export report</button><button className="hp-primary" onClick={()=>go('employees')}>＋ &nbsp; Add employee</button></div></div>
          <div className="hp-stats">
            {[{icon:'♧',label:'Total employees',value:'248',delta:'↑ 8.2%',desc:'vs. last month',tint:'indigo'},
              {icon:'✓',label:'Present today',value:'231',delta:'93.1%',desc:'attendance rate',tint:'green'},
              {icon:'☼',label:'On leave today',value:'12',delta:'4.8%',desc:'of total workforce',tint:'yellow'},
              {icon:'▣',label:'Pending approvals',value:'08',delta:'3 urgent',desc:'require your review',tint:'rose'}].map((s,i)=><button key={s.label} className="hp-stat" onClick={()=>go(['employees','attendance','leave','approvals'][i])}><div className={'hp-stat-icon hp-tint-'+s.tint}>{s.icon}</div><div className="hp-stat-label">{s.label}<span>↗</span></div><div className="hp-stat-value">{s.value}</div><div className="hp-stat-foot"><strong>{s.delta}</strong> {s.desc}</div></button>)}
          </div>
          <div className="hp-dashboard-grid">
            <section className="hp-card hp-chart-card"><div className="hp-card-head"><div><h2>Workforce overview</h2><p>Headcount growth over time</p></div><select aria-label="Workforce date range" value={date} onChange={e=>setDate(e.target.value)}><option>This month</option><option>Last 6 months</option><option>This year</option></select></div><div className="hp-chart-info"><strong>248 <small>employees</small></strong><span>↗ +12.4% this year</span></div><div className="hp-chart"><div className="hp-gridlines"><span>250</span><span>200</span><span>150</span><span>100</span><span>50</span></div><svg viewBox="0 0 700 210" preserveAspectRatio="none" aria-label="Illustrative workforce growth chart" role="img"><defs><linearGradient id="hp-area" x1="0" y1="0" x2="0" y2="1"><stop offset="0%" stopColor="#7669ed" stopOpacity=".25"/><stop offset="100%" stopColor="#7669ed" stopOpacity="0"/></linearGradient></defs><path d="M0 172 C50 170 60 160 116 151 S220 152 278 119 S360 132 411 89 S492 108 540 61 S635 63 700 23 L700 210 L0 210Z" fill="url(#hp-area)"/><path d="M0 172 C50 170 60 160 116 151 S220 152 278 119 S360 132 411 89 S492 108 540 61 S635 63 700 23" fill="none" stroke="#7568e9" strokeWidth="3.5" strokeLinecap="round"/><circle cx="700" cy="23" r="6" fill="#7568e9" stroke="white" strokeWidth="3"/></svg></div><div className="hp-chart-months">{['May','Jun','Jul','Aug','Sep','Oct','Nov'].map(t=><span key={t}>{t}</span>)}</div></section>
            <section className="hp-card hp-attendance-card"><div className="hp-card-head"><div><h2>Today’s attendance</h2><p>Saturday, October 31</p></div><button className="hp-link" onClick={()=>go('attendance')}>View details ↗</button></div><div className="hp-ring-wrap"><div className="hp-ring"><div><strong>93%</strong><small>Attendance</small></div></div><div className="hp-ring-legend"><div><span className="hp-legend-dot green"/>Present <strong>231</strong></div><div><span className="hp-legend-dot yellow"/>On leave <strong>12</strong></div><div><span className="hp-legend-dot purple"/>Absent <strong>5</strong></div></div></div><div className="hp-attendance-footer">✦ Attendance looks good today <strong>+2.3%</strong></div></section>
            <section className="hp-card hp-activity-card"><div className="hp-card-head"><div><h2>Needs your attention <span className="hp-count">3</span></h2><p>Review and take action</p></div><button className="hp-link" onClick={()=>go('approvals')}>View all ↗</button></div>{peopleTasks.map((t,i)=><button className="hp-task" key={t.title} onClick={()=>go('approvals')}><div className={'hp-task-icon hp-task-'+i}>{i===0?'◷':i===1?'☼':'✳'}</div><div><strong>{t.title}</strong><small>{t.info}</small></div><span>›</span></button>)}</section>
            <section className="hp-card hp-schedule-card"><div className="hp-card-head"><div><h2>Upcoming events</h2><p>What's on the calendar</p></div><button className="hp-link" onClick={()=>go('schedules')}>View calendar ↗</button></div>{calendar.map(c=><button className="hp-event" key={c.date} onClick={()=>go('schedules')}><div className="hp-event-date"><small>{c.day}</small><strong>{c.date}</strong></div><div><strong>{c.label}</strong><small>◷ &nbsp; {c.time}</small></div><span>›</span></button>)}</section>
          </div>
          <section className="hp-card hp-people-card"><div className="hp-card-head"><div><h2>Recently added employees</h2><p>Latest members of your growing team</p></div><button className="hp-link" onClick={()=>go('employees')}>View directory ↗</button></div><PeopleTable people={employees.slice(0,4)} onSelect={setSelectedPerson}/></section>
        </>:<>
          <div className="hp-page-head"><div><div className="hp-date-eyebrow">WORKSPACE <span className="hp-small-dot">/</span> {current.group}</div><h1>{titles[active]||current.label}</h1><p>{current.description}</p></div><div className="hp-page-actions"><button className="hp-secondary" onClick={()=>notify('Sample export is not connected to live data.')}>⇩ &nbsp; Export</button><button className="hp-primary" onClick={()=>notify('This is a preview. Create and edit workflows will be enabled as each module is implemented.')}>＋ &nbsp; {active==='employees'?'Add employee':active==='leave'?'New request':active==='announcements'?'New announcement':'Create new'}</button></div></div>
          <div className="hp-module-intro"><span className="hp-module-illustration">{current.icon}</span><div><strong>{current.label} workspace</strong><p>This is an interactive design preview using representative sample records. Existing backend capabilities remain available separately in the real workspace.</p></div><Badge value="Prototype view"/></div>
          {active==='employees'?<section className="hp-card hp-directory"><div className="hp-card-head"><div><h2>All employees <span className="hp-count">248</span></h2><p>Search and explore your team</p></div><button className="hp-link" onClick={()=>notify('Employee import is not active in the prototype.')}>Import employees ↗</button></div><div className="hp-filters"><label>⌕ <input value={search} onChange={e=>setSearch(e.target.value)} placeholder="Search name, team, job title..." /></label><select value={status} onChange={e=>setStatus(e.target.value)} aria-label="Filter by employment status"><option>All statuses</option><option>Active</option><option>On Leave</option></select></div><PeopleTable people={shownEmployees} onSelect={setSelectedPerson}/></section>:
          active==='organization'?<section className="hp-card hp-org"><h2>Organization structure</h2><p>Explore the sample reporting hierarchy.</p><div className="hp-org-root"><Avatar initials="JD"/><strong>Jordan Davis</strong><span>HR Administrator</span></div><div className="hp-org-branches">{[{i:'IC',name:'Isabella Cruz',team:'Human Resources'},{i:'SR',name:'Sofia Reyes',team:'Product'},{i:'LS',name:'Liam Santos',team:'Engineering'},{i:'NG',name:'Noah Garcia',team:'Finance'}].map(p=><button key={p.i} onClick={()=>{go('employees');setSelectedPerson(p.name)}}><Avatar initials={p.i}/><strong>{p.name}</strong><small>{p.team}</small></button>)}</div></section>:
          active==='analytics'?<div className="hp-analytics-grid">{[{k:'Headcount',v:'248',d:'+8.2% month over month'},{k:'Average tenure',v:'3.2 yr',d:'Across all departments'},{k:'Retention rate',v:'96.8%',d:'Illustrative trailing 12 months'},{k:'Attendance rate',v:'93.1%',d:'Illustrative current period'}].map(m=><section className="hp-card hp-mini-stat" key={m.k}><small>{m.k}</small><strong>{m.v}</strong><p>{m.d}</p></section>)}<section className="hp-card hp-wide"><h2>Department breakdown</h2>{[['Engineering',42],['Operations',31],['Product',22],['Finance',17],['Human Resources',12]].map(([name,val])=><div className="hp-bar-row" key={name}><span>{name}</span><div><i style={{width:`${Number(val)*2}%`}}/></div><strong>{val}</strong></div>)}</section></div>:
          active==='settings'?<div className="hp-settings-grid">{[{title:'Workspace profile',description:'Company name, address, logo, and operating preferences',icon:'▦'},{title:'Security & sign-in',description:'MFA, passwords, sessions and authentication controls',icon:'♢'},{title:'Roles & permissions',description:'Control access to every module',icon:'♧'},{title:'Notifications',description:'Alerts, email preferences and approvals',icon:'♧'},{title:'Regions & localization',description:'Timezone, language, currency and holidays',icon:'◎'},{title:'Integrations',description:'Payroll, devices and other external systems',icon:'↗'}].map(m=><button className="hp-card hp-settings-tile" key={m.title} onClick={()=>notify('Preview only — workspace settings have not been changed.')}><span>{m.icon}</span><div><h2>{m.title}</h2><p>{m.description}</p></div><b>›</b></button>)}</div>:
          <section className="hp-card hp-generic"><div className="hp-card-head"><div><h2>{current.label} <span className="hp-count">{(rowsByModule[active]||peopleTasks).length}</span></h2><p>Sample records for layout and workflow review</p></div><div className="hp-segment">{['All','Pending','Completed'].map(v=><button className={tab===v?'active':''} key={v} onClick={()=>setTab(v)}>{v}</button>)}</div></div><div className="hp-table-wrap"><table><thead><tr><th>NAME / ITEM</th><th>DETAILS</th><th>DATE / SCOPE</th><th>STATUS</th><th/></tr></thead><tbody>{(rowsByModule[active]||[
            {a:'Sample workspace item',b:current.description,c:'October 2026',d:'Demo only'},
            {a:'Review workflow',b:'Planning and design preview',c:'November 2026',d:'Pending'},
            {a:'Configuration',b:'Requires integration and acceptance',c:'Not scheduled',d:'Prototype view'}
          ]).filter(r=>tab==='All'||(tab==='Pending'?/pending|review|progress|screening|interview/i.test(r.d):/approved|verified|published|on time/i.test(r.d))).map((r,i)=><tr key={r.a+i}><td><strong>{r.a}</strong></td><td>{r.b}</td><td>{r.c}</td><td><Badge value={r.d}/></td><td><button className="hp-row-link" onClick={()=>notify('Read-only demo record — no backend change was made.')}>View ›</button></td></tr>)}</tbody></table></div><div className="hp-table-foot">Showing sample records · Prototype-only data</div></section>}
        </>}
        <footer className="hp-footer"><span>© 2026 Apex People · HRMS Prototype</span><span>Privacy first · Accessible by design · No live employee data</span></footer>
      </main>
    </div>
    {selectedPerson&&<div className="hp-modal-backdrop" onClick={()=>setSelectedPerson(null)}><section className="hp-person-modal" role="dialog" aria-modal="true" aria-label="Employee details" onClick={e=>e.stopPropagation()}><button className="hp-modal-close" onClick={()=>setSelectedPerson(null)} aria-label="Close employee details">×</button>{(()=>{const e=employees.find(p=>p.name===selectedPerson);return e?<><Avatar initials={e.initials} color={e.color}/><h2>{e.name}</h2><p>{e.role}</p><Badge value={e.status}/><div className="hp-detail"><span>Employee ID</span><strong>{e.code}</strong></div><div className="hp-detail"><span>Department</span><strong>{e.team}</strong></div><div className="hp-detail"><span>Work email</span><strong>{e.email}</strong></div><div className="hp-modal-actions"><button onClick={()=>setSelectedPerson(null)}>Close</button><button onClick={()=>notify('Employee profile editing is not enabled in demo mode.')}>Edit profile ↗</button></div></>:null})()}</section></div>}
    {toast&&<div className="hp-toast" role="status"><span>ℹ</span>{toast}<button onClick={()=>setToast('')} aria-label="Dismiss notice">×</button></div>}
  </div>;
}
function PeopleTable({people,onSelect}:{people:typeof employees;onSelect:(name:string)=>void}){
  return <div className="hp-table-wrap"><table><thead><tr><th>EMPLOYEE</th><th>EMPLOYEE ID</th><th>DEPARTMENT</th><th>STATUS</th><th/></tr></thead><tbody>{people.map(e=><tr key={e.code}><td><button className="hp-person-link" onClick={()=>onSelect(e.name)}><Avatar initials={e.initials} color={e.color} small/><span><strong>{e.name}</strong><small>{e.role}</small></span></button></td><td>{e.code}</td><td>{e.team}</td><td><Badge value={e.status}/></td><td><button className="hp-row-link" onClick={()=>onSelect(e.name)}>View ›</button></td></tr>)}</tbody></table>{people.length===0&&<p className="hp-empty">No employees match your filters.</p>}</div>;
}
