import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { get, post } from '../../api/client';
import { useReferenceData } from '../../api/hooks';
import type { Consultation, ConsultationStatus } from '../../api/types';
import { Empty, ErrorNote, Loading, PageHead, Plate, RequestPill } from '../../components/ui';
import { dt, duration, money, stamp, time, vehicleName } from '../../lib/format';

const TABS: { id: ConsultationStatus | 'ALL'; label: string }[] = [
  { id: 'SUBMITTED', label: 'Waiting for advice' },
  { id: 'APPROVED', label: 'Approved, not booked' },
  { id: 'BOOKED', label: 'Booked' },
  { id: 'ALL', label: 'All' },
];

export function QueuePage() {
  const [tab, setTab] = useState<(typeof TABS)[number]['id']>('SUBMITTED');
  const all = useQuery({
    queryKey: ['staff', 'consultations'],
    queryFn: () => get<Consultation[]>('/api/staff/consultations'),
    refetchInterval: 60_000,
  });
  const shown = (all.data ?? []).filter((c) => tab === 'ALL' || c.status === tab);
  const waiting = (all.data ?? []).filter((c) => c.status === 'SUBMITTED').length;

  return (
    <div className="page page-narrow">
      <PageHead title="Advice requests">
        Customers describe a problem or a customisation. Turn each one into a job the workshop can schedule: how long, which skill, which bay.
      </PageHead>
      <div className="tabs" role="tablist">
        {TABS.map((t) => (
          <button key={t.id} role="tab" aria-selected={tab === t.id} onClick={() => setTab(t.id)}>
            {t.label}
            {t.id === 'SUBMITTED' && waiting > 0 && <span className="count">{waiting}</span>}
          </button>
        ))}
      </div>
      <ErrorNote error={all.error} />
      {all.isLoading && <Loading />}
      {all.data && shown.length === 0 && <Empty title="Nothing here">{tab === 'SUBMITTED' ? 'No requests are waiting. Nice work.' : 'No requests in this list.'}</Empty>}
      <div className="list">
        {shown.map((c) => (
          <RequestCard key={c.id} c={c} />
        ))}
      </div>
    </div>
  );
}

function RequestCard({ c }: { c: Consultation }) {
  const ref = useReferenceData();
  const areaName = (code: string) => ref.data?.inspectionAreas.find((a) => a.code === code)?.name ?? code;

  return (
    <div className="panel panel-tight stack">
      <div className="spread">
        <div className="row">
          <strong>{c.kind === 'MODIFICATION' ? 'Customisation' : 'Inspection'}</strong>
          <RequestPill status={c.status} />
          <span className="muted small sign">{c.reference}</span>
        </div>
        <Plate text={c.vehicle.plate} />
      </div>
      <p className="small muted">
        {c.customer.fullName}
        {c.customer.phone ? ` · ${c.customer.phone}` : ''} · {vehicleName(c.vehicle)} ({c.vehicle.vehicleType === 'CAR' ? 'car' : 'motorbike'}) · sent{' '}
        {stamp(c.createdAt)}
      </p>
      <p>
        <span className="muted small">{c.areas.map(areaName).join(', ')}:</span> {c.description}
      </p>
      {c.status === 'SUBMITTED' ? (
        <ReviewForm c={c} />
      ) : (
        c.advisorNote && (
          <div className="notice notice-info">
            <strong>{c.advisorName}:</strong> {c.advisorNote}
            {c.durationMinutes && (
              <span className="ref" style={{ opacity: 1 }}>
                {duration(c.durationMinutes)} · {c.requiredSkill} · {c.bayType} ·{' '}
                {money(c.estimatedPrice !== null ? { amount: c.estimatedPrice, currency: 'VND' } : null)}
                {c.appointment && ` · booked ${dt(c.appointment.start).toFormat('ccc d LLL')} ${time(c.appointment.start)}`}
              </span>
            )}
          </div>
        )
      )}
    </div>
  );
}

const DURATIONS = [30, 45, 60, 90, 120, 150, 180, 240, 300, 360, 480];

