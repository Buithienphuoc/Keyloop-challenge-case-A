import { duration, money, time, whenLong } from '../lib/format';
import { Plate } from './ui';

export interface TicketData {
  plate?: string;
  vehicle?: string;
  service?: string;
  durationMinutes?: number;
  place?: string;
  placeSub?: string;
  start?: string;
  end?: string;
  price?: { amount: number; currency: string } | null;
  reference?: string;
}

/**
 * The service ticket that fills in as the customer chooses. It is the same object
 * they get at the end, so what they see while choosing is exactly what they book.
 */
export function Ticket({ data }: { data: TicketData }) {
  const row = (label: string, value?: React.ReactNode, sub?: React.ReactNode) => (
    <div>
      <dt>{label}</dt>
      {value ? (
        <dd>
          {value}
          {sub && <span className="sub">{sub}</span>}
        </dd>
      ) : (
        <dd className="empty-val">Not chosen yet</dd>
      )}
    </div>
  );

  return (
    <aside className="ticket" aria-label="Your booking">
      <div className="ticket-head">
        <span className="sign">{data.reference ?? 'Service ticket'}</span>
        <span>{data.reference ? 'Confirmed' : 'Draft'}</span>
      </div>
      <div className="ticket-plate">
        {data.plate ? <Plate text={data.plate} large /> : <span className="blank" aria-hidden="true" />}
        <div className="veh">{data.vehicle ?? 'Choose a vehicle'}</div>
      </div>
      <dl>
        {row('Service', data.service, data.durationMinutes ? `About ${duration(data.durationMinutes)}` : undefined)}
        {row('Where', data.place, data.placeSub)}
        {row(
          'When',
          data.start ? <span className="when">{whenLong(data.start)}</span> : undefined,
          data.start && data.end ? `Ready by about ${time(data.end)}` : undefined,
        )}
      </dl>
      <div className="ticket-foot">
        <span className="muted small">{data.price === null ? 'Estimate' : 'Price'}</span>
        <span className="total">{data.price !== undefined ? money(data.price) : '–'}</span>
      </div>
    </aside>
  );
}
