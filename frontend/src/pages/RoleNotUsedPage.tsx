import { useAuth } from '../auth/AuthContext';
import { Empty } from '../components/ui';
import { ROLE_LABEL } from '../lib/format';

/** Advisors, dealership admins and other staff roles are not part of this version of the app. */
export function RoleNotUsedPage() {
  const { user } = useAuth();
  return (
    <div className="page page-narrow">
      <Empty title={`${user ? ROLE_LABEL[user.role] : 'This'} accounts are not used in this version`}>
        The app has three roles: customers book a service, technicians carry it out, and the system manager sees everything. Please sign in
        with one of those accounts.
      </Empty>
    </div>
  );
}
