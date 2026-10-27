import { useState } from 'react';
import { Navigate, Route, Routes, useLocation } from 'react-router-dom';
import type { Role } from './api/types';
import { useAuth } from './auth/AuthContext';
import { Layout } from './components/Layout';
import { Loading } from './components/ui';
import { LoginPage, RegisterPage } from './pages/AuthPages';
import { RoleNotUsedPage } from './pages/RoleNotUsedPage';
import { AppointmentsPage } from './pages/customer/AppointmentsPage';
import { BookPage } from './pages/customer/BookPage';
import { HomePage } from './pages/customer/HomePage';
import { VehiclesPage } from './pages/customer/VehiclesPage';
import { JobsPage } from './pages/staff/JobsPage';
import { AllDataPage } from './pages/system/AllDataPage';
import { DealershipsPage } from './pages/system/DealershipsPage';
import { PackagesPage } from './pages/system/PackagesPage';
import { PeoplePage } from './pages/system/PeoplePage';

const HOME: Record<Role, string> = {
  CUSTOMER: '/',
  TECHNICIAN: '/jobs',
  SYSTEM_MANAGER: '/system',
  // Roles the brief does not need: their accounts stay in the database but have no pages.
  SERVICE_ADVISOR: '/not-used',
  ADMIN: '/not-used',
  WORKSHOP_CONTROLLER: '/not-used',
  PARTS_STAFF: '/not-used',
};

function Only({ roles, children }: { roles: Role[]; children: React.ReactNode }) {
  const { user } = useAuth();
  if (!user) return null;
  return roles.includes(user.role) ? <>{children}</> : <Navigate to={HOME[user.role]} replace />;
}

export function App() {
  const { user, loading } = useAuth();
  const location = useLocation();
  // After someone signs out, the next person to sign in starts on their own home page, not on
  // the page the previous person was on.
  const [signedInBefore, setSignedInBefore] = useState(false);
  if (user && !signedInBefore) setSignedInBefore(true);

  if (loading) return <Loading what="Signing you in" />;

  if (!user) {
    return (
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/register" element={<RegisterPage />} />
        <Route path="*" element={<Navigate to="/login" replace state={signedInBefore ? undefined : { from: location.pathname + location.search }} />} />
      </Routes>
    );
  }

  return (
    <Routes>
      <Route element={<Layout />}>
        <Route path="/" element={user.role === 'CUSTOMER' ? <HomePage /> : <Navigate to={HOME[user.role]} replace />} />
        <Route path="/book" element={<Only roles={['CUSTOMER']}><BookPage /></Only>} />
        <Route path="/appointments" element={<Only roles={['CUSTOMER']}><AppointmentsPage /></Only>} />
        <Route path="/vehicles" element={<Only roles={['CUSTOMER']}><VehiclesPage /></Only>} />
        <Route path="/jobs" element={<Only roles={['TECHNICIAN']}><JobsPage /></Only>} />
        <Route path="/system" element={<Only roles={['SYSTEM_MANAGER']}><DealershipsPage /></Only>} />
        <Route path="/system/data" element={<Only roles={['SYSTEM_MANAGER']}><AllDataPage /></Only>} />
        <Route path="/system/people" element={<Only roles={['SYSTEM_MANAGER']}><PeoplePage /></Only>} />
        <Route path="/system/packages" element={<Only roles={['SYSTEM_MANAGER']}><PackagesPage /></Only>} />
        <Route path="/not-used" element={<RoleNotUsedPage />} />
      </Route>
      <Route path="/login" element={<Navigate to={(location.state as { from?: string } | null)?.from ?? HOME[user.role]} replace />} />
      <Route path="*" element={<Navigate to={HOME[user.role]} replace />} />
    </Routes>
  );
}
