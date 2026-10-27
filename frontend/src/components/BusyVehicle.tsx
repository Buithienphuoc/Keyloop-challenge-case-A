import { Link } from 'react-router-dom';
import type { Appointment } from '../api/types';
import { dayShort, time } from '../lib/format';

/** One line on why a vehicle cannot be booked again yet, and what frees it. */
export function BusyNote({ appointment: a }: { appointment: Appointment }) {
  const when = `${dayShort(a.start)} at ${time(a.start)}`;
  if (a.status === 'IN_PROGRESS') {
    return <span className="busy-note">In the workshop now ({a.reference}). You can book it again once that job is done.</span>;
  }
  return (
    <span className="busy-note">
      {a.status === 'HELD' ? 'On hold' : 'Already booked'}: {a.reference}, {when}. <Link to="/appointments">Cancel it in My appointments</Link> to
      book this vehicle again.
    </span>
  );
}
