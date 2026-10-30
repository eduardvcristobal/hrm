import { useEffect, useRef, useState, type FormEvent, type KeyboardEvent as ReactKeyboardEvent, type ReactNode } from 'react';
import { GoogleLogin, type CredentialResponse } from '@react-oauth/google';
import QRCode from 'qrcode';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import './styles.css';

const TOKEN_KEY = 'apexHrm.accessToken';
const EXPIRY_KEY = 'apexHrm.expiresAt';

type CurrentUser = {
  id: string;
  tenantId: string | null;
  email: string;
  roles: string[];
  platformAdmin: boolean;
  workspaceName?: string | null;
};

type AccessToken = {
  value: string;
  expiresAt: string;
};

type AccountSession = {
  id: string;
  issuedAt: string;
  lastUsedAt: string | null;
  expiresAt: string;
  current: boolean;
};
type BillingPlan = { code: string; name: string; description: string; amount: number; currency: string; billingInterval: 'MONTHLY'|'ANNUAL'; trialDays: number; expiryNoticeDays: number; featureCodes: string[] };
type BillingRequest = { id: string; planCode: string; planName: string; amount: number; currency: string; billingInterval: 'MONTHLY'|'ANNUAL'; paymentReference: string; note: string; status: string; submittedAt: string };
type BillingSubscription = { planCode: string; planName: string; amount: number; currency: string; billingInterval: 'MONTHLY'|'ANNUAL'; status: string; trialEndsAt: string|null; periodEndsAt: string|null; expiryNoticeDays: number; paymentQrDataUrl: string|null; paymentQrFileName: string|null; availablePlans: BillingPlan[]; pendingRequest: BillingRequest|null };

type MessageKind = 'error' | 'success';
const GOOGLE_CLIENT_ID = import.meta.env.VITE_GOOGLE_CLIENT_ID ?? '';

function Icon({ name }: { name: 'mark' | 'mail' | 'lock' | 'eye' | 'shield' | 'arrow' }) {
  if (name === 'mark') {
    return <svg viewBox="0 0 40 40" aria-hidden="true"><path d="M20 5.5 34.5 20 20 34.5 5.5 20 20 5.5Z" /><path d="M20 12v16M12 20h16" /><circle cx="20" cy="20" r="3.2" /></svg>;
  }
  if (name === 'mail') {
    return <svg viewBox="0 0 24 24" aria-hidden="true"><rect x="3" y="5" width="18" height="14" rx="3" /><path d="m4 7 8 6 8-6" /></svg>;
  }
  if (name === 'lock') {
    return <svg viewBox="0 0 24 24" aria-hidden="true"><rect x="4" y="10" width="16" height="11" rx="2.5" /><path d="M8 10V7a4 4 0 0 1 8 0v3M12 14v3" /></svg>;
  }
  if (name === 'eye') {
    return <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M2.5 12s3.2-6 9.5-6 9.5 6 9.5 6-3.2 6-9.5 6-9.5-6-9.5-6Z" /><circle cx="12" cy="12" r="2.7" /></svg>;
  }
  if (name === 'shield') {
    return <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3 19 6v5c0 4.5-2.9 8-7 10-4.1-2-7-5.5-7-10V6l7-3Z" /><path d="m9 12 2 2 4-4" /></svg>;
  }
  return <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 12h14m-6-6 6 6-6 6" /></svg>;
}

function BrandMark({ mobile = false }: { mobile?: boolean }) {
  return <span className={`brand-mark${mobile ? ' brand-mark-mobile' : ''}`}><Icon name="mark" /></span>;
}

function Message({ text, kind = 'error' }: { text: string; kind?: MessageKind }) {
  if (!text) return null;
  return <div className="form-message" data-kind={kind} role={kind === 'error' ? 'alert' : 'status'} aria-live="polite">{text}</div>;
}

