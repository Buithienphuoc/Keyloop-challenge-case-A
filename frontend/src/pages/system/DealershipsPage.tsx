import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { get, post } from '../../api/client';
import { keys } from '../../api/hooks';
import { ErrorNote, Loading, PageHead } from '../../components/ui';
import { WEEKDAYS, weekdays } from '../../lib/format';

interface DealershipRow {
  id: string;
  code: string;
  name: string;
  address: string;
  timezone: string;
  openingTime: string;
  closingTime: string;
  openDays: number[];
  activeBays: number;
  activeTechnicians: number;
  staffAccounts: number;
  upcomingAppointments: number;
}

/** Platform view: every dealership on the system and onboarding a new one with its first admin. */
export function DealershipsPage() {
  const qc = useQueryClient();
  const list = useQuery({ queryKey: ['system', 'dealerships'], queryFn: () => get<DealershipRow[]>('/api/system/dealerships') });
  const empty = {
    code: '',
    name: '',
    address: '',
    timezone: 'Asia/Ho_Chi_Minh',
    openingTime: '08:00',
    closingTime: '17:30',
    openDays: [1, 2, 3, 4, 5, 6],
    adminName: '',
    adminEmail: '',
    adminPassword: '',
  };
  const [f, setF] = useState(empty);
  const create = useMutation({
    mutationFn: () =>
      post('/api/system/dealerships', {
        code: f.code,
        name: f.name,
        address: f.address,
        timezone: f.timezone,
        openingTime: f.openingTime,
        closingTime: f.closingTime,
        openDays: f.openDays,
        admin: { fullName: f.adminName, email: f.adminEmail, password: f.adminPassword },
      }),
    onSuccess: () => {
      setF(empty);
      qc.invalidateQueries({ queryKey: ['system', 'dealerships'] });
      qc.invalidateQueries({ queryKey: keys.dealerships });
    },
  });
  const flip = (d: number) => setF({ ...f, openDays: f.openDays.includes(d) ? f.openDays.filter((x) => x !== d) : [...f.openDays, d].sort() });

  return (
    <div className="page">
      <PageHead title="Dealerships">Every workshop on the platform, with what it can currently offer customers.</PageHead>
      <div className="split">
        <div className="table-wrap">
          {list.isLoading ? (
            <Loading />
          ) : (
            <table className="table">
              <thead>
                <tr>
                  <th>Dealership</th>
                  <th>Hours</th>
                  <th>Bays</th>
                  <th>Technicians</th>
                  <th>Upcoming</th>
                </tr>
              </thead>
              <tbody>
                {list.data?.map((d) => (
                  <tr key={d.id}>
                    <td>
                      <strong>{d.name}</strong>
                      <div className="muted small">
                        <span className="sign">{d.code}</span> · {d.address}
                      </div>
                    </td>
                    <td>
                      {d.openingTime}–{d.closingTime}
                      <div className="muted small">{weekdays(d.openDays)}</div>
                    </td>
                    <td>{d.activeBays}</td>
                    <td>{d.activeTechnicians}</td>
                    <td>{d.upcomingAppointments}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
        <form
          className="panel panel-tight stack"
          onSubmit={(e: FormEvent) => {
            e.preventDefault();
            create.mutate();
          }}
        >
          <h3>Add a dealership</h3>
          <ErrorNote error={create.error} />
          <div className="grid-2">
            <div className="field">
              <label htmlFor="dc">Code</label>
              <input id="dc" className="input" required placeholder="HN-CG" value={f.code} onChange={(e) => setF({ ...f, code: e.target.value })} />
            </div>
            <div className="field">
              <label htmlFor="tz">Time zone</label>
              <input id="tz" className="input" required value={f.timezone} onChange={(e) => setF({ ...f, timezone: e.target.value })} />
            </div>
          </div>
          <div className="field">
            <label htmlFor="dn">Name</label>
            <input id="dn" className="input" required value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} />
          </div>
          <div className="field">
            <label htmlFor="da">Address</label>
            <input id="da" className="input" required value={f.address} onChange={(e) => setF({ ...f, address: e.target.value })} />
          </div>
          <div className="grid-2">
            <div className="field">
              <label htmlFor="do">Opens</label>
              <input id="do" className="input" type="time" value={f.openingTime} onChange={(e) => setF({ ...f, openingTime: e.target.value })} />
            </div>
            <div className="field">
              <label htmlFor="dcl">Closes</label>
              <input id="dcl" className="input" type="time" value={f.closingTime} onChange={(e) => setF({ ...f, closingTime: e.target.value })} />
            </div>
          </div>
          <div className="checks" role="group" aria-label="Open days">
            {WEEKDAYS.map((d, i) => (
              <label className="check" key={d}>
                <input type="checkbox" checked={f.openDays.includes(i + 1)} onChange={() => flip(i + 1)} />
                <span>{d}</span>
              </label>
            ))}
          </div>
          <hr className="divider" style={{ margin: '8px 0' }} />
          <h3>Its first admin</h3>
          <div className="field">
            <label htmlFor="an">Name</label>
            <input id="an" className="input" required value={f.adminName} onChange={(e) => setF({ ...f, adminName: e.target.value })} />
          </div>
          <div className="field">
            <label htmlFor="ae">Email</label>
            <input id="ae" className="input" type="email" required value={f.adminEmail} onChange={(e) => setF({ ...f, adminEmail: e.target.value })} />
          </div>
          <div className="field">
            <label htmlFor="ap">Temporary password</label>
            <input id="ap" className="input" type="password" required minLength={8} value={f.adminPassword} onChange={(e) => setF({ ...f, adminPassword: e.target.value })} />
          </div>
          <button className="btn btn-primary" disabled={create.isPending}>
            Create dealership
          </button>
        </form>
      </div>
    </div>
  );
}
