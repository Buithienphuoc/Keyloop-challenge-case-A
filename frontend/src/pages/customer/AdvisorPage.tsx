import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { DateTime } from 'luxon';
import { useState, type FormEvent } from 'react';
import { get, post } from '../../api/client';
import { useDealerships } from '../../api/hooks';
import type { AdvisorAppointment, Dealership } from '../../api/types';
import { Empty, ErrorNote, Loading, PageHead } from '../../components/ui';
import { whenLong } from '../../lib/format';

const MY_KEY = ['me', 'advisor-appointments'] as const;

/** Start times a dealership offers on a day: its opening hours on its slot grid. */
function startTimes(d: Dealership): string[] {
  const [oh = 0, om = 0] = d.openingTime.split(':').map(Number);
  const [ch = 0, cm = 0] = d.closingTime.split(':').map(Number);
  const out: string[] = [];
  for (let m = oh * 60 + om; m < ch * 60 + cm; m += d.slotMinutes)
    out.push(`${String(Math.floor(m / 60)).padStart(2, '0')}:${String(m % 60).padStart(2, '0')}`);
  return out;
}

/** A customer books a meeting with a service advisor: a time and what they want advice on. */
export function AdvisorPage() {
  const qc = useQueryClient();
  const dealerships = useDealerships();
  const mine = useQuery({ queryKey: MY_KEY, queryFn: () => get<AdvisorAppointment[]>('/api/me/advisor-appointments') });
  const [dealershipId, setDealershipId] = useState('');
  const dealership = dealerships.data?.find((d) => d.id === dealershipId) ?? dealerships.data?.[0];
  const zone = dealership?.timezone ?? 'Asia/Ho_Chi_Minh';
  const [date, setDate] = useState(DateTime.now().setZone(zone).plus({ days: 1 }).toISODate()!);
  const [at, setAt] = useState('09:00');
  const [message, setMessage] = useState('');

  const book = useMutation({
    mutationFn: () =>
      post<AdvisorAppointment>('/api/advisor-appointments', {
        dealershipId: dealership?.id,
        start: DateTime.fromISO(`${date}T${at}`, { zone }).toISO({ suppressMilliseconds: true }),
        message,
      }),
    onSuccess: () => {
      setMessage('');
      qc.invalidateQueries({ queryKey: MY_KEY });
    },
  });
  const cancel = useMutation({
    mutationFn: (id: string) => post<AdvisorAppointment>(`/api/advisor-appointments/${id}/cancel`, {}),
    onSuccess: () => qc.invalidateQueries({ queryKey: MY_KEY }),
  });

  const onSubmit = (e: FormEvent) => {
    e.preventDefault();
    book.mutate();
  };

  return (
    <div className="page page-narrow">
      <PageHead title="Meet an advisor">Book a time to talk to a service advisor. Tell them what you would like advice on.</PageHead>
      <div className="panel">
        {!dealership ? (
          <Loading />
        ) : (
          <form className="stack" onSubmit={onSubmit}>
            <ErrorNote error={book.error} />
            {book.isSuccess && <div className="notice notice-ok">Booked. An advisor will take your appointment and meet you then.</div>}
            <div className="field">
              <label htmlFor="adv-dealer">Which dealership?</label>
              <select id="adv-dealer" className="input" value={dealership.id} onChange={(e) => setDealershipId(e.target.value)}>
                {dealerships.data?.map((d) => (
                  <option key={d.id} value={d.id}>
                    {d.name}
                  </option>
                ))}
              </select>
            </div>
            <div className="grid-2">
              <div className="field">
                <label htmlFor="adv-date">Day</label>
                <input id="adv-date" type="date" className="input" value={date} onChange={(e) => setDate(e.target.value)} />
              </div>
              <div className="field">
                <label htmlFor="adv-time">Time</label>
                <select id="adv-time" className="input" value={at} onChange={(e) => setAt(e.target.value)}>
                  {startTimes(dealership).map((t) => (
                    <option key={t} value={t}>
                      {t}
                    </option>
                  ))}
                </select>
              </div>
            </div>
            <div className="field">
              <label htmlFor="adv-message">What would you like advice on?</label>
              <textarea
                id="adv-message"
                className="input"
                required
                minLength={5}
                maxLength={2000}
                value={message}
                onChange={(e) => setMessage(e.target.value)}
              />
            </div>
            <button className="btn btn-primary" disabled={book.isPending || message.trim().length < 5}>
              {book.isPending ? 'Booking…' : 'Book the appointment'}
            </button>
          </form>
        )}
      </div>

      <h2 style={{ marginTop: 32 }}>My advisor appointments</h2>
      <ErrorNote error={mine.error ?? cancel.error} />
      {mine.data?.length === 0 && <Empty title="No appointments yet" />}
      <div className="list">
        {mine.data?.map((a) => (
          <article key={a.id} className="panel stack">
            <div className="spread">
              <strong>{whenLong(a.start)}</strong>
              <span className={`pill pill-${a.status}`}>{a.status === 'SCHEDULED' ? 'Scheduled' : a.status === 'DONE' ? 'Done' : 'Cancelled'}</span>
            </div>
            <div className="muted small">
              {a.dealership.name} · {a.reference} · {a.advisor ? `with ${a.advisor.name}` : 'waiting for an advisor'}
            </div>
            <p>{a.message}</p>
            {a.status === 'SCHEDULED' && (
              <button className="btn btn-sm" disabled={cancel.isPending} onClick={() => cancel.mutate(a.id)}>
                Cancel
              </button>
            )}
          </article>
        ))}
      </div>
    </div>
  );
}
