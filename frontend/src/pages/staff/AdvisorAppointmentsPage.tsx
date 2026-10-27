import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { get, post } from '../../api/client';
import type { AdvisorAppointment } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { DayNav } from '../../components/DayNav';
import { Empty, ErrorNote, Loading, PageHead } from '../../components/ui';
import { dayShort, time, todayIn } from '../../lib/format';

/**
 * The advisor's first screen: meetings customers booked that no advisor has taken yet
 * ("New"), then the day's appointments. Advisor appointments are not service jobs.
 */
export function AdvisorAppointmentsPage() {
  const zone = 'Asia/Ho_Chi_Minh';
  const [date, setDate] = useState(todayIn(zone));
  const open = useQuery({
    queryKey: ['advisor-appointments', 'open'],
    queryFn: () => get<AdvisorAppointment[]>('/api/staff/advisor-appointments?open=true'),
    refetchInterval: 30_000,
  });
  const day = useQuery({
    queryKey: ['advisor-appointments', 'day', date],
    queryFn: () => get<AdvisorAppointment[]>(`/api/staff/advisor-appointments?date=${date}`),
  });

  return (
    <div className="page page-narrow">
      <PageHead title="New appointments">Customers who asked to meet an advisor. Take one and it is yours.</PageHead>
      <ErrorNote error={open.error} />
      {open.isLoading && <Loading />}
      {open.data?.length === 0 && <Empty title="No open appointments">Every appointment has an advisor. New ones appear here.</Empty>}
      <div className="list">
        {open.data?.map((a) => (
          <AppointmentCard key={a.id} a={a} />
        ))}
      </div>

      <PageHead title="Appointments of the day" actions={<DayNav date={date} zone={zone} onChange={setDate} />} />
      <ErrorNote error={day.error} />
      {day.data?.length === 0 && <Empty title="No appointments on this day" />}
      <div className="list">
        {day.data?.map((a) => (
          <AppointmentCard key={a.id} a={a} />
        ))}
      </div>
    </div>
  );
}

function AppointmentCard({ a }: { a: AdvisorAppointment }) {
  const { user } = useAuth();
  const qc = useQueryClient();
  const act = useMutation({
    mutationFn: (action: 'take' | 'complete') => post<AdvisorAppointment>(`/api/advisor-appointments/${a.id}/${action}`, {}),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['advisor-appointments'] }),
  });
  const mine = a.advisor?.id === user?.id;

  return (
    <article className="job-card">
      <div className="job-card-time">
        <span className="sign">{time(a.start)}</span>
        {a.status === 'SCHEDULED' && !a.advisor ? (
          <span className="pill pill-NEW">New</span>
        ) : (
          <span className={`pill pill-${a.status}`}>{a.status === 'SCHEDULED' ? 'Scheduled' : a.status === 'DONE' ? 'Done' : 'Cancelled'}</span>
        )}
      </div>
      <div className="job-card-body">
        <div>
          <strong>{a.customer.fullName}</strong>
          <div className="muted small">
            {dayShort(a.start)} · {a.reference}
            {a.customer.phone ? ` · ${a.customer.phone}` : ''}
            {a.advisor ? ` · with ${a.advisor.name}` : ''}
          </div>
        </div>
        <div className="notice notice-info">To be advised: {a.message}</div>
        <ErrorNote error={act.error} />
        {a.status === 'SCHEDULED' && !a.advisor && (
          <button className="btn btn-primary btn-block" disabled={act.isPending} onClick={() => act.mutate('take')}>
            Take this appointment
          </button>
        )}
        {a.status === 'SCHEDULED' && mine && (
          <button className="btn btn-block" disabled={act.isPending} onClick={() => act.mutate('complete')}>
            Mark as done
          </button>
        )}
      </div>
    </article>
  );
}
