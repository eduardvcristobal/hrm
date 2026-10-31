# Apex People — HRMS experience prototype

Branch: `feature/hrms-ui-prototype` (based on `feature/m2-timekeeping-attendance`).
This is a **visual / interactive product prototype**, not a replacement for the existing authenticated HRMS screens.

## Start locally

From the repository root:

```bash
cd frontend
npm ci
npm run dev
```

Visit **http://localhost:5173/prototype**. The prototype is frontend-only and uses synthetic `example.test` people and metrics, so its route can render without the backend. Do not treat the sample KPIs as actual organizational results.

## Experience inventory

- Responsive collapsible mobile sidebar and fully linked navigation: Overview, Directory, Onboarding, Organization, Documents, Attendance, Shifts, Leave, Payroll, Benefits, Recruitment, Performance, Learning, Reports, Approvals, Announcements, Audit, Users & Roles, Settings.
- Sticky header with breadcrumb, search (Cmd/Ctrl+K), notification menu, account avatar/dropdown, dark/light appearance switch, and exit-prototype / demo logout control.
- Dashboard with KPI cards, illustrative headcount chart, attendance ring, approval tasks, upcoming events and people table.
- Filterable employee directory and profile detail modal.
- Organization hierarchy; analytics dashboard; module-specific sample tables and status filters; settings navigation.
- Responsive laptop/tablet/mobile layout and keyboard support (Escape closes overlays; Cmd/Ctrl+K focuses search).
- Each destructive/create/export action either navigates to a prototype surface or explicitly reports that it is not connected. It must never modify live employee or payroll data.

## Design constraints / acceptance

1. All prototype pages must remain explicitly identified as demo and synthetic.
2. Prototype logout exits the demonstration; it **does not revoke a separately authenticated production/backend token**. Real session logout remains implemented by the existing app.
3. Backend permissions, MFA, tenant policies, audit trails and data handling remain governed by the existing production API, not the demo.
4. No fake charts or records may be promoted into live operational reporting.
5. Before turning prototype screens into production: connect to real role-scoped APIs, add empty/loading/error states and validated mutations, test tenant isolation and privacy, supply localized content, complete accessibility review and E2E tests.
6. M2 attendance-policy certification and the existing draft PR #4 continue separately.

## Suggested visual review

Open `/prototype` and navigate to Employees, Attendance, Approvals, Payroll, Settings; check avatar menu, notifications, filter/search, organization cards and mobile sidebar. Validate at 390, 768, 1280 and 1440 px widths before adopting designs.
