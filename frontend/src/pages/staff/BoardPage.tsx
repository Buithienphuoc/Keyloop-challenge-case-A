import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { DateTime } from 'luxon';
import { useState } from 'react';
import { get, post } from '../../api/client';
import { useDealerships } from '../../api/hooks';
import type { Appointment, AppointmentStatus } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { DayNav } from '../../components/DayNav';
import { Empty, ErrorNote, Loading, PageHead, Plate, StatusPill } from '../../components/ui';
import { dt, money, stamp, time, todayIn, vehicleName } from '../../lib/format';

interface Resources {
  bays: { id: string; name: string; bayType: string; bayTypeName: string }[];
  technicians: { id: string; displayName: string; skills: string[] }[];
}

const minutes = (hhmm: string) => {
  const [h = 0, m = 0] = hhmm.split(':').map(Number);
  return h * 60 + m;
};

/**
 * The advisor's view of one workshop day: every bay as a row, every job as a block
 * positioned by its time. Clicking a job opens it on the right with the actions that
 * are allowed for its current status.
 */
export function BoardPage() {
  const { user } = useAuth();
  const dealerships = useDealerships();
  const dealership = dealerships.data?.find((d) => d.id === user?.dealershipId);
  const zone = dealership?.timezone ?? 'Asia/Ho_Chi_Minh';
  const [date, setDate] = useState(todayIn(zone));
  const [selectedId, setSelectedId] = useState<string | null>(null);

  const resources = useQuery({ queryKey: ['staff', 'resources'], queryFn: () => get<Resources>('/api/staff/resources') });
  const appointments = useQuery({
    queryKey: ['staff', 'appointments', date],
    queryFn: () => get<Appointment[]>(`/api/staff/appointments?date=${date}`),
    refetchInterval: 30_000,
  });
  // The board shows one day, and customers can only book a little ahead, so a new booking
  // usually lands on a later day. This list makes sure the advisor still sees it.
  const upcoming = useQuery({
    queryKey: ['staff', 'appointments', 'upcoming'],
    queryFn: () => get<Appointment[]>('/api/staff/appointments/upcoming'),
    refetchInterval: 30_000,
  });

  if (!dealership || resources.isLoading) return <Loading />;

  const open = minutes(dealership.openingTime);
  const close = minutes(dealership.closingTime);
  const span = close - open;
  const pos = (iso: string) => {
    const d = dt(iso).setZone(zone);
    return ((d.hour * 60 + d.minute - open) / span) * 100;
  };
  const hours: number[] = [];
  for (let h = Math.ceil(open / 60); h * 60 <= close; h++) hours.push(h);

  const list = appointments.data ?? [];
  const active = list.filter((a) => a.status !== 'CANCELLED' && a.status !== 'EXPIRED');
  const selected = list.find((a) => a.id === selectedId) ?? null;
  const later = (upcoming.data ?? []).filter((a) => dt(a.start).toISODate() !== date);
  const openDay = (a: Appointment) => {
    setDate(dt(a.start).toISODate()!);
    setSelectedId(a.id);
  };
  const nowLocal = DateTime.now().setZone(zone);
  const showNow = nowLocal.toISODate() === date && nowLocal.hour * 60 + nowLocal.minute > open && nowLocal.hour * 60 + nowLocal.minute < close;
  const nowPos = (((nowLocal.hour * 60 + nowLocal.minute) - open) / span) * 100;

  const counts = {
    booked: active.length,
    inProgress: active.filter((a) => a.status === 'IN_PROGRESS').length,
    awaitingPayment: active.filter((a) => a.status === 'AWAITING_PAYMENT').length,
    done: active.filter((a) => a.status === 'COMPLETED').length,
  };

  return (
    <div className="page">
      <PageHead title="Workshop day" actions={<DayNav date={date} zone={zone} onChange={(d) => { setDate(d); setSelectedId(null); }} />}>
        {dealership.name} · {DateTime.fromISO(date, { zone }).toFormat('cccc d LLLL')} · {counts.booked} jobs, {counts.inProgress} in progress,{' '}
        {counts.awaitingPayment} awaiting payment, {counts.done} finished
      </PageHead>
      <ErrorNote error={appointments.error ?? resources.error} />

      <div className="split">
        <div className="board" role="region" aria-label="Bays and jobs">
          <div className="board-grid">
            <div className="board-row board-hours" style={{ minHeight: 30 }}>
              <div className="bay" />
              <div className="ticks">
                {hours.map((h) => (
                  <span key={h} style={{ left: `${((h * 60 - open) / span) * 100}%` }}>
                    {String(h).padStart(2, '0')}:00
                  </span>
                ))}
              </div>
            </div>
            {resources.data?.bays.map((bay) => (
              <div className="board-row" key={bay.id}>
                <div className="bay">
                  {bay.name}
                  <span>{bay.bayTypeName}</span>
                </div>
                <div className="board-track">
                  {hours.map((h) => (
                    <div key={h} className="hourline" style={{ left: `${((h * 60 - open) / span) * 100}%` }} />
                  ))}
                  {showNow && <div className="now-line" style={{ left: `${nowPos}%` }} aria-label="Now" />}
                  {active
                    .filter((a) => a.bay.id === bay.id)
                    .map((a) => (
                      <button
                        key={a.id}
                        className={`job s-${a.status}${a.id === selectedId ? ' selected' : ''}`}
                        style={{ left: `${pos(a.start)}%`, width: `${pos(a.end) - pos(a.start)}%` }}
                        onClick={() => setSelectedId(a.id)}
                        title={`${time(a.start)}–${time(a.end)} ${a.service.name}`}
                      >
                        <strong>{a.vehicle.plate}</strong>
                        <span>
                          {time(a.start)} {a.service.name}
                        </span>
                        <span>{a.technician.name}</span>
                      </button>
                    ))}
                </div>
              </div>
            ))}
          </div>
        </div>

        <aside>
          {selected ? (
            <JobPanel a={selected} date={date} resources={resources.data} onClose={() => setSelectedId(null)} />
          ) : (
            <div className="panel panel-tight stack">
              <h3>{date === todayIn(zone) ? 'Jobs today' : 'Jobs this day'}</h3>
              {list.length === 0 && <Empty title="Nothing booked">This day has no appointments yet.</Empty>}
              {list.map((a) => (
                <button key={a.id} className="choice" style={{ gridTemplateColumns: 'auto 1fr' }} onClick={() => setSelectedId(a.id)}>
                  <span className="sign" style={{ fontSize: '1.125rem' }}>
                    {time(a.start)}
                  </span>
                  <span>
                    <span className="title">{a.service.name}</span>
                    <span className="sub" style={{ display: 'block' }}>
                      {a.vehicle.plate} · {a.bay.name} · {a.technician.name}
                    </span>
                    <StatusPill status={a.status} />
                  </span>
                </button>
              ))}
            </div>
          )}
          {!selected && (
            <div className="panel panel-tight stack" role="region" aria-label="Coming up">
              <h3>Coming up</h3>
              {upcoming.isLoading && <Loading what="Looking ahead" />}
              <ErrorNote error={upcoming.error} />
              {upcoming.data && later.length === 0 && (
                <p className="muted small">No other confirmed bookings{list.length > 0 ? ' after this day' : ''}.</p>
              )}
              {later.map((a) => (
                <button key={a.id} className="choice" style={{ gridTemplateColumns: 'auto 1fr' }} onClick={() => openDay(a)}>
                  <span className="sign" style={{ fontSize: '1rem', textAlign: 'center' }}>
                    {dt(a.start).toFormat('ccc d LLL')}
                    <br />
                    {time(a.start)}
                  </span>
                  <span>
                    <span className="title">{a.service.name}</span>
                    <span className="sub" style={{ display: 'block' }}>
                      {a.vehicle.plate} · {a.customer.fullName}
                    </span>
                    <span className="sub" style={{ display: 'block' }}>
                      {a.reference} · booked {stamp(a.createdAt, a.dealership.timezone)}
                    </span>
                  </span>
                </button>
              ))}
            </div>
          )}
        </aside>
      </div>
    </div>
  );
}