export function InvitationAcceptance() {
  const location = useLocation();
  const inviteToken = new URLSearchParams(location.search).get('token') || '';
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');
  const [accepted, setAccepted] = useState(false);
  async function accept(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setMessage('');
    if (password !== confirmPassword) { setMessage('The passwords do not match.'); return; }
    setBusy(true);
    try {
      const csrf = await fetchCsrfHeaders();
      const response = await fetch('/api/v1/invitations/accept', { method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json', ...csrf }, body: JSON.stringify({ token: inviteToken, password }) });
      if (!response.ok) throw new Error(await responseError(response));
      setAccepted(true);
    } catch (error) { setMessage(error instanceof Error ? error.message : 'Could not accept this invitation.'); }
    finally { setBusy(false); }
  }
  async function acceptWithGoogle(response: CredentialResponse) {
    if (!response.credential) { setMessage('Google did not return a verified account. Please try again.'); return; }
    setBusy(true); setMessage('');
    try {
      const csrf = await fetchCsrfHeaders();
      const result = await fetch('/api/v1/invitations/accept/google', { method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json', ...csrf }, body: JSON.stringify({ token: inviteToken, idToken: response.credential }) });
      if (!result.ok) throw new Error(await responseError(result));
      setAccepted(true);
    } catch (error) { setMessage(error instanceof Error ? error.message : 'Could not accept this invitation with Google.'); }
    finally { setBusy(false); }
  }
  return <div className="invite-accept-page"><section className="invite-accept-card"><div className="mobile-brand"><BrandMark /><span><strong>Apex</strong> People</span></div><p className="eyebrow">TEAM INVITATION</p><h1>{accepted ? 'Your account is ready' : 'Join your workspace'}</h1><p className="account-page-intro">{accepted ? 'Your invitation has been accepted. You can now sign in to your workspace.' : 'Activate your staff account with a password or your invited Google account.'}</p>{!inviteToken ? <Message text="This invitation link is missing its token. Ask your workspace administrator for a new invitation." /> : accepted ? <Link className="security-submit invite-signin-link" to="/">Continue to sign in <span>→</span></Link> : <><form className="invite-accept-form" onSubmit={event => void accept(event)}><label className="field-label" htmlFor="invite-password">Password</label><div className="input-wrap"><input id="invite-password" type="password" autoComplete="new-password" minLength={12} maxLength={72} required value={password} onChange={event => setPassword(event.currentTarget.value)} /></div><label className="field-label" htmlFor="invite-confirm-password">Confirm password</label><div className="input-wrap"><input id="invite-confirm-password" type="password" autoComplete="new-password" minLength={12} maxLength={72} required value={confirmPassword} onChange={event => setConfirmPassword(event.currentTarget.value)} /></div><p className="invite-password-hint">Use at least 12 characters. This invitation expires after 48 hours and can only be used once.</p><button type="submit" className="security-submit" disabled={busy}>{busy ? 'Activating account…' : 'Accept with password'}</button></form><div className="invite-google-divider"><span>OR</span></div>{GOOGLE_CLIENT_ID ? <div className={`invite-google-button${busy ? ' is-busy' : ''}`}><GoogleLogin onSuccess={result => void acceptWithGoogle(result)} onError={() => setMessage('Google sign-in was cancelled or failed.')} text="continue_with" theme="outline" shape="rectangular" size="large" width="360" /></div> : <p className="invite-password-hint">Google sign-in is not configured for this app.</p>}<Message text={message} /></>}</section></div>;
}

async function fetchCsrfHeaders(): Promise<Record<string, string>> {
  const response = await fetch('/api/v1/security/csrf', { credentials: 'same-origin' });
  if (!response.ok) throw new Error('Could not start a secure sign-in session. Check that the HRM service is running.');
  const csrf = await response.json() as { headerName?: string; token: string };
  return { [csrf.headerName || 'X-CSRF-TOKEN']: csrf.token };
}

async function responseError(response: Response): Promise<string> {
  let detail = '';
  try {
    const body = await response.json() as { detail?: string; message?: string; title?: string; error?: string };
    detail = body.detail || body.message || body.title || body.error || '';
  } catch { /* The service may return an empty response. */ }
  if (response.status === 429) return 'Too many sign-in attempts. Please wait a few minutes and try again.';
  if (response.status === 403 && /authenticator enrollment required/i.test(detail)) {
    return 'This account needs authenticator setup before administrator tools are available. Sign in to open the setup page.';
  }
  if (response.status === 428) return 'This account has an authenticator enabled. Enter its current six-digit code to finish signing in.';
  if (response.status === 401 && /invalid mfa code|authenticator code/i.test(detail)) return 'That authenticator code is incorrect or expired. Enter the current six-digit code and try again.';
  if (response.status === 401) return 'Those details could not be verified. Check your email or username, password, Google account, and authenticator code.';
  if (response.status === 403) return detail || 'This action requires an account with the appropriate administrator role. Platform operations may also require a verified authenticator session.';
  return detail || 'We couldn’t complete that request. Please try again.';
}

type WorkspaceAccount = { id: string; email: string; enabled: boolean; roles: string[] };
type WorkspaceInvitation = { id: string; email: string; role: string; createdAt: string; expiresAt: string };
type CreatedInvitation = WorkspaceInvitation & { acceptanceUrl: string };
const STAFF_ROLES = [
  { value: 'HR_ADMIN', label: 'HR administrator', detail: 'Manage workspace staff and people operations' },
  { value: 'PAYROLL_OFFICER', label: 'Payroll officer', detail: 'Access payroll administration' },
  { value: 'MANAGER', label: 'Manager', detail: 'Manage team operations' },
  { value: 'EMPLOYEE', label: 'Employee', detail: 'Standard staff access' },
  { value: 'AUDITOR', label: 'Auditor', detail: 'Read-only oversight access' },
];
function staffRoleLabel(role: string) { return STAFF_ROLES.find(item => item.value === role)?.label || (role === 'TENANT_ADMIN' ? 'Workspace owner' : role.replaceAll('_', ' ').toLowerCase()); }

function StaffAdministration({ token, isWorkspaceOwner }: { token: string; isWorkspaceOwner: boolean }) {
  const [tab, setTab] = useState<'team' | 'invitations'>('team');
  const [accounts, setAccounts] = useState<WorkspaceAccount[]>([]);
  const [invitations, setInvitations] = useState<WorkspaceInvitation[]>([]);
  const [email, setEmail] = useState('');
  const [role, setRole] = useState('EMPLOYEE');
  const [createdInvite, setCreatedInvite] = useState<CreatedInvitation | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState('');
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const invitationLink = createdInvite ? `${window.location.origin}${createdInvite.acceptanceUrl}` : '';

  async function load() {
    setLoading(true); setError('');
    try {
      const [accountResponse, invitationResponse] = await Promise.all([
        fetch('/api/v1/admin/users', { headers: { Authorization: `Bearer ${token}` } }),
        fetch('/api/v1/admin/invitations', { headers: { Authorization: `Bearer ${token}` } }),
      ]);
      if (!accountResponse.ok) throw new Error(await responseError(accountResponse));
      if (!invitationResponse.ok) throw new Error(await responseError(invitationResponse));
      setAccounts(await accountResponse.json() as WorkspaceAccount[]);
      setInvitations(await invitationResponse.json() as WorkspaceInvitation[]);
    } catch (e) { setError(e instanceof Error ? e.message : 'Could not load staff administration.'); }
    finally { setLoading(false); }
  }
  useEffect(() => { void load(); }, [token]);

  async function createInvite(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setBusy('invite'); setError(''); setNotice(''); setCreatedInvite(null);
    try {
      const response = await fetch('/api/v1/admin/invitations', { method: 'POST', headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }, body: JSON.stringify({ email: email.trim(), role }) });
      if (!response.ok) throw new Error(await responseError(response));
      const result = await response.json() as CreatedInvitation;
      setCreatedInvite(result); setEmail(''); setNotice('Invitation created. Copy and share this one-time link privately.');
      await load();
    } catch (e) { setError(e instanceof Error ? e.message : 'Could not create invitation.'); }
    finally { setBusy(''); }
  }

  async function copyInviteLink() {
    if (!invitationLink) return;
    try { await navigator.clipboard.writeText(invitationLink); setNotice('Invitation link copied. Share it only with the invited person.'); }
    catch { setNotice('Copy is unavailable in this browser. Select and copy the invitation link.'); }
  }

  async function revokeInvite(invite: WorkspaceInvitation) {
    setBusy(invite.id); setError(''); setNotice('');
    try {
      const response = await fetch(`/api/v1/admin/invitations/${invite.id}`, { method: 'DELETE', headers: { Authorization: `Bearer ${token}` } });
      if (!response.ok) throw new Error(await responseError(response));
      setInvitations(items => items.filter(item => item.id !== invite.id)); setNotice(`Invitation for ${invite.email} revoked.`);
    } catch (e) { setError(e instanceof Error ? e.message : 'Could not revoke invitation.'); }
    finally { setBusy(''); }
  }

  async function toggleRole(account: WorkspaceAccount, selectedRole: string) {
    const hasRole = account.roles.includes(selectedRole);
    if (hasRole && selectedRole === 'TENANT_ADMIN') return;
    const key = `${account.id}:${selectedRole}`; setBusy(key); setError(''); setNotice('');
    try {
      const response = await fetch(`/api/v1/admin/users/${account.id}/roles/${selectedRole}`, { method: hasRole ? 'DELETE' : 'PUT', headers: { Authorization: `Bearer ${token}` } });
      if (!response.ok) throw new Error(await responseError(response));
      setAccounts(items => items.map(item => item.id !== account.id ? item : { ...item, roles: hasRole ? item.roles.filter(value => value !== selectedRole) : [...item.roles, selectedRole] }));
      setNotice(`${staffRoleLabel(selectedRole)} ${hasRole ? 'removed from' : 'assigned to'} ${account.email}.`);
    } catch (e) { setError(e instanceof Error ? e.message : 'Could not update staff role.'); }
    finally { setBusy(''); }
  }

  async function toggleStatus(account: WorkspaceAccount) {
    setBusy(account.id); setError(''); setNotice('');
    try {
      const response = await fetch(`/api/v1/admin/users/${account.id}/status`, { method: 'PATCH', headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }, body: JSON.stringify({ enabled: !account.enabled }) });
      if (!response.ok) throw new Error(await responseError(response));
      setAccounts(items => items.map(item => item.id === account.id ? { ...item, enabled: !item.enabled } : item));
      setNotice(`${account.email} ${account.enabled ? 'deactivated' : 'reactivated'}.`);
    } catch (e) { setError(e instanceof Error ? e.message : 'Could not update account status.'); }
    finally { setBusy(''); }
  }

  return <section className="account-page staff-admin-page">
    <p className="eyebrow">WORKSPACE ADMINISTRATION</p><div className="staff-heading-row"><div><h1>Staff assignments</h1><p className="account-page-intro">Invite staff and manage their access to this workspace.</p></div><span className="staff-access-tag">{isWorkspaceOwner ? 'OWNER ACCESS' : 'ADMIN ACCESS'}</span></div>
    {!isWorkspaceOwner && <div className="staff-admin-note">HR administrators can manage staff access. Only the workspace owner can assign or remove owner access.</div>}
    <div className="account-tabs staff-tabs" role="tablist" aria-label="Staff administration"><button type="button" role="tab" aria-selected={tab === 'team'} className={tab === 'team' ? 'active' : ''} onClick={() => setTab('team')}>Team members <span>{accounts.length}</span></button><button type="button" role="tab" aria-selected={tab === 'invitations'} className={tab === 'invitations' ? 'active' : ''} onClick={() => setTab('invitations')}>Invitations <span>{invitations.length}</span></button></div>
    {error && <div className="platform-alert" role="alert">{error}</div>}{notice && <p className="platform-notice" role="status">{notice}</p>}
    {createdInvite && <section className="staff-invite-success"><div><strong>Invite link ready for {createdInvite.email}</strong><p>Assigned role: {staffRoleLabel(createdInvite.role)} · Expires {new Date(createdInvite.expiresAt).toLocaleString()}</p></div><label>One-time invitation link<input readOnly value={invitationLink} onFocus={event => event.currentTarget.select()} /></label><button type="button" className="checklist-action" onClick={() => void copyInviteLink()}>Copy invite link</button></section>}
    {tab === 'invitations' ? <div className="staff-admin-card"><div className="staff-card-heading"><span className="staff-card-icon">♙</span><div><p className="eyebrow">TEAM / ACCESS</p><h2>Staff invitations</h2><p>Invitations expire after 48 hours. Share one-time links privately; no email is sent automatically.</p></div></div><div className="staff-subscription-note"><span className="eyebrow">ACCESS ROLES</span><div>{STAFF_ROLES.map(item => <span key={item.value} title={item.detail}>{item.label}</span>)}</div></div><form className="staff-invite-form" onSubmit={event => void createInvite(event)}><label>Email<input type="email" value={email} onChange={event => setEmail(event.currentTarget.value)} placeholder="name@company.com" required maxLength={254}/></label><label>Role<SelectField label="Staff role" value={role} onChange={setRole} options={STAFF_ROLES.map(item => ({ value: item.value, label: item.label }))}/></label><button type="submit" className="security-submit" disabled={!!busy}>{busy === 'invite' ? 'Creating…' : '＋ Create invitation'}</button></form><div className="staff-list-heading"><div><h3>Pending invitations</h3><p>Unused links for this workspace.</p></div><button type="button" className="session-revoke" onClick={() => void load()} disabled={loading}>{loading ? 'Refreshing…' : 'Refresh'}</button></div>{loading ? <p className="platform-empty">Loading invitations…</p> : invitations.length ? <div className="staff-record-list">{invitations.map(invite => <div className="staff-record" key={invite.id}><span className="staff-record-avatar">{invite.email[0]?.toUpperCase()}</span><div className="staff-record-main"><strong>{invite.email}</strong><small>{staffRoleLabel(invite.role)} · Expires {new Date(invite.expiresAt).toLocaleString()}</small></div><span className="staff-pending-tag">PENDING</span><button type="button" className="session-revoke" onClick={() => void revokeInvite(invite)} disabled={!!busy}>{busy === invite.id ? 'Revoking…' : 'Revoke'}</button></div>)}</div> : <div className="platform-empty">No pending invitations. Create one above to add a staff member.</div>}</div>
    : <div className="staff-admin-card"><div className="staff-list-heading"><div><p className="eyebrow">WORKSPACE / TEAM</p><h2>Team members</h2><p>Assign one or more workspace roles and suspend access when needed.</p></div><button type="button" className="security-submit" onClick={() => setTab('invitations')}>＋ Invite staff</button></div>{loading ? <p className="platform-empty">Loading staff accounts…</p> : accounts.length ? <div className="staff-record-list">{accounts.map(account => <article className="staff-account-record" key={account.id}><div className="staff-record"><span className="staff-record-avatar">{account.email[0]?.toUpperCase()}</span><div className="staff-record-main"><strong>{account.email}</strong><small>{account.enabled ? 'Active account' : 'Access suspended'}</small></div><span className={`staff-pending-tag${account.enabled ? '' : ' is-disabled'}`}>{account.enabled ? 'ACTIVE' : 'SUSPENDED'}</span><button type="button" className="session-revoke" onClick={() => void toggleStatus(account)} disabled={!!busy || (!isWorkspaceOwner && account.roles.includes('TENANT_ADMIN'))}>{busy === account.id ? 'Saving…' : account.enabled ? 'Suspend' : 'Reactivate'}</button></div><div className="staff-role-assignments"><span>Assigned roles</span><div>{account.roles.includes('TENANT_ADMIN') && <span className="staff-role-chip owner-role">Workspace owner</span>}{STAFF_ROLES.map(item => { const assigned = account.roles.includes(item.value); return <button type="button" key={item.value} className={`staff-role-chip${assigned ? ' is-assigned' : ''}`} aria-pressed={assigned} title={`${assigned ? 'Remove' : 'Assign'} ${item.label}`} onClick={() => void toggleRole(account, item.value)} disabled={!!busy || !account.enabled}>{assigned ? '✓ ' : '+ '}{item.label}</button>; })}</div></div></article>)}</div> : <div className="platform-empty">No staff accounts are registered yet.</div>}</div>}
  </section>;
}

type AuditEvent = { id: number; occurredAt: string; actorEmail: string | null; action: string; entityType: string; entityId: string | null };
type AuditFilter = 'ALL' | 'ACCESS' | 'WORKSPACE' | 'OTHER';
const ACCESS_AUDIT_ACTIONS = /^(USER_SIGNED_IN|USER_ROLE|USER_STATUS|INVITATION_)/;
const WORKSPACE_AUDIT_ACTIONS = /^(WORKSPACE_SIGNUP|COMPANY_CREATED|BRANCH_CREATED)$/;
function auditCategory(action: string): Exclude<AuditFilter, 'ALL'> {
  if (ACCESS_AUDIT_ACTIONS.test(action)) return 'ACCESS';
  if (WORKSPACE_AUDIT_ACTIONS.test(action)) return 'WORKSPACE';
  return 'OTHER';
}
function auditActionLabel(action: string) { return action.toLowerCase().replaceAll('_', ' ').replace(/\b\w/g, character => character.toUpperCase()); }

function AuditLogsPage({ token }: { token: string }) {
  const [events, setEvents] = useState<AuditEvent[]>([]);
  const [search, setSearch] = useState('');
  const [filter, setFilter] = useState<AuditFilter>('ALL');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [refreshedAt, setRefreshedAt] = useState('');
  async function load() {
    setLoading(true); setError('');
    try {
      const response = await fetch('/api/v1/audit-logs?limit=500', { headers: { Authorization: `Bearer ${token}` } });
      if (!response.ok) throw new Error(await responseError(response));
      setEvents(await response.json() as AuditEvent[]);
      setRefreshedAt(new Date().toLocaleTimeString());
    } catch (cause) { setError(cause instanceof Error ? cause.message : 'Could not load audit logs.'); }
    finally { setLoading(false); }
  }
  useEffect(() => { void load(); }, [token]);
  const filtered = events.filter(event => {
    if (filter !== 'ALL' && auditCategory(event.action) !== filter) return false;
    const query = search.trim().toLowerCase();
    return !query || [event.action, event.actorEmail, event.entityType, event.entityId]
      .some(value => value?.toLowerCase().includes(query));
  });
  return <section className="account-page audit-logs-page">
    <p className="eyebrow">WORKSPACE ADMINISTRATION</p>
    <div className="staff-heading-row"><div><h1>Audit logs</h1><p className="account-page-intro">Review a read-only history of access and workspace changes.</p></div><span className="staff-access-tag">READ ONLY</span></div>
    <section className="staff-admin-card audit-log-card">
      <div className="staff-list-heading audit-log-heading"><div><h2>Recent activity</h2><p>Events are limited to this workspace and shown newest first.</p></div><button type="button" className="session-revoke" onClick={() => void load()} disabled={loading}>{loading ? 'Refreshing…' : '↻ Refresh'}</button></div>
      {error && <div className="platform-alert" role="alert">{error}</div>}
      <div className="audit-log-toolbar"><label className="audit-log-search"><span aria-hidden="true">⌕</span><input type="search" value={search} onChange={event => setSearch(event.currentTarget.value)} placeholder="Search actor, event, or record" aria-label="Search audit events" /></label><label className="audit-log-filter"><span>Event type</span><SelectField label="Filter audit events" value={filter} onChange={value => setFilter(value as AuditFilter)} options={[{ value: 'ALL', label: 'All events' }, { value: 'ACCESS', label: 'Access changes' }, { value: 'WORKSPACE', label: 'Workspace changes' }, { value: 'OTHER', label: 'Other events' }]} /></label></div>
      {loading ? <div className="platform-empty">Loading audit events…</div> : filtered.length === 0 ? <div className="platform-empty"><strong>{events.length ? 'No matching events' : 'No audit events yet'}</strong><span>{events.length ? 'Try a different search or event type.' : 'Workspace changes will appear here as they happen.'}</span></div> : <div className="audit-log-table-wrap"><table className="audit-log-table"><thead><tr><th>DATE &amp; TIME</th><th>EVENT</th><th>ACTOR</th><th>RECORD</th></tr></thead><tbody>{filtered.map(event => <tr key={event.id}><td><time dateTime={event.occurredAt}>{new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(event.occurredAt))}</time></td><td><span className={`audit-log-category category-${auditCategory(event.action).toLowerCase()}`}>{auditCategory(event.action) === 'ACCESS' ? 'Access' : auditCategory(event.action) === 'WORKSPACE' ? 'Workspace' : 'Activity'}</span><strong>{auditActionLabel(event.action)}</strong></td><td>{event.actorEmail || 'System'}</td><td><span>{event.entityType.replaceAll('_', ' ').toLowerCase()}</span>{event.entityId && <code title={event.entityId}>{event.entityId}</code>}</td></tr>)}</tbody></table></div>}
      {!loading && !error && events.length > 0 && <p className="audit-log-footnote">Showing {filtered.length} of {events.length} events{events.length === 500 ? ' · latest 500 only' : ''}{refreshedAt ? ` · refreshed ${refreshedAt}` : ''}.</p>}
    </section>
  </section>;
}

function basicAuth(email: string, password: string): string {
  const bytes = new TextEncoder().encode(`${email}:${password}`);
  let binary = '';
  bytes.forEach((byte) => { binary += String.fromCharCode(byte); });
  return `Basic ${btoa(binary)}`;
}

function BrandPanel() {
  return (
    <aside className="brand-panel" aria-label="Apex People">
      <div className="brand-lockup">
        <BrandMark />
        <span className="brand-name"><strong>Apex</strong><span>PEOPLE</span></span>
      </div>
      <div className="brand-copy">
        <p className="eyebrow">YOUR PEOPLE WORKSPACE</p>
        <h1>Every person.<br />One clear path.</h1>
        <p className="brand-description">A thoughtful home for the people who move your organization forward.</p>
      </div>
      <div className="brand-art" aria-hidden="true">
        <div className="orbit orbit-one" />
        <div className="orbit orbit-two" />
        <div className="orbit orbit-three" />
        <div className="art-core"><span /><span /><span /><span /></div>
        <div className="art-label label-top"><i /> PEOPLE, CONNECTED</div>
        <div className="art-label label-bottom">A calmer way to work together <span>↗</span></div>
      </div>
      <div className="brand-footer"><span className="footer-dot" /> Built for people-first teams</div>
    </aside>
  );
}

function SignedIn({ user, onSignOut }: { user: CurrentUser; onSignOut: () => void }) {
  const firstName = user.email.split('@')[0].split(/[._-]/)[0];
  return (
    <div className="signed-in">
      <div className="welcome-icon"><Icon name="mark" /></div>
      <p className="eyebrow">ACCESS CONFIRMED</p>
      <h3>Welcome, {firstName}.</h3>
      <p className="signed-in-email">{user.email}</p>
      <p className="signed-in-copy">{user.platformAdmin
        ? 'You’re signed in to the Apex People platform administration workspace.'
        : 'You’re signed in to your Apex People workspace. Your secure session is ready.'}</p>
      <button className="signout-button" type="button" onClick={onSignOut}>Sign out</button>
    </div>
  );
}

function AccountMenuIcon({ kind }: { kind: 'profile' | 'settings' | 'billing' | 'signout' }) {
  const paths = {
    profile: <><circle cx="12" cy="8" r="3.5" /><path d="M5 21v-1.5a7 7 0 0 1 14 0V21" /></>,
    settings: <><path d="M12 8.5a3.5 3.5 0 1 0 0 7 3.5 3.5 0 0 0 0-7Z" /><path d="m19.4 13.5 1.2.9-1.6 2.8-1.5-.5a7.8 7.8 0 0 1-1.6.9l-.3 1.6h-3.2l-.3-1.6a7.8 7.8 0 0 1-1.6-.9l-1.5.5-1.6-2.8 1.2-.9a7 7 0 0 1 0-1.9l-1.2-.9 1.6-2.8 1.5.5a7.8 7.8 0 0 1 1.6-.9l.3-1.6h3.2l.3 1.6a7.8 7.8 0 0 1 1.6.9l1.5-.5 1.6 2.8-1.2.9a7 7 0 0 1 0 1.9Z" transform="translate(-1.5 -1.5) scale(1.12)" /></>,
    billing: <><rect x="3" y="5" width="18" height="14" rx="2.5" /><path d="M3 10h18" /></>,
    signout: <><path d="M10 17l5-5-5-5M15 12H3" /><path d="M12 3h6a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-6" /></>,
  };
  return <svg className="account-menu-svg" viewBox="0 0 24 24" aria-hidden="true">{paths[kind]}</svg>;
}

function Dashboard({ user, onSignOut }: { user: CurrentUser; onSignOut: () => void }) {
  const [mfaEnabled, setMfaEnabled] = useState<boolean | null>(null);
  const [secret, setSecret] = useState('');
  const [code, setCode] = useState('');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');
  const [messageKind, setMessageKind] = useState<MessageKind>('error');
  const [activeSection, setActiveSection] = useState('Overview');
  const [navigationOpen, setNavigationOpen] = useState(() => window.matchMedia('(min-width: 681px)').matches);
  const [accountMenuOpen, setAccountMenuOpen] = useState(false);
  const [profileTab, setProfileTab] = useState<'profile' | 'settings' | 'plan' | 'guides'>('profile');
  const [securityTab, setSecurityTab] = useState<'password' | 'mfa' | 'sessions'>('password');
  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [profileBusy, setProfileBusy] = useState(false);
  const [profileMessage, setProfileMessage] = useState('');
  const [profileMessageKind, setProfileMessageKind] = useState<MessageKind>('error');
  const [sessions, setSessions] = useState<AccountSession[]>([]);
  const [sessionsBusy, setSessionsBusy] = useState(false);
  const [billing, setBilling] = useState<BillingSubscription|null>(null);
  const [billingLoading, setBillingLoading] = useState(false);
  const [billingError, setBillingError] = useState('');
  const [billingMessage, setBillingMessage] = useState('');
  const [billingBusy, setBillingBusy] = useState(false);
  const [billingPlan, setBillingPlan] = useState('');
  const [paymentReference, setPaymentReference] = useState('');
  const [paymentNote, setPaymentNote] = useState('');
  const accountMenuRef = useRef<HTMLDivElement>(null);
  const roleName = user.roles.includes('ROLE_TENANT_ADMIN') ? 'Workspace owner'
    : user.roles.includes('ROLE_HR_ADMIN') ? 'HR administrator'
      : user.roles.includes('ROLE_PAYROLL_OFFICER') ? 'Payroll officer'
        : user.roles.map((role) => role.replace(/^ROLE_/, '').replaceAll('_', ' ').toLowerCase()).join(', ') || 'Workspace member';
  const token = sessionStorage.getItem(TOKEN_KEY) || '';
  const privileged = user.roles.some((role) => ['ROLE_TENANT_ADMIN', 'ROLE_HR_ADMIN', 'ROLE_PAYROLL_OFFICER'].includes(role));
  const isWorkspaceOwner = user.roles.includes('ROLE_TENANT_ADMIN');
  const firstName = user.email.split('@')[0].split(/[._-]/)[0];
  const planEndsAt = billing?.status === 'TRIALING' ? billing.trialEndsAt : billing?.periodEndsAt;
  const planDaysRemaining = planEndsAt ? Math.ceil((Date.parse(planEndsAt) - Date.now()) / 86400000) : null;
  const showPlanNotice = isWorkspaceOwner && billing && (
    billing.status === 'TRIALING' ||
    (billing.status === 'ACTIVE' && billing.expiryNoticeDays > 0 && planDaysRemaining !== null && planDaysRemaining >= 0 && planDaysRemaining <= billing.expiryNoticeDays)
  );
  const navigation = [
    { label: 'Overview', icon: '◫', available: true },
    { label: 'Users & staff', icon: '♙', available: user.roles.some(role => ['ROLE_TENANT_ADMIN', 'ROLE_HR_ADMIN'].includes(role)) },
    { label: 'Audit logs', icon: '▤', available: user.roles.some(role => ['ROLE_TENANT_ADMIN', 'ROLE_HR_ADMIN', 'ROLE_AUDITOR'].includes(role)) },
    { label: 'Time & attendance', icon: '◷', available: false },
    { label: 'Payroll', icon: '₱', available: false },
    { label: 'Reports', icon: '▥', available: false },
  ];

  useEffect(() => {
    const canViewPlan = user.roles.includes('ROLE_TENANT_ADMIN');
    if (activeSection !== 'Billing' && !(activeSection === 'Overview' && canViewPlan)) return;
    let active = true;
    setBillingLoading(true); setBillingError(''); setBillingMessage('');
    fetch('/api/v1/billing/subscription', { headers: { Authorization: `Bearer ${token}` } })
      .then(async response => { if (!response.ok) throw new Error(await responseError(response)); return await response.json() as BillingSubscription; })
      .then(data => { if (active) { setBilling(data); setBillingPlan(data.availablePlans.find(plan => plan.code !== data.planCode && plan.amount > 0)?.code || data.availablePlans[0]?.code || ''); } })
      .catch(error => { if (active) setBillingError(error instanceof Error ? error.message : 'Could not load billing details.'); })
      .finally(() => { if (active) setBillingLoading(false); });
    return () => { active = false; };
  }, [activeSection, token, user.roles]);

  async function submitPaymentRequest(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setBillingBusy(true); setBillingError(''); setBillingMessage('');
    try {
      const response = await fetch('/api/v1/billing/payment-requests', { method: 'POST', headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }, body: JSON.stringify({ planCode: billingPlan, paymentReference, note: paymentNote }) });
      if (!response.ok) throw new Error(await responseError(response));
      const request = await response.json() as BillingRequest;
      setBilling(current => current ? { ...current, pendingRequest: request } : current);
      setPaymentReference(''); setPaymentNote(''); setBillingMessage('Payment request submitted. The platform team will review it.');
    } catch (error) { setBillingError(error instanceof Error ? error.message : 'Could not submit payment request.'); }
    finally { setBillingBusy(false); }
  }

  useEffect(() => {
    if (!privileged) return;
    fetch('/api/v1/security/mfa', { headers: { Authorization: `Bearer ${token}` } })
      .then(async (response) => {
        if (!response.ok) throw new Error('Could not check authenticator setup.');
        return await response.json() as { enabled: boolean };
      })
      .then((status) => setMfaEnabled(status.enabled))
      .catch(() => setMfaEnabled(false));
  }, [privileged, token]);

  useEffect(() => {
    if (!accountMenuOpen) return;
    const closeOnOutsideClick = (event: MouseEvent) => {
      if (event.target instanceof Node && !accountMenuRef.current?.contains(event.target)) setAccountMenuOpen(false);
    };
    const closeOnEscape = (event: KeyboardEvent) => { if (event.key === 'Escape') setAccountMenuOpen(false); };
    document.addEventListener('mousedown', closeOnOutsideClick);
    document.addEventListener('keydown', closeOnEscape);
    return () => {
      document.removeEventListener('mousedown', closeOnOutsideClick);
      document.removeEventListener('keydown', closeOnEscape);
    };
  }, [accountMenuOpen]);

  useEffect(() => {
    if (activeSection !== 'Profile' || profileTab !== 'settings' || securityTab !== 'sessions') return;
    let active = true;
    setSessionsBusy(true);
    fetch('/api/v1/security/account/sessions', { headers: { Authorization: `Bearer ${token}` } })
      .then(async (response) => {
        if (!response.ok) throw new Error(await responseError(response));
        return await response.json() as AccountSession[];
      })
      .then((items) => { if (active) setSessions(items); })
      .catch((error: unknown) => {
        if (!active) return;
        setProfileMessage(error instanceof Error ? error.message : 'Could not load active sessions.');
        setProfileMessageKind('error');
      })
      .finally(() => { if (active) setSessionsBusy(false); });
    return () => { active = false; };
  }, [activeSection, profileTab, securityTab, token]);

  useEffect(() => {
    if (!navigationOpen) return;
    const closeOnEscape = (event: KeyboardEvent) => { if (event.key === 'Escape') setNavigationOpen(false); };
    document.addEventListener('keydown', closeOnEscape);
    return () => document.removeEventListener('keydown', closeOnEscape);
  }, [navigationOpen]);

  async function beginMfaSetup() {
    setBusy(true); setMessage('');
    try {
      const response = await fetch('/api/v1/security/mfa/enroll', { method: 'POST', headers: { Authorization: `Bearer ${token}` } });
      if (!response.ok) throw new Error(await responseError(response));
      const enrollment = await response.json() as { secretHex: string };
      const bytes = enrollment.secretHex.match(/.{2}/g)?.map((part) => parseInt(part, 16)) ?? [];
      const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
      let buffer = 0; let bits = 0; let base32 = '';
      for (const byte of bytes) { buffer = (buffer << 8) | byte; bits += 8; while (bits >= 5) { base32 += alphabet[(buffer >>> (bits - 5)) & 31]; bits -= 5; } }
      if (bits > 0) base32 += alphabet[(buffer << (5 - bits)) & 31];
      setSecret(base32); setMessage('Add this key to an authenticator app, then enter the six-digit code it displays.'); setMessageKind('success');
    } catch (error) { setMessage(error instanceof Error ? error.message : 'Could not start authenticator setup.'); setMessageKind('error'); }
    finally { setBusy(false); }
  }

  async function confirmMfaSetup(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setBusy(true); setMessage('');
    try {
      const response = await fetch('/api/v1/security/mfa/confirm', { method: 'POST', headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }, body: JSON.stringify({ code }) });
      if (!response.ok) throw new Error(await responseError(response));
      setMfaEnabled(true); setSecret(''); setCode(''); setMessage('Authenticator enabled. Sign out and sign in again with a code to unlock administrator tools.'); setMessageKind('success');
    } catch (error) { setMessage(error instanceof Error ? error.message : 'Could not verify that code.'); setMessageKind('error'); }
    finally { setBusy(false); }
  }

  async function changePassword(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setProfileMessage('');
    if (newPassword !== confirmPassword) {
      setProfileMessage('The new passwords do not match.'); setProfileMessageKind('error'); return;
    }
    setProfileBusy(true);
    try {
      const response = await fetch('/api/v1/security/account/password', {
        method: 'POST', headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ currentPassword, newPassword }),
      });
      if (!response.ok) throw new Error(await responseError(response));
      setProfileMessage('Password updated. Signing you out so you can sign in with your new password.');
      setProfileMessageKind('success');
      window.setTimeout(() => void onSignOut(), 1200);
    } catch (error) {
      setProfileMessage(error instanceof Error ? error.message : 'Could not change your password.');
      setProfileMessageKind('error');
    } finally { setProfileBusy(false); }
  }

  async function revokeSession(sessionId: string) {
    setProfileBusy(true); setProfileMessage('');
    try {
      const response = await fetch(`/api/v1/security/account/sessions/${sessionId}`, {
        method: 'DELETE', headers: { Authorization: `Bearer ${token}` },
      });
      if (!response.ok) throw new Error(await responseError(response));
      setSessions((items) => items.filter((session) => session.id !== sessionId));
      setProfileMessage('Session signed out.'); setProfileMessageKind('success');
    } catch (error) {
      setProfileMessage(error instanceof Error ? error.message : 'Could not sign out that session.');
      setProfileMessageKind('error');
    } finally { setProfileBusy(false); }
  }

  async function revokeOtherSessions() {
    if (!window.confirm('Sign out of all other active sessions? This device will stay signed in.')) return;
    setProfileBusy(true); setProfileMessage('');
    try {
      const response = await fetch('/api/v1/security/account/sessions/others', {
        method: 'DELETE', headers: { Authorization: `Bearer ${token}` },
      });
      if (!response.ok) throw new Error(await responseError(response));
      setSessions((items) => items.filter((session) => session.current));
      setProfileMessage('All other sessions have been signed out.'); setProfileMessageKind('success');
    } catch (error) {
      setProfileMessage(error instanceof Error ? error.message : 'Could not sign out other sessions.');
      setProfileMessageKind('error');
    } finally { setProfileBusy(false); }
  }

  return (
    <div className={`workspace-app${navigationOpen ? ' navigation-open' : ''}`}>
      {navigationOpen && <button type="button" className="navigation-backdrop" aria-label="Close navigation" onClick={() => setNavigationOpen(false)} />}
      <aside className={`workspace-sidebar${navigationOpen ? ' is-open' : ''}`} aria-label="Workspace navigation" aria-hidden={!navigationOpen}>
        <div className="workspace-brand"><BrandMark /><span><strong>Apex</strong><small>PEOPLE</small></span><button type="button" className="navigation-close" aria-label="Close navigation" onClick={() => setNavigationOpen(false)}>×</button></div>
        <div className="workspace-picker"><span className="workspace-avatar">{(user.workspaceName?.[0] || 'W').toUpperCase()}</span><span><strong>{user.workspaceName || 'My workspace'}</strong><small>People operations</small></span><span className="picker-chevron">⌄</span></div>
        <p className="nav-caption">WORKSPACE</p>
        <nav className="workspace-nav" aria-label="Workspace navigation">
          {navigation.map((item) => <button key={item.label} type="button" className={`workspace-nav-item${activeSection === item.label ? ' selected' : ''}`} aria-current={activeSection === item.label ? 'page' : undefined} onClick={() => { setActiveSection(item.label); setNavigationOpen(false); }}>
            <span className="workspace-nav-icon" aria-hidden="true">{item.icon}</span><span>{item.label}</span>{!item.available && <small>SOON</small>}
          </button>)}
        </nav>
        <p className="nav-caption nav-caption-bottom">PREFERENCES</p>
        <button type="button" className={`workspace-nav-item${activeSection === 'Settings' ? ' selected' : ''}`} onClick={() => { setActiveSection('Settings'); setNavigationOpen(false); }}><span className="workspace-nav-icon" aria-hidden="true">⚙</span><span>Settings</span></button>
        <div className="sidebar-help"><span className="help-mark">?</span><span><strong>Need a hand?</strong><small>Help center</small></span></div>
        <div className="sidebar-profile"><span className="profile-avatar">{firstName.slice(0, 1).toUpperCase()}</span><span className="profile-copy"><strong>{firstName}</strong><small>{user.email}</small></span><button type="button" className="profile-menu" aria-label="Sign out" title="Sign out" onClick={onSignOut}>Sign out</button></div>
      </aside>
      <div className="workspace-main-column">
        <header className="workspace-header">
          <div className="header-leading"><button type="button" className="navigation-toggle" aria-label={navigationOpen ? 'Hide navigation' : 'Show navigation'} aria-expanded={navigationOpen} onClick={() => setNavigationOpen((open) => !open)}><span /><span /><span /></button><div className="breadcrumb"><span>Workspace</span><b>/</b><strong>{activeSection}</strong></div></div>
          <div className="header-actions">{showPlanNotice && <button type="button" className={`workspace-plan-notice${billing.status === 'TRIALING' ? ' is-trial' : ' is-expiring'}`} onClick={() => setActiveSection('Billing')} aria-label={`${billing.planName} plan. ${billing.status === 'TRIALING' ? 'Trial ends' : 'Plan period ends'} ${planEndsAt ? new Date(planEndsAt).toLocaleDateString() : 'date unavailable'}`}><span className="workspace-plan-notice-icon"><AccountMenuIcon kind="billing" /></span><span className="workspace-plan-notice-copy"><strong>{billing.planName}</strong><small>{billing.status === 'TRIALING' ? 'TRIALING' : 'RENEWAL SOON'}{planEndsAt ? ` · ${billing.status === 'TRIALING' ? 'Trial' : 'Plan'} ends ${new Date(planEndsAt).toLocaleDateString()}` : ''}{planDaysRemaining !== null ? ` · ${planDaysRemaining < 0 ? 'Trial ended' : planDaysRemaining === 0 ? 'Ends today' : `${planDaysRemaining} ${planDaysRemaining === 1 ? 'day' : 'days'} left`}` : ''}</small></span></button>}<span className="header-date">{new Intl.DateTimeFormat(undefined, { weekday: 'short', month: 'short', day: 'numeric' }).format(new Date())}</span><span className="header-divider" /><div className="account-menu-anchor" ref={accountMenuRef}>
            <button type="button" className={`account-trigger${accountMenuOpen ? ' is-open' : ''}`} aria-label="Open account menu" aria-haspopup="menu" aria-expanded={accountMenuOpen} onClick={() => setAccountMenuOpen((open) => !open)}><span className="header-avatar">{firstName.slice(0, 1).toUpperCase()}</span><svg viewBox="0 0 20 20" aria-hidden="true"><path d="m5 7.5 5 5 5-5" /></svg></button>
            {accountMenuOpen && <div className="account-popover" role="menu" aria-label="Account menu">
              <div className="account-summary"><span className="account-summary-avatar">{firstName.slice(0, 1).toUpperCase()}</span><span className="account-summary-copy"><strong>{firstName}</strong><small title={user.email}>{user.email}</small><small className="account-role">{roleName}{user.workspaceName ? ` · ${user.workspaceName}` : ''}</small></span></div>
              <div className="account-menu-items">
                <button type="button" role="menuitem" className="account-menu-item" onClick={() => { setActiveSection('Profile'); setAccountMenuOpen(false); }}><span className="account-menu-icon"><AccountMenuIcon kind="profile" /></span><span><strong>My Profile</strong><small>Account and workspace details</small></span></button>
                <button type="button" role="menuitem" className="account-menu-item" onClick={() => { setActiveSection('Settings'); setAccountMenuOpen(false); }}><span className="account-menu-icon"><AccountMenuIcon kind="settings" /></span><span><strong>Settings</strong><small>Security and account preferences</small></span></button>
                <button type="button" role="menuitem" className="account-menu-item" onClick={() => { setActiveSection('Billing'); setAccountMenuOpen(false); }}><span className="account-menu-icon"><AccountMenuIcon kind="billing" /></span><span><strong>Plan &amp; billing</strong><small>Subscription and plan options</small></span></button>
              </div>
              <div className="account-menu-footer"><button type="button" role="menuitem" className="account-signout" onClick={onSignOut}><span className="account-menu-icon"><AccountMenuIcon kind="signout" /></span><span><strong>Sign out</strong><small>End this session</small></span></button></div>
            </div>}
          </div></div>
        </header>
        <main className="workspace-content">
          {activeSection === 'Overview' ? <>
            <div className="dashboard-welcome"><div><p className="eyebrow">{user.workspaceName || 'YOUR WORKSPACE'}</p><h1>Good to see you, {firstName} <span>✦</span></h1><p>Here’s what’s happening with your people today.</p></div><div className="welcome-date">{new Intl.DateTimeFormat(undefined, { month: 'long', day: 'numeric', year: 'numeric' }).format(new Date())}</div></div>
            <section className="workspace-hero">
              <div className="hero-copy"><span className="hero-badge"><i /> WORKSPACE CREATED</span><h2>A better way to work,<br />starts right here.</h2><p>Your workspace is set up. Complete the security step to unlock administrator tools and get your team ready.</p><button type="button" className="hero-button" onClick={() => document.getElementById('security-setup')?.scrollIntoView({ behavior: 'smooth', block: 'center' })}>Complete setup <span>→</span></button></div>
              <div className="hero-illustration" aria-hidden="true"><div className="hero-ring ring-a" /><div className="hero-ring ring-b" /><div className="hero-card"><span className="hero-card-dot" /><span /><span /><span className="hero-card-line short" /><div className="hero-card-avatars"><i /><i /><i /><b>+</b></div></div><div className="hero-sparkle sparkle-one">✳</div><div className="hero-sparkle sparkle-two">✦</div></div>
            </section>
            <div className="section-heading"><div><p className="eyebrow">GETTING STARTED</p><h2>Your setup checklist</h2></div><span className="setup-progress">{privileged ? (mfaEnabled ? '2 of 2 complete' : '1 of 2 complete') : '1 of 1 complete'}</span></div>
            <section className="setup-checklist">
              <div className="checklist-row complete"><span className="checklist-check">✓</span><span className="checklist-copy"><strong>Create your workspace</strong><small>Your organization workspace is ready to use.</small></span><span className="checklist-status">COMPLETE</span></div>
              {privileged && <><div className={`checklist-row${mfaEnabled ? ' complete' : ''}`} id="security-setup"><span className="checklist-check">{mfaEnabled ? '✓' : '2'}</span><span className="checklist-copy"><strong>Strengthen your sign-in security</strong><small>{mfaEnabled ? 'Authenticator is enabled and requested at sign-in.' : 'Authenticator is optional. Workspace permissions follow your assigned role.'}</small></span>{!mfaEnabled && <button type="button" className="checklist-action" onClick={() => void beginMfaSetup()} disabled={busy || Boolean(secret)}>{busy ? 'Starting…' : secret ? 'Setup started' : 'Set up MFA'}</button>}{mfaEnabled && <span className="checklist-status">ENABLED</span>}</div>
              {secret && !mfaEnabled && <form className="mfa-setup-card checklist-mfa" onSubmit={(event) => void confirmMfaSetup(event)}><div><p className="eyebrow">AUTHENTICATOR SETUP</p><p className="signed-in-copy">Add this key to your authenticator app, then enter the six-digit code it displays.</p><div className="setup-secret">{secret}</div></div><div className="mfa-confirm-row"><label className="field-label" htmlFor="setup-code">6-digit code</label><div className="input-wrap"><input id="setup-code" type="text" inputMode="numeric" autoComplete="one-time-code" value={code} onChange={(event) => setCode(event.currentTarget.value.replace(/\D/g, '').slice(0, 6))} required /></div><button type="submit" className="checklist-action" disabled={busy || code.length !== 6}>{busy ? 'Verifying…' : 'Verify code'}</button></div><Message text={message} kind={messageKind} /></form>}</>}
            </section>
            <div className="dashboard-lower-grid"><section className="dashboard-info-card"><div className="info-card-heading"><span className="info-card-icon people-icon">♙</span><span className="info-card-kicker">YOUR PEOPLE</span></div><h3>Your team starts here</h3><p>When you’re ready, invite your team and bring your people operations into one place.</p><button type="button" className="quiet-link" onClick={() => setActiveSection('Users & staff')}>Manage users &amp; staff <span>→</span></button></section><section className="dashboard-info-card workspace-status-card"><div className="info-card-heading"><span className="info-card-icon shield-icon">◇</span><span className="info-card-kicker">ACCOUNT SECURITY</span></div><h3>{mfaEnabled ? 'Your account is protected' : 'Secure your account (optional)'}</h3><p>{mfaEnabled ? 'Your authenticator is requested when you sign in.' : 'Enable an authenticator for an extra sign-in check. Your workspace access is based on your role.'}</p><span className={`security-status${mfaEnabled ? ' is-secure' : ''}`}><i />{mfaEnabled ? 'Authenticator enabled' : privileged ? 'Role-based access' : 'Workspace active'}</span></section></div>
          </> : activeSection === 'Users & staff' && user.roles.some(role => ['ROLE_TENANT_ADMIN', 'ROLE_HR_ADMIN'].includes(role)) ? <StaffAdministration token={token} isWorkspaceOwner={user.roles.includes('ROLE_TENANT_ADMIN')} />
            : activeSection === 'Audit logs' && user.roles.some(role => ['ROLE_TENANT_ADMIN', 'ROLE_HR_ADMIN', 'ROLE_AUDITOR'].includes(role)) ? <AuditLogsPage token={token} />
            : activeSection === 'Profile' ? <section className="account-page">
            <p className="eyebrow">ACCOUNT</p><h1>Profile</h1><p className="account-page-intro">Your profile and account details.</p>
            <div className="account-tabs" role="tablist" aria-label="Account sections">
              <button type="button" role="tab" aria-selected={profileTab === 'profile'} className={profileTab === 'profile' ? 'active' : ''} onClick={() => { setProfileTab('profile'); setProfileMessage(''); }}>Profile</button>
              <button type="button" role="tab" aria-selected={profileTab === 'settings'} className={profileTab === 'settings' ? 'active' : ''} onClick={() => { setProfileTab('settings'); setProfileMessage(''); }}>Settings</button>
              <button type="button" role="tab" aria-selected={profileTab === 'plan'} className={profileTab === 'plan' ? 'active' : ''} onClick={() => setProfileTab('plan')}>Plan</button>
              <button type="button" role="tab" aria-selected={profileTab === 'guides'} className={profileTab === 'guides' ? 'active' : ''} onClick={() => setProfileTab('guides')}>Guides</button>
            </div>
            {profileTab === 'profile' ? <div className="profile-detail-card"><div className="profile-detail-heading"><span className="account-summary-avatar">{firstName.slice(0, 1).toUpperCase()}</span><div><h2>{firstName}</h2><p>{roleName}</p></div></div><div className="profile-detail-grid"><div><small>EMAIL ADDRESS</small><strong>{user.email}</strong></div><div><small>WORKSPACE</small><strong>{user.workspaceName || 'My workspace'}</strong></div><div><small>ROLE</small><strong>{roleName}</strong></div><div><small>ACCOUNT STATUS</small><strong className="profile-active"><i /> Active</strong></div></div></div>
              : profileTab === 'settings' ? <>
                <div className="security-tabs" role="tablist" aria-label="Security settings">
                  <button type="button" role="tab" aria-selected={securityTab === 'password'} className={securityTab === 'password' ? 'active' : ''} onClick={() => { setSecurityTab('password'); setProfileMessage(''); }}><span aria-hidden="true">♙</span>Password</button>
                  <button type="button" role="tab" aria-selected={securityTab === 'mfa'} className={securityTab === 'mfa' ? 'active' : ''} onClick={() => { setSecurityTab('mfa'); setProfileMessage(''); }}><span aria-hidden="true">▣</span>Multi-factor authentication</button>
                  <button type="button" role="tab" aria-selected={securityTab === 'sessions'} className={securityTab === 'sessions' ? 'active' : ''} onClick={() => { setSecurityTab('sessions'); setProfileMessage(''); }}><span aria-hidden="true">▱</span>Active sessions</button>
                </div>
                {securityTab === 'password' ? <form className="security-card password-card" onSubmit={(event) => void changePassword(event)}>
                  <h2>Change password</h2><p className="security-description">Use 12+ characters with uppercase, lowercase, a number, and a symbol. You will be signed out after changing it.</p>
                  <label className="field-label" htmlFor="current-password">Current password</label><div className="input-wrap"><input id="current-password" type="password" autoComplete="current-password" value={currentPassword} onChange={(event) => setCurrentPassword(event.currentTarget.value)} required /></div>
                  <label className="field-label" htmlFor="new-password">New password</label><div className="input-wrap"><input id="new-password" type="password" autoComplete="new-password" minLength={12} maxLength={72} value={newPassword} onChange={(event) => setNewPassword(event.currentTarget.value)} required /></div>
                  <label className="field-label" htmlFor="confirm-new-password">Confirm new password</label><div className="input-wrap"><input id="confirm-new-password" type="password" autoComplete="new-password" minLength={12} maxLength={72} value={confirmPassword} onChange={(event) => setConfirmPassword(event.currentTarget.value)} required /></div>
                  <Message text={profileMessage} kind={profileMessageKind} /><button type="submit" className="security-submit" disabled={profileBusy}>{profileBusy ? 'Updating…' : 'Change password'}</button>
                </form> : securityTab === 'mfa' ? <div className="security-card">
                  <h2>Multi-factor authentication</h2><p className="security-description">Add a time-based authenticator code to protect your account.</p>
                  {mfaEnabled ? <p className="security-enabled"><i /> Authenticator is enabled for your account.</p> : <><p className="security-description">{secret ? 'Add this key to your authenticator app, then enter the six-digit code it displays.' : 'Authenticator setup is not complete.'}</p>{secret && <div className="setup-secret">{secret}</div>}<div className="security-actions"><button type="button" className="security-submit" onClick={() => void beginMfaSetup()} disabled={busy || Boolean(secret)}>{busy ? 'Starting…' : secret ? 'Setup started' : 'Set up authenticator'}</button></div>{secret && <form className="security-mfa-confirm" onSubmit={(event) => void confirmMfaSetup(event)}><label className="field-label" htmlFor="profile-mfa-code">6-digit authenticator code</label><div className="input-wrap"><input id="profile-mfa-code" type="text" inputMode="numeric" autoComplete="one-time-code" value={code} onChange={(event) => setCode(event.currentTarget.value.replace(/\D/g, '').slice(0, 6))} required /></div><button type="submit" className="security-submit" disabled={busy || code.length !== 6}>{busy ? 'Verifying…' : 'Verify and enable'}</button></form>}</>}
                  <Message text={message || profileMessage} kind={message ? messageKind : profileMessageKind} />
                </div> : <div className="security-card sessions-card"><div className="sessions-card-heading"><h2>Active sessions</h2>{sessions.some((session) => !session.current) && <button type="button" className="session-revoke-others" onClick={() => void revokeOtherSessions()} disabled={profileBusy}>Sign out of other sessions</button>}</div><p className="security-description">Review and sign out of sessions on your account.</p>{sessionsBusy ? <p className="security-description">Loading sessions…</p> : sessions.length ? <div className="session-list">{sessions.map((session) => <div className="session-row" key={session.id}><span className="session-device">▱</span><span className="session-info"><strong>{session.current ? 'This device' : 'Signed-in session'}</strong><small>Signed in {new Date(session.issuedAt).toLocaleString()}{session.lastUsedAt ? ` · Last active ${new Date(session.lastUsedAt).toLocaleString()}` : ''}</small></span>{session.current ? <span className="current-session-tag">CURRENT</span> : <button type="button" className="session-revoke" onClick={() => void revokeSession(session.id)} disabled={profileBusy}>Sign out</button>}</div>)}</div> : <p className="security-description">No active sessions found.</p>}<Message text={profileMessage} kind={profileMessageKind} /></div>}
              </> : profileTab === 'plan' ? <div className="security-card"><h2>Plan &amp; billing</h2><p className="security-description">Subscription and payment options for your workspace.</p><button type="button" className="security-submit" onClick={() => setActiveSection('Billing')}>View plan &amp; billing</button></div>
                : <div className="security-card"><h2>Guides</h2><p className="security-description">Guides for setting up and managing your workspace will appear here.</p></div>}
          </section>
            : activeSection === 'Settings' ? <section className="account-page"><p className="eyebrow">PREFERENCES</p><h1>Settings</h1><p className="account-page-intro">Manage your account security and workspace preferences.</p><div className="settings-detail-card"><div className="settings-detail-icon"><AccountMenuIcon kind="settings" /></div><div className="settings-detail-copy"><h2>Account security</h2><p>{privileged ? 'Authenticator verification protects administrator features.' : 'Your account security status.'}</p><span className={`security-status${mfaEnabled ? ' is-secure' : ''}`}><i />{mfaEnabled ? 'Authenticator enabled' : privileged ? 'Authenticator needed' : 'Account active'}</span></div>{privileged && !mfaEnabled && <button type="button" className="checklist-action" onClick={() => void beginMfaSetup()} disabled={busy || Boolean(secret)}>{busy ? 'Starting…' : secret ? 'Setup started' : 'Set up MFA'}</button>}</div>{secret && !mfaEnabled && <form className="mfa-setup-card settings-mfa" onSubmit={(event) => void confirmMfaSetup(event)}><p className="eyebrow">AUTHENTICATOR SETUP</p><p className="signed-in-copy">Add this key to your authenticator app, then enter the six-digit code it displays.</p><div className="setup-secret">{secret}</div><label className="field-label" htmlFor="settings-setup-code">6-digit authenticator code</label><div className="input-wrap"><input id="settings-setup-code" type="text" inputMode="numeric" autoComplete="one-time-code" value={code} onChange={(event) => setCode(event.currentTarget.value.replace(/\D/g, '').slice(0, 6))} required /></div><button type="submit" className="checklist-action" disabled={busy || code.length !== 6}>{busy ? 'Verifying…' : 'Verify code'}</button><Message text={message} kind={messageKind} /></form>}</section>
              : activeSection === 'Billing' ? <section className="account-page"><p className="eyebrow">WORKSPACE PLAN</p><h1>Plan &amp; billing</h1><p className="account-page-intro">Review your current subscription, compare available plans, and submit payment for review.</p>{billingError&&<div className="platform-alert" role="alert">{billingError}</div>}{billingMessage&&<p className="platform-notice" role="status">{billingMessage}</p>}{billingLoading?<div className="security-card"><p className="security-description">Loading subscription details…</p></div>:billing&&<><div className="billing-detail-card"><span className="billing-card-icon"><AccountMenuIcon kind="billing" /></span><div><h2>{billing.planName}</h2><p>{billing.status.replaceAll('_',' ')}{billing.periodEndsAt?` · Renews through ${new Date(billing.periodEndsAt).toLocaleDateString()}`:billing.trialEndsAt?` · Trial ends ${new Date(billing.trialEndsAt).toLocaleDateString()}`:''}</p></div><strong>{billing.amount>0?new Intl.NumberFormat(undefined,{style:'currency',currency:billing.currency}).format(billing.amount):'Free'}<small> / {billing.billingInterval==='ANNUAL'?'year':'month'}</small></strong></div><div className="billing-workspace-grid"><section className="security-card"><h2>Available plans</h2><p className="security-description">Choose a plan, make the payment, then submit its reference for verification.</p><div className="billing-plan-list">{billing.availablePlans.map(plan=><label key={plan.code} className={`billing-plan-option${billingPlan===plan.code?' selected':''}`}><input type="radio" name="billing-plan" value={plan.code} checked={billingPlan===plan.code} onChange={()=>setBillingPlan(plan.code)}/><span><strong>{plan.name}</strong><small>{plan.description||plan.featureCodes.join(' · ')}</small></span><b>{plan.amount>0?new Intl.NumberFormat(undefined,{style:'currency',currency:plan.currency}).format(plan.amount):'Free'}<small> / {plan.billingInterval==='ANNUAL'?'year':'month'}</small></b></label>)}</div></section><section className="security-card billing-payment-card"><h2>Payment instructions</h2><p className="security-description">Make payment using the platform QR code, then submit the payment reference.</p>{billing.paymentQrDataUrl?<img className="billing-payment-qr" src={billing.paymentQrDataUrl} alt="Subscription payment QR code"/>:<p className="platform-empty">Payment QR has not been configured. Contact your platform administrator.</p>}{billing.pendingRequest?<div className="billing-pending"><span className="platform-status-tag suspended">Pending review</span><strong>{billing.pendingRequest.planName}</strong><small>Reference: {billing.pendingRequest.paymentReference}</small><small>Submitted {new Date(billing.pendingRequest.submittedAt).toLocaleString()}</small></div>:user.roles.includes('ROLE_TENANT_ADMIN')?<form className="billing-request-form" onSubmit={event=>void submitPaymentRequest(event)}><label>Payment reference<input value={paymentReference} onChange={event=>setPaymentReference(event.currentTarget.value)} required minLength={4} maxLength={100}/></label><label>Note (optional)<textarea value={paymentNote} onChange={event=>setPaymentNote(event.currentTarget.value)} maxLength={300}/></label><button type="submit" className="security-submit" disabled={billingBusy||!billingPlan||!billing.paymentQrDataUrl}>{billingBusy?'Submitting…':'Submit payment for review'}</button><small>Only the workspace owner can submit a request. One pending request is allowed at a time.</small></form>:<p className="security-description">Ask your workspace owner to submit a payment request.</p>}</section></div></>}</section>
                : <section className="module-placeholder"><span className="placeholder-icon">{navigation.find((item) => item.label === activeSection)?.icon || '⚙'}</span><p className="eyebrow">COMING SOON</p><h1>{activeSection}</h1><p>This area is part of your workspace navigation. We’re building these tools next.</p><button type="button" className="checklist-action" onClick={() => setActiveSection('Overview')}>Back to overview</button></section>}
        </main>
      </div>
    </div>
  );
}

