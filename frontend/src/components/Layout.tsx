import { Link, NavLink, Outlet, useNavigate } from 'react-router-dom';
import type { Role } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { ROLE_LABEL } from '../lib/format';

const NAV: Record<Role, { to: string; label: string }[]> = {
  CUSTOMER: [
    { to: '/', label: 'Home' },
    { to: '/book', label: 'Book a service' },
    { to: '/appointments', label: 'My appointments' },
    { to: '/requests', label: 'Advice requests' },
    { to: '/vehicles', label: 'My vehicles' },
  ],
  SERVICE_ADVISOR: [
    { to: '/staff/board', label: 'Workshop day' },
    { to: '/staff/appointments', label: 'All appointments' },
    { to: '/staff/requests', label: 'Advice requests' },
    { to: '/staff/workload', label: 'Workload' },
    { to: '/parts', label: 'Parts' },
  ],
  ADMIN: [
    { to: '/staff/board', label: 'Workshop day' },
    { to: '/admin/jobs', label: 'Jobs & payments' },
    { to: '/staff/appointments', label: 'All appointments' },
    { to: '/staff/requests', label: 'Advice requests' },
    { to: '/staff/workload', label: 'Workload' },
    { to: '/parts', label: 'Parts' },
    { to: '/admin', label: 'Bays, team & hours' },
  ],
  WORKSHOP_CONTROLLER: [
    { to: '/staff/board', label: 'Workshop day' },
    { to: '/staff/workload', label: 'Workload' },
    { to: '/parts', label: 'Parts' },
  ],
  PARTS_STAFF: [{ to: '/parts', label: 'Parts orders & stock' }],
  TECHNICIAN: [
    { to: '/jobs', label: 'My jobs' },
    { to: '/staff/appointments', label: 'All appointments' },
    { to: '/staff/requests', label: 'Advice requests' },
    { to: '/parts', label: 'Parts' },
  ],
  SYSTEM_MANAGER: [
    { to: '/system', label: 'Dealerships' },
    { to: '/system/people', label: 'People' },
    { to: '/system/packages', label: 'Service packages' },
  ],
};

export function Layout() {
  const { user, signOut } = useAuth();
  const navigate = useNavigate();
  if (!user) return null;

  return (
    <>
      <header className="topbar">
        <div className="topbar-inner">
          <Link to="/" className="brand" aria-label="Service Scheduler home">
            <span className="brand-mark" aria-hidden="true" />
            Service Scheduler
          </Link>
          <nav className="nav" aria-label="Main">
            {NAV[user.role].map((n) => (
              <NavLink key={n.to} to={n.to} end={n.to === '/'}>
                {n.label}
              </NavLink>
            ))}
          </nav>
          <div className="who">
            <div className="who-name">
              {user.fullName}
              <span>{user.dealershipName ?? ROLE_LABEL[user.role]}</span>
            </div>
            <button
              className="btn btn-sm"
              onClick={async () => {
                await signOut();
                navigate('/login');
              }}
            >
              Sign out
            </button>
          </div>
        </div>
      </header>
      <main>
        <Outlet />
      </main>
    </>
  );
}