function JobPanel({ a, date, resources, onClose }: { a: Appointment; date: string; resources?: Resources; onClose: () => void }) {
  const qc = useQueryClient();
  const { user } = useAuth();
  const canMove = user?.role === 'WORKSHOP_CONTROLLER' || user?.role === 'ADMIN';
  const canChangeStatus = user?.role !== 'WORKSHOP_CONTROLLER';
  const isAdmin = user?.role === 'ADMIN';
  const change = useMutation({
    mutationFn: (status: AppointmentStatus) =>
      status === 'CANCELLED'
        ? post<Appointment>(`/api/appointments/${a.id}/cancel`, { reason: 'Cancelled by the dealership' })
        : post<Appointment>(`/api/appointments/${a.id}/status`, { status }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['staff', 'appointments', date] });
      qc.invalidateQueries({ queryKey: ['staff', 'appointments', 'upcoming'] });
    },
  });
  const started = Date.parse(a.start) <= Date.now();

  return (
    <div className="panel panel-tight stack">
      <div className="spread">
        <span className="sign" style={{ fontSize: '1.25rem' }}>
          {a.reference}
        </span>
        <button className="btn btn-sm btn-ghost" onClick={onClose}>
          Close
        </button>
      </div>
      <div className="row">
        <Plate text={a.vehicle.plate} />
        <StatusPill status={a.status} />
      </div>
      <dl className="kv">
        <dt>Time</dt>
        <dd>
          {time(a.start)}–{time(a.end)}
        </dd>
        <dt>Service</dt>
        <dd>{a.service.name}</dd>
        <dt>Vehicle</dt>
        <dd>{vehicleName(a.vehicle)}</dd>
        <dt>Customer</dt>
        <dd>
          {a.customer.fullName}
          {a.customer.phone && (
            <>
              <br />
              <a href={`tel:${a.customer.phone.replace(/\s/g, '')}`}>{a.customer.phone}</a>
            </>
          )}
        </dd>
        <dt>Bay</dt>
        <dd>{a.bay.name}</dd>
        <dt>Technician</dt>
        <dd>{a.technician.name}</dd>
        <dt>Price</dt>
        <dd>{money(a.price)}</dd>
        {a.customerNote && (
          <>
            <dt>Note</dt>
            <dd>{a.customerNote}</dd>
          </>
        )}
      </dl>
      {a.service.checklist.length > 0 && (
        <ul className="checklist" style={{ columns: 1 }}>
          {a.service.checklist.map((c) => (
            <li key={c}>{c}</li>
          ))}
        </ul>
      )}
      <ErrorNote error={change.error} />
      {a.status === 'HELD' && <p className="muted small">The customer is confirming this time right now; it is released if they do not.</p>}
      {canMove && a.status === 'CONFIRMED' && resources && <MoveJob a={a} date={date} resources={resources} />}
      {canChangeStatus && <div className="row">
        {a.status === 'CONFIRMED' && (
          <button className="btn btn-primary btn-sm" disabled={change.isPending} onClick={() => change.mutate('IN_PROGRESS')}>
            Vehicle arrived, start work
          </button>
        )}
        {a.status === 'IN_PROGRESS' && (
          <button className="btn btn-primary btn-sm" disabled={change.isPending} onClick={() => change.mutate('AWAITING_PAYMENT')}>
            Customer checked the vehicle, finish job
          </button>
        )}
        {a.status === 'AWAITING_PAYMENT' && isAdmin && (
          <button className="btn btn-primary btn-sm" disabled={change.isPending} onClick={() => change.mutate('COMPLETED')}>
            Payment received, close job
          </button>
        )}
        {a.status === 'CONFIRMED' && started && (
          <button className="btn btn-sm" disabled={change.isPending} onClick={() => change.mutate('NO_SHOW')}>
            Customer did not come
          </button>
        )}
        {a.status === 'CONFIRMED' && (
          <button className="btn btn-sm btn-danger" disabled={change.isPending} onClick={() => change.mutate('CANCELLED')}>
            Cancel booking
          </button>
        )}
      </div>}
      <p className="muted small">Booked {stamp(a.createdAt, a.dealership.timezone)}</p>
    </div>
  );
}

