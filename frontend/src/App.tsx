import { useState, type FormEvent } from "react";

type Employee = { id: string; companyId: string; employeeNumber: string; firstName: string; lastName: string; hireDate: string; employmentStatus: string };
type Department = { id: string; code: string; name: string; active: boolean };
type Position = { id: string; code: string; name: string; active: boolean };
type Profile = Employee & { middleName?: string; preferredName?: string; suffix?: string; employmentType: string; departmentId?: string; positionId?: string };

const base = import.meta.env.VITE_API_BASE_URL || "";
const initial = { employeeNumber: "", firstName: "", lastName: "", hireDate: new Date().toISOString().slice(0, 10) };

export default function App() {
  const [token, setToken] = useState("");
  const [companyId, setCompanyId] = useState("");
  const [employees, setEmployees] = useState<Employee[]>([]);
  const [departments, setDepartments] = useState<Department[]>([]);
  const [positions, setPositions] = useState<Position[]>([]);
  const [selected, setSelected] = useState<Profile | null>(null);
  const [input, setInput] = useState(initial);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");

  async function api<T>(path: string, init?: RequestInit): Promise<T> {
    if (!token.trim()) throw new Error("An MFA-verified bearer token is required.");
    const response = await fetch(base + path, {
      ...init,
      headers: { Authorization: `Bearer ${token.trim()}`, ...(init?.body ? { "Content-Type": "application/json" } : {}), ...init?.headers },
    });
    if (!response.ok) throw new Error(`Request failed (HTTP ${response.status}). Check authorization and company scope.`);
    if (response.status === 204) return undefined as T;
    return response.json() as Promise<T>;
  }

  async function load() {
    if (!companyId.trim()) { setError("Enter a company UUID."); return; }
    setBusy(true); setError(""); setMessage("");
    try {
      const company = encodeURIComponent(companyId.trim());
      const [people, teams, jobs] = await Promise.all([
        api<Employee[]>(`/api/v1/personnel/employees?companyId=${company}`),
        api<Department[]>(`/api/v1/personnel/departments?companyId=${company}`),
        api<Position[]>(`/api/v1/personnel/positions?companyId=${company}`),
      ]);
      setEmployees(people); setDepartments(teams); setPositions(jobs); setSelected(null);
      setMessage("Directory synchronized.");
    } catch (ex) { setError(ex instanceof Error ? ex.message : "Unable to load personnel."); }
    finally { setBusy(false); }
  }

  async function register(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setBusy(true); setError(""); setMessage("");
    try {
      const created = await api<Employee>("/api/v1/personnel/employees", {
        method: "POST", body: JSON.stringify({ companyId: companyId.trim(), ...input }),
      });
      setEmployees(previous => [...previous, created].sort((a, b) => a.lastName.localeCompare(b.lastName)));
      setInput(initial); setMessage(`Registered ${created.firstName} ${created.lastName}.`);
    } catch (ex) { setError(ex instanceof Error ? ex.message : "Unable to register employee."); }
    finally { setBusy(false); }
  }

  async function openProfile(employeeId: string) {
    setBusy(true); setError("");
    try { setSelected(await api<Profile>(`/api/v1/personnel/employees/${encodeURIComponent(employeeId)}/profile`)); }
    catch (ex) { setError(ex instanceof Error ? ex.message : "Unable to load profile."); }
    finally { setBusy(false); }
  }

  return <main className="shell">
    <header className="masthead"><div><p className="eyebrow">APEXMEDSUITE / HRM</p><h1>Personnel workspace</h1><p className="subtitle">Employees, organization, and employment records in one place.</p></div><span className="preview">DEVELOPER PREVIEW</span></header>
    <section className="connection" aria-label="API connection">
      <div><label htmlFor="company">Company UUID</label><input id="company" value={companyId} placeholder="00000000-0000-0000-0000-000000000000" onChange={event => setCompanyId(event.target.value)} autoComplete="off" /></div>
      <div><label htmlFor="access">MFA-verified bearer token</label><input id="access" value={token} placeholder="Token (in memory only)" onChange={event => setToken(event.target.value)} type="password" autoComplete="off" /></div>
      <button disabled={busy || !token || !companyId} onClick={load}>Refresh directory</button>
    </section>
    <p className="note">Access tokens are kept only in this browser tab's memory. Do not use this preview with real personnel data.</p>
    {error && <div className="notice error" role="alert">{error}</div>}
    {message && <div className="notice" role="status">{message}</div>}
    <div className="stats"><article><span>Employees</span><strong>{employees.length}</strong></article><article><span>Departments</span><strong>{departments.filter(d => d.active).length}</strong></article><article><span>Positions</span><strong>{positions.filter(p => p.active).length}</strong></article></div>
    <div className="workspace">
      <section className="panel directory"><div className="panel-head"><h2>Employee directory</h2><span>{employees.length} records shown</span></div>
        <div className="table-wrap"><table><thead><tr><th>Employee</th><th>Employee no.</th><th>Status</th><th>Hired</th><th></th></tr></thead><tbody>
          {employees.map(employee => <tr key={employee.id}><td><strong>{employee.firstName} {employee.lastName}</strong></td><td>{employee.employeeNumber}</td><td><span className="status">{employee.employmentStatus}</span></td><td>{employee.hireDate}</td><td><button className="subtle" disabled={busy} onClick={() => openProfile(employee.id)}>Profile</button></td></tr>)}
          {employees.length === 0 && <tr><td colSpan={5} className="empty">Connect to a company to load employees.</td></tr>}
        </tbody></table></div>
      </section>
      <aside className="panel new-employee"><h2>Register employee</h2><form onSubmit={register}>
        <label>Employee number<input required maxLength={50} value={input.employeeNumber} onChange={e => setInput({...input,employeeNumber:e.target.value})} /></label>
        <label>First name<input required maxLength={120} value={input.firstName} onChange={e => setInput({...input,firstName:e.target.value})} /></label>
        <label>Last name<input required maxLength={120} value={input.lastName} onChange={e => setInput({...input,lastName:e.target.value})} /></label>
        <label>Hire date<input type="date" required max={new Date().toISOString().slice(0,10)} value={input.hireDate} onChange={e => setInput({...input,hireDate:e.target.value})} /></label>
        <button type="submit" disabled={busy || !companyId || !token}>Create employee</button>
      </form></aside>
      <section className="panel"><h2>Departments</h2><ul className="catalog">{departments.map(d => <li key={d.id}><strong>{d.name}</strong><small>{d.code} · {d.active ? "Active" : "Inactive"}</small></li>)}{departments.length === 0 && <li className="empty">No departments loaded.</li>}</ul></section>
      <section className="panel"><h2>Job positions</h2><ul className="catalog">{positions.map(p => <li key={p.id}><strong>{p.name}</strong><small>{p.code} · {p.active ? "Active" : "Inactive"}</small></li>)}{positions.length === 0 && <li className="empty">No positions loaded.</li>}</ul></section>
    </div>
    {selected && <section className="panel profile"><div className="panel-head"><h2>Employee profile</h2><button className="subtle" onClick={() => setSelected(null)}>Close</button></div>
      <h3>{selected.firstName} {selected.lastName}</h3><dl><div><dt>Employee number</dt><dd>{selected.employeeNumber}</dd></div><div><dt>Employment</dt><dd>{selected.employmentStatus} / {selected.employmentType}</dd></div><div><dt>Hire date</dt><dd>{selected.hireDate}</dd></div><div><dt>Preferred name</dt><dd>{selected.preferredName || "—"}</dd></div></dl>
    </section>}
  </main>;
}
