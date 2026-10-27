import { Link } from 'react-router-dom';
import { useBusyVehicles, useMyAppointments, useMyRequests, usePackages, useVehicles } from '../../api/hooks';
import { BusyNote } from '../../components/BusyVehicle';
import { useAuth } from '../../auth/AuthContext';
import { Plate, StatusPill } from '../../components/ui';
import { dt, duration, time, vehicleName } from '../../lib/format';

export function HomePage() {
  const { user } = useAuth();
  const vehicles = useVehicles();
  const packages = usePackages();
  const appointments = useMyAppointments();
  const requests = useMyRequests();
  const busy = useBusyVehicles();

  const firstName = user?.fullName.trim().split(/\s+/).at(-1) ?? '';
  const mainType = vehicles.data?.[0]?.vehicleType ?? 'MOTORBIKE';
  const recommended = packages.data?.find((p) => p.isRecommended && p.vehicleType === mainType);
  const upcoming = (appointments.data ?? [])
    .filter((a) => (a.status === 'CONFIRMED' || a.status === 'IN_PROGRESS') && dt(a.end) > dt(new Date().toISOString()))
    .sort((a, b) => a.start.localeCompare(b.start));
  const next = upcoming[0];
  const readyToBook = (requests.data ?? []).filter((r) => r.status === 'APPROVED');
  const waiting = (requests.data ?? []).filter((r) => r.status === 'SUBMITTED');

  return (
    <div className="page">
      <div className="hello">
        <h1>Hi {firstName}, what does your {mainType === 'CAR' ? 'car' : 'bike'} need?</h1>
        <p>Choose what you need and we will show only the times a bay and the right technician are both free.</p>
      </div>

      {readyToBook.map((r) => (
        <div key={r.id} className="notice notice-ok" style={{ marginBottom: 16 }}>
          <div className="spread">
            <span>
              <strong>{r.advisorName ?? 'An advisor'} has replied to your request {r.reference}.</strong> It takes about{' '}
              {duration(r.durationMinutes ?? 60)} at {r.dealership.name}.
            </span>
            {busy.has(r.vehicle.id) ? (
              <button className="btn btn-primary btn-sm" disabled>
                Pick a time
              </button>
            ) : (
              <Link className="btn btn-primary btn-sm" to={`/book?request=${r.id}`}>
                Pick a time
              </Link>
            )}
          </div>
          {busy.get(r.vehicle.id) && <BusyNote appointment={busy.get(r.vehicle.id)!} />}
        </div>
      ))}

      <div className="needs">
        <Link to="/book" className="need need-main">
          <div className="stack">
            <h2>Regular maintenance</h2>
            <p>Fixed-price packages with a clear checklist. Best if you just want your vehicle looked after properly.</p>
            {recommended && (
              <ul aria-label={`Included in ${recommended.name}`}>
                {recommended.checklist.slice(0, 5).map((c) => (
                  <li key={c}>{c}</li>
                ))}
                {recommended.checklist.length > 5 && <li>and more</li>}
              </ul>
            )}
          </div>
          <span className="need-cta">Book maintenance</span>
        </Link>
        <Link to="/requests/new?kind=INSPECTION" className="need">
          <h3>Check something specific</h3>
          <p>A noise, a warning light, brakes that feel wrong. Tell an advisor first; they will say what it needs before you book.</p>
          <span className="link">Ask an advisor</span>
        </Link>
        <Link to="/requests/new?kind=MODIFICATION" className="need">
          <h3>Customise my vehicle</h3>
          <p>Exhaust, lights, paint or performance parts. Get expert advice and a quote first.</p>
          <span className="link">Talk about customising</span>
        </Link>
      </div>

      <h2 className="section-title">Coming up</h2>
      {next ? (
        <div className="panel panel-tight">
          <div className="upcoming">
            <div className="datebox" aria-hidden="true">
              <div className="m">{dt(next.start).toFormat('LLL')}</div>
              <div className="d">{dt(next.start).day}</div>
              <div className="t">{time(next.start)}</div>
            </div>
            <div>
              <div className="row">
                <strong>{next.service.name}</strong>
                <StatusPill status={next.status} />
              </div>
              <p className="muted small">
                {dt(next.start).toFormat('cccc d LLLL')} at {time(next.start)} · {next.dealership.name}
              </p>
              <p className="muted small">
                {vehicleName(next.vehicle)} with {next.technician.name} · Ref <span className="sign">{next.reference}</span>
              </p>
            </div>
            <Plate text={next.vehicle.plate} />
          </div>
        </div>
      ) : (
        <p className="muted">No appointments booked. When you book, it will appear here.</p>
      )}
      {upcoming.length > 1 && (
        <p className="small" style={{ marginTop: 10 }}>
          <Link to="/appointments">See all {upcoming.length} upcoming appointments</Link>
        </p>
      )}
      {waiting.length > 0 && (
        <p className="small muted" style={{ marginTop: 10 }}>
          {waiting.length === 1 ? 'One request is' : `${waiting.length} requests are`} waiting for an advisor.{' '}
          <Link to="/requests">View requests</Link>
        </p>
      )}
    </div>
  );
}
