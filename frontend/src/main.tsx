import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { GoogleOAuthProvider } from '@react-oauth/google';
import { BrowserRouter, Route, Routes } from 'react-router-dom';
import App, { InvitationAcceptance } from './App';
import PayrollFoundationPage from './PayrollFoundationPage';

const googleClientId = import.meta.env.VITE_GOOGLE_CLIENT_ID ?? '';

function AppRoutes() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/platform-admin/login" element={<App platformAdminPage />} />
        <Route path="/platform-admin" element={<App platformAdminDashboard />} />
        <Route path="/signup" element={<App signupPage />} />
        <Route path="/invite/accept" element={<InvitationAcceptance />} />
        <Route path="/dashboard" element={<App dashboardPage />} />
        <Route path="/payroll" element={<PayrollFoundationPage />} />
        <Route path="*" element={<App />} />
      </Routes>
    </BrowserRouter>
  );
}

createRoot(document.getElementById('app')!).render(
  <StrictMode>
    {googleClientId
      ? <GoogleOAuthProvider clientId={googleClientId}><AppRoutes /></GoogleOAuthProvider>
      : <AppRoutes />}
  </StrictMode>,
);