function TextField({
  id, label, type = 'text', value, onChange, placeholder, autoComplete, maxLength, icon, trailing, inputMode, pattern,
}: {
  id: string;
  label: ReactNode;
  type?: string;
  value: string;
  onChange: (value: string) => void;
  placeholder: string;
  autoComplete?: string;
  maxLength?: number;
  icon?: 'mail' | 'lock';
  trailing?: ReactNode;
  inputMode?: 'text' | 'email' | 'numeric' | 'search' | 'tel' | 'url' | 'none' | 'decimal';
  pattern?: string;
}) {
  return (
    <>
      <label className="field-label" htmlFor={id}>{label}</label>
      <div className="input-wrap">
        {icon && <span className="input-icon"><Icon name={icon} /></span>}
        <input
          id={id}
          name={id}
          type={type}
          value={value}
          onChange={(event) => onChange(event.currentTarget.value)}
          placeholder={placeholder}
          autoComplete={autoComplete}
          maxLength={maxLength}
          inputMode={inputMode}
          pattern={pattern}
          required
        />
        {trailing}
      </div>
    </>
  );
}

type PlatformWorkspace = { id: string; slug: string; name: string; status: 'ACTIVE' | 'SUSPENDED' | 'CLOSED'; subscriptionStatus: 'TRIALING' | 'ACTIVE' | 'PAST_DUE' | 'SUSPENDED' | 'CANCELLED'; planCode: string; planName: string; planAmount: number; planCurrency: string; billingInterval: 'MONTHLY' | 'ANNUAL'; planPriceConfigured: boolean; trialEndsAt: string | null; periodStartsAt: string | null; periodEndsAt: string | null; version: number; createdAt: string; userCount: number; ownerEmail: string | null };
type PlatformPlan = { code: string; name: string; description: string; amount: number; currency: string; billingInterval: 'MONTHLY' | 'ANNUAL'; trialDays: number; expiryNoticeDays: number; active: boolean; priceConfigured: boolean; version: number; workspaceCount: number; updatedAt: string; featureCodes: string[] };
type PlatformPaymentRequest = { id: string; tenantId: string; workspaceName: string; workspaceSlug: string; planCode: string; planName: string; amount: number; currency: string; billingInterval: 'MONTHLY' | 'ANNUAL'; paymentReference: string; note: string; status: 'PENDING' | 'APPROVED' | 'REJECTED'; submittedAt: string; decidedAt: string | null; decidedByEmail: string | null; decisionReason: string | null };

