import { useQuery } from '@tanstack/react-query';
import { useId, useState } from 'react';
import { get } from '../../api/client';
import { useDealerships } from '../../api/hooks';
import type { Appointment, AppointmentStatus, Vehicle } from '../../api/types';
import { Empty, ErrorNote, Loading, PageHead, Plate, StatusPill } from '../../components/ui';
import { dt, money, stamp, time, vehicleName, weekdays } from '../../lib/format';

type Tab = 'appointments' | 'workshop' | 'vehicles';

const TABS: { id: Tab; label: string }[] = [
  { id: 'appointments', label: 'Appointments' },
  { id: 'workshop', label: 'Bays & technicians' },
  { id: 'vehicles', label: 'Vehicles' },
];

/**
 * The system manager reads the business data of every dealership: appointments (with their
 * customer, vehicle, technician and bay), the bays and technicians the scheduler allocates, and
 * the customers' vehicles. Read only.
 */
export function AllDataPage() {
  const [tab, setTab] = useState<Tab>('appointments');
  return (
    <div className="page">
      <PageHead title="All data">Everything the scheduler works with, across every dealership. Nothing can be changed here.</PageHead>
      <div className="tabs" role="tablist">
        {TABS.map((t) => (
          <button key={t.id} role="tab" aria-selected={tab === t.id} onClick={() => setTab(t.id)}>
            {t.label}
          </button>
        ))}
      </div>
      {tab === 'appointments' && <Appointments />}
      {tab === 'workshop' && <Workshop />}
      {tab === 'vehicles' && <Vehicles />}
    </div>
  );
}

/** '' = every dealership (only offered where the list allows it), otherwise one dealership's id. */
function DealershipSelect({ value, onChange, allowAll }: { value: string; onChange: (id: string) => void; allowAll: boolean }) {
  const dealerships = useDealerships();
  const id = useId();
  return (
    <div className="row small" style={{ gap: 8, marginBottom: 16 }}>
      <label htmlFor={id} className="muted">
        Dealership
      </label>
      <select id={id} className="input" style={{ width: 'auto', minHeight: 36, padding: '4px 10px' }} value={value} onChange={(e) => onChange(e.target.value)}>
        {allowAll && <option value="">All dealerships</option>}
        {(dealerships.data ?? []).map((d) => (
          <option key={d.id} value={d.id}>
            {d.name}
          </option>
        ))}
      </select>
    </div>
  );
}

const STATUSES: { id: string; label: string; statuses: AppointmentStatus[] }[] = [
  { id: 'ALL', label: 'All', statuses: [] },
  { id: 'BOOKED', label: 'Booked', statuses: ['CONFIRMED'] },
  { id: 'WORK', label: 'In the workshop', statuses: ['IN_PROGRESS', 'AWAITING_PAYMENT'] },
  { id: 'DONE', label: 'Completed', statuses: ['COMPLETED'] },
  { id: 'CLOSED', label: 'Cancelled & missed', statuses: ['CANCELLED', 'NO_SHOW', 'EXPIRED'] },
];

function Appointments() {
  const [dealershipId, setDealershipId] = useState('');
  const [status, setStatus] = useState('ALL');
  const list = useQuery({
    queryKey: ['system', 'appointments', dealershipId],
    queryFn: () => get<Appointment[]>(`/api/appointments?dealershipId=${dealershipId || 'all'}`),
    refetchInterval: 30_000,
  });
  const current = STATUSES.find((s) => s.id === status)!;
  const shown = (list.data ?? []).filter((a) => current.statuses.length === 0 || current.statuses.includes(a.status));

  return (
    <>
      <div className="spread">
        <DealershipSelect value={dealershipId} onChange={setDealershipId} allowAll />
        <div className="row small" style={{ gap: 6, marginBottom: 16 }}>
          {STATUSES.map((s) => (
            <button key={s.id} className={`btn btn-sm${status === s.id ? ' btn-primary' : ''}`} onClick={() => setStatus(s.id)}>
              {s.label}
            </button>
          ))}
        </div>
      </div>
      <ErrorNote error={list.error} />
      {list.isLoading && <Loading />}
      {list.data && shown.length === 0 && <Empty title="Nothing here">No appointments in this list.</Empty>}
      <div className="list">
        {shown.map((a) => (
          <div className="item" key={a.id} data-appointment={a.reference}>
            <Plate text={a.vehicle.plate} />
            <div>
              <div className="row">
                <strong>{a.service.name}</strong>
                <StatusPill status={a.status} />
                <span className="muted small sign">{a.reference}</span>
              </div>
              <div className="meta">
                {a.dealership.name} · {dt(a.start).toFormat('ccc d LLL yyyy')}, {time(a.start)}–{time(a.end)}
              </div>
              <div className="meta">
                Customer {a.customer.fullName} · {vehicleName(a.vehicle)} · Technician {a.technician.name} · Bay {a.bay.name}
              </div>
              <div className="meta">
                <strong>{money(a.price)}</strong> · booked {stamp(a.createdAt, a.dealership.timezone)}
              </div>
            </div>
          </div>
        ))}
      </div>
    </>
  );
}

