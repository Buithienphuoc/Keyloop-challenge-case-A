import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { get, post } from '../../api/client';
import type { Appointment, AppointmentStatus, Role } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { Empty, ErrorNote, Loading, PageHead, Plate, StatusPill } from '../../components/ui';
import { WorkshopFilter, scopeQuery, type WorkshopScope } from '../../components/WorkshopFilter';
import { dt, money, stamp, time, vehicleName } from '../../lib/format';

type Tab = 'ALL' | 'NEW' | 'IN_PROGRESS' | 'AWAITING_PAYMENT' | 'FINISHED' | 'CLOSED';

const TABS: { id: Tab; label: string; statuses: AppointmentStatus[] }[] = [
  { id: 'ALL', label: 'All', statuses: [] },
  { id: 'NEW', label: 'New', statuses: ['CONFIRMED'] },
  { id: 'IN_PROGRESS', label: 'In progress', statuses: ['IN_PROGRESS'] },
  { id: 'AWAITING_PAYMENT', label: 'Awaiting payment', statuses: ['AWAITING_PAYMENT'] },
  { id: 'FINISHED', label: 'Finished', statuses: ['COMPLETED'] },
  { id: 'CLOSED', label: 'Cancelled & missed', statuses: ['CANCELLED', 'NO_SHOW'] },
];

/** The status moves each role may make; the server checks them again, with the lifecycle rules. */
const MOVES: Record<'SERVICE_ADVISOR' | 'TECHNICIAN' | 'ADMIN', AppointmentStatus[]> = {
  SERVICE_ADVISOR: ['IN_PROGRESS', 'AWAITING_PAYMENT', 'NO_SHOW', 'CANCELLED'],
  TECHNICIAN: ['IN_PROGRESS', 'AWAITING_PAYMENT'],
  ADMIN: ['IN_PROGRESS', 'AWAITING_PAYMENT', 'COMPLETED', 'NO_SHOW', 'CANCELLED'],
};

const ACTION: Record<string, { from: AppointmentStatus; label: string; primary?: boolean }> = {
  IN_PROGRESS: { from: 'CONFIRMED', label: 'Start work', primary: true },
  AWAITING_PAYMENT: { from: 'IN_PROGRESS', label: 'Finish job', primary: true },
  COMPLETED: { from: 'AWAITING_PAYMENT', label: 'Payment received', primary: true },
  NO_SHOW: { from: 'CONFIRMED', label: 'Customer did not come' },
  CANCELLED: { from: 'CONFIRMED', label: 'Cancel booking' },
};

/**
 * Every appointment, newest first, for advisors, technicians and admins: their own workshop by
 * default, or any other one, or all of them. Each row offers the status changes the role may make.
 * Appointments are never deleted; cancelling is a status.
 */
export function AllAppointmentsPage() {
  const { user } = useAuth();
  const [scope, setScope] = useState<WorkshopScope>('');
  const [tab, setTab] = useState<Tab>('ALL');
  const list = useQuery({
    queryKey: ['staff', 'all-appointments', scope],
    queryFn: () => get<Appointment[]>(`/api/appointments?${scopeQuery(scope)}`),
    refetchInterval: 30_000,
  });
  const current = TABS.find((t) => t.id === tab)!;
  const shown = (list.data ?? []).filter((a) => current.statuses.length === 0 || current.statuses.includes(a.status));

  return (
    <div className="page page-narrow">
      <PageHead title="All appointments" actions={<WorkshopFilter value={scope} ownId={user?.dealershipId} onChange={setScope} />}>
        Every booking, newest first. Look at your own workshop, another one, or all of them, and move a job to its next status.
      </PageHead>
      <div className="tabs" role="tablist">
        {TABS.map((t) => (
          <button key={t.id} role="tab" aria-selected={tab === t.id} onClick={() => setTab(t.id)}>
            {t.label}
          </button>
        ))}
      </div>
      <ErrorNote error={list.error} />
      {list.isLoading && <Loading />}
      {list.data && shown.length === 0 && <Empty title="Nothing here">No appointments in this list.</Empty>}
      <div className="list">
        {shown.map((a) => (
          <AppointmentRow key={a.id} a={a} role={user!.role} />
        ))}
      </div>
    </div>
  );
}

function AppointmentRow({ a, role }: { a: Appointment; role: Role }) {
  const qc = useQueryClient();
  const change = useMutation({
    mutationFn: (status: AppointmentStatus) =>
      status === 'CANCELLED'
        ? post<Appointment>(`/api/appointments/${a.id}/cancel`, { reason: 'Cancelled by the dealership' })
        : post<Appointment>(`/api/appointments/${a.id}/status`, { status }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['staff'] });
      qc.invalidateQueries({ queryKey: ['admin', 'jobs'] });
    },
  });
  const started = Date.parse(a.start) <= Date.now();
  const moves = (MOVES[role as keyof typeof MOVES] ?? []).filter(
    (to) => ACTION[to].from === a.status && (to !== 'NO_SHOW' || started),
  );

  return (
    <div className="item" data-appointment={a.reference}>
      <Plate text={a.vehicle.plate} />
      <div>
        <div className="row">
          <strong>{a.service.name}</strong>
          <StatusPill status={a.status} />
          <span className="muted small sign">{a.reference}</span>
        </div>
        <div className="meta">
          {a.dealership.name} · {dt(a.start).toFormat('ccc d LLL yyyy')}, {time(a.start)}–{time(a.end)} · {a.bay.name} · {a.technician.name}
        </div>
        <div className="meta">
          {a.customer.fullName}
          {a.customer.phone ? ` · ${a.customer.phone}` : ''} · {vehicleName(a.vehicle)} · <strong>{money(a.price)}</strong>
        </div>
        <div className="meta">Booked {stamp(a.createdAt, a.dealership.timezone)}</div>
        <ErrorNote error={change.error} />
      </div>
      {moves.length > 0 && (
        <div className="actions">
          {moves.map((to) => (
            <button
              key={to}
              className={`btn btn-sm${ACTION[to].primary ? ' btn-primary' : to === 'CANCELLED' ? ' btn-danger' : ''}`}
              disabled={change.isPending}
              onClick={() => change.mutate(to)}
            >
              {ACTION[to].label}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
