import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { get } from '../../api/client';
import type { Role } from '../../api/types';
import { Empty, ErrorNote, Loading, PageHead } from '../../components/ui';

interface Person {
  id: string;
  fullName: string;
  email: string;
  phone: string | null;
  role: Role;
  dealershipId: string | null;
  dealershipName: string | null;
  isActive: boolean;
}

const TABS: { role: Role; label: string }[] = [
  { role: 'CUSTOMER', label: 'Customers' },
  { role: 'TECHNICIAN', label: 'Technicians' },
];

/** The system manager's list of people: customers and the technicians of every workshop. */
export function PeoplePage() {
  const [role, setRole] = useState<Role>('CUSTOMER');
  const people = useQuery({ queryKey: ['system', 'users'], queryFn: () => get<Person[]>('/api/system/users') });
  const count = (r: Role) => (people.data ?? []).filter((p) => p.role === r).length;
  const shown = (people.data ?? []).filter((p) => p.role === role);

  return (
    <div className="page page-narrow">
      <PageHead title="People">
        Customers and technicians with an account. The workshops themselves are on <Link to="/system">Dealerships</Link>.
      </PageHead>
      <div className="tabs" role="tablist">
        {TABS.map((t) => (
          <button key={t.role} role="tab" aria-selected={role === t.role} onClick={() => setRole(t.role)}>
            {t.label}
            {people.data && <span className="count">{count(t.role)}</span>}
          </button>
        ))}
      </div>
      <ErrorNote error={people.error} />
      {people.isLoading && <Loading />}
      {people.data && shown.length === 0 && <Empty title="Nobody here">No accounts with this role yet.</Empty>}
      <div className="list" aria-label="People">
        {shown.map((p) => (
          <div className="item" key={p.id} data-person={p.email}>
            <div>
              <div className="row">
                <strong>{p.fullName}</strong>
                {!p.isActive && <span className="pill pill-CANCELLED">Inactive</span>}
              </div>
              <div className="meta">
                {p.email}
                {p.phone ? ` · ${p.phone}` : ''}
              </div>
              {p.dealershipName && <div className="meta">{p.dealershipName}</div>}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
