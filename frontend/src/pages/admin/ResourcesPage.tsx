import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { get, patch, post, put } from '../../api/client';
import { keys, useDealerships, useReferenceData } from '../../api/hooks';
import { useAuth } from '../../auth/AuthContext';
import type { Role } from '../../api/types';
import { ErrorNote, Loading, PageHead } from '../../components/ui';
import { ROLE_LABEL, WEEKDAYS, weekdays } from '../../lib/format';

interface Bay {
  id: string;
  name: string;
  bayType: string;
  bayTypeName: string;
  isActive: boolean;
}
interface Tech {
  id: string;
  displayName: string;
  shiftStart: string;
  shiftEnd: string;
  workDays: number[];
  isActive: boolean;
  loginEmail: string | null;
  skills: string[];
}
interface Staff {
  id: string;
  fullName: string;
  email: string;
  phone: string | null;
  role: Role;
  isActive: boolean;
}

type Tab = 'bays' | 'technicians' | 'staff' | 'hours';

/** What the scheduler allocates (bays, technicians with skills and shifts) and who can sign in. */
export function ResourcesPage() {
  const [tab, setTab] = useState<Tab>('bays');
  return (
    <div className="page">
      <PageHead title="Bays & team">
        The scheduler only offers a time when a bay of the right type and a technician with the right skill, on shift, are both free. Keep this
        list accurate and availability stays honest.
      </PageHead>
      <div className="tabs" role="tablist">
        {(
          [
            ['bays', 'Service bays'],
            ['technicians', 'Technicians'],
            ['staff', 'Staff accounts'],
            ['hours', 'Opening hours'],
          ] as [Tab, string][]
        ).map(([id, label]) => (
          <button key={id} role="tab" aria-selected={tab === id} onClick={() => setTab(id)}>
            {label}
          </button>
        ))}
      </div>
      {tab === 'bays' && <Bays />}
      {tab === 'technicians' && <Technicians />}
      {tab === 'staff' && <StaffAccounts />}
      {tab === 'hours' && <OpeningHours />}
    </div>
  );
}

