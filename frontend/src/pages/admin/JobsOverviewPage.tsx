import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { get, post } from '../../api/client';
import type { Appointment, AppointmentStatus } from '../../api/types';
import { Empty, ErrorNote, Loading, PageHead, Plate, StatusPill } from '../../components/ui';
import { dt, money, stamp, time, vehicleName } from '../../lib/format';

type Tab = 'NEW' | 'IN_PROGRESS' | 'AWAITING_PAYMENT' | 'FINISHED' | 'CLOSED' | 'ALL';

const TABS: { id: Tab; label: string; statuses: AppointmentStatus[] }[] = [
  { id: 'AWAITING_PAYMENT', label: 'Awaiting payment', statuses: ['AWAITING_PAYMENT'] },
  { id: 'NEW', label: 'New', statuses: ['CONFIRMED'] },
  { id: 'IN_PROGRESS', label: 'In progress', statuses: ['IN_PROGRESS'] },
  { id: 'FINISHED', label: 'Finished', statuses: ['COMPLETED'] },
  { id: 'CLOSED', label: 'Cancelled & missed', statuses: ['CANCELLED', 'NO_SHOW'] },
  { id: 'ALL', label: 'All', statuses: [] },
];

/**
 * The dealership admin's view of every job, whatever its status, newest first. Once the
 * customer has paid at the desk the admin closes the job here (AWAITING_PAYMENT → COMPLETED);
 * there is no bank integration in the demo, so this is done by hand.
 */
export function JobsOverviewPage() {
  const [tab, setTab] = useState<Tab>('AWAITING_PAYMENT');
  const jobs = useQuery({
    queryKey: ['admin', 'jobs'],
    queryFn: () => get<Appointment[]>('/api/admin/jobs'),
    refetchInterval: 30_000,
  });
  const all = jobs.data ?? [];
  const inTab = (t: (typeof TABS)[number]) => all.filter((a) => t.statuses.length === 0 || t.statuses.includes(a.status));
  const current = TABS.find((t) => t.id === tab)!;
  const shown = inTab(current);

  return (
    <div className="page page-narrow">
      <PageHead title="Jobs & payments">
        Every job at your dealership. When a customer has paid for finished work, close the job here so they see it as finished.
      </PageHead>
      <div className="tabs" role="tablist">
        {TABS.map((t) => {
          // Only the list that needs the admin's action shows a count.
          const n = t.id === 'AWAITING_PAYMENT' ? inTab(t).length : 0;
          return (
            <button key={t.id} role="tab" aria-selected={tab === t.id} onClick={() => setTab(t.id)}>
              {t.label}
              {n > 0 && <span className="count">{n}</span>}
            </button>
          );
        })}
      </div>
      <ErrorNote error={jobs.error} />
      {jobs.isLoading && <Loading />}
      {jobs.data && shown.length === 0 && (
        <Empty title="Nothing here">{tab === 'AWAITING_PAYMENT' ? 'No finished job is waiting to be paid.' : 'No jobs in this list.'}</Empty>
      )}
      <div className="list">
        {shown.map((a) => (
          <JobRow key={a.id} a={a} />
        ))}
      </div>
    </div>
  );
}

function JobRow({ a }: { a: Appointment }) {
  const qc = useQueryClient();
  const [confirming, setConfirming] = useState(false);
  const paid = useMutation({
    mutationFn: () => post<Appointment>(`/api/appointments/${a.id}/status`, { status: 'COMPLETED', reason: 'Payment received' }),
    onSuccess: () => {
      setConfirming(false);
      qc.invalidateQueries({ queryKey: ['admin', 'jobs'] });
      qc.invalidateQueries({ queryKey: ['staff'] });
    },
  });

  return (
    <div className="item" data-job={a.reference}>
      <Plate text={a.vehicle.plate} />
      <div>
        <div className="row">
          <strong>{a.service.name}</strong>
          <StatusPill status={a.status} />
          <span className="muted small sign">{a.reference}</span>
        </div>
        <div className="meta">
          {dt(a.start).toFormat('ccc d LLL yyyy')}, {time(a.start)}–{time(a.end)} · {a.bay.name} · {a.technician.name}
        </div>
        <div className="meta">
          {a.customer.fullName}
          {a.customer.phone ? ` · ${a.customer.phone}` : ''} · {vehicleName(a.vehicle)} · <strong>{money(a.price)}</strong>
        </div>
        <div className="meta">Booked {stamp(a.createdAt, a.dealership.timezone)}</div>
        <ErrorNote error={paid.error} />
      </div>
      {a.status === 'AWAITING_PAYMENT' && (
        <div className="actions">
          {confirming ? (
            <>
              <button className="btn btn-sm btn-primary" disabled={paid.isPending} onClick={() => paid.mutate()}>
                {paid.isPending ? 'Closing…' : `Yes, ${money(a.price)} received`}
              </button>
              <button className="btn btn-sm btn-ghost" onClick={() => setConfirming(false)}>
                Not yet
              </button>
            </>
          ) : (
            <button className="btn btn-sm btn-primary" onClick={() => setConfirming(true)}>
              Payment received
            </button>
          )}
        </div>
      )}
    </div>
  );
}
