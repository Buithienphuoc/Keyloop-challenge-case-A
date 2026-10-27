import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { get, post } from '../../api/client';
import type { Appointment } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { Empty, ErrorNote, Loading, PageHead, Plate } from '../../components/ui';
import { dayShort, time, vehicleName } from '../../lib/format';

interface Technicians {
  technicians: { id: string; displayName: string; skills: string[] }[];
}

/**
 * Service jobs nobody has taken yet, soonest first. The technician's first screen: take a job
 * for yourself. Advisors see the same list and assign a technician (never themselves).
 */
export function OpenServicesPage() {
  const { user } = useAuth();
  const isTechnician = user?.role === 'TECHNICIAN';
  const open = useQuery({
    queryKey: ['services', 'open'],
    queryFn: () => get<Appointment[]>('/api/staff/open-services'),
    refetchInterval: 30_000,
  });
  const team = useQuery({
    queryKey: ['staff', 'resources'],
    queryFn: () => get<Technicians>('/api/staff/resources'),
    enabled: !isTechnician,
  });

  return (
    <div className="page page-narrow">
      <PageHead title="New services">
        {isTechnician
          ? 'Booked jobs no technician has taken yet. Take one and it moves to My jobs.'
          : 'Booked jobs no technician has taken yet. Assign a technician to each one.'}
      </PageHead>
      <ErrorNote error={open.error} />
      {open.isLoading && <Loading />}
      {open.data?.length === 0 && <Empty title="No open services">Every booked job has a technician. New bookings appear here.</Empty>}
      <div className="list">
        {open.data?.map((j) => (
          <OpenServiceCard key={j.id} j={j} technicians={isTechnician ? null : (team.data?.technicians ?? [])} />
        ))}
      </div>
    </div>
  );
}

function OpenServiceCard({ j, technicians }: { j: Appointment; technicians: Technicians['technicians'] | null }) {
  const qc = useQueryClient();
  const [technicianId, setTechnicianId] = useState(j.technician.id);
  const assign = useMutation({
    mutationFn: () => post<Appointment>(`/api/appointments/${j.id}/technician`, technicians ? { technicianId } : {}),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['services', 'open'] });
      qc.invalidateQueries({ queryKey: ['tech', 'jobs'] });
    },
  });

  return (
    <article className="job-card">
      <div className="job-card-time">
        <span className="sign">
          {time(j.start)}–{time(j.end)}
        </span>
        <span className="pill pill-NEW">New</span>
      </div>
      <div className="job-card-body">
        <div className="spread">
          <div>
            <strong>{j.service.name}</strong>
            <div className="muted small">
              {dayShort(j.start)} · {j.customer.fullName} · {vehicleName(j.vehicle)} · {j.bay.name}
            </div>
          </div>
          <Plate text={j.vehicle.plate} />
        </div>
        {j.customerNote && <div className="notice notice-info">Customer says: {j.customerNote}</div>}
        <ErrorNote error={assign.error} />
        {technicians ? (
          <form
            className="row"
            onSubmit={(e) => {
              e.preventDefault();
              assign.mutate();
            }}
          >
            <select aria-label="Technician" className="input" value={technicianId} onChange={(e) => setTechnicianId(e.target.value)}>
              {technicians.map((t) => (
                <option key={t.id} value={t.id}>
                  {t.displayName}
                </option>
              ))}
            </select>
            <button className="btn btn-primary" disabled={assign.isPending}>
              Assign technician
            </button>
          </form>
        ) : (
          <button className="btn btn-primary btn-block" disabled={assign.isPending} onClick={() => assign.mutate()}>
            Take this job
          </button>
        )}
      </div>
    </article>
  );
}
