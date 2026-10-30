# M1.12 — Personnel Workspace (developer preview)

This is a **developer preview**, not a production authentication frontend. Requires Node.js 22 and a running HRM Spring Boot API.

Run `cd frontend && npm install && npm run dev`; for a production build run `npm run build`.

Supply the company UUID and a short-lived bearer token issued to an MFA-verified HR administrator (or tenant administrator) through the backend's existing authentication flow. The token is held **in React memory only** and is never persisted to localStorage or cookies. Do not paste real tokens into screenshots, logs, issue reports, or shared devices.

`VITE_API_BASE_URL` may be set to an API origin. In development, Vite's proxy forwards `/api` to `http://localhost:8080`, so leave it empty when running both locally. Never bundle access tokens or private encryption keys into Vite environment variables.

Available now: company-scoped employee directory, department and position lists, employee registration and profile display. Before production: interactive sign-in/MFA, nuanced HR permissions, complete lifecycle UI, translations, accessibility/browser E2E, and privacy acceptance.
# Apex People frontend

Standalone React and TypeScript sign-in frontend for the HRM backend. The Vite development server proxies `/api/*` to `http://localhost:8080`, so the browser can use the backend without cross-origin requests.

```sh
./run.sh
```

The root launcher starts the backend and Vite frontend together, waits for backend health before starting the frontend, and stops both when you press Ctrl+C. It frees the configured `SERVER_PORT` and Vite `FRONTEND_PORT` first. Vite serves the page at `http://localhost:5173` by default. Set `FRONTEND_PORT` or `BACKEND_URL` in `backend/.env` to change those settings.

Sign-in requests a CSRF token, posts HTTP Basic credentials (and an optional six-digit authenticator code) to `/api/v1/security/tokens`, then keeps the returned bearer token in tab-scoped `sessionStorage`. The password is not saved. Signing out revokes the current API token.

Staff can choose Google or username and password on the sign-in page. Google sign-in must match an existing active HRM account and continues to enforce authenticator checks for accounts that require MFA. Platform administrators use `/platform-admin/login` and Google Identity Services. Keep `GOOGLE_CLIENT_ID`, `GOOGLE_JWK_SET_URI`, and `PLATFORM_ADMIN_EMAILS` together in `backend/.env`. `run.sh` passes the public Google client ID to Vite; the backend independently verifies Google ID tokens. No Google client secret is needed for this ID-token flow.

New organizations can create a workspace at `/signup` with email and password or Google. The first account becomes its tenant administrator and can sign in with its email or username; after signup, it is redirected to `/dashboard` and prompted to enroll an authenticator before using admin tools.
