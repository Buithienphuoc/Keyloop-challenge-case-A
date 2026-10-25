import { useQuery } from '@tanstack/react-query';
import { DateTime } from 'luxon';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { get } from '../../api/client';
import { useDealerships, useReferenceData } from '../../api/hooks';
import type { ResourceLoad, Workload } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { DayNav } from '../../components/DayNav';
import { ErrorNote, Loading, PageHead } from '../../components/ui';
import { duration, todayIn } from '../../lib/format';

/**
 * The workshop controller's overview: how much of each technician's shift and each bay's
 * opening hours is booked on a day. Overloaded people stand out, and the day board is one
 * click away to move a job.
 */
export function WorkloadPage() {
  const { user } = useAuth();
  const dealerships = useDealerships();
  const ref = useReferenceData();
  const zone = dealerships.data?.find((d) => d.id === user?.dealershipId)?.timezone ?? 'Asia/Ho_Chi_Minh';
  const [date, setDate] = useState(todayIn(zone));
  const load = useQuery({
    queryKey: ['staff', 'workload', date],
    queryFn: () => get<Workload>(`/api/staff/workload?date=${date}`),
    refetchInterval: 30_000,
  });
  const skillName = (c: string) => ref.data?.skills.find((s) => s.code === c)?.name ?? c;
  const bayTypeName = (c: string) => ref.data?.bayTypes.find((b) => b.code === c)?.name ?? c;

  const totals = (rows: ResourceLoad[]) => {
    const available = rows.reduce((n, r) => n + r.availableMinutes, 0);
    const booked = rows.reduce((n, r) => n + r.bookedMinutes, 0);
    return available === 0 ? 0 : Math.round((booked * 100) / available);
  };

  return (
    <div className="page">
      <PageHead title="Workload" actions={<DayNav date={date} zone={zone} onChange={setDate} />}>
        {DateTime.fromISO(date, { zone }).toFormat('cccc d LLLL')}
        {load.data && (
          <>
            {' '}
            · technicians {totals(load.data.technicians)}% booked · bays {totals(load.data.bays)}% booked
          </>
        )}
      </PageHead>
      <ErrorNote error={load.error} />
      {load.isLoading && <Loading />}
      {load.data && (
        <div className="stack">
          <section className="panel stack">
            <div className="spread">
              <h2>Technicians</h2>
              <Link className="btn btn-sm" to="/staff/board">
                Move jobs on the day board
              </Link>
            </div>
            <div className="load-list">
              {load.data.technicians.map((t) => (
                <LoadRow key={t.id} r={t} sub={t.availableMinutes === 0 ? 'Not working' : t.skills.map(skillName).join(', ')} />
              ))}
            </div>
          </section>
          <section className="panel stack">
            <h2>Bays</h2>
            <div className="load-list">
              {load.data.bays.map((b) => (
                <LoadRow key={b.id} r={b} sub={bayTypeName(b.bayType)} />
              ))}
            </div>
          </section>
        </div>
      )}
    </div>
  );
}

function LoadRow({ r, sub }: { r: ResourceLoad; sub: string }) {
  const level = r.utilisation >= 100 ? ' full' : r.utilisation >= 80 ? ' high' : '';
  return (
    <div className="load-row">
      <div className="who-cell">
        <strong>{r.name}</strong>
        <span>
          {sub}
          {r.availableMinutes > 0 && ` · ${r.jobs} job${r.jobs === 1 ? '' : 's'}, ${duration(r.bookedMinutes || 0)} of ${duration(r.availableMinutes)}`}
        </span>
      </div>
      <div
        className={`load-bar${level}`}
        role="meter"
        aria-label={`${r.name} booked`}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={Math.min(100, r.utilisation)}
      >
        <div style={{ width: `${Math.min(100, r.utilisation)}%` }} />
      </div>
      <span className="pct">{r.availableMinutes === 0 ? '–' : `${r.utilisation}%`}</span>
    </div>
  );
}