async function platformApi<T>(path: string, token: string, init: RequestInit = {}): Promise<T> {
  const response = await fetch(path, { ...init, headers: { Authorization: `Bearer ${token}`, ...(init.body ? { 'Content-Type': 'application/json' } : {}), ...init.headers } });
  if (!response.ok) throw new Error(await responseError(response));
  if (response.status === 204) return undefined as T;
  return await response.json() as T;
}

type SelectOption = { value: string; label: string };
function SelectField({ value, onChange, options, label, disabled = false }: {
  value: string; onChange: (value: string) => void; options: SelectOption[]; label: string; disabled?: boolean;
}) {
  const [open,setOpen]=useState(false);
  const [active,setActive]=useState(Math.max(0,options.findIndex(option=>option.value===value)));
  const root=useRef<HTMLDivElement>(null);
  const trigger=useRef<HTMLButtonElement>(null);
  const selectedIndex=Math.max(0,options.findIndex(option=>option.value===value));
  useEffect(()=>{
    if(!open)return;
    const closeOutside=(event:PointerEvent)=>{if(event.target instanceof Node&&!root.current?.contains(event.target))setOpen(false)};
    document.addEventListener('pointerdown',closeOutside);
    return()=>document.removeEventListener('pointerdown',closeOutside);
  },[open]);
  function show(index=selectedIndex){setActive(index);setOpen(true)}
  function choose(index:number){const option=options[index];if(option)onChange(option.value);setOpen(false);requestAnimationFrame(()=>trigger.current?.focus())}
  function keyDown(event:ReactKeyboardEvent<HTMLDivElement>){
    if(event.key==='ArrowDown'||event.key==='ArrowUp'){
      event.preventDefault();
      if(!open){show(selectedIndex);return}
      setActive(current=>(current+(event.key==='ArrowDown'?1:-1)+options.length)%options.length);
    }else if(event.key==='Home'||event.key==='End'){
      event.preventDefault();const index=event.key==='Home'?0:options.length-1;setActive(index);if(!open)setOpen(true);
    }else if((event.key==='Enter'||event.key===' ')&&open){event.preventDefault();choose(active)}
    else if(event.key==='Escape'&&open){event.preventDefault();setOpen(false);trigger.current?.focus()}
    else if(event.key==='Tab'&&open){setOpen(false)}
    else if(!open&&event.key.length===1&&/^[a-z0-9]$/i.test(event.key)){
      const index=options.findIndex(option=>option.label.toLowerCase().startsWith(event.key.toLowerCase()));if(index>=0)choose(index);
    }
  }
  return <div className={`custom-select${open?' is-open':''}`} ref={root} onKeyDown={keyDown}>
    <button ref={trigger} type="button" className="custom-select-trigger" role="combobox" aria-label={label} aria-haspopup="listbox" aria-expanded={open} aria-controls={`select-${label.toLowerCase().replace(/[^a-z0-9]+/g,'-')}`} aria-activedescendant={open?`option-${label.toLowerCase().replace(/[^a-z0-9]+/g,'-')}-${active}`:undefined} disabled={disabled} onClick={()=>open?setOpen(false):show()}>
      <span>{options.find(option=>option.value===value)?.label||'Select an option'}</span><svg viewBox="0 0 20 20" aria-hidden="true"><path d="m5 7.5 5 5 5-5"/></svg>
    </button>
    {open&&<div className="custom-select-menu" id={`select-${label.toLowerCase().replace(/[^a-z0-9]+/g,'-')}`} role="listbox" aria-label={label}>
      {options.map((option,index)=><button id={`option-${label.toLowerCase().replace(/[^a-z0-9]+/g,'-')}-${index}`} key={option.value} type="button" role="option" tabIndex={-1} aria-selected={option.value===value} className={index===active?'is-active':''} onMouseDown={event=>event.preventDefault()} onMouseEnter={()=>setActive(index)} onClick={()=>choose(index)}>{option.label}{option.value===value&&<span aria-hidden="true">✓</span>}</button>)}
    </div>}
  </div>;
}

