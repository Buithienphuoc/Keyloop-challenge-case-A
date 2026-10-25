import { Navigate, Route, Routes, useLocation } from 'react-router-dom';
import type { Role } from './api/types';
import { useAuth } from './auth/AuthContext';
import { Layout } from './components/Layout';
import { Loading } from './components/ui';
import { LoginPage, RegisterPage } from './pages/AuthPages';
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

const HOME: Record<Role, string> = {
  CUSTOMER: '/',
  SERVICE_ADVISOR: '/staff/board',
  ADMIN: '/staff/board',
  TECHNICIAN: '/jobs',
  SYSTEM_MANAGER: '/system',
};

function Only({ roles, children }: { roles: Role[]; children: React.ReactNode }) {
  const { user } = useAuth();
  if (!user) return null;
  return roles.includes(user.role) ? <>{children}</> : <Navigate to={HOME[user.role]} replace />;
}

export function App() {
  const { user, loading } = useAuth();
  const location = useLocation();

  if (loading) return <Loading what="Signing you in" />;

  if (!user) {
    return (
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/register" element={<RegisterPage />} />
        <Route path="*" element={<Navigate to="/login" replace state={{ from: location.pathname + location.search }} />} />
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
        <Route path="/staff/board" element={<Only roles={['SERVICE_ADVISOR', 'ADMIN']}><BoardPage /></Only>} />
        <Route path="/staff/requests" element={<Only roles={['SERVICE_ADVISOR', 'ADMIN']}><QueuePage /></Only>} />
        <Route path="/admin" element={<Only roles={['ADMIN']}><ResourcesPage /></Only>} />
        <Route path="/jobs" element={<Only roles={['TECHNICIAN']}><JobsPage /></Only>} />
        <Route path="/system" element={<Only roles={['SYSTEM_MANAGER']}><DealershipsPage /></Only>} />
      </Route>
      <Route path="/login" element={<Navigate to={(location.state as { from?: string } | null)?.from ?? HOME[user.role]} replace />} />
      <Route path="*" element={<Navigate to={HOME[user.role]} replace />} />
    </Routes>
  );
}
