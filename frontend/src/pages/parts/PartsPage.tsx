import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { useSearchParams } from 'react-router-dom';
import { get, post } from '../../api/client';
import type { Appointment, Part, PartOrder, PartOrderStatus, Role } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { Empty, ErrorNote, Loading, PageHead } from '../../components/ui';
import { PART_ORDER_LABEL, dayShort, stamp, time, todayIn, vnd0 } from '../../lib/format';

type Tab = 'orders' | 'stock';
const SHELF: Role[] = ['PARTS_STAFF', 'ADMIN'];

/** What the parts desk can do next with an order in each status. */
const NEXT: Record<PartOrderStatus, { to: PartOrderStatus; label: string; primary?: boolean }[]> = {
  REQUESTED: [
    { to: 'ISSUED', label: 'Issue from stock', primary: true },
    { to: 'ORDERED', label: 'Order from supplier' },
    { to: 'CANCELLED', label: 'Cancel' },
  ],
  ORDERED: [
    { to: 'RECEIVED', label: 'Delivery received', primary: true },
    { to: 'CANCELLED', label: 'Cancel' },
  ],
  RECEIVED: [{ to: 'ISSUED', label: 'Issue to technician', primary: true }],
  ISSUED: [],
  CANCELLED: [],
};

/**
 * Replacement parts for one dealership. Advisors and technicians raise orders (usually for a
 * booked job); parts staff issue from the shelf or order from the supplier. Stock follows.
 */
export function PartsPage() {
  const { user } = useAuth();
  const [params] = useSearchParams();
  const isShelf = Boolean(user && SHELF.includes(user.role));
  const [tab, setTab] = useState<Tab>('orders');

  return (
    <div className="page">
      <PageHead title="Parts">
        {isShelf
          ? 'Orders raised by advisors and technicians, and what is on the shelf. Issuing takes parts off the shelf; a delivery puts them on.'
          : 'Ask the parts desk for what a job needs. You can see when it is ready to collect.'}
      </PageHead>
      <div className="tabs" role="tablist">
        {(
          [
            ['orders', 'Orders'],
            ['stock', 'Stock'],
          ] as [Tab, string][]
        ).map(([id, label]) => (
          <button key={id} role="tab" aria-selected={tab === id} onClick={() => setTab(id)}>
            {label}
          </button>
        ))}
      </div>
      {tab === 'orders' ? <Orders isShelf={isShelf} preselect={params.get('appointment')} /> : <Stock isShelf={isShelf} />}
    </div>
  );
}

const useParts = () => useQuery({ queryKey: ['parts'], queryFn: () => get<Part[]>('/api/parts') });

function Orders({ isShelf, preselect }: { isShelf: boolean; preselect: string | null }) {
  const [status, setStatus] = useState<PartOrderStatus | ''>(isShelf ? 'REQUESTED' : '');
  const orders = useQuery({
    queryKey: ['part-orders', status],
    queryFn: () => get<PartOrder[]>(`/api/part-orders${status ? `?status=${status}` : ''}`),
    refetchInterval: 30_000,
  });

  return (
    <div className="split">
      <div className="stack">
        <div className="row">
          <label htmlFor="po-status" className="muted small">
            Show
          </label>
          <select id="po-status" className="input" style={{ width: 'auto' }} value={status} onChange={(e) => setStatus(e.target.value as PartOrderStatus | '')}>
            <option value="">All orders</option>
            {(Object.keys(PART_ORDER_LABEL) as PartOrderStatus[]).map((s) => (
              <option key={s} value={s}>
                {PART_ORDER_LABEL[s]}
              </option>
            ))}
          </select>
        </div>
        <ErrorNote error={orders.error} />
        {orders.isLoading && <Loading />}
        {orders.data?.length === 0 && <Empty title="No orders here">New orders appear here as soon as they are raised.</Empty>}
        <div className="list">
          {orders.data?.map((o) => (
            <OrderCard key={o.id} o={o} isShelf={isShelf} />
          ))}
        </div>
      </div>
      <NewOrder preselect={preselect} />
    </div>
  );
}