function PlatformPlansPanel({ token }: { token: string }) {
  const editorDialogRef = useRef<HTMLDialogElement>(null);
  const [plans, setPlans] = useState<PlatformPlan[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [edit, setEdit] = useState<PlatformPlan | 'new' | null>(null);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState('');
  const [draft, setDraft] = useState({ code: '', name: '', description: '', amount: '0', currency: 'PHP', billingInterval: 'MONTHLY' as 'MONTHLY' | 'ANNUAL', trialDays: '0', expiryNoticeDays: '14', active: true, priceConfirmed: false, featureCodes: ['people'] as string[], reason: '' });
  const options = [['people','People'],['time-attendance','Time & attendance'],['payroll','Payroll'],['reports','Reports'],['workspace-settings','Workspace settings']];
  useEffect(() => {
    const dialog = editorDialogRef.current;
    if (edit && dialog && !dialog.open) dialog.showModal();
  }, [edit]);
  async function load() { setLoading(true); setError(''); try { setPlans(await platformApi<PlatformPlan[]>('/api/v1/platform-admin/plans',token)); } catch (e) { setError(e instanceof Error?e.message:'Could not load plans.'); } finally { setLoading(false); } }
  useEffect(() => { void load(); }, [token]);
  function open(plan: PlatformPlan | null) { setNotice(''); setError(''); setEdit(plan ?? 'new'); setDraft(plan ? {code:plan.code,name:plan.name,description:plan.description,amount:String(plan.amount),currency:plan.currency,billingInterval:plan.billingInterval,trialDays:String(plan.trialDays),expiryNoticeDays:String(plan.expiryNoticeDays),active:plan.active,priceConfirmed:plan.priceConfigured,featureCodes:plan.featureCodes,reason:''} : {code:'',name:'',description:'',amount:'0',currency:'PHP',billingInterval:'MONTHLY',trialDays:'0',expiryNoticeDays:'14',active:true,priceConfirmed:false,featureCodes:['people'],reason:''}); }
  async function save(event: FormEvent) { event.preventDefault(); setBusy(true);setError('');setNotice('');try { const payload={...draft,amount:Number(draft.amount),trialDays:Number(draft.trialDays),expiryNoticeDays:Number(draft.expiryNoticeDays)};const updated=edit==='new'?await platformApi<PlatformPlan>('/api/v1/platform-admin/plans',token,{method:'POST',body:JSON.stringify(payload)}):await platformApi<PlatformPlan>(`/api/v1/platform-admin/plans/${(edit as PlatformPlan).code}`,token,{method:'PUT',body:JSON.stringify(payload)});setPlans(current=>edit==='new'?[updated,...current]:current.map(plan=>plan.code===updated.code?updated:plan));setEdit(null);setNotice('Plan saved.');}catch(e){setError(e instanceof Error?e.message:'Could not save plan.');}finally{setBusy(false);} }
  return <section className="platform-admin-card"><div className="platform-card-heading"><div><h2>Plan maintenance</h2><p>Maintain subscription prices, trial length, expiry notices, and included workspace features.</p></div><button type="button" className="platform-primary-action" onClick={()=>open(null)}>＋ New plan</button></div>{error&&<div className="platform-alert" role="alert">{error}</div>}{notice&&<p className="platform-notice" role="status">{notice}</p>}{loading?<div className="platform-empty">Loading plans…</div>:<div className="platform-plan-grid">{plans.map(plan=><article className="platform-plan-card" key={plan.code}><div className="platform-plan-top"><span className={`platform-status-tag ${plan.active?'active':'suspended'}`}>{plan.active?'Available':'Archived'}</span><code>{plan.code}</code><button type="button" className="platform-text-action" onClick={()=>open(plan)}>Edit</button></div><h3>{plan.name}</h3><p>{plan.description||'No description provided.'}</p><strong>{plan.priceConfigured?new Intl.NumberFormat(undefined,{style:'currency',currency:plan.currency}).format(plan.amount):'Price not set'} <small>/ {plan.billingInterval==='ANNUAL'?'year':'month'}</small></strong><span>{plan.trialDays}-day trial · {plan.expiryNoticeDays===0?'no expiry notice':`${plan.expiryNoticeDays}-day expiry notice`} · {plan.workspaceCount} workspaces</span><div><small>FEATURES</small><p>{plan.featureCodes.map(code=>options.find(([value])=>value===code)?.[1]||code).join(' · ')}</p></div></article>)}</div>}
    {edit&&<dialog ref={editorDialogRef} className="platform-edit-dialog" onClose={()=>setEdit(null)}><form onSubmit={event=>void save(event)}><header><div><p className="eyebrow">SUBSCRIPTION CATALOG</p><h3>{edit==='new'?'Create plan':'Edit plan'}</h3></div><button type="button" aria-label="Close" onClick={()=>setEdit(null)}>×</button></header><div className="platform-editor-grid"><label>Plan code<input value={draft.code} disabled={edit!=='new'} onChange={e=>setDraft({...draft,code:e.target.value})} required maxLength={32}/></label><label>Plan name<input value={draft.name} onChange={e=>setDraft({...draft,name:e.target.value})} required maxLength={80}/></label><label className="wide">Description<input value={draft.description} onChange={e=>setDraft({...draft,description:e.target.value})} maxLength={300}/></label><label>Price<input type="number" min="0" step="0.01" value={draft.amount} onChange={e=>setDraft({...draft,amount:e.target.value})}/></label><label>Currency<input value={draft.currency} onChange={e=>setDraft({...draft,currency:e.target.value.toUpperCase()})} maxLength={3}/></label><div className="platform-field"> <span>Billing interval</span><SelectField label="Billing interval" value={draft.billingInterval} onChange={value=>setDraft({...draft,billingInterval:value as 'MONTHLY'|'ANNUAL'})} options={[{value:"MONTHLY",label:"Monthly"},{value:"ANNUAL",label:"Annual"}]}/></div><label>Trial days<input type="number" min="0" max="365" value={draft.trialDays} onChange={e=>setDraft({...draft,trialDays:e.target.value})}/></label><label>Expiry notice days<input type="number" min="0" max="365" value={draft.expiryNoticeDays} onChange={e=>setDraft({...draft,expiryNoticeDays:e.target.value})}/><small>Show an alert for active plans this many days before they end. Set to 0 to disable.</small></label></div><div className="platform-check-options"><strong>Included features</strong>{options.map(([code,label])=><label key={code}><input type="checkbox" checked={draft.featureCodes.includes(code)} onChange={e=>setDraft({...draft,featureCodes:e.target.checked?[...draft.featureCodes,code]:draft.featureCodes.filter(item=>item!==code)})}/>{label}</label>)}</div><label className="platform-checkbox"><input type="checkbox" checked={draft.priceConfirmed} onChange={e=>setDraft({...draft,priceConfirmed:e.target.checked})}/> Price confirmed and ready for billing</label><label className="platform-checkbox"><input type="checkbox" checked={draft.active} onChange={e=>setDraft({...draft,active:e.target.checked})}/> Plan available for new subscriptions</label><label className="wide platform-reason">Administrative note<textarea value={draft.reason} onChange={e=>setDraft({...draft,reason:e.target.value})} minLength={8} maxLength={500} required placeholder="Why is this plan being created or changed?"/></label><footer><button type="button" className="session-revoke" onClick={()=>setEdit(null)}>Cancel</button><button type="submit" className="platform-primary-action" disabled={busy}>{busy?'Saving…':'Save plan'}</button></footer></form></dialog>}
  </section>;
}

function PlatformModulesPanel({ token }: { token: string }) {
  const [modules,setModules]=useState<Array<{code:string;name:string;description:string;enabled:boolean;updatedAt:string;updatedByEmail:string|null;updateReason:string}>>([]);const [loading,setLoading]=useState(true);const [error,setError]=useState('');const [pending,setPending]=useState<string|null>(null);
  async function load(){setLoading(true);try{setModules(await platformApi('/api/v1/platform-admin/modules',token));}catch(e){setError(e instanceof Error?e.message:'Could not load module settings.')}finally{setLoading(false)}}
  useEffect(()=>{void load()},[token]);
  async function toggle(module:typeof modules[number]){const note=window.prompt(`Add an administrative note to ${module.enabled?'disable':'enable'} ${module.name}:`);if(note===null)return;if(note.trim().length<8){setError('Enter a reason of at least 8 characters.');return}setPending(module.code);setError('');try{const updated=await platformApi<typeof module>(`/api/v1/platform-admin/modules/${module.code}`,token,{method:'PUT',body:JSON.stringify({enabled:!module.enabled,reason:note})});setModules(items=>items.map(item=>item.code===updated.code?updated:item));}catch(e){setError(e instanceof Error?e.message:'Could not update module.')}finally{setPending(null)}}
  return <section className="platform-admin-card"><div className="platform-card-heading"><div><h2>Module availability</h2><p>Control which HRM capabilities are available across all workspaces.</p></div><span className="platform-status-tag active">{modules.filter(m=>m.enabled).length}/{modules.length} enabled</span></div>{error&&<div className="platform-alert" role="alert">{error}</div>}{loading?<div className="platform-empty">Loading modules…</div>:<div className="platform-module-list">{modules.map(module=><article key={module.code}><div><span className={`platform-status-tag ${module.enabled?'active':'suspended'}`}>{module.enabled?'Enabled':'Disabled'}</span><h3>{module.name}</h3><p>{module.description}</p>{module.updatedAt&&<small>{module.updatedByEmail?`Updated by ${module.updatedByEmail}`:'Default setting'}{module.updateReason?` · ${module.updateReason}`:''}</small>}</div><button type="button" className={module.enabled?'session-revoke':'platform-primary-action'} onClick={()=>void toggle(module)} disabled={pending===module.code}>{pending===module.code?'Saving…':module.enabled?'Disable':'Enable'}</button></article>)}</div>}</section>;
}

function PlatformQrPanel({ token }: { token: string }) {
  const [qr,setQr]=useState<{dataUrl:string|null;fileName:string|null;updatedAt:string|null;uploadedByEmail:string|null}|null>(null);const [file,setFile]=useState<File|null>(null);const [loading,setLoading]=useState(true);const [busy,setBusy]=useState(false);const [error,setError]=useState('');const [message,setMessage]=useState('');
  async function load(){setLoading(true);try{setQr(await platformApi('/api/v1/platform-admin/payment-qr',token));}catch(e){setError(e instanceof Error?e.message:'Could not load payment QR.')}finally{setLoading(false)}}
  useEffect(()=>{void load()},[token]);
  async function save(event:FormEvent){event.preventDefault();if(!file)return;if(file.size>2*1024*1024){setError('Choose an image smaller than 2 MB.');return}setBusy(true);setError('');setMessage('');try{const dataUrl=await new Promise<string>((resolve,reject)=>{const reader=new FileReader();reader.onload=()=>typeof reader.result==='string'?resolve(reader.result):reject(new Error('Could not read the image.'));reader.onerror=()=>reject(new Error('Could not read the image.'));reader.readAsDataURL(file)});const updated=await platformApi<typeof qr>('/api/v1/platform-admin/payment-qr',token,{method:'PUT',body:JSON.stringify({dataUrl,fileName:file.name})});setQr(updated);setFile(null);setMessage('Subscription payment QR saved.')}catch(e){setError(e instanceof Error?e.message:'Could not save payment QR.')}finally{setBusy(false)}}
  async function remove(){if(!window.confirm('Remove the subscription payment QR?'))return;setBusy(true);try{await platformApi('/api/v1/platform-admin/payment-qr',token,{method:'DELETE'});setQr(null);setMessage('Payment QR removed.')}catch(e){setError(e instanceof Error?e.message:'Could not remove QR.')}finally{setBusy(false)}}
  return <section className="platform-admin-card"><div className="platform-card-heading"><div><h2>Subscription payment QR</h2><p>Upload the payment QR shown to workspace owners when they submit a plan payment reference.</p></div><span className={`platform-status-tag ${qr?.dataUrl?'active':'suspended'}`}>{qr?.dataUrl?'Configured':'Not configured'}</span></div>{error&&<div className="platform-alert" role="alert">{error}</div>}{message&&<p className="platform-notice" role="status">{message}</p>}{loading?<div className="platform-empty">Loading payment settings…</div>:<div className="platform-qr-layout">{qr?.dataUrl?<div className="platform-qr-preview"><img src={qr.dataUrl} alt="Subscription payment QR"/><div><strong>{qr.fileName}</strong><small>Updated {qr.updatedAt?new Date(qr.updatedAt).toLocaleString():'—'} by {qr.uploadedByEmail||'platform admin'}</small><button type="button" className="session-revoke" onClick={()=>void remove()} disabled={busy}>Remove QR</button></div></div>:<div className="platform-empty"><strong>No payment QR uploaded</strong><span>Upload a reusable QR code for subscription payments.</span></div>}<form className="platform-qr-upload" onSubmit={event=>void save(event)}><label>Choose a QR image<input type="file" accept="image/png,image/jpeg,image/webp" onChange={e=>setFile(e.currentTarget.files?.[0]||null)}/><small>PNG, JPG, or WebP · maximum 2 MB.</small></label>{file&&<span>{file.name}</span>}<button type="submit" className="platform-primary-action" disabled={!file||busy}>{busy?'Saving…':qr?.dataUrl?'Replace payment QR':'Upload payment QR'}</button></form></div>}</section>;
}

type PlatformMailSettings = {enabled:boolean;mode:'DEVELOPMENT'|'PRODUCTION';fromEmail:string;fromName:string;replyTo:string|null;devRecipient:string|null;apiKeyConfigured:boolean;apiKeyMasked:string|null;updatedByEmail:string|null;updatedAt:string|null};
function PlatformMailPanel({ token }: { token: string }) {
  const [settings,setSettings]=useState<PlatformMailSettings|null>(null);const [apiKey,setApiKey]=useState('');const [testTo,setTestTo]=useState('');const [loading,setLoading]=useState(true);const [busy,setBusy]=useState<'save'|'test'|''>('');const [error,setError]=useState('');const [notice,setNotice]=useState('');
  async function load(){setLoading(true);setError('');try{const value=await platformApi<PlatformMailSettings>('/api/v1/platform-admin/mail-settings',token);setSettings(value);if(!testTo&&value.devRecipient)setTestTo(value.devRecipient)}catch(e){setError(e instanceof Error?e.message:'Could not load mail settings.')}finally{setLoading(false)}}
  useEffect(()=>{void load()},[token]);
  function patch(value:Partial<PlatformMailSettings>){setSettings(current=>current?{...current,...value}:current);setNotice('');setError('')}
  async function save(){if(!settings)return;setBusy('save');setError('');setNotice('');try{const saved=await platformApi<PlatformMailSettings>('/api/v1/platform-admin/mail-settings',token,{method:'PUT',body:JSON.stringify({enabled:settings.enabled,mode:settings.mode,fromEmail:settings.fromEmail,fromName:settings.fromName,replyTo:settings.replyTo||null,devRecipient:settings.devRecipient||null,apiKey:apiKey.trim()||null})});setSettings(saved);setApiKey('');setNotice('Mail settings saved.')}catch(e){setError(e instanceof Error?e.message:'Could not save mail settings.')}finally{setBusy('')}}
  async function sendTest(){setBusy('test');setError('');setNotice('');try{const result=await platformApi<{deliveredTo:string;mode:string}>('/api/v1/platform-admin/mail-settings/test',token,{method:'POST',body:JSON.stringify({recipient:testTo})});setNotice(`Resend accepted the test email for ${result.deliveredTo} (${result.mode.toLowerCase()} mode).`)}catch(e){setError(e instanceof Error?e.message:'Could not send test email.')}finally{setBusy('')}}
  return <section className="platform-admin-card platform-mail-card"><div className="platform-card-heading"><div><h2>Mail settings</h2><p>Configure platform email delivery through Resend. The API key is encrypted before it is stored in PostgreSQL.</p></div><span className={`platform-status-tag ${settings?.enabled?'active':'suspended'}`}>{settings?.enabled?'Enabled':'Disabled'}</span></div>{error&&<div className="platform-alert" role="alert">{error}</div>}{notice&&<p className="platform-notice" role="status">{notice}</p>}{loading||!settings?<div className="platform-empty">Loading mail settings…</div>:<div className="platform-mail-layout"><div className="platform-mail-form"><div className="platform-provider-row"><div><strong>Provider</strong><span>Resend</span></div><label><input type="checkbox" checked={settings.enabled} onChange={e=>patch({enabled:e.target.checked})}/> Enable platform email</label></div><div className="platform-field"> <span>Mode</span><SelectField label="Mail delivery mode" value={settings.mode} onChange={value=>patch({mode:value as PlatformMailSettings['mode']})} options={[{value:"DEVELOPMENT",label:"Development"},{value:"PRODUCTION",label:"Production"}]}/><small>Development mode routes all messages to the configured development recipient. Production mode uses the intended recipient.</small></div><div className="platform-editor-grid"><label>From name<input value={settings.fromName} onChange={e=>patch({fromName:e.target.value})}/></label><label>From email<input type="email" value={settings.fromEmail} onChange={e=>patch({fromEmail:e.target.value})}/></label><label>Reply-to (optional)<input type="email" value={settings.replyTo||''} onChange={e=>patch({replyTo:e.target.value||null})}/></label><label>Development recipient<input type="email" value={settings.devRecipient||''} onChange={e=>patch({devRecipient:e.target.value||null})} placeholder="you@example.com"/></label></div><label>Resend API key<input type="password" autoComplete="new-password" value={apiKey} onChange={e=>setApiKey(e.target.value)} placeholder={settings.apiKeyConfigured?`Configured (${settings.apiKeyMasked}) — leave blank to keep`:'re_xxxxxxxxx'}/><small>The key is never returned to the browser. Enter a new key only to add or rotate it. Configure <code>HRM_MFA_ENCRYPTION_KEY</code> to encrypt stored credentials.</small></label><button type="button" className="platform-primary-action" onClick={()=>void save()} disabled={!!busy}>{busy==='save'?'Saving…':'Save mail settings'}</button>{settings.updatedAt&&<small className="platform-updated">Last updated {new Date(settings.updatedAt).toLocaleString()}{settings.updatedByEmail?` by ${settings.updatedByEmail}`:''}</small>}</div><aside className="platform-mail-test"><h3>Send test email</h3><p>Save your Resend API key and sender details, then send a test to confirm the setup.</p><label>Requested recipient<input type="email" value={testTo} onChange={e=>setTestTo(e.target.value)} placeholder="you@example.com"/></label>{settings.mode==='DEVELOPMENT'&&<p className="platform-mail-note">Development mode routes the test to the configured development recipient above.</p>}<button type="button" className="platform-primary-action" onClick={()=>void sendTest()} disabled={!!busy||!settings.apiKeyConfigured||!testTo}>{busy==='test'?'Sending…':'Send test email'}</button>{!settings.apiKeyConfigured&&<small>Save a Resend API key before testing.</small>}</aside></div>}</section>;
}

function PlatformConfirmDialog({ request, decision, busy, onCancel, onConfirm }: { request: PlatformPaymentRequest; decision: 'APPROVE'|'REJECT'; busy: boolean; onCancel: () => void; onConfirm: (reason: string) => void }) {
  const dialogRef = useRef<HTMLDialogElement>(null);
  const [reason, setReason] = useState(() => decision === 'APPROVE'
    ? 'Payment received and verified.'
    : 'Payment reference could not be verified.');
  const [validation, setValidation] = useState('');
  useEffect(() => { const dialog = dialogRef.current; if (dialog && !dialog.open) dialog.showModal(); }, []);
  const approve = decision === 'APPROVE';
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const cleanReason = reason.trim();
    if (cleanReason.length < 8) { setValidation('Add a note of at least 8 characters to continue.'); return; }
    setValidation(''); onConfirm(cleanReason);
  }
  return <dialog ref={dialogRef} className="platform-confirm-dialog" onClose={onCancel} onClick={event => { if (event.target === event.currentTarget) dialogRef.current?.close(); }}>
    <form onSubmit={submit}>
      <header><span className={`platform-confirm-icon${approve ? ' is-approve' : ' is-reject'}`}>{approve ? '✓' : '!'}</span><button type="button" className="platform-confirm-close" aria-label="Close confirmation" onClick={() => dialogRef.current?.close()}>×</button></header>
      <p className="eyebrow">PAYMENT REVIEW</p><h2>{approve ? 'Verify and activate plan?' : 'Reject payment request?'}</h2>
      <p className="platform-confirm-description">{approve ? `Confirm that the payment for ${request.workspaceName} has been received. This will activate the ${request.planName} plan.` : `Reject the submitted payment for ${request.workspaceName}. The reason will be recorded for the workspace owner.`}</p>
      <div className="platform-confirm-summary"><span>Payment reference</span><strong>{request.paymentReference}</strong><span>Workspace</span><strong>{request.workspaceName}</strong></div>
      <label className="platform-confirm-reason">{approve ? 'Verification note' : 'Reason for rejection'}<textarea autoFocus value={reason} onChange={event => { setReason(event.currentTarget.value); setValidation(''); }} minLength={8} maxLength={500} placeholder={approve ? 'Add a note confirming the payment was checked…' : 'Explain why this payment cannot be approved…'} required /></label>
      <p className="platform-confirm-hint">At least 8 characters. This note will be included in the administrative record.</p>
      {validation && <p className="platform-confirm-error" role="alert">{validation}</p>}
      <footer><button type="button" className="session-revoke" onClick={() => dialogRef.current?.close()} disabled={busy}>Cancel</button><button type="submit" className={approve ? 'platform-primary-action' : 'platform-danger-action'} disabled={busy}>{busy ? 'Saving…' : approve ? 'Verify & activate' : 'Reject request'}</button></footer>
    </form>
  </dialog>;
}

function PlatformPaymentsPanel({ token, onPendingCountChange }: { token: string; onPendingCountChange: (count: number) => void }) {
  const [requests,setRequests]=useState<PlatformPaymentRequest[]>([]);const [loading,setLoading]=useState(true);const [busy,setBusy]=useState<string|null>(null);const [error,setError]=useState('');const [confirming,setConfirming]=useState<{request:PlatformPaymentRequest;decision:'APPROVE'|'REJECT'}|null>(null);
  async function load(){setLoading(true);setError('');try{const items=await platformApi<PlatformPaymentRequest[]>('/api/v1/platform-admin/payment-requests',token);setRequests(items);onPendingCountChange(items.filter(request=>request.status==='PENDING').length);}catch(e){setError(e instanceof Error?e.message:'Could not load payment requests.')}finally{setLoading(false)}}
  useEffect(()=>{void load()},[token]);
  async function decide(reason:string){if(!confirming)return;const {request,decision}=confirming;setBusy(request.id);setError('');try{await platformApi(`/api/v1/platform-admin/payment-requests/${request.id}`,token,{method:'PUT',body:JSON.stringify({decision,reason})});setConfirming(null);await load()}catch(e){setError(e instanceof Error?e.message:'Could not review payment.')}finally{setBusy(null)}}
  return <section className="platform-admin-card"><div className="platform-card-heading"><div><h2>Payment reference review</h2><p>Verify each submitted payment reference with your payment provider before approval.</p></div><button type="button" className="session-revoke" onClick={()=>void load()} disabled={loading}>Refresh requests</button></div>{error&&<div className="platform-alert" role="alert">{error}</div>}{loading?<div className="platform-empty">Loading payment requests…</div>:requests.length===0?<div className="platform-empty"><strong>No payment requests yet</strong><span>Workspace payment submissions will appear here when a billing request is created.</span></div>:<div className="platform-payment-list">{requests.map(request=><article key={request.id}><header><div><span className={`platform-status-tag ${request.status==='PENDING'?'suspended':request.status==='APPROVED'?'active':'closed'}`}>{request.status==='PENDING'?'Needs review':request.status==='APPROVED'?'Approved':'Rejected'}</span><h3>{request.workspaceName}</h3><small>{request.workspaceSlug} · submitted {new Date(request.submittedAt).toLocaleString()}</small></div><strong>{new Intl.NumberFormat(undefined,{style:'currency',currency:request.currency}).format(request.amount)} / {request.billingInterval==='ANNUAL'?'year':'month'}</strong></header><p className="platform-payment-details">Plan: <strong>{request.planName}</strong><span>Payment reference: <strong>{request.paymentReference}</strong></span></p>{request.note&&<p className="platform-payment-note">{request.note}</p>}{request.status==='PENDING'&&<footer><button type="button" className="session-revoke" onClick={()=>setConfirming({request,decision:'REJECT'})} disabled={busy===request.id}>Reject</button><button type="button" className="platform-primary-action" onClick={()=>setConfirming({request,decision:'APPROVE'})} disabled={busy===request.id}>Verify &amp; activate</button></footer>}{request.status!=='PENDING'&&<small className="platform-payment-reviewed">Reviewed by {request.decidedByEmail||'platform admin'} · {request.decisionReason}</small>}</article>)}</div>}{confirming&&<PlatformConfirmDialog key={`${confirming.request.id}:${confirming.decision}`} request={confirming.request} decision={confirming.decision} busy={busy===confirming.request.id} onCancel={()=>setConfirming(null)} onConfirm={reason=>void decide(reason)}/>}</section>;
}

