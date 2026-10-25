import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router-dom';
import { post } from '../../api/client';
import { keys, useMyRequests, useReferenceData } from '../../api/hooks';
import type { Consultation } from '../../api/types';
import { Empty, ErrorNote, Loading, PageHead, Plate, RequestPill } from '../../components/ui';
import { dt, duration, money, time, vehicleName } from '../../lib/format';

export function RequestsPage() {
  const [params] = useSearchParams();
  const requests = useMyRequests();
  const ref = useReferenceData();
  const areaName = (code: string) => ref.data?.inspectionAreas.find((a) => a.code === code)?.name ?? code;

  return (
    <div className="page page-narrow">
      <PageHead
        title="Advice requests"
        actions={
          <Link className="btn" to="/requests/new?kind=INSPECTION">
            New request
          </Link>
        }
      >
        Ask about a specific problem or a customisation. An advisor replies with what it needs; then you pick a time.
      </PageHead>
      {params.get('sent') && (
        <div className="notice notice-ok" style={{ marginBottom: 16 }}>
          Sent. An advisor usually replies within a working day, and the reply will appear here.
        </div>
      )}
      <ErrorNote error={requests.error} />
      {requests.isLoading && <Loading />}
      {requests.data?.length === 0 && (
        <Empty title="No requests yet" action={<Link className="btn btn-primary" to="/requests/new?kind=INSPECTION">Ask an advisor</Link>}>
          Not sure what your vehicle needs? Describe it and an advisor will tell you.
        </Empty>
      )}
      <div className="list">
        {requests.data?.map((r) => (
          <RequestItem key={r.id} r={r} areaName={areaName} />
        ))}
      </div>
    </div>
  );
}

function RequestItem({ r, areaName }: { r: Consultation; areaName: (c: string) => string }) {
  const qc = useQueryClient();
  const withdraw = useMutation({
    mutationFn: () => post(`/api/consultations/${r.id}/cancel`),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.myRequests }),
  });

  return (
    <div className="panel panel-tight stack">
      <div className="spread">
        <div className="row">
          <strong>{r.kind === 'MODIFICATION' ? 'Customisation' : 'Inspection'}</strong>
          <RequestPill status={r.status} />
          <span className="muted small sign">{r.reference}</span>
        </div>
        <Plate text={r.vehicle.plate} />
      </div>
      <p className="small muted">
        {vehicleName(r.vehicle)} · {r.dealership.name} · sent {dt(r.createdAt).toFormat('d LLL, HH:mm')}
      </p>
      <p>
        <span className="muted small">{r.areas.map(areaName).join(', ')}:</span> {r.description}
      </p>

      {(r.status === 'APPROVED' || r.status === 'BOOKED' || r.status === 'REJECTED') && r.advisorNote && (
        <div className={`notice ${r.status === 'REJECTED' ? 'notice-error' : 'notice-info'}`}>
          <strong>{r.advisorName ?? 'Advisor'}:</strong> {r.advisorNote}
          {r.status !== 'REJECTED' && r.durationMinutes && (
            <span className="ref" style={{ opacity: 1 }}>
              About {duration(r.durationMinutes)} · {money(r.estimatedPrice !== null ? { amount: r.estimatedPrice, currency: 'VND' } : null)}
            </span>
          )}
        </div>
      )}
      <ErrorNote error={withdraw.error} />
      <div className="row">
        {r.status === 'APPROVED' && (
          <Link className="btn btn-primary" to={`/book?request=${r.id}`}>
            Pick a time
          </Link>
        )}
        {r.status === 'BOOKED' && r.appointment && (
          <Link className="btn" to="/appointments">
            Booked for {dt(r.appointment.start).toFormat('ccc d LLL')} at {time(r.appointment.start)}
          </Link>
        )}
        {(r.status === 'SUBMITTED' || r.status === 'APPROVED') && (
          <button className="btn btn-ghost" disabled={withdraw.isPending} onClick={() => withdraw.mutate()}>
            Withdraw request
          </button>
        )}
      </div>
    </div>
  );
}
