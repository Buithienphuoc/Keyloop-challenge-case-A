import { useState } from 'react';
import { Navigate, Route, Routes, useLocation } from 'react-router-dom';
import type { Role } from './api/types';
import { useAuth } from './auth/AuthContext';
import { Layout } from './components/Layout';
import { Loading } from './components/ui';
import { LoginPage, RegisterPage } from './pages/AuthPages';
import { JobsOverviewPage } from './pages/admin/JobsOverviewPage';
import { ResourcesPage } from './pages/admin/ResourcesPage';
import { AppointmentsPage } from './pages/customer/AppointmentsPage';
import { BookPage } from './pages/customer/BookPage';
import { ConsultPage } from './pages/customer/ConsultPage';
import { HomePage } from './pages/customer/HomePage';
import { RequestsPage } from './pages/customer/RequestsPage';
import { VehiclesPage } from './pages/customer/VehiclesPage';
import { BoardPage } from './pages/staff/BoardPage';
import { JobsPage } from './pages/staff/JobsPage';
import { QueuePage } from './pages/staff/QueuePage';
import { DealershipsPage } from './pages/system/DealershipsPage';
import { PackagesPage } from './pages/system/PackagesPage';
import { PartsPage } from './pages/parts/PartsPage';
import { WorkloadPage } from './pages/staff/WorkloadPage';
import { AdvisorAppointmentsPage } from './pages/staff/AdvisorAppointmentsPage';
import { OpenServicesPage } from './pages/staff/OpenServicesPage';
import { AdvisorPage } from './pages/customer/AdvisorPage';

const HOME: Record<Role, string> = {
  CUSTOMER: '/',
  // First screen after sign-in: what still needs someone (open appointments / open services).
  SERVICE_ADVISOR: '/staff/advisor-appointments',
  ADMIN: '/staff/board',
  TECHNICIAN: '/services/open',
  SYSTEM_MANAGER: '/system',
  WORKSHOP_CONTROLLER: '/staff/board',
  PARTS_STAFF: '/parts',
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
        <Route path="/requests" element={<Only roles={['CUSTOMER']}><RequestsPage /></Only>} />
        <Route path="/requests/new" element={<Only roles={['CUSTOMER']}><ConsultPage /></Only>} />
        <Route path="/vehicles" element={<Only roles={['CUSTOMER']}><VehiclesPage /></Only>} />
        <Route path="/advisor" element={<Only roles={['CUSTOMER']}><AdvisorPage /></Only>} />
        <Route path="/staff/advisor-appointments" element={<Only roles={['SERVICE_ADVISOR', 'ADMIN']}><AdvisorAppointmentsPage /></Only>} />
        <Route path="/services/open" element={<Only roles={['TECHNICIAN', 'SERVICE_ADVISOR']}><OpenServicesPage /></Only>} />
        <Route path="/staff/board" element={<Only roles={['SERVICE_ADVISOR', 'ADMIN', 'WORKSHOP_CONTROLLER']}><BoardPage /></Only>} />
        <Route path="/staff/workload" element={<Only roles={['SERVICE_ADVISOR', 'ADMIN', 'WORKSHOP_CONTROLLER']}><WorkloadPage /></Only>} />
        <Route path="/parts" element={<Only roles={['SERVICE_ADVISOR', 'ADMIN', 'WORKSHOP_CONTROLLER', 'PARTS_STAFF', 'TECHNICIAN']}><PartsPage /></Only>} />
        <Route path="/staff/requests" element={<Only roles={['SERVICE_ADVISOR', 'ADMIN']}><QueuePage /></Only>} />
        <Route path="/admin" element={<Only roles={['ADMIN']}><ResourcesPage /></Only>} />
        <Route path="/admin/jobs" element={<Only roles={['ADMIN']}><JobsOverviewPage /></Only>} />
        <Route path="/jobs" element={<Only roles={['TECHNICIAN']}><JobsPage /></Only>} />
        <Route path="/system" element={<Only roles={['SYSTEM_MANAGER']}><DealershipsPage /></Only>} />
        <Route path="/system/packages" element={<Only roles={['SYSTEM_MANAGER']}><PackagesPage /></Only>} />
      </Route>
      <Route path="/login" element={<Navigate to={(location.state as { from?: string } | null)?.from ?? HOME[user.role]} replace />} />
      <Route path="*" element={<Navigate to={HOME[user.role]} replace />} />
    </Routes>
  );
}