type PlatformBackup = {id:string;fileName:string;sha256:string;sizeBytes:number;createdAt:string;generatedByEmail:string;status:'CREATED'|'VERIFIED'|'VERIFY_FAILED';verifyMessage:string|null};
function PlatformBackupPanel({ token, onOpenSecurity }: { token: string; onOpenSecurity: () => void }) {
  const [history,setHistory]=useState<PlatformBackup[]>([]);const [loading,setLoading]=useState(true);const [busy,setBusy]=useState('');const [error,setError]=useState('');const [notice,setNotice]=useState('');const [archive,setArchive]=useState<File|null>(null);const [connection,setConnection]=useState('');const [restoreTarget,setRestoreTarget]=useState<{databaseName:string;host:string;empty:boolean;canRestore:boolean}|null>(null);const [restoreDone,setRestoreDone]=useState<{databaseName:string;durationSeconds:number;migrationRows:string;message:string}|null>(null);
  async function load(){setLoading(true);setError('');try{setHistory(await platformApi<PlatformBackup[]>('/api/v1/platform-admin/backup/history',token));}catch(e){setError(e instanceof Error?e.message:'Could not load backup history.')}finally{setLoading(false)}}
  useEffect(()=>{void load()},[token]);
  async function create(){setBusy('create');setError('');setNotice('');let verifiedForRequest=false;try{const session=await platformApi<{enabled:boolean;tokenVerified:boolean}>('/api/v1/platform-admin/mfa',token,{cache:'no-store'});if(!session.tokenVerified){onOpenSecurity();return}verifiedForRequest=true;const backup=await platformApi<PlatformBackup>('/api/v1/platform-admin/backup',token,{method:'POST'});setHistory(items=>[backup,...items]);setNotice(`Encrypted backup created: ${backup.fileName}. Verify it before using it for recovery.`)}catch(e){let message=e instanceof Error?e.message:'Could not create backup.';if(verifiedForRequest&&message.includes('authenticator verification')){try{const latest=await platformApi<{enabled:boolean;tokenVerified:boolean}>('/api/v1/platform-admin/mfa',token,{cache:'no-store'});message=latest.tokenVerified?'The backup request returned HTTP 403 even though this token passed the MFA check. The backup handler now checks the same admin principal; inspect the active backend request if this persists.':'This sign-in session needs authenticator verification. Verify it in Account security, then try again.';if(!latest.tokenVerified)onOpenSecurity()}catch{}}setError(message)}finally{setBusy('')}}
  async function verify(backup:PlatformBackup){setBusy(`verify:${backup.id}`);setError('');setNotice('');try{const updated=await platformApi<PlatformBackup>(`/api/v1/platform-admin/backup/${backup.id}/verify`,token,{method:'POST'});setHistory(items=>items.map(item=>item.id===updated.id?updated:item));if(updated.status==='VERIFIED')setNotice(`Verification passed for ${updated.fileName}. ${updated.verifyMessage||''}`);else setError(`Verification failed. ${updated.verifyMessage||''}`)}catch(e){setError(e instanceof Error?e.message:'Could not verify backup.')}finally{setBusy('')}}
  async function download(backup:PlatformBackup){setBusy(`download:${backup.id}`);try{const response=await fetch(`/api/v1/platform-admin/backup/${backup.id}/download`,{headers:{Authorization:`Bearer ${token}`}});if(!response.ok)throw new Error(await responseError(response));const blob=await response.blob();const url=URL.createObjectURL(blob);const link=document.createElement('a');link.href=url;link.download=backup.fileName;link.click();URL.revokeObjectURL(url)}catch(e){setError(e instanceof Error?e.message:'Could not download backup.')}finally{setBusy('')}}
  async function checkTarget(){setBusy('check');setError('');setRestoreTarget(null);setRestoreDone(null);try{setRestoreTarget(await platformApi('/api/v1/platform-admin/backup/restore/test-connection',token,{method:'POST',body:JSON.stringify({connectionString:connection})}))}catch(e){setError(e instanceof Error?e.message:'Could not verify the restore target.')}finally{setBusy('')}}
  async function restore(){if(!archive||!restoreTarget||!window.confirm(`Restore ${archive.name} to the empty database “${restoreTarget.databaseName}”?`))return;setBusy('restore');setError('');setRestoreDone(null);try{const form=new FormData();form.append('backup',archive);form.append('connectionString',connection);const response=await fetch('/api/v1/platform-admin/backup/restore',{method:'POST',headers:{Authorization:`Bearer ${token}`},body:form});if(!response.ok)throw new Error(await responseError(response));setRestoreDone(await response.json() as {databaseName:string;durationSeconds:number;migrationRows:string;message:string})}catch(e){setError(e instanceof Error?e.message:'Restore failed.')}finally{setBusy('')}}
  return <div className="platform-backup-grid"><section className="platform-admin-card"><div className="platform-card-heading"><div><h2>Backup history</h2><p>Encrypted PostgreSQL backups with checksum verification.</p></div><button type="button" className="platform-primary-action" onClick={()=>void create()} disabled={!!busy}>{busy==='create'?'Creating…':'Create backup'}</button></div>{error&&<div className="platform-alert" role="alert">{error}</div>}{notice&&<p className="platform-notice" role="status">{notice}</p>}{loading?<div className="platform-empty">Loading backups…</div>:history.length===0?<div className="platform-empty"><strong>No backups yet</strong><span>Configure the backup encryption key and create your first backup.</span></div>:<div className="platform-backup-list">{history.map(backup=><article key={backup.id}><div><strong>{backup.fileName}</strong><small>{new Date(backup.createdAt).toLocaleString()} · {(backup.sizeBytes/1024/1024).toFixed(2)} MB · {backup.generatedByEmail}</small><code>SHA-256 {backup.sha256}</code>{backup.verifyMessage&&<small>{backup.verifyMessage}</small>}</div><span className={`platform-status-tag ${backup.status==='VERIFIED'?'active':backup.status==='VERIFY_FAILED'?'closed':'suspended'}`}>{backup.status.replace('_',' ')}</span><div><button type="button" className="session-revoke" onClick={()=>void verify(backup)} disabled={!!busy}>{busy===`verify:${backup.id}`?'Verifying…':'Verify'}</button><button type="button" className="session-revoke" onClick={()=>void download(backup)} disabled={!!busy}>Download</button></div></article>)}</div>}</section><section className="platform-admin-card"><div className="platform-card-heading"><div><h2>Restore to an empty database</h2><p>Restore an encrypted Apex backup into a separate PostgreSQL database.</p></div></div><label className="platform-restore-label">Target PostgreSQL connection string<input type="password" autoComplete="off" value={connection} onChange={e=>{setConnection(e.target.value);setRestoreTarget(null)}} placeholder="postgresql://user:password@host:5432/database"/><small>Credentials are used only for this restore operation and are not saved.</small></label><button type="button" className="session-revoke" onClick={()=>void checkTarget()} disabled={!connection||!!busy}>{busy==='check'?'Checking…':'Check empty target'}</button>{restoreTarget&&<div className="platform-restore-target"><strong>{restoreTarget.databaseName}</strong><span>{restoreTarget.host} · {restoreTarget.empty?'Empty database':'Database is not empty'}</span></div>}<label className="platform-restore-label">Encrypted backup file<input type="file" accept=".age,.zip" onChange={e=>setArchive(e.currentTarget.files?.[0]||null)}/><small>Use a backup file downloaded from this page.</small></label>{archive&&<p className="platform-description">Selected: {archive.name} ({(archive.size/1024/1024).toFixed(2)} MB)</p>}{restoreDone&&<p className="platform-notice" role="status">{restoreDone.message} · {restoreDone.durationSeconds}s · {restoreDone.migrationRows} migrations</p>}<button type="button" className="platform-primary-action" onClick={()=>void restore()} disabled={!archive||!restoreTarget||!restoreTarget.empty||!!busy}>{busy==='restore'?'Restoring…':'Restore backup'}</button></section></div>;
}

function PlatformAdminMfaPanel({token,email,onSignOut}:{token:string;email:string;onSignOut:()=>void}){
  const [status,setStatus]=useState<{enabled:boolean;tokenVerified:boolean}|null>(null);
  const [secret,setSecret]=useState('');const[qrCode,setQrCode]=useState('');const[code,setCode]=useState('');const[disableCode,setDisableCode]=useState('');const[busy,setBusy]=useState(false);const[error,setError]=useState('');const[notice,setNotice]=useState('');
  async function load(){setError('');try{setStatus(await platformApi<{enabled:boolean;tokenVerified:boolean}>('/api/v1/platform-admin/mfa',token));}catch(e){setError(e instanceof Error?e.message:'Could not load authenticator status.')}}
  useEffect(()=>{void load()},[token]);
  useEffect(()=>{if(!secret){setQrCode('');return}let cancelled=false;const issuer='Apex People Platform';const label=`${encodeURIComponent(issuer)}:${encodeURIComponent(email)}`;const uri=`otpauth://totp/${label}?secret=${secret}&issuer=${encodeURIComponent(issuer)}&algorithm=SHA1&digits=6&period=30`;QRCode.toDataURL(uri,{width:240,margin:2,errorCorrectionLevel:'M',color:{dark:'#153f3a',light:'#ffffff'}}).then(data=>{if(!cancelled)setQrCode(data)}).catch(()=>{if(!cancelled)setError('Could not generate the authenticator QR code. Use the setup key below instead.')});return()=>{cancelled=true}},[secret,email]);
  async function enroll(){setBusy(true);setError('');setNotice('');try{const result=await platformApi<{secretHex:string}>('/api/v1/platform-admin/mfa/enroll',token,{method:'POST'});const bytes=result.secretHex.match(/.{2}/g)?.map(part=>parseInt(part,16))||[];const alphabet='ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';let buffer=0,bits=0,base32='';for(const byte of bytes){buffer=(buffer<<8)|byte;bits+=8;while(bits>=5){base32+=alphabet[(buffer>>>(bits-5))&31];bits-=5}}if(bits>0)base32+=alphabet[(buffer<<(5-bits))&31];setSecret(base32);setNotice('Scan this QR code in your authenticator app, then enter its current six-digit code.')}catch(e){setError(e instanceof Error?e.message:'Could not start authenticator setup.')}finally{setBusy(false)}}
  async function submit(event:FormEvent){event.preventDefault();setBusy(true);setError('');setNotice('');try{await platformApi<void>(`/api/v1/platform-admin/mfa/${status?.enabled?'verify':'confirm'}`,token,{method:'POST',body:JSON.stringify({code})});setCode('');setSecret('');await load();setNotice(status?.enabled?'This platform admin session is verified.':'Authenticator enabled. This session is verified.')}catch(e){setError(e instanceof Error?e.message:'Could not verify authenticator code.')}finally{setBusy(false)}}
  async function disable(event:FormEvent){event.preventDefault();setBusy(true);setError('');setNotice('');try{await platformApi<void>('/api/v1/platform-admin/mfa/disable',token,{method:'POST',body:JSON.stringify({code:disableCode})});setDisableCode('');setNotice('Authenticator disabled. All platform admin sessions have been signed out.');window.setTimeout(onSignOut,1200)}catch(e){setError(e instanceof Error?e.message:'Could not disable authenticator.')}finally{setBusy(false)}}
  return <section className="platform-admin-card platform-mfa-card"><div className="platform-card-heading"><div><h2>Platform admin authenticator</h2><p>Set up a time-based authenticator before changing platform settings, reviewing payments, or creating and restoring backups.</p></div><span className={`platform-status-tag ${status?.tokenVerified?'active':'suspended'}`}>{status?.tokenVerified?'Verified this session':status?.enabled?'Verification needed':'Not configured'}</span></div>{error&&<div className="platform-alert" role="alert">{error}</div>}{notice&&<p className="platform-notice" role="status">{notice}</p>}{!status?<div className="platform-empty">Loading authenticator status…</div>:status.enabled?<><div className="platform-mfa-confirmed"><span aria-hidden="true">✓</span><div><strong>{status.tokenVerified?'Platform admin actions are unlocked for this session.':'Your authenticator is configured.'}</strong><p>{status.tokenVerified?'Your authenticator is enabled and this sign-in has been verified.':'Enter a current code to verify this sign-in before managing platform operations.'}</p></div></div>{!status.tokenVerified&&<form className="platform-mfa-code-form" onSubmit={event=>void submit(event)}><label>Authenticator code<input inputMode="numeric" autoComplete="one-time-code" value={code} onChange={event=>setCode(event.currentTarget.value.replace(/\D/g,'').slice(0,6))} placeholder="000000" required/></label><button type="submit" className="platform-primary-action" disabled={busy||code.length!==6}>{busy?'Verifying…':'Verify this session'}</button></form>}<section className="platform-mfa-disable"><div><h3>Disable multi-factor authentication</h3><p>This removes the extra sign-in check for platform admin operations. You’ll be signed out of all platform admin sessions and must sign in again.</p></div><form className="platform-mfa-code-form" onSubmit={event=>void disable(event)}><label>Current authenticator code<input inputMode="numeric" autoComplete="one-time-code" value={disableCode} onChange={event=>setDisableCode(event.currentTarget.value.replace(/\D/g,'').slice(0,6))} placeholder="000000" required/></label><button type="submit" className="platform-danger-action" disabled={busy||disableCode.length!==6}>{busy?'Disabling…':'Disable authenticator'}</button></form></section></>:<><p className="platform-mfa-copy">Use an authenticator app such as Google Authenticator, Microsoft Authenticator, or 1Password. Setup is completed by confirming a live code.</p>{secret&&<div className="platform-mfa-enrollment"><div className="platform-mfa-qr-frame">{qrCode?<img src={qrCode} alt="Scan this QR code with your authenticator app"/>:<span>Preparing QR code…</span>}</div><div className="platform-mfa-qr-copy"><strong>Scan with your authenticator app</strong><p>Open the app, add an account, and scan this QR code. Enter the six-digit code it generates to confirm setup.</p><details><summary>Can’t scan the code?</summary><div className="platform-mfa-secret"><strong>Enter this setup key manually</strong><code>{secret}</code></div></details></div></div>}{secret?<form className="platform-mfa-code-form" onSubmit={event=>void submit(event)}><label>Authenticator code<input inputMode="numeric" autoComplete="one-time-code" value={code} onChange={event=>setCode(event.currentTarget.value.replace(/\D/g,'').slice(0,6))} placeholder="000000" required/></label><button type="submit" className="platform-primary-action" disabled={busy||code.length!==6}>{busy?'Verifying…':'Confirm and enable'}</button></form>:<button type="button" className="platform-primary-action" onClick={()=>void enroll()} disabled={busy}>{busy?'Starting…':'Set up authenticator'}</button>}</>}</section>;
}

type PlatformSectionName = 'Subscriptions' | 'Payment requests' | 'Plan maintenance' | 'Payment QR' | 'Module status' | 'Mail settings' | 'Backup & restore' | 'Account security';

function PlatformSectionIcon({section}:{section:PlatformSectionName}) {
  let drawing: ReactNode;
  switch(section) {
    case 'Subscriptions': drawing=<><rect x="4" y="4" width="16" height="16" rx="3"/><path d="M8 8h8M8 12h8M8 16h5"/></>; break;
    case 'Payment requests': drawing=<><rect x="3" y="5" width="18" height="14" rx="2.5"/><path d="M3 10h18M7 15h4m5-1 1.5 1.5L20 13"/></>; break;
    case 'Plan maintenance': drawing=<><path d="M20.5 13 13 20.5a2 2 0 0 1-2.8 0L3.5 13.8a2 2 0 0 1 0-2.8L11 3.5h7.5a2 2 0 0 1 2 2Z"/><circle cx="16" cy="8" r="1.3"/></>; break;
    case 'Payment QR': drawing=<><rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><path d="M14 14h3v3h-3zm4 4h3v3h-3zm0-4h3m-7 7h3"/></>; break;
    case 'Module status': drawing=<><path d="M4 7h16M4 12h16M4 17h16"/><circle cx="9" cy="7" r="2" fill="currentColor"/><circle cx="15" cy="12" r="2" fill="currentColor"/><circle cx="8" cy="17" r="2" fill="currentColor"/></>; break;
    case 'Mail settings': drawing=<><rect x="3" y="5" width="18" height="14" rx="2.5"/><path d="m4 7 8 6 8-6"/></>; break;
    case 'Backup & restore': drawing=<><ellipse cx="12" cy="5" rx="8" ry="3"/><path d="M4 5v6c0 1.7 3.6 3 8 3m8-9v5M4 11v6c0 1.7 3.6 3 8 3h1m4-4 3 3 3-3m-3 3v-6"/></>; break;
    case 'Account security': drawing=<><path d="M12 3 19 6v5c0 4.5-2.9 8-7 10-4.1-2-7-5.5-7-10V6l7-3Z"/><rect x="9" y="10" width="6" height="5" rx="1"/><path d="M10.5 10V8.5a1.5 1.5 0 0 1 3 0V10"/></>; break;
  }
  return <svg className="platform-section-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">{drawing}</svg>;
}