function ReviewForm({ c }: { c: Consultation }) {
  const qc = useQueryClient();
  const ref = useReferenceData();
  // Bays that can take this vehicle; the dedicated one (stand or lift) first, the shared diagnostic bay last.
  const bayTypes = (ref.data?.bayTypes ?? [])
    .filter((b) => b.vehicleTypes.includes(c.vehicle.vehicleType))
    .sort((a, b) => a.vehicleTypes.length - b.vehicleTypes.length);
  const [form, setForm] = useState({
    durationMinutes: 60,
    requiredSkill: c.kind === 'MODIFICATION' ? 'CUSTOMISATION' : c.areas.includes('ENGINE') ? 'ENGINE' : 'GENERAL_SERVICE',
    bayType: '',
    estimatedPrice: '',
    note: '',
  });
  const bayType = form.bayType || bayTypes[0]?.code || '';

  const review = useMutation({
    mutationFn: (decision: 'APPROVE' | 'REJECT') =>
      post<Consultation>(
        `/api/consultations/${c.id}/review`,
        decision === 'APPROVE'
          ? {
              decision,
              durationMinutes: form.durationMinutes,
              requiredSkill: form.requiredSkill,
              bayType,
              estimatedPrice: form.estimatedPrice ? Number(form.estimatedPrice) : undefined,
              note: form.note || undefined,
            }
          : { decision, note: form.note },
      ),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['staff', 'consultations'] }),
  });

  return (
    <div className="stack" style={{ borderTop: '1px solid var(--line)', paddingTop: 16 }}>
      <h3>Your advice</h3>
      <ErrorNote error={review.error} />
      <div className="grid-2">
        <div className="field">
          <label htmlFor={`d-${c.id}`}>Time needed</label>
          <select id={`d-${c.id}`} className="input" value={form.durationMinutes} onChange={(e) => setForm({ ...form, durationMinutes: Number(e.target.value) })}>
            {DURATIONS.map((d) => (
              <option key={d} value={d}>
                {duration(d)}
              </option>
            ))}
          </select>
        </div>
        <div className="field">
          <label htmlFor={`s-${c.id}`}>Skill needed</label>
          <select id={`s-${c.id}`} className="input" value={form.requiredSkill} onChange={(e) => setForm({ ...form, requiredSkill: e.target.value })}>
            {ref.data?.skills.map((s) => (
              <option key={s.code} value={s.code}>
                {s.name}
              </option>
            ))}
          </select>
        </div>
        <div className="field">
          <label htmlFor={`b-${c.id}`}>Bay</label>
          <select id={`b-${c.id}`} className="input" value={bayType} onChange={(e) => setForm({ ...form, bayType: e.target.value })}>
            {bayTypes.map((b) => (
              <option key={b.code} value={b.code}>
                {b.name}
              </option>
            ))}
          </select>
        </div>
        <div className="field">
          <label htmlFor={`p-${c.id}`}>Estimate in VND (optional)</label>
          <input
            id={`p-${c.id}`}
            className="input"
            inputMode="numeric"
            pattern="[0-9]*"
            value={form.estimatedPrice}
            onChange={(e) => setForm({ ...form, estimatedPrice: e.target.value.replace(/\D/g, '') })}
          />
        </div>
      </div>
      <div className="field">
        <label htmlFor={`n-${c.id}`}>Message to the customer</label>
        <textarea
          id={`n-${c.id}`}
          className="input"
          placeholder="What you think it is and what you will do. Required if you decline."
          value={form.note}
          onChange={(e) => setForm({ ...form, note: e.target.value })}
        />
      </div>
      <div className="row">
        <button className="btn btn-primary" disabled={review.isPending} onClick={() => review.mutate('APPROVE')}>
          Approve and let them book
        </button>
        <button className="btn btn-danger" disabled={review.isPending || form.note.trim().length < 5} onClick={() => review.mutate('REJECT')}>
          Decline
        </button>
      </div>
    </div>
  );
}
