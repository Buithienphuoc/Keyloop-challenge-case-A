import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { post } from '../../api/client';
import { keys, useMyAppointments } from '../../api/hooks';
import type { Appointment } from '../../api/types';
import { Empty, ErrorNote, Loading, PageHead, Plate, StatusPill } from '../../components/ui';
import { downloadIcs } from '../../lib/calendar';
import { dt, money, time, vehicleName } from '../../lib/format';

export function AppointmentsPage() {
  const appointments = useMyAppointments();
  const now = Date.now();
  const all = appointments.data ?? [];
  const upcoming = all.filter((a) => ['CONFIRMED', 'IN_PROGRESS'].includes(a.status) && Date.parse(a.end) > now);
  const past = all.filter((a) => !upcoming.includes(a)).reverse();

  return (
    <div className="page page-narrow">
      <PageHead
        title="My appointments"
        actions={
          <Link className="btn btn-primary" to="/book">
            Book a service
          </Link>
        }
      />
      <ErrorNote error={appointments.error} />
      {appointments.isLoading && <Loading />}
      {appointments.data && all.length === 0 && (
        <Empty title="No appointments yet" action={<Link className="btn btn-primary" to="/book">Book your first service</Link>}>
          Book a service and it will show up here with its reference number.
        </Empty>
      )}
      {upcoming.length > 0 && (
        <>
          <h2 className="section-title">Upcoming</h2>
          <div className="list">
            {upcoming.map((a) => (
              <AppointmentItem key={a.id} a={a} />
            ))}
          </div>
        </>
      )}
      {past.length > 0 && (
        <>
          <h2 className="section-title">Past and cancelled</h2>
          <div className="list">
            {past.map((a) => (
              <AppointmentItem key={a.id} a={a} past />
            ))}
          </div>
        </>
      )}
    </div>
  );
}

function AppointmentItem({ a, past = false }: { a: Appointment; past?: boolean }) {
  const qc = useQueryClient();
  const [confirming, setConfirming] = useState(false);
  const cancel = useMutation({
    mutationFn: () => post<Appointment>(`/api/appointments/${a.id}/cancel`, { reason: 'Cancelled by customer' }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: keys.myAppointments });
      qc.invalidateQueries({ queryKey: keys.myRequests });
      qc.invalidateQueries({ queryKey: ['availability'] });
    },
  });
  const canCancel = a.status === 'CONFIRMED' && Date.parse(a.start) > Date.now();

  return (
    <div className={`item${past ? ' past' : ''}`}>
      <Plate text={a.vehicle.plate} />
      <div>
        <div className="row">
          <strong>{a.service.name}</strong>
          <StatusPill status={a.status} />
        </div>
        <div className="meta">
          {dt(a.start).toFormat('ccc d LLL yyyy')}, {time(a.start)}–{time(a.end)} · {a.dealership.name}
        </div>
        <div className="meta">
          {vehicleName(a.vehicle)} · {a.technician.name} · {money(a.price)} · Ref <span className="sign">{a.reference}</span>
        </div>
        {cancel.error ? <ErrorNote error={cancel.error} /> : null}
      </div>
      {!past && (
        <div className="actions">
          <button className="btn btn-sm" onClick={() => downloadIcs(a)}>
            Add to calendar
          </button>
          {canCancel &&
            (confirming ? (
              <>
                <button className="btn btn-sm btn-danger" disabled={cancel.isPending} onClick={() => cancel.mutate()}>
                  {cancel.isPending ? 'Cancelling…' : 'Yes, cancel it'}
                </button>
                <button className="btn btn-sm btn-ghost" onClick={() => setConfirming(false)}>
                  Keep it
                </button>
              </>
            ) : (
              <button className="btn btn-sm btn-danger" onClick={() => setConfirming(true)}>
                Cancel
              </button>
            ))}
        </div>
      )}
    </div>
  );
}