function PlatformAdminDashboard({ user, onSignOut }: { user: CurrentUser; onSignOut: () => void }) {
  const subscriptionDialogRef = useRef<HTMLDialogElement>(null);
  const platformAccountMenuRef = useRef<HTMLDivElement>(null);
  const sections = ['Subscriptions','Payment requests','Plan maintenance','Payment QR','Module status','Mail settings','Backup & restore'] as const;
  type AdminSection = typeof sections[number] | 'Account security';
  const [section, setSection] = useState<AdminSection>('Subscriptions');
  const [workspaces, setWorkspaces] = useState<PlatformWorkspace[]>([]);
  const [plans, setPlans] = useState<PlatformPlan[]>([]);
  const [query, setQuery] = useState('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [selectedWorkspace, setSelectedWorkspace] = useState<PlatformWorkspace|null>(null);
  const [subscriptionDraft, setSubscriptionDraft] = useState({status:'TRIALING',planCode:'TRIAL',trialEndsAt:'',periodStartsAt:'',periodEndsAt:'',reason:''});
  const [savingSubscription,setSavingSubscription]=useState(false);
  const [accountMenuOpen,setAccountMenuOpen]=useState(false);
  const [pendingPaymentCount,setPendingPaymentCount]=useState(0);
  useEffect(() => {
    const dialog = subscriptionDialogRef.current;
    if (selectedWorkspace && dialog && !dialog.open) dialog.showModal();
  }, [selectedWorkspace]);
  useEffect(() => {
    if (!accountMenuOpen) return;
    const closeOnOutsideClick = (event: MouseEvent) => {
      if (event.target instanceof Node && !platformAccountMenuRef.current?.contains(event.target)) setAccountMenuOpen(false);
    };
    const closeOnEscape = (event: KeyboardEvent) => { if (event.key === 'Escape') setAccountMenuOpen(false); };
    document.addEventListener('mousedown', closeOnOutsideClick);
    document.addEventListener('keydown', closeOnEscape);
    return () => {
      document.removeEventListener('mousedown', closeOnOutsideClick);
      document.removeEventListener('keydown', closeOnEscape);
    };
  }, [accountMenuOpen]);
  const token = sessionStorage.getItem(TOKEN_KEY) || '';
  const loadWorkspaces = () => {
    setLoading(true); setError('');
    Promise.all([platformApi<PlatformWorkspace[]>('/api/v1/platform-admin/workspaces',token),platformApi<PlatformPlan[]>('/api/v1/platform-admin/plans',token)])
      .then(([items,planItems])=>{setWorkspaces(items);setPlans(planItems);})
      .catch((reason: unknown) => setError(reason instanceof Error ? reason.message : 'Could not load workspaces.'))
      .finally(() => setLoading(false));
  };
  useEffect(() => { loadWorkspaces(); }, [token]);
  useEffect(() => { platformApi<PlatformPaymentRequest[]>('/api/v1/platform-admin/payment-requests',token).then(items=>setPendingPaymentCount(items.filter(request=>request.status==='PENDING').length)).catch(()=>{}); }, [token]);
  const filtered = workspaces.filter((workspace) => `${workspace.name} ${workspace.slug} ${workspace.ownerEmail || ''}`.toLowerCase().includes(query.toLowerCase()));
  const activeWorkspaces = workspaces.filter((workspace) => workspace.subscriptionStatus === 'ACTIVE' || workspace.subscriptionStatus === 'TRIALING').length;
  const userCount = workspaces.reduce((sum, workspace) => sum + workspace.userCount, 0);
  const adminInitial = user.email.slice(0, 1).toUpperCase();
  async function saveSubscription(event:FormEvent){event.preventDefault();if(!selectedWorkspace)return;setSavingSubscription(true);setError('');try{await platformApi(`/api/v1/platform-admin/workspaces/${selectedWorkspace.id}/subscription`,token,{method:'PUT',body:JSON.stringify({...subscriptionDraft,expectedVersion:selectedWorkspace.version})});setSelectedWorkspace(null);loadWorkspaces();}catch(e){setError(e instanceof Error?e.message:'Could not update subscription.')}finally{setSavingSubscription(false)}}
  const descriptions:Record<AdminSection,string>={Subscriptions:'Review workspace subscription status, plans, and billing periods.', 'Payment requests':'Review submitted payment references and approve or reject subscription payments.','Plan maintenance':'Set subscription rates, trial terms, and included features.','Payment QR':'Manage the payment QR shown to workspace owners.','Module status':'Control which HRM modules are available across the platform.','Mail settings':'Configure platform email delivery through Resend.','Backup & restore':'Create encrypted database backups and verify recovery readiness.','Account security':'Set up and verify an authenticator for platform administration.'};
  return <div className="platform-app">
    <aside className="platform-sidebar">
      <div className="platform-brand"><BrandMark /><span><strong>Apex</strong><small>PLATFORM ADMIN</small></span></div>
      <p className="platform-nav-caption">PLATFORM OPERATIONS</p>
      <nav aria-label="Platform administration">{sections.map((item) => <button type="button" key={item} className={section === item ? 'selected' : ''} onClick={() => setSection(item)}><span><PlatformSectionIcon section={item}/></span>{item}</button>)}</nav>
      <p className="platform-nav-caption platform-security-caption">SECURITY</p>
      <nav aria-label="Platform administrator security"><button type="button" className={section==='Account security'?'selected':''} onClick={()=>setSection('Account security')}><span><PlatformSectionIcon section="Account security"/></span>Account security</button></nav>
      <div className="platform-sidebar-profile"><span>{adminInitial}</span><div><strong>Platform administrator</strong><small title={user.email}>{user.email}</small></div><button type="button" onClick={onSignOut}>Sign out</button></div>
    </aside>
    <main className="platform-main">
      <header className="platform-header"><div><span>Platform</span><b>/</b><strong>{section}</strong></div><div className="platform-account-anchor" ref={platformAccountMenuRef}><button type="button" className={`platform-admin-account-trigger${accountMenuOpen?' is-open':''}`} aria-label="Open account menu" aria-haspopup="menu" aria-expanded={accountMenuOpen} onClick={()=>setAccountMenuOpen(open=>!open)}><span className="platform-admin-avatar" title={user.email}>{adminInitial}</span><svg viewBox="0 0 20 20" aria-hidden="true"><path d="m5 7.5 5 5 5-5"/></svg></button>{accountMenuOpen&&<div className="account-popover platform-account-popover" role="menu" aria-label="Platform administrator account menu"><div className="account-summary"><span className="account-summary-avatar">{adminInitial}</span><span className="account-summary-copy"><strong>Platform administrator</strong><small title={user.email}>{user.email}</small><small className="account-role">Apex People platform</small></span></div><div className="account-menu-items"><button type="button" role="menuitem" className="account-menu-item" onClick={()=>{setSection('Account security');setAccountMenuOpen(false)}}><span className="account-menu-icon"><AccountMenuIcon kind="settings"/></span><span><strong>Account security</strong><small>Authenticator and session access</small></span></button></div><div className="account-menu-footer"><button type="button" role="menuitem" className="account-signout" onClick={()=>{setAccountMenuOpen(false);onSignOut()}}><span className="account-menu-icon"><AccountMenuIcon kind="signout"/></span><span><strong>Sign out</strong><small>End this session</small></span></button></div></div>}</div></header>
      <div className="platform-content">
        <div className="platform-page-heading"><div><p className="eyebrow">PLATFORM OPERATIONS</p><div className="platform-heading-title"><PlatformSectionIcon section={section}/><h1>{section}</h1></div><p>{descriptions[section]}</p></div>{section==='Subscriptions'&&<button type="button" className="platform-refresh" onClick={loadWorkspaces} disabled={loading}>↻ <span>{loading ? 'Refreshing…' : 'Refresh list'}</span></button>}</div>
        {section!=='Account security'&&<nav className="platform-tabs" aria-label="Platform operations sections">{sections.map((item)=><button type="button" className={section===item?'selected':''} aria-current={section===item?'page':undefined} onClick={()=>setSection(item)} key={item}><PlatformSectionIcon section={item}/>{item}{item==='Plan maintenance'&&<span>{plans.length}</span>}{item==='Payment requests'&&pendingPaymentCount>0&&<span className="platform-count-badge" aria-label={`${pendingPaymentCount} pending payment requests`}>{pendingPaymentCount>99?'99+':pendingPaymentCount}</span>}</button>)}</nav>}
        {error && <div className="platform-alert" role="alert">{error}</div>}
        {section==='Subscriptions'&&<><div className="platform-metrics"><article><span>Workspaces</span><strong>{loading?'—':workspaces.length}</strong><small>All registered accounts</small></article><article><span>Active subscriptions</span><strong>{loading?'—':activeWorkspaces}</strong><small>Active and trialing</small></article><article><span>User accounts</span><strong>{loading?'—':userCount}</strong><small>Across all workspaces</small></article><article><span>Needs attention</span><strong>{loading?'—':workspaces.filter(w=>['PAST_DUE','SUSPENDED','CANCELLED'].includes(w.subscriptionStatus)).length}</strong><small>Past due or inactive</small></article></div><section className="platform-admin-card platform-workspaces-card"><div className="platform-card-heading"><div><h2>Workspace subscriptions</h2><p>Review subscription status and record administrative changes.</p></div></div><label className="platform-search"><span aria-hidden="true">⌕</span><input value={query} onChange={(event)=>setQuery(event.currentTarget.value)} placeholder="Search workspaces or owners"/></label>{loading?<div className="platform-empty">Loading workspace directory…</div>:filtered.length===0?<div className="platform-empty">{query?'No workspaces match your search.':'No workspaces have been created yet.'}</div>:<div className="platform-table-wrap"><table className="platform-table"><thead><tr><th>WORKSPACE</th><th>OWNER</th><th>PLAN</th><th>PERIOD COVERAGE</th><th>STATUS</th><th>USERS</th><th>CREATED</th><th></th></tr></thead><tbody>{filtered.map(workspace=><tr key={workspace.id}><td><strong>{workspace.name}</strong><small>{workspace.slug}</small></td><td>{workspace.ownerEmail||'—'}</td><td>{workspace.planName}<small>{workspace.planPriceConfigured?new Intl.NumberFormat(undefined,{style:'currency',currency:workspace.planCurrency}).format(workspace.planAmount)+' / '+(workspace.billingInterval==='ANNUAL'?'year':'month'):'Price not set'}</small></td><td>{workspace.subscriptionStatus==='TRIALING'?<>Trial<small>{workspace.trialEndsAt?`Through ${new Date(workspace.trialEndsAt).toLocaleDateString()}`:'End date not set'}</small></>:<>{workspace.periodStartsAt&&workspace.periodEndsAt?`${new Date(workspace.periodStartsAt).toLocaleDateString()} – ${new Date(workspace.periodEndsAt).toLocaleDateString()}`:'Not set'}<small>{workspace.periodEndsAt?'Billing period':'Period coverage'}</small></>}</td><td><span className={`platform-status-tag ${workspace.subscriptionStatus==='ACTIVE'||workspace.subscriptionStatus==='TRIALING'?'active':workspace.subscriptionStatus==='SUSPENDED'?'closed':'suspended'}`}>{workspace.subscriptionStatus.replace('_',' ')}</span></td><td>{workspace.userCount}</td><td>{new Intl.DateTimeFormat(undefined,{dateStyle:'medium'}).format(new Date(workspace.createdAt))}</td><td><button type="button" className="platform-text-action" onClick={()=>{setSelectedWorkspace(workspace);setSubscriptionDraft({status:workspace.subscriptionStatus,planCode:workspace.planCode,trialEndsAt:workspace.trialEndsAt?.slice(0,10)||'',periodStartsAt:workspace.periodStartsAt?.slice(0,10)||'',periodEndsAt:workspace.periodEndsAt?.slice(0,10)||'',reason:''})}}>Manage</button></td></tr>)}</tbody></table></div>}</section></>}
        {section==='Account security'&&<PlatformAdminMfaPanel token={token} email={user.email} onSignOut={onSignOut}/>}
        {section==='Payment requests'&&<PlatformPaymentsPanel token={token} onPendingCountChange={setPendingPaymentCount}/>}
        {section==='Plan maintenance'&&<PlatformPlansPanel token={token}/>}
        {section==='Payment QR'&&<PlatformQrPanel token={token}/>}
        {section==='Module status'&&<PlatformModulesPanel token={token}/>}
        {section==='Mail settings'&&<PlatformMailPanel token={token}/>}
        {section==='Backup & restore'&&<PlatformBackupPanel token={token} onOpenSecurity={()=>setSection('Account security')}/>}
        {selectedWorkspace&&<dialog ref={subscriptionDialogRef} className="platform-edit-dialog" onClose={()=>setSelectedWorkspace(null)}><form onSubmit={event=>void saveSubscription(event)}><header><div><p className="eyebrow">SUBSCRIPTION</p><h3>{selectedWorkspace.name}</h3></div><button type="button" aria-label="Close" onClick={()=>setSelectedWorkspace(null)}>×</button></header><p className="security-description">Changes to status, plan, and coverage dates are recorded in the administrative audit trail.</p><div className="platform-subscription-coverage"><strong>Current period coverage</strong><span>{selectedWorkspace.subscriptionStatus==='TRIALING'?`Trial ends ${selectedWorkspace.trialEndsAt?new Date(selectedWorkspace.trialEndsAt).toLocaleDateString():'date not set'}`:`${selectedWorkspace.periodStartsAt?new Date(selectedWorkspace.periodStartsAt).toLocaleDateString():'start not set'} – ${selectedWorkspace.periodEndsAt?new Date(selectedWorkspace.periodEndsAt).toLocaleDateString():'end not set'}`}</span></div><div className="platform-field"> <span>Status</span><SelectField label="Subscription status" value={subscriptionDraft.status} onChange={status=>setSubscriptionDraft({...subscriptionDraft,status})} options={[{value:"TRIALING",label:"Trialing"},{value:"ACTIVE",label:"Active"},{value:"PAST_DUE",label:"Past due"},{value:"SUSPENDED",label:"Suspended"},{value:"CANCELLED",label:"Cancelled"}]}/></div><div className="platform-field"> <span>Plan</span><SelectField label="Workspace plan" value={subscriptionDraft.planCode} onChange={planCode=>setSubscriptionDraft({...subscriptionDraft,planCode})} options={plans.filter(plan=>plan.active||plan.code===subscriptionDraft.planCode).map(plan=>({value:plan.code,label:`${plan.name}${!plan.active?' (archived)':''}`}))}/></div><div className="platform-editor-grid"><label>Trial end date<input type="date" value={subscriptionDraft.trialEndsAt} onChange={e=>setSubscriptionDraft({...subscriptionDraft,trialEndsAt:e.target.value})}/></label><label>Billing period starts<input type="date" value={subscriptionDraft.periodStartsAt} onChange={e=>setSubscriptionDraft({...subscriptionDraft,periodStartsAt:e.target.value})}/></label><label>Billing period ends<input type="date" value={subscriptionDraft.periodEndsAt} onChange={e=>setSubscriptionDraft({...subscriptionDraft,periodEndsAt:e.target.value})}/></label></div><label className="platform-reason">Administrative note<textarea minLength={8} maxLength={500} required value={subscriptionDraft.reason} onChange={e=>setSubscriptionDraft({...subscriptionDraft,reason:e.target.value})}/></label><footer><button type="button" className="session-revoke" onClick={()=>setSelectedWorkspace(null)}>Cancel</button><button type="submit" className="platform-primary-action" disabled={savingSubscription}>{savingSubscription?'Saving…':'Save subscription'}</button></footer></form></dialog>}
      </div>
    </main>
  </div>;
}

function App({ platformAdminPage = false, platformAdminDashboard = false, signupPage = false, dashboardPage = false }: { platformAdminPage?: boolean; platformAdminDashboard?: boolean; signupPage?: boolean; dashboardPage?: boolean }) {
  const navigate = useNavigate();
  const location = useLocation();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [workspaceName, setWorkspaceName] = useState('');
  const [username, setUsername] = useState('');
  const [signupMethod, setSignupMethod] = useState<'email' | 'google'>('email');
  const [mfaCode, setMfaCode] = useState('');
  const [mfaRequired, setMfaRequired] = useState(false);
  const [loginMethod, setLoginMethod] = useState<'password' | 'google'>('password');
  const [showPassword, setShowPassword] = useState(false);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');
  const [messageKind, setMessageKind] = useState<MessageKind>('error');
  const [user, setUser] = useState<CurrentUser | null>(null);
  const [recoveryOpen, setRecoveryOpen] = useState(false);
  const [recoveryEmail, setRecoveryEmail] = useState('');
  const [recoveryBusy, setRecoveryBusy] = useState(false);
  const [recoveryMessage, setRecoveryMessage] = useState('');
  const [recoveryMessageKind, setRecoveryMessageKind] = useState<MessageKind>('error');
  const [recoverySent, setRecoverySent] = useState(false);
  const resetDialog = useRef<HTMLDialogElement>(null);
  const signupUsernameInput = useRef<HTMLInputElement>(null);

  useEffect(() => {
    const dialog = resetDialog.current;
    if (!dialog) return;
    if (recoveryOpen && !dialog.open) dialog.showModal();
    if (!recoveryOpen && dialog.open) dialog.close();
  }, [recoveryOpen]);

  useEffect(() => {
    let active = true;
    const token = sessionStorage.getItem(TOKEN_KEY);
    const expiry = Number(sessionStorage.getItem(EXPIRY_KEY) || 0);
    if (!token) {
      if (dashboardPage) navigate('/', { replace: true });
      if (platformAdminDashboard) navigate('/platform-admin/login', { replace: true });
      return () => { active = false; };
    }
    if (expiry && expiry <= Date.now()) {
      sessionStorage.removeItem(TOKEN_KEY);
      sessionStorage.removeItem(EXPIRY_KEY);
      if (dashboardPage) navigate('/', { replace: true });
      if (platformAdminDashboard) navigate('/platform-admin/login', { replace: true });
      return () => { active = false; };
    }
    fetch('/api/v1/me', { headers: { Authorization: `Bearer ${token}` } })
      .then(async (response) => {
        if (!response.ok) throw new Error('Session expired');
        return await response.json() as CurrentUser;
      })
      .then((currentUser) => {
        if (!active) return;
        if (platformAdminDashboard && !currentUser.platformAdmin) {
          sessionStorage.removeItem(TOKEN_KEY); sessionStorage.removeItem(EXPIRY_KEY);
          navigate('/platform-admin/login', { replace: true }); return;
        }
        if (platformAdminPage && !currentUser.platformAdmin) return;
        setUser(currentUser);
        if (currentUser.platformAdmin && location.pathname !== '/platform-admin' && (dashboardPage || platformAdminPage)) navigate('/platform-admin', { replace: true });
        else if (!currentUser.platformAdmin && dashboardPage && location.pathname !== '/dashboard') navigate('/dashboard', { replace: true });
      })
      .catch(() => {
        sessionStorage.removeItem(TOKEN_KEY);
        sessionStorage.removeItem(EXPIRY_KEY);
        if (platformAdminDashboard) navigate('/platform-admin/login', { replace: true });
      });
    return () => { active = false; };
  }, [platformAdminPage, platformAdminDashboard, dashboardPage, location.pathname, navigate]);

  function setFormMessage(text: string, kind: MessageKind = 'error') {
    setMessage(text);
    setMessageKind(kind);
  }

  async function handleSignIn(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setFormMessage('');
    if (mfaRequired && mfaCode.length !== 6) { setFormMessage('Enter your six-digit authenticator code.'); return; }
    if (mfaCode && !/^\d{6}$/.test(mfaCode)) {
      setFormMessage('Enter a six-digit authenticator code.');
      return;
    }
    setBusy(true);
    try {
      const csrf = await fetchCsrfHeaders();
      const headers: Record<string, string> = { ...csrf, Authorization: basicAuth(email.trim(), password) };
      if (mfaCode) headers['X-TOTP-Code'] = mfaCode;
      const response = await fetch('/api/v1/security/tokens', {
        method: 'POST',
        credentials: 'same-origin',
        headers,
      });
      if (response.status === 428) {
        setMfaRequired(true);
        setFormMessage('This account has an authenticator enabled. Enter its current six-digit code to finish signing in.');
        return;
      }
      if (!response.ok) throw new Error(await responseError(response));
      const token = await response.json() as AccessToken;
      sessionStorage.setItem(TOKEN_KEY, token.value);
      sessionStorage.setItem(EXPIRY_KEY, String(new Date(token.expiresAt).getTime()));
      const me = await fetch('/api/v1/me', { headers: { Authorization: `Bearer ${token.value}` } });
      if (!me.ok) throw new Error('Your sign-in worked, but your profile could not be loaded. Please try again.');
      setUser(await me.json() as CurrentUser);
      navigate('/dashboard');
    } catch (error) {
      sessionStorage.removeItem(TOKEN_KEY);
      sessionStorage.removeItem(EXPIRY_KEY);
      setFormMessage(error instanceof Error ? error.message : 'We couldn’t sign you in. Please try again.');
    } finally {
      setBusy(false);
    }
  }

  async function handleGoogleSignIn(response: CredentialResponse) {
    if(!response.credential) {
      setFormMessage('Google did not return a credential. Please try again.');
      return;
    }
    setFormMessage('');
    setBusy(true);
    try {
      const result=await fetch('/api/v1/security/platform-admin/google', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ idToken: response.credential }),
      });
      if(!result.ok) throw new Error(await responseError(result));
      const token=await result.json() as AccessToken;
      sessionStorage.setItem(TOKEN_KEY,token.value);
      sessionStorage.setItem(EXPIRY_KEY,String(new Date(token.expiresAt).getTime()));
      const me=await fetch('/api/v1/me',{headers:{Authorization:`Bearer ${token.value}`}});
      if(!me.ok) throw new Error('Your sign-in worked, but your profile could not be loaded. Please try again.');
      const currentUser=await me.json() as CurrentUser;
      if(!currentUser.platformAdmin) throw new Error('This Google account is not enabled for platform administration.');
      setUser(currentUser);
      navigate('/platform-admin', { replace: true });
    } catch(error) {
      sessionStorage.removeItem(TOKEN_KEY);
      sessionStorage.removeItem(EXPIRY_KEY);
      setFormMessage(error instanceof Error ? error.message : 'Google sign-in failed. Please try again.');
    } finally {
      setBusy(false);
    }
  }

  async function handleSignup(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setFormMessage(''); setBusy(true);
    try {
      const csrf = await fetchCsrfHeaders();
      const response = await fetch('/api/v1/security/signup', {
        method: 'POST', credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json', ...csrf },
        body: JSON.stringify({ workspaceName: workspaceName.trim(), email: email.trim(), username: username.trim(), password }),
      });
      if (!response.ok) throw new Error(await responseError(response));
      const token = await response.json() as AccessToken;
      sessionStorage.setItem(TOKEN_KEY, token.value);
      sessionStorage.setItem(EXPIRY_KEY, String(new Date(token.expiresAt).getTime()));
      const me = await fetch('/api/v1/me', { headers: { Authorization: `Bearer ${token.value}` } });
      if (!me.ok) throw new Error('Your workspace was created, but your profile could not be loaded. Please sign in.');
      setUser(await me.json() as CurrentUser); navigate('/dashboard');
    } catch (error) { setFormMessage(error instanceof Error ? error.message : 'Could not create your workspace. Please try again.'); }
    finally { setBusy(false); }
  }

  async function handleGoogleSignup(credential: CredentialResponse) {
    if (!credential.credential) { setFormMessage('Google did not return a credential. Please try again.'); return; }
    const enteredUsername = username.trim() || signupUsernameInput.current?.value.trim() || '';
    const candidateUsername = (enteredUsername.includes('@') ? enteredUsername.split('@')[0] : enteredUsername).toLowerCase();
    if (!workspaceName.trim() || !/^[a-z0-9._-]{3,32}$/.test(candidateUsername)) {
      setFormMessage('Enter your organization name and a valid username before continuing.'); return;
    }
    setUsername(candidateUsername);
    setFormMessage(''); setBusy(true);
    try {
      const csrf = await fetchCsrfHeaders();
      const response = await fetch('/api/v1/security/signup/google', {
        method: 'POST', credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json', ...csrf },
        body: JSON.stringify({ workspaceName: workspaceName.trim(), username: candidateUsername, idToken: credential.credential }),
      });
      if (!response.ok) throw new Error(await responseError(response));
      const token = await response.json() as AccessToken;
      sessionStorage.setItem(TOKEN_KEY, token.value);
      sessionStorage.setItem(EXPIRY_KEY, String(new Date(token.expiresAt).getTime()));
      const me = await fetch('/api/v1/me', { headers: { Authorization: `Bearer ${token.value}` } });
      if (!me.ok) throw new Error('Your workspace was created, but your profile could not be loaded. Please sign in.');
      setUser(await me.json() as CurrentUser); navigate('/dashboard');
    } catch (error) { setFormMessage(error instanceof Error ? error.message : 'Google signup failed. Please try again.'); }
    finally { setBusy(false); }
  }

  async function handleGoogleUserSignIn(response: CredentialResponse) {
    if (!response.credential) {
      setFormMessage('Google did not return a credential. Please try again.');
      return;
    }
    if (mfaRequired && mfaCode.length !== 6) { setFormMessage('Enter your six-digit authenticator code, then choose Google sign-in again.'); return; }
    if (mfaCode && !/^\d{6}$/.test(mfaCode)) {
      setFormMessage('Enter a six-digit authenticator code.');
      return;
    }
    setFormMessage('');
    setBusy(true);
    try {
      const headers: Record<string, string> = { 'Content-Type': 'application/json' };
      if (mfaCode) headers['X-TOTP-Code'] = mfaCode;
      const result = await fetch('/api/v1/security/google', {
        method: 'POST',
        headers,
        body: JSON.stringify({ idToken: response.credential }),
      });
      if (result.status === 428) {
        setMfaRequired(true);
        setFormMessage('This account has an authenticator enabled. Enter its current six-digit code, then choose Google sign-in again.');
        return;
      }
      if (!result.ok) throw new Error(await responseError(result));
      const token = await result.json() as AccessToken;
      sessionStorage.setItem(TOKEN_KEY, token.value);
      sessionStorage.setItem(EXPIRY_KEY, String(new Date(token.expiresAt).getTime()));
      const me = await fetch('/api/v1/me', { headers: { Authorization: `Bearer ${token.value}` } });
      if (!me.ok) throw new Error('Your sign-in worked, but your profile could not be loaded. Please try again.');
      setUser(await me.json() as CurrentUser);
      navigate('/dashboard');
    } catch (error) {
      sessionStorage.removeItem(TOKEN_KEY);
      sessionStorage.removeItem(EXPIRY_KEY);
      setFormMessage(error instanceof Error ? error.message : 'Google sign-in failed. Please try again.');
    } finally {
      setBusy(false);
    }
  }

  async function handleSignOut() {
    const token = sessionStorage.getItem(TOKEN_KEY);
    if (token) {
      try {
        await fetch(user?.platformAdmin
          ? '/api/v1/security/platform-admin/tokens/current'
          : '/api/v1/security/tokens/current', {
          method: 'DELETE',
          headers: { Authorization: `Bearer ${token}` },
        });
      } catch { /* Clear the browser session even if the service is unavailable. */ }
    }
    sessionStorage.removeItem(TOKEN_KEY);
    sessionStorage.removeItem(EXPIRY_KEY);
    setUser(null);
    setPassword('');
    setMfaCode('');
    setMfaRequired(false);
    setFormMessage('You have signed out.', 'success');
    navigate('/');
  }

  async function handleRecovery(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setRecoveryBusy(true);
    setRecoveryMessage('');
    try {
      const csrf = await fetchCsrfHeaders();
      const response = await fetch('/api/v1/security/password-reset/request', {
        method: 'POST',
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json', ...csrf },
        body: JSON.stringify({ email: recoveryEmail.trim() }),
      });
      if (!response.ok) throw new Error(await responseError(response));
      setRecoveryMessage('If recovery is enabled for that account, a reset link will arrive by email.');
      setRecoveryMessageKind('success');
      setRecoverySent(true);
    } catch (error) {
      setRecoveryMessage(error instanceof Error ? error.message : 'Password recovery is unavailable right now.');
      setRecoveryMessageKind('error');
    } finally {
      setRecoveryBusy(false);
    }
  }

  function openRecovery() {
    setRecoveryEmail(email.trim());
    setRecoveryMessage('');
    setRecoverySent(false);
    setRecoveryOpen(true);
  }

  if (platformAdminDashboard) {
    return user?.platformAdmin ? <PlatformAdminDashboard user={user} onSignOut={handleSignOut} /> : <main className="dashboard-loading"><BrandMark /><span>Loading platform administration…</span></main>;
  }

  if (dashboardPage) {
    if (user?.platformAdmin) return <main className="dashboard-loading"><BrandMark /><span>Opening platform administration…</span></main>;
    return user ? <Dashboard user={user} onSignOut={handleSignOut} /> : <main className="dashboard-loading"><BrandMark /><span>Loading your workspace…</span></main>;
  }

  if (platformAdminPage && user?.platformAdmin) return <PlatformAdminDashboard user={user} onSignOut={handleSignOut} />;

  return (
    <main className="signin-shell">
      <BrandPanel />
      <section className="form-panel">
        <div className="mobile-brand"><BrandMark mobile /><span><strong>Apex</strong> People</span></div>
        <div className="form-wrap">
          <div className="form-intro">
            <p className="eyebrow">{platformAdminPage ? 'PLATFORM ACCESS' : signupPage ? 'GET STARTED' : dashboardPage ? 'YOUR WORKSPACE' : 'WELCOME BACK'}</p>
            <h2>{platformAdminPage ? 'Platform administrator' : signupPage ? 'Create your workspace' : dashboardPage ? 'Your dashboard' : 'Sign in to your workspace'}</h2>
            <p>{platformAdminPage
              ? 'Continue with the Google account authorized for platform administration.'
              : signupPage ? 'Set up your organization and owner account.' : dashboardPage ? 'A clearer place to manage your people.' : 'Sign in to your HRM workspace securely.'}</p>
          </div>
          {user ? (dashboardPage ? <Dashboard user={user} onSignOut={handleSignOut} /> : <SignedIn user={user} onSignOut={handleSignOut} />) : dashboardPage ? (
            <div className="signed-in"><p className="signed-in-copy">Checking your secure session…</p></div>
          ) : platformAdminPage ? (
            <div className="platform-admin-signin">
              <div className={`google-login-slot${busy ? ' busy' : ''}`}>
                {GOOGLE_CLIENT_ID
                  ? <GoogleLogin
                      onSuccess={(credential) => void handleGoogleSignIn(credential)}
                      onError={() => setFormMessage('Google sign-in was cancelled or failed.')}
                      text="signin_with"
                      theme="outline"
                      shape="rectangular"
                      size="large"
                      width="320"
                    />
                  : <p className="google-config-message">Set <code>GOOGLE_CLIENT_ID</code> in <code>backend/.env</code> to enable Google sign-in.</p>}
              </div>
              <Message text={message} kind={messageKind} />
              <p className="admin-allowlist-note">Only email addresses listed in <code>PLATFORM_ADMIN_EMAILS</code> in <code>backend/.env</code> can sign in here.</p>
            </div>
          ) : signupPage ? (
            <div className="signup-form">
              <div className="login-method-tabs" role="tablist" aria-label="Signup method">
                <button type="button" role="tab" aria-selected={signupMethod === 'email'} className={`login-method-tab${signupMethod === 'email' ? ' active' : ''}`} onClick={() => { setSignupMethod('email'); setFormMessage(''); }}>Email</button>
                <button type="button" role="tab" aria-selected={signupMethod === 'google'} className={`login-method-tab${signupMethod === 'google' ? ' active' : ''}`} onClick={() => { setSignupMethod('google'); setFormMessage(''); }}>Google</button>
              </div>
              <TextField id="workspace-name" label="Organization name" value={workspaceName} onChange={setWorkspaceName} placeholder="Your organization" autoComplete="organization" maxLength={200} />
              <div className="label-row"><label className="field-label" htmlFor="signup-username">Username</label></div>
              <div className="input-wrap"><input ref={signupUsernameInput} id="signup-username" name="workspace-username" type="text" value={username} onChange={(event) => setUsername(event.currentTarget.value)} onBlur={(event) => { const value = event.currentTarget.value.trim(); if (value.includes('@')) setUsername(value.split('@')[0].toLowerCase()); }} placeholder="3–32 letters, numbers, . _ -" autoComplete="off" spellCheck={false} minLength={3} maxLength={32} pattern="[A-Za-z0-9._-]{3,32}" required /></div>
              {signupMethod === 'email' ? <form onSubmit={handleSignup}>
              <div className="label-row"><label className="field-label" htmlFor="signup-email">Work email</label></div>
              <div className="input-wrap"><span className="input-icon"><Icon name="mail" /></span><input id="signup-email" type="email" value={email} onChange={(event) => setEmail(event.currentTarget.value)} placeholder="you@company.com" autoComplete="email" maxLength={254} required /></div>
              <div className="label-row"><label className="field-label" htmlFor="signup-password">Password</label></div>
              <div className="input-wrap"><span className="input-icon"><Icon name="lock" /></span><input id="signup-password" type="password" value={password} onChange={(event) => setPassword(event.currentTarget.value)} placeholder="At least 12 characters" autoComplete="new-password" minLength={12} maxLength={72} required /></div>
              <Message text={message} kind={messageKind} />
              <button className="submit-button" type="submit" disabled={busy}><span>{busy ? 'Creating workspace…' : 'Create workspace'}</span><Icon name="arrow" /></button>
              </form> : <div className="signup-google-panel">
                <p className="google-staff-note">Google verifies your email. Your username can be the part before @ in that address.</p>
                {GOOGLE_CLIENT_ID ? <div className={`google-login-slot${busy ? ' busy' : ''}`}><GoogleLogin onSuccess={(credential) => void handleGoogleSignup(credential)} onError={() => setFormMessage('Google signup was cancelled or failed.')} text="signup_with" theme="outline" shape="rectangular" size="large" width="320" /></div> : <p className="google-config-message">Google sign-in is not configured. Set <code>GOOGLE_CLIENT_ID</code> in <code>backend/.env</code>.</p>}
                <Message text={message} kind={messageKind} />
              </div>}
            </div>
          ) : (
            <div className="staff-signin">
              <div className="login-method-tabs" role="tablist" aria-label="Sign-in method">
                <button type="button" role="tab" aria-selected={loginMethod === 'google'} className={`login-method-tab${loginMethod === 'google' ? ' active' : ''}`} onClick={() => { setLoginMethod('google'); setFormMessage(''); }}>Google</button>
                <button type="button" role="tab" aria-selected={loginMethod === 'password'} className={`login-method-tab${loginMethod === 'password' ? ' active' : ''}`} onClick={() => { setLoginMethod('password'); setFormMessage(''); }}>Username &amp; password</button>
              </div>
              {loginMethod === 'google' ? (
                <div className="google-staff-panel" role="tabpanel">
                  <p className="google-staff-note">Use the Google account linked to your active HRM profile.</p>
                  <div className={`google-login-slot${busy ? ' busy' : ''}`}>
                    {GOOGLE_CLIENT_ID
                      ? <GoogleLogin onSuccess={(credential) => void handleGoogleUserSignIn(credential)} onError={() => setFormMessage('Google sign-in was cancelled or failed.')} text="signin_with" theme="outline" shape="rectangular" size="large" width="320" />
                      : <p className="google-config-message">Google sign-in is not configured. Set <code>GOOGLE_CLIENT_ID</code> in <code>backend/.env</code>.</p>}
                  </div>
                  {(mfaRequired || mfaCode) && <><div className="mfa-label-row google-mfa-label"><label className="field-label" htmlFor="google-mfa-code">Authenticator code</label><span>{mfaRequired ? 'REQUIRED' : 'IF ENABLED'}</span></div>
                  <div className="input-wrap mfa-wrap">
                    <span className="input-icon code-icon">#</span>
                    <input id="google-mfa-code" name="googleMfaCode" type="text" inputMode="numeric" autoComplete="one-time-code" placeholder="Enter your six-digit code" value={mfaCode} onChange={(event) => setMfaCode(event.currentTarget.value.replace(/\D/g, '').slice(0, 6))} maxLength={6} required={mfaRequired} />
                  </div></>}
                  <Message text={message} kind={messageKind} />
                </div>
              ) : (
            <form onSubmit={handleSignIn}>
              <TextField
                id="email"
                label="Email or username"
                value={email}
                onChange={setEmail}
                placeholder="you@company.com or username"
                autoComplete="username"
                maxLength={254}
                icon="mail"
              />
              <div className="label-row">
                <label className="field-label" htmlFor="password">Password</label>
                <button className="text-button" type="button" onClick={openRecovery}>Forgot password?</button>
              </div>
              <div className="input-wrap">
                <span className="input-icon"><Icon name="lock" /></span>
                <input
                  id="password"
                  name="password"
                  type={showPassword ? 'text' : 'password'}
                  value={password}
                  onChange={(event) => setPassword(event.currentTarget.value)}
                  autoComplete="current-password"
                  placeholder="Enter your password"
                  required
                />
                <button className="visibility-button" type="button" aria-label={showPassword ? 'Hide password' : 'Show password'} onClick={() => setShowPassword((value) => !value)}>
                  <Icon name="eye" />
                </button>
              </div>
              {(mfaRequired || mfaCode) && <><div className="mfa-label-row">
                <label className="field-label" htmlFor="mfa-code">Authenticator code</label>
                <span>{mfaRequired ? 'REQUIRED' : 'IF ENABLED'}</span>
              </div>
              <div className="input-wrap mfa-wrap">
                <span className="input-icon code-icon">#</span>
                <input
                  id="mfa-code"
                  name="mfaCode"
                  type="text"
                  inputMode="numeric"
                  autoComplete="one-time-code"
                  placeholder="Enter your six-digit code"
                  value={mfaCode}
                  onChange={(event) => setMfaCode(event.currentTarget.value.replace(/\D/g, '').slice(0, 6))}
                  maxLength={6}
                  required={mfaRequired}
                />
              </div></>}
              <Message text={message} kind={messageKind} />
              <button className="submit-button" type="submit" disabled={busy}>
                <span>{busy ? 'Signing in…' : 'Sign in'}</span><Icon name="arrow" />
              </button>
            </form>
              )}
            </div>
          )}
          {!user && !platformAdminPage && !dashboardPage && <div className="secure-note"><span className="secure-icon"><Icon name="shield" /></span><span>Your sign-in is protected with encrypted access.</span></div>}
          {!user && <div className="signin-mode-link">
            {platformAdminPage
              ? <Link to="/">← Staff sign in</Link>
              : signupPage
                ? <Link to="/">Already have an account? Sign in</Link>
                : dashboardPage ? <Link to="/">Return to sign in</Link>
                  : <><Link to="/signup">Create a workspace</Link><Link to="/platform-admin/login">Platform admin <Icon name="arrow" /></Link></>}
          </div>}
        </div>
        <footer className="page-footer"><span>© 2026 ApexMedSuite</span><span className="footer-separator" /><span>People, in good hands.</span></footer>
      </section>

      <dialog
        ref={resetDialog}
        className="reset-dialog"
        aria-labelledby="reset-title"
        onCancel={() => setRecoveryOpen(false)}
        onClose={() => setRecoveryOpen(false)}
        onClick={(event) => { if (event.target === event.currentTarget) setRecoveryOpen(false); }}
      >
        <form onSubmit={handleRecovery}>
          <button className="dialog-close" type="button" aria-label="Close" onClick={() => setRecoveryOpen(false)}>×</button>
          <p className="eyebrow">ACCOUNT RECOVERY</p>
          <h2 id="reset-title">Reset your password</h2>
          <p className="dialog-copy">Enter your work email and we’ll send a reset link if recovery is enabled for your account.</p>
          <TextField
            id="reset-email"
            label="Work email"
            type="email"
            value={recoveryEmail}
            onChange={setRecoveryEmail}
            placeholder="you@company.com"
            autoComplete="email"
            maxLength={254}
            icon="mail"
          />
          <Message text={recoveryMessage} kind={recoveryMessageKind} />
          {!recoverySent && <button className="submit-button" type="submit" disabled={recoveryBusy}>
            <span>{recoveryBusy ? 'Sending…' : 'Send reset link'}</span><Icon name="arrow" />
          </button>}
        </form>
      </dialog>
    </main>
  );
}

export default App;
