# M1.12 — Personnel Workspace (developer preview)

This is a **developer preview**, not a production authentication frontend. Requires Node.js 22 and a running HRM Spring Boot API.

Run `cd frontend && npm install && npm run dev`; for a production build run `npm run build`.

Supply the company UUID and a short-lived bearer token issued to an MFA-verified HR administrator (or tenant administrator) through the backend's existing authentication flow. The token is held **in React memory only** and is never persisted to localStorage or cookies. Do not paste real tokens into screenshots, logs, issue reports, or shared devices.

`VITE_API_BASE_URL` may be set to an API origin. In development, Vite's proxy forwards `/api` to `http://localhost:8080`, so leave it empty when running both locally. Never bundle access tokens or private encryption keys into Vite environment variables.

Available now: company-scoped employee directory, department and position lists, employee registration and profile display. Before production: interactive sign-in/MFA, nuanced HR permissions, complete lifecycle UI, translations, accessibility/browser E2E, and privacy acceptance.