function OrderCard({ o, isShelf }: { o: PartOrder; isShelf: boolean }) {
  const qc = useQueryClient();
  const move = useMutation({
    mutationFn: (to: PartOrderStatus) => post<PartOrder>(`/api/part-orders/${o.id}/status`, { status: to }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['part-orders'] });
      qc.invalidateQueries({ queryKey: ['parts'] });
    },
  });
  return (
    <article className="panel panel-tight stack">
      <div className="spread">
        <div>
          <span className="sign" style={{ fontSize: '1.125rem' }}>
            {o.reference}
          </span>{' '}
          <span className={`pill pill-${o.status}`}>{PART_ORDER_LABEL[o.status]}</span>
        </div>
        <strong>{vnd0(o.total)}</strong>
      </div>
      <div className="muted small">
        {o.requestedBy} · {stamp(o.createdAt)}
        {o.appointment && (
          <>
            {' '}
            · for {o.appointment.reference} ({o.appointment.vehiclePlate}, {dayShort(o.appointment.start)} {time(o.appointment.start)})
          </>
        )}
      </div>
      <table className="order-lines">
        <tbody>
          {o.lines.map((l) => (
            <tr key={l.partId}>
              <td>
                {l.quantity} × {l.name} <span className="muted small">{l.sku}</span>
              </td>
              <td className={o.status === 'REQUESTED' && l.inStock < l.quantity ? 'low-stock' : 'muted small'}>{l.inStock} on shelf</td>
            </tr>
          ))}
        </tbody>
      </table>
      {o.note && <div className="notice notice-info">{o.note}</div>}
      <ErrorNote error={move.error} />
      {isShelf && NEXT[o.status].length > 0 && (
        <div className="row">
          {NEXT[o.status].map((n) => (
            <button
              key={n.to}
              className={`btn btn-sm${n.primary ? ' btn-primary' : n.to === 'CANCELLED' ? ' btn-danger' : ''}`}
              disabled={move.isPending}
              onClick={() => move.mutate(n.to)}
            >
              {n.label}
            </button>
          ))}
        </div>
      )}
    </article>
  );
}

/** Jobs the signed-in person can attach an order to (none for the parts desk itself). */
function useJobs(role: Role | undefined) {
  const date = todayIn();
  const url =
    role === 'TECHNICIAN' ? `/api/technician/jobs?date=${date}` : role && role !== 'PARTS_STAFF' ? `/api/staff/appointments?date=${date}` : null;
  return useQuery({ queryKey: ['jobs-for-parts', url], queryFn: () => get<Appointment[]>(url!), enabled: Boolean(url) });
}

function NewOrder({ preselect }: { preselect: string | null }) {
  const { user } = useAuth();
  const qc = useQueryClient();
  const parts = useParts();
  const jobs = useJobs(user?.role);
  const [appointmentId, setAppointmentId] = useState(preselect ?? '');
  const [note, setNote] = useState('');
  const [lines, setLines] = useState<{ partId: string; quantity: number }[]>([{ partId: '', quantity: 1 }]);
  const create = useMutation({
    mutationFn: () =>
      post<PartOrder>('/api/part-orders', {
        appointmentId: appointmentId || undefined,
        note: note || undefined,
        lines: lines.filter((l) => l.partId),
      }),
    onSuccess: () => {
      setLines([{ partId: '', quantity: 1 }]);
      setNote('');
      qc.invalidateQueries({ queryKey: ['part-orders'] });
    },
  });
  const setLine = (i: number, patch: Partial<{ partId: string; quantity: number }>) =>
    setLines(lines.map((l, j) => (j === i ? { ...l, ...patch } : l)));
  const active = (parts.data ?? []).filter((p) => p.isActive);
  const openJobs = (jobs.data ?? []).filter((a) => a.status === 'CONFIRMED' || a.status === 'IN_PROGRESS');

  return (
    <form
      className="panel panel-tight stack"
      onSubmit={(e: FormEvent) => {
        e.preventDefault();
        create.mutate();
      }}
    >
      <h3>Request parts</h3>
      <ErrorNote error={create.error} />
      {create.isSuccess && <div className="notice notice-ok">Order {create.data.reference} sent to the parts desk.</div>}
      {user?.role !== 'PARTS_STAFF' && (
        <div className="field">
          <label htmlFor="po-job">For which job? (optional)</label>
          <select id="po-job" className="input" value={appointmentId} onChange={(e) => setAppointmentId(e.target.value)}>
            <option value="">Not for a booked job</option>
            {preselect && !openJobs.some((a) => a.id === preselect) && <option value={preselect}>The job you came from</option>}
            {openJobs.map((a) => (
              <option key={a.id} value={a.id}>
                {time(a.start)} {a.vehicle.plate} – {a.service.name}
              </option>
            ))}
          </select>
        </div>
      )}
      {lines.map((l, i) => (
        <div className="grid-2" key={i} style={{ gridTemplateColumns: '1fr 90px' }}>
          <div className="field">
            <label htmlFor={`po-part-${i}`}>Part</label>
            <select id={`po-part-${i}`} className="input" value={l.partId} onChange={(e) => setLine(i, { partId: e.target.value })}>
              <option value="">Choose a part</option>
              {active.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.name} ({p.stockQty} on shelf)
                </option>
              ))}
            </select>
          </div>
          <div className="field">
            <label htmlFor={`po-qty-${i}`}>Qty</label>
            <input id={`po-qty-${i}`} className="input" type="number" min={1} max={100} value={l.quantity}
              onChange={(e) => setLine(i, { quantity: Math.max(1, Number(e.target.value) || 1) })} />
          </div>
        </div>
      ))}
      <button type="button" className="btn btn-ghost btn-sm" onClick={() => setLines([...lines, { partId: '', quantity: 1 }])}>
        Add another part
      </button>
      <div className="field">
        <label htmlFor="po-note">Note for the parts desk (optional)</label>
        <textarea id="po-note" className="input" maxLength={500} value={note} onChange={(e) => setNote(e.target.value)} />
      </div>
      <button className="btn btn-primary" disabled={create.isPending || !lines.some((l) => l.partId)}>
        Send to parts desk
      </button>
    </form>
  );
}