/**
 * Moves a job to another bay and/or technician. The lists only offer bays of the same type;
 * the server still checks skills, shifts and clashes, and explains a refusal.
 */
function MoveJob({ a, date, resources }: { a: Appointment; date: string; resources: Resources }) {
  const qc = useQueryClient();
  const currentBay = resources.bays.find((b) => b.id === a.bay.id);
  const [bayId, setBayId] = useState(a.bay.id);
  const [technicianId, setTechnicianId] = useState(a.technician.id);
  const [reason, setReason] = useState('');
  const move = useMutation({
    mutationFn: () =>
      post<Appointment>(`/api/appointments/${a.id}/reassign`, {
        bayId: bayId !== a.bay.id ? bayId : undefined,
        technicianId: technicianId !== a.technician.id ? technicianId : undefined,
        reason: reason || undefined,
      }),
    onSuccess: () => {
      setReason('');
      qc.invalidateQueries({ queryKey: ['staff', 'appointments', date] });
      qc.invalidateQueries({ queryKey: ['staff', 'workload', date] });
    },
  });
  const unchanged = bayId === a.bay.id && technicianId === a.technician.id;

  return (
    <form
      className="stack"
      style={{ borderTop: '1px solid var(--line)', paddingTop: 12 }}
      onSubmit={(e) => {
        e.preventDefault();
        move.mutate();
      }}
    >
      <h4>Move this job</h4>
      <div className="grid-2">
        <div className="field">
          <label htmlFor="mv-bay">Bay</label>
          <select id="mv-bay" className="input" value={bayId} onChange={(e) => setBayId(e.target.value)}>
            {resources.bays
              .filter((b) => b.bayType === currentBay?.bayType)
              .map((b) => (
                <option key={b.id} value={b.id}>
                  {b.name}
                </option>
              ))}
          </select>
        </div>
        <div className="field">
          <label htmlFor="mv-tech">Technician</label>
          <select id="mv-tech" className="input" value={technicianId} onChange={(e) => setTechnicianId(e.target.value)}>
            {resources.technicians.map((t) => (
              <option key={t.id} value={t.id}>
                {t.displayName}
              </option>
            ))}
          </select>
        </div>
      </div>
      <div className="field">
        <label htmlFor="mv-reason">Why (optional)</label>
        <input id="mv-reason" className="input" placeholder="Hùng is off sick" value={reason} onChange={(e) => setReason(e.target.value)} />
      </div>
      <ErrorNote error={move.error} />
      {move.isSuccess && <div className="notice notice-ok">Moved. The technician sees it in their jobs straight away.</div>}
      <button className="btn btn-sm btn-primary" disabled={unchanged || move.isPending}>
        Move job
      </button>
    </form>
  );
}
