import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { get, post } from '../../api/client';
import type { Appointment, AppointmentStatus } from '../../api/types';
import { DayNav } from '../../components/DayNav';
import { Empty, ErrorNote, Loading, PageHead, Plate, StatusPill } from '../../components/ui';
import { time, todayIn, vehicleName } from '../../lib/format';

/**
 * The technician's only page: the service jobs booked for them on a day, with the checklist and
 * the customer's note, and big buttons to start, finish and close each one.
 */
export function JobsPage() {
  const zone = 'Asia/Ho_Chi_Minh';
  const [date, setDate] = useState(todayIn(zone));
  const jobs = useQuery({
    queryKey: ['tech', 'jobs', date],
    queryFn: () => get<Appointment[]>(`/api/technician/jobs?date=${date}`),
    refetchInterval: 30_000,
  });
  const list = (jobs.data ?? []).filter((j) => j.status !== 'CANCELLED');

  return (
    <div className="page page-narrow">
      <PageHead title="Service requests" actions={<DayNav date={date} zone={zone} onChange={setDate} />}>
        Jobs customers booked with you. Start one when the vehicle is in the bay and finish it when the work is done.
      </PageHead>
      <ErrorNote error={jobs.error} />
      {jobs.isLoading && <Loading />}
      {jobs.data && list.length === 0 && <Empty title="No jobs on this day">New bookings assigned to you appear here automatically.</Empty>}
      <div className="list">
        {list.map((j) => (
          <JobCard key={j.id} j={j} date={date} />
        ))}
      </div>
    </div>
  );
}

function JobCard({ j, date }: { j: Appointment; date: string }) {
  const qc = useQueryClient();
  const move = useMutation({
    mutationFn: (status: AppointmentStatus) => post<Appointment>(`/api/appointments/${j.id}/status`, { status }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['tech', 'jobs', date] }),
  });

  return (
    <article className="job-card">
      <div className="job-card-time">
        <span className="sign">
          {time(j.start)}–{time(j.end)}
        </span>
        <StatusPill status={j.status} />
      </div>
      <div className="job-card-body">
        <div className="spread">
          <div>
            <strong>{j.service.name}</strong>
            <div className="muted small">
              {j.customer.fullName} · {vehicleName(j.vehicle)} · {j.bay.name}
            </div>
          </div>
          <Plate text={j.vehicle.plate} />
        </div>
        {j.customerNote && <div className="notice notice-info">Customer says: {j.customerNote}</div>}
        {j.service.checklist.length > 0 && (
          <ul className="checklist">
            {j.service.checklist.map((c) => (
              <li key={c}>{c}</li>
            ))}
          </ul>
        )}
        <ErrorNote error={move.error} />
        {j.status === 'CONFIRMED' && (
          <button className="btn btn-primary btn-block" disabled={move.isPending} onClick={() => move.mutate('IN_PROGRESS')}>
            Start job
          </button>
        )}
        {j.status === 'IN_PROGRESS' && (
          <button className="btn btn-primary btn-block" disabled={move.isPending} onClick={() => move.mutate('AWAITING_PAYMENT')}>
            Customer checked the vehicle, finish job
          </button>
        )}
        {j.status === 'AWAITING_PAYMENT' && (
          <button className="btn btn-block" disabled={move.isPending} onClick={() => move.mutate('COMPLETED')}>
            Customer has paid, close job
          </button>
        )}
      </div>
    </article>
  );
}
