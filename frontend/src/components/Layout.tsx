import { Link, NavLink, Outlet, useNavigate } from 'react-router-dom';
import type { Role } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { ROLE_LABEL } from '../lib/format';

interface NavItem {
  to: string;
  label: string;
}

/**
 * The brief needs three roles: the customer books, the technician does the work, the system
 * manager sees everything. Other roles in the database have no menu (see App: RoleNotUsedPage).
 */
const NAV: Record<Role, NavItem[]> = {
  CUSTOMER: [
    { to: '/', label: 'Home' },
    { to: '/book', label: 'Book a service' },
    { to: '/appointments', label: 'My appointments' },
    { to: '/vehicles', label: 'My vehicles' },
  ],
  TECHNICIAN: [{ to: '/jobs', label: 'Service requests' }],
  SYSTEM_MANAGER: [
    { to: '/system', label: 'Dealerships' },
    { to: '/system/data', label: 'All data' },
    { to: '/system/people', label: 'People' },
    { to: '/system/packages', label: 'Service packages' },
  ],
  SERVICE_ADVISOR: [],
  ADMIN: [],
  WORKSHOP_CONTROLLER: [],
  PARTS_STAFF: [],
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
              <NavLink key={n.to} to={n.to} end>
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