function Bays() {
  const qc = useQueryClient();
  const ref = useReferenceData();
  const bays = useQuery({ queryKey: ['admin', 'bays'], queryFn: () => get<Bay[]>('/api/admin/bays') });
  const [name, setName] = useState('');
  const [bayType, setBayType] = useState('MOTORBIKE_STAND');
  const refresh = () => qc.invalidateQueries({ queryKey: ['admin', 'bays'] });
  const add = useMutation({ mutationFn: () => post('/api/admin/bays', { name, bayType }), onSuccess: () => (setName(''), refresh()) });
  const toggle = useMutation({ mutationFn: (b: Bay) => patch(`/api/admin/bays/${b.id}`, { isActive: !b.isActive }), onSuccess: refresh });

  return (
    <div className="split">
      <div className="table-wrap">
        {bays.isLoading ? (
          <Loading />
        ) : (
          <table className="table">
            <thead>
              <tr>
                <th>Bay</th>
                <th>Type</th>
                <th>Status</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {bays.data?.map((b) => (
                <tr key={b.id} style={{ opacity: b.isActive ? 1 : 0.55 }}>
                  <td>
                    <strong>{b.name}</strong>
                  </td>
                  <td>{b.bayTypeName}</td>
                  <td>{b.isActive ? 'In use' : 'Out of service'}</td>
                  <td style={{ textAlign: 'right' }}>
                    <button className="btn btn-sm" onClick={() => toggle.mutate(b)}>
                      {b.isActive ? 'Take out of service' : 'Put back in service'}
                    </button>
                  </td>
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
          add.mutate();
        }}
      >
        <h3>Add a bay</h3>
        <ErrorNote error={add.error ?? toggle.error} />
        <div className="field">
          <label htmlFor="bay-name">Name</label>
          <input id="bay-name" className="input" required placeholder="Stand D" value={name} onChange={(e) => setName(e.target.value)} />
        </div>
        <div className="field">
          <label htmlFor="bay-type">Type</label>
          <select id="bay-type" className="input" value={bayType} onChange={(e) => setBayType(e.target.value)}>
            {ref.data?.bayTypes.map((t) => (
              <option key={t.code} value={t.code}>
                {t.name}
              </option>
            ))}
          </select>
        </div>
        <button className="btn btn-primary" disabled={add.isPending}>
          Add bay
        </button>
      </form>
    </div>
  );
}

function Technicians() {
  const qc = useQueryClient();
  const ref = useReferenceData();
  const techs = useQuery({ queryKey: ['admin', 'technicians'], queryFn: () => get<Tech[]>('/api/admin/technicians') });
  const empty = { displayName: '', skills: ['GENERAL_SERVICE'], shiftStart: '08:00', shiftEnd: '17:30', workDays: [1, 2, 3, 4, 5, 6], email: '', password: '' };
  const [form, setForm] = useState(empty);
  const refresh = () => qc.invalidateQueries({ queryKey: ['admin', 'technicians'] });
  const add = useMutation({
    mutationFn: () =>
      post('/api/admin/technicians', {
        displayName: form.displayName,
        skills: form.skills,
        shiftStart: form.shiftStart,
        shiftEnd: form.shiftEnd,
        workDays: form.workDays,
        login: form.email ? { email: form.email, password: form.password } : undefined,
      }),
    onSuccess: () => (setForm(empty), refresh()),
  });
  const toggle = useMutation({ mutationFn: (t: Tech) => patch(`/api/admin/technicians/${t.id}`, { isActive: !t.isActive }), onSuccess: refresh });
  const skillName = (c: string) => ref.data?.skills.find((s) => s.code === c)?.name ?? c;
  const flip = <T,>(list: T[], v: T) => (list.includes(v) ? list.filter((x) => x !== v) : [...list, v]);

  return (
    <div className="split">
      <div className="table-wrap">
        {techs.isLoading ? (
          <Loading />
        ) : (
          <table className="table">
            <thead>
              <tr>
                <th>Technician</th>
                <th>Skills</th>
                <th>Shift</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {techs.data?.map((t) => (
                <tr key={t.id} style={{ opacity: t.isActive ? 1 : 0.55 }}>
                  <td>
                    <strong>{t.displayName}</strong>
                    <div className="muted small">{t.loginEmail ?? 'No login'}</div>
                  </td>
                  <td>{t.skills.map(skillName).join(', ')}</td>
                  <td>
                    {t.shiftStart}–{t.shiftEnd}
                    <div className="muted small">{weekdays(t.workDays)}</div>
                  </td>
                  <td style={{ textAlign: 'right' }}>
                    <button className="btn btn-sm" onClick={() => toggle.mutate(t)}>
                      {t.isActive ? 'Deactivate' : 'Reactivate'}
                    </button>
                  </td>
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
          add.mutate();
        }}
      >
        <h3>Add a technician</h3>
        <ErrorNote error={add.error ?? toggle.error} />
        <div className="field">
          <label htmlFor="t-name">Name</label>
          <input id="t-name" className="input" required value={form.displayName} onChange={(e) => setForm({ ...form, displayName: e.target.value })} />
        </div>
        <fieldset className="field fieldset">
          <legend className="label" style={{ marginBottom: 6 }}>
            Skills
          </legend>
          <div className="checks">
            {ref.data?.skills.map((s) => (
              <label className="check" key={s.code}>
                <input type="checkbox" checked={form.skills.includes(s.code)} onChange={() => setForm({ ...form, skills: flip(form.skills, s.code) })} />
                <span>{s.name}</span>
              </label>
            ))}
          </div>
        </fieldset>
        <div className="grid-2">
          <div className="field">
            <label htmlFor="t-start">Shift starts</label>
            <input id="t-start" className="input" type="time" value={form.shiftStart} onChange={(e) => setForm({ ...form, shiftStart: e.target.value })} />
          </div>
          <div className="field">
            <label htmlFor="t-end">Shift ends</label>
            <input id="t-end" className="input" type="time" value={form.shiftEnd} onChange={(e) => setForm({ ...form, shiftEnd: e.target.value })} />
          </div>
        </div>
        <fieldset className="field fieldset">
          <legend className="label" style={{ marginBottom: 6 }}>
            Works on
          </legend>
          <div className="checks">
            {WEEKDAYS.map((d, i) => (
              <label className="check" key={d}>
                <input type="checkbox" checked={form.workDays.includes(i + 1)} onChange={() => setForm({ ...form, workDays: flip(form.workDays, i + 1).sort() })} />
                <span>{d}</span>
              </label>
            ))}
          </div>
        </fieldset>
        <div className="field">
          <label htmlFor="t-email">Login email (optional)</label>
          <input id="t-email" className="input" type="email" value={form.email} onChange={(e) => setForm({ ...form, email: e.target.value })} />
          <span className="hint">Give them a login to see and update their own jobs.</span>
        </div>
        {form.email && (
          <div className="field">
            <label htmlFor="t-pw">Temporary password</label>
            <input id="t-pw" className="input" type="password" required minLength={8} value={form.password} onChange={(e) => setForm({ ...form, password: e.target.value })} />
          </div>
        )}
        <button className="btn btn-primary" disabled={add.isPending || form.skills.length === 0 || form.workDays.length === 0}>
          Add technician
        </button>
      </form>
    </div>
  );
}

function StaffAccounts() {
  const qc = useQueryClient();
  const staff = useQuery({ queryKey: ['admin', 'staff'], queryFn: () => get<Staff[]>('/api/admin/staff') });
  const empty = { fullName: '', email: '', password: '', role: 'SERVICE_ADVISOR' as 'SERVICE_ADVISOR' | 'ADMIN' | 'WORKSHOP_CONTROLLER' | 'PARTS_STAFF' };
  const [form, setForm] = useState(empty);
  const add = useMutation({
    mutationFn: () => post('/api/admin/staff', form),
    onSuccess: () => (setForm(empty), qc.invalidateQueries({ queryKey: ['admin', 'staff'] })),
  });

  return (
    <div className="split">
      <div className="table-wrap">
        {staff.isLoading ? (
          <Loading />
        ) : (
          <table className="table">
            <thead>
              <tr>
                <th>Name</th>
                <th>Role</th>
                <th>Email</th>
              </tr>
            </thead>
            <tbody>
              {staff.data?.map((s) => (
                <tr key={s.id}>
                  <td>
                    <strong>{s.fullName}</strong>
                  </td>
                  <td>{ROLE_LABEL[s.role]}</td>
                  <td>{s.email}</td>
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
          add.mutate();
        }}
      >
        <h3>Add a staff account</h3>
        <ErrorNote error={add.error} />
        <div className="field">
          <label htmlFor="s-name">Name</label>
          <input id="s-name" className="input" required value={form.fullName} onChange={(e) => setForm({ ...form, fullName: e.target.value })} />
        </div>
        <div className="field">
          <label htmlFor="s-email">Email</label>
          <input id="s-email" className="input" type="email" required value={form.email} onChange={(e) => setForm({ ...form, email: e.target.value })} />
        </div>
        <div className="field">
          <label htmlFor="s-pw">Temporary password</label>
          <input id="s-pw" className="input" type="password" required minLength={8} value={form.password} onChange={(e) => setForm({ ...form, password: e.target.value })} />
        </div>
        <div className="field">
          <label htmlFor="s-role">Role</label>
          <select id="s-role" className="input" value={form.role} onChange={(e) => setForm({ ...form, role: e.target.value as typeof form.role })}>
            <option value="SERVICE_ADVISOR">Service advisor</option>
            <option value="WORKSHOP_CONTROLLER">Workshop controller</option>
            <option value="PARTS_STAFF">Parts staff</option>
            <option value="ADMIN">Dealership admin</option>
          </select>
        </div>
        <button className="btn btn-primary" disabled={add.isPending}>
          Create account
        </button>
      </form>
    </div>
  );
}

interface HoursSaved {
  upcomingOutsideHours: number;
}

/** Opening hours and booking rules. Availability follows them at once; existing bookings stay. */
function OpeningHours() {
  const { user } = useAuth();
  const qc = useQueryClient();
  const dealerships = useDealerships();
  const d = dealerships.data?.find((x) => x.id === user?.dealershipId);
  const [form, setForm] = useState<null | {
    openingTime: string;
    closingTime: string;
    openDays: number[];
    slotMinutes: number;
    minLeadMinutes: number;
    maxDaysAhead: number;
  }>(null);
  const save = useMutation({
    mutationFn: (body: NonNullable<typeof form>) => put<HoursSaved>('/api/admin/opening-hours', body),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.dealerships }),
  });
  if (!d) return <Loading />;
  const f = form ?? {
    openingTime: d.openingTime,
    closingTime: d.closingTime,
    openDays: d.openDays,
    slotMinutes: d.slotMinutes,
    minLeadMinutes: d.minLeadMinutes,
    maxDaysAhead: d.maxDaysAhead,
  };
  const set = (patchValue: Partial<typeof f>) => setForm({ ...f, ...patchValue });
  const flip = (day: number) => set({ openDays: f.openDays.includes(day) ? f.openDays.filter((x) => x !== day) : [...f.openDays, day].sort() });

  return (
    <form
      className="panel stack"
      style={{ maxWidth: 560 }}
      onSubmit={(e: FormEvent) => {
        e.preventDefault();
        save.mutate(f);
      }}
    >
      <h3>Opening hours and booking rules</h3>
      <p className="muted small">Customers only see times inside these hours. Bookings already made are kept; you will see how many fall outside.</p>
      <ErrorNote error={save.error} />
      {save.isSuccess && (
        <div className={`notice ${save.data.upcomingOutsideHours > 0 ? 'notice-info' : 'notice-ok'}`}>
          Saved.{' '}
          {save.data.upcomingOutsideHours > 0
            ? `${save.data.upcomingOutsideHours} upcoming booking(s) now fall outside the new hours: please call those customers.`
            : 'No upcoming booking is affected.'}
        </div>
      )}
      <div className="grid-2">
        <div className="field">
          <label htmlFor="h-open">Opens</label>
          <input id="h-open" className="input" type="time" value={f.openingTime} onChange={(e) => set({ openingTime: e.target.value })} />
        </div>
        <div className="field">
          <label htmlFor="h-close">Closes</label>
          <input id="h-close" className="input" type="time" value={f.closingTime} onChange={(e) => set({ closingTime: e.target.value })} />
        </div>
      </div>
      <fieldset className="field fieldset">
        <legend className="label" style={{ marginBottom: 6 }}>
          Open on
        </legend>
        <div className="checks">
          {WEEKDAYS.map((day, i) => (
            <label className="check" key={day}>
              <input type="checkbox" checked={f.openDays.includes(i + 1)} onChange={() => flip(i + 1)} />
              <span>{day}</span>
            </label>
          ))}
        </div>
      </fieldset>
      <div className="grid-2">
        <div className="field">
          <label htmlFor="h-slot">Start times every</label>
          <select id="h-slot" className="input" value={f.slotMinutes} onChange={(e) => set({ slotMinutes: Number(e.target.value) })}>
            {[15, 30, 60].map((m) => (
              <option key={m} value={m}>
                {m} minutes
              </option>
            ))}
          </select>
        </div>
        <div className="field">
          <label htmlFor="h-lead">Earliest booking (minutes from now)</label>
          <input id="h-lead" className="input" type="number" min={0} value={f.minLeadMinutes} onChange={(e) => set({ minLeadMinutes: Number(e.target.value) })} />
        </div>
      </div>
      <div className="field">
        <label htmlFor="h-ahead">Bookable up to (days ahead)</label>
        <input id="h-ahead" className="input" type="number" min={1} max={365} value={f.maxDaysAhead} onChange={(e) => set({ maxDaysAhead: Number(e.target.value) })} />
      </div>
      <button className="btn btn-primary" disabled={save.isPending || f.openDays.length === 0}>
        Save hours
      </button>
    </form>
  );
}