function Stock({ isShelf }: { isShelf: boolean }) {
  const qc = useQueryClient();
  const parts = useParts();
  const empty = { sku: '', name: '', unitPrice: 0, stockQty: 0 };
  const [form, setForm] = useState(empty);
  const refresh = () => qc.invalidateQueries({ queryKey: ['parts'] });
  const add = useMutation({ mutationFn: () => post<Part>('/api/parts', form), onSuccess: () => (setForm(empty), refresh()) });
  const adjust = useMutation({ mutationFn: ({ id, delta }: { id: string; delta: number }) => post<Part>(`/api/parts/${id}/stock`, { delta }), onSuccess: refresh });

  return (
    <div className={isShelf ? 'split' : ''}>
      <div className="table-wrap">
        <ErrorNote error={parts.error ?? adjust.error} />
        {parts.isLoading ? (
          <Loading />
        ) : (
          <table className="table">
            <thead>
              <tr>
                <th>Part</th>
                <th>Price</th>
                <th>On shelf</th>
                {isShelf && <th />}
              </tr>
            </thead>
            <tbody>
              {parts.data?.map((p) => (
                <tr key={p.id}>
                  <td>
                    <strong>{p.name}</strong>
                    <div className="muted small">{p.sku}</div>
                  </td>
                  <td>{vnd0(p.unitPrice)}</td>
                  <td className={p.stockQty <= 2 ? 'low-stock' : ''}>{p.stockQty}</td>
                  {isShelf && (
                    <td style={{ textAlign: 'right', whiteSpace: 'nowrap' }}>
                      <button className="btn btn-sm" aria-label={`One less ${p.name}`} disabled={p.stockQty === 0 || adjust.isPending}
                        onClick={() => adjust.mutate({ id: p.id, delta: -1 })}>
                        −1
                      </button>{' '}
                      <button className="btn btn-sm" aria-label={`One more ${p.name}`} disabled={adjust.isPending}
                        onClick={() => adjust.mutate({ id: p.id, delta: 1 })}>
                        +1
                      </button>
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
      {isShelf && (
        <form
          className="panel panel-tight stack"
          onSubmit={(e: FormEvent) => {
            e.preventDefault();
            add.mutate();
          }}
        >
          <h3>Add a part to the list</h3>
          <ErrorNote error={add.error} />
          <div className="field">
            <label htmlFor="p-sku">SKU</label>
            <input id="p-sku" className="input" required value={form.sku} onChange={(e) => setForm({ ...form, sku: e.target.value })} />
          </div>
          <div className="field">
            <label htmlFor="p-name">Name</label>
            <input id="p-name" className="input" required value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
          </div>
          <div className="grid-2">
            <div className="field">
              <label htmlFor="p-price">Price (VND)</label>
              <input id="p-price" className="input" type="number" min={0} required value={form.unitPrice}
                onChange={(e) => setForm({ ...form, unitPrice: Number(e.target.value) })} />
            </div>
            <div className="field">
              <label htmlFor="p-stock">On shelf now</label>
              <input id="p-stock" className="input" type="number" min={0} value={form.stockQty}
                onChange={(e) => setForm({ ...form, stockQty: Number(e.target.value) })} />
            </div>
          </div>
          <button className="btn btn-primary" disabled={add.isPending}>
            Add part
          </button>
        </form>
      )}
    </div>
  );
}