interface BayRow {
  id: string;
  name: string;
  bayType: string;
  bayTypeName: string;
  isActive: boolean;
}

interface TechnicianRow {
  id: string;
  displayName: string;
  shiftStart: string;
  shiftEnd: string;
  workDays: number[];
  isActive: boolean;
  loginEmail: string | null;
  skills: string[];
}

function Workshop() {
  const dealerships = useDealerships();
  const [chosen, setChosen] = useState('');
  const dealershipId = chosen || dealerships.data?.[0]?.id || '';
  const bays = useQuery({
    queryKey: ['system', 'bays', dealershipId],
    queryFn: () => get<BayRow[]>(`/api/admin/bays?dealershipId=${dealershipId}`),
    enabled: Boolean(dealershipId),
  });
  const technicians = useQuery({
    queryKey: ['system', 'technicians', dealershipId],
    queryFn: () => get<TechnicianRow[]>(`/api/admin/technicians?dealershipId=${dealershipId}`),
    enabled: Boolean(dealershipId),
  });

  return (
    <>
      <DealershipSelect value={dealershipId} onChange={setChosen} allowAll={false} />
      <ErrorNote error={bays.error ?? technicians.error} />
      {(bays.isLoading || technicians.isLoading) && <Loading />}
      <div className="grid-2">
        <section>
          <h2 className="section-title">Service bays</h2>
          <div className="list" aria-label="Service bays">
            {bays.data?.map((b) => (
              <div className="item" key={b.id}>
                <div>
                  <div className="row">
                    <strong>{b.name}</strong>
                    {!b.isActive && <span className="pill pill-CANCELLED">Out of service</span>}
                  </div>
                  <div className="meta">{b.bayTypeName}</div>
                </div>
              </div>
            ))}
          </div>
        </section>
        <section>
          <h2 className="section-title">Technicians</h2>
          <div className="list" aria-label="Technicians">
            {technicians.data?.map((t) => (
              <div className="item" key={t.id}>
                <div>
                  <div className="row">
                    <strong>{t.displayName}</strong>
                    {!t.isActive && <span className="pill pill-CANCELLED">Inactive</span>}
                  </div>
                  <div className="meta">
                    {t.shiftStart.slice(0, 5)}–{t.shiftEnd.slice(0, 5)} · {weekdays(t.workDays)}
                    {t.loginEmail ? ` · ${t.loginEmail}` : ''}
                  </div>
                  <div className="meta">{t.skills.join(', ')}</div>
                </div>
              </div>
            ))}
          </div>
        </section>
      </div>
    </>
  );
}

type OwnedVehicle = Vehicle & { ownerName: string; ownerEmail: string };

function Vehicles() {
  const vehicles = useQuery({ queryKey: ['system', 'vehicles'], queryFn: () => get<OwnedVehicle[]>('/api/system/vehicles') });
  return (
    <>
      <ErrorNote error={vehicles.error} />
      {vehicles.isLoading && <Loading />}
      {vehicles.data?.length === 0 && <Empty title="No vehicles yet" />}
      <div className="list" aria-label="Vehicles">
        {vehicles.data?.map((v) => (
          <div className="item" key={v.id}>
            <Plate text={v.plate} />
            <div>
              <strong>{vehicleName(v)}</strong>
              <div className="meta">
                {v.vehicleType === 'CAR' ? 'Car' : 'Motorbike'}
                {v.year ? ` · ${v.year}` : ''}
                {v.mileageKm != null ? ` · ${v.mileageKm.toLocaleString()} km` : ''}
                {v.vin ? ` · VIN ${v.vin}` : ''}
              </div>
              <div className="meta">
                Owner {v.ownerName} · {v.ownerEmail}
              </div>
            </div>
          </div>
        ))}
      </div>
    </>
  );
}
