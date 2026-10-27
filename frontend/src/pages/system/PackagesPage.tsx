import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { get, patch, post } from '../../api/client';
import { keys, useReferenceData } from '../../api/hooks';
import type { PackageRecord, VehicleType } from '../../api/types';
import { ErrorNote, Loading, PageHead } from '../../components/ui';
import { duration, vnd0 } from '../../lib/format';

/**
 * The service package catalogue shared by every dealership. A new package can be booked at
 * once wherever there is a matching bay and a technician with the skill; retiring one hides
 * it from new bookings and keeps existing appointments as they are.
 */
export function PackagesPage() {
  const qc = useQueryClient();
  const ref = useReferenceData();
  const packages = useQuery({ queryKey: ['system', 'packages'], queryFn: () => get<PackageRecord[]>('/api/system/packages') });
  const refresh = () => {
    qc.invalidateQueries({ queryKey: ['system', 'packages'] });
    qc.invalidateQueries({ queryKey: keys.packages });
  };
  const empty = {
    code: '',
    name: '',
    description: '',
    vehicleType: 'MOTORBIKE' as VehicleType,
    durationMinutes: 60,
    requiredSkill: 'GENERAL_SERVICE',
    bayType: 'MOTORBIKE_STAND',
    priceAmount: 0,
    checklist: '',
    isRecommended: false,
  };
  const [form, setForm] = useState(empty);
  const add = useMutation({
    mutationFn: () => post('/api/system/packages', { ...form, checklist: form.checklist.split('\n').map((l) => l.trim()).filter(Boolean) }),
    onSuccess: () => (setForm(empty), refresh()),
  });
  const update = useMutation({
    mutationFn: ({ id, changes }: { id: string; changes: Partial<PackageRecord> }) => patch(`/api/system/packages/${id}`, changes),
    onSuccess: refresh,
  });
  const skillName = (c: string) => ref.data?.skills.find((s) => s.code === c)?.name ?? c;
  const bayTypes = (ref.data?.bayTypes ?? []).filter((b) => b.vehicleTypes.includes(form.vehicleType));

  return (
    <div className="page">
      <PageHead title="Service packages">
        The ready-made services customers pick from. Every dealership offers a package when it has a bay of the right type and a technician with
        the skill.
      </PageHead>
      <div className="split">
        <div className="table-wrap">
          <ErrorNote error={update.error} />
          {packages.isLoading ? (
            <Loading />
          ) : (
            <table className="table">
              <thead>
                <tr>
                  <th>Package</th>
                  <th>Needs</th>
                  <th>Price</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {packages.data?.map((p) => (
                  <tr key={p.id} style={{ opacity: p.isActive ? 1 : 0.55 }}>
                    <td>
                      <strong>{p.name}</strong>
                      <div className="muted small">
                        {p.code} · {p.vehicleType === 'CAR' ? 'Car' : 'Motorbike'} · {duration(p.durationMinutes)}
                      </div>
                    </td>
                    <td className="small">{skillName(p.requiredSkill)}</td>
                    <td>{vnd0(p.priceAmount)}</td>
                    <td style={{ textAlign: 'right', whiteSpace: 'nowrap' }}>
                      <button
                        className="btn btn-sm"
                        onClick={() => {
                          const value = window.prompt(`New price for ${p.name} (VND)`, String(p.priceAmount));
                          if (value && Number.isFinite(Number(value))) update.mutate({ id: p.id, changes: { priceAmount: Number(value) } });
                        }}
                      >
                        Price
                      </button>{' '}
                      <button className="btn btn-sm" disabled={update.isPending} onClick={() => update.mutate({ id: p.id, changes: { isActive: !p.isActive } })}>
                        {p.isActive ? 'Retire' : 'Bring back'}
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
          <h3>Add a package</h3>
          <ErrorNote error={add.error} />
          <div className="grid-2">
            <div className="field">
              <label htmlFor="pk-code">Code</label>
              <input id="pk-code" className="input" required placeholder="MB-WASH" value={form.code} onChange={(e) => setForm({ ...form, code: e.target.value })} />
            </div>
            <div className="field">
              <label htmlFor="pk-vt">For</label>
              <select id="pk-vt" className="input" value={form.vehicleType}
                onChange={(e) => {
                  const vehicleType = e.target.value as VehicleType;
                  setForm({ ...form, vehicleType, bayType: vehicleType === 'CAR' ? 'CAR_LIFT' : 'MOTORBIKE_STAND' });
                }}>
                <option value="MOTORBIKE">Motorbike</option>
                <option value="CAR">Car</option>
              </select>
            </div>
          </div>
          <div className="field">
            <label htmlFor="pk-name">Name</label>
            <input id="pk-name" className="input" required value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
          </div>
          <div className="field">
            <label htmlFor="pk-desc">Description</label>
            <input id="pk-desc" className="input" required value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} />
          </div>
          <div className="grid-2">
            <div className="field">
              <label htmlFor="pk-dur">Duration (minutes)</label>
              <input id="pk-dur" className="input" type="number" min={15} step={15} value={form.durationMinutes}
                onChange={(e) => setForm({ ...form, durationMinutes: Number(e.target.value) })} />
            </div>
            <div className="field">
              <label htmlFor="pk-price">Price (VND)</label>
              <input id="pk-price" className="input" type="number" min={0} value={form.priceAmount}
                onChange={(e) => setForm({ ...form, priceAmount: Number(e.target.value) })} />
            </div>
          </div>
          <div className="grid-2">
            <div className="field">
              <label htmlFor="pk-skill">Skill needed</label>
              <select id="pk-skill" className="input" value={form.requiredSkill} onChange={(e) => setForm({ ...form, requiredSkill: e.target.value })}>
                {ref.data?.skills.map((s) => (
                  <option key={s.code} value={s.code}>
                    {s.name}
                  </option>
                ))}
              </select>
            </div>
            <div className="field">
              <label htmlFor="pk-bay">Bay</label>
              <select id="pk-bay" className="input" value={form.bayType} onChange={(e) => setForm({ ...form, bayType: e.target.value })}>
                {bayTypes.map((b) => (
                  <option key={b.code} value={b.code}>
                    {b.name}
                  </option>
                ))}
              </select>
            </div>
          </div>
          <div className="field">
            <label htmlFor="pk-check">Checklist, one item per line</label>
            <textarea id="pk-check" className="input" required value={form.checklist} onChange={(e) => setForm({ ...form, checklist: e.target.value })} />
          </div>
          <label className="check">
            <input type="checkbox" checked={form.isRecommended} onChange={(e) => setForm({ ...form, isRecommended: e.target.checked })} />
            <span>Show as recommended</span>
          </label>
          <button className="btn btn-primary" disabled={add.isPending}>
            Add package
          </button>
        </form>
      </div>
    </div>
  );
}
