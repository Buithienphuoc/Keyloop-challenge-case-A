import { Link } from 'react-router-dom';
import { useMyAppointments, usePackages, useVehicles } from '../../api/hooks';
import { useAuth } from '../../auth/AuthContext';
import { Plate, StatusPill } from '../../components/ui';
import { dt, time, vehicleName } from '../../lib/format';

export function HomePage() {
  const { user } = useAuth();
  const vehicles = useVehicles();
  const packages = usePackages();
  const appointments = useMyAppointments();

  const firstName = user?.fullName.trim().split(/\s+/).at(-1) ?? '';
  const mainType = vehicles.data?.[0]?.vehicleType ?? 'MOTORBIKE';
  const recommended = packages.data?.find((p) => p.isRecommended && p.vehicleType === mainType);
  const upcoming = (appointments.data ?? [])
    .filter((a) => (a.status === 'CONFIRMED' || a.status === 'IN_PROGRESS') && dt(a.end) > dt(new Date().toISOString()))
    .sort((a, b) => a.start.localeCompare(b.start));
  const next = upcoming[0];

  return (
    <div className="page">
      <div className="hello">
        <h1>Hi {firstName}, what does your vehicle need?</h1>
        <p>Choose what you need and we will show only the times a bay and the right technician are both free.</p>
      </div>

      <div className="needs" style={{ gridTemplateColumns: 'minmax(0, 1fr)' }}>
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
    </div>
  );
}
