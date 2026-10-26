import { Link } from 'react-router-dom';
import type { Appointment } from '../../../api/types';
import { Ticket, type TicketData } from '../../../components/Ticket';
import { downloadIcs } from '../../../lib/calendar';
import { dayLong, time } from '../../../lib/format';

/** The end of the journey: the reference, add to calendar, and the ticket. */
export function BookedPanel({ booked, ticket }: { booked: Appointment; ticket: TicketData }) {
  return (
    <div className="page">
      <div className="book">
        <div className="panel confirmed">
          <div className="tick" aria-hidden="true" />
          <h1>You're booked in</h1>
          <p className="ref">{booked.reference}</p>
          <p className="muted">
            {dayLong(booked.start)} at {time(booked.start)}, {booked.dealership.name}.
            <br />
            {booked.technician.name} will look after your {booked.vehicle.make}. Show this reference at the service desk.
          </p>
          <div className="row" style={{ justifyContent: 'center', marginTop: 24 }}>
            <button className="btn" onClick={() => downloadIcs(booked)}>
              Add to calendar
            </button>
            <Link className="btn btn-primary" to="/appointments">
              See my appointments
            </Link>
          </div>
        </div>
        <Ticket data={ticket} />
      </div>
    </div>
  );
}
