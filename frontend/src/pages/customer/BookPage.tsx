import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useMemo, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { ApiError, get, post } from '../../api/client';
import { keys, useDealerships, usePackages, useVehicles } from '../../api/hooks';
import type { Appointment, Availability, Consultation, Slot } from '../../api/types';
import { DayStrip, SlotGrid } from '../../components/SlotPicker';
import { Ticket } from '../../components/Ticket';
import { ErrorNote, Loading, Plate } from '../../components/ui';
import { VehicleForm } from '../../components/VehicleForm';
import { downloadIcs } from '../../lib/calendar';
import { dayLong, dayShort, duration, money, time, todayIn, vehicleName, weekdays } from '../../lib/format';

type Step = 'vehicle' | 'service' | 'time' | 'confirm';
const LAST_DEALERSHIP = 'scheduler.lastDealership';

const remembered = () => {
  try {
    return localStorage.getItem(LAST_DEALERSHIP);
  } catch {
    return null;
  }
};

/**
 * The main customer journey: vehicle → service → place & time → confirm.
 * With ?request=<id> it books an advisor-approved consultation instead, and only the
 * time and confirmation steps are shown (vehicle, service and dealership are already fixed).
 */
export function BookPage() {
  const [params] = useSearchParams();
  const requestId = params.get('request');
  const qc = useQueryClient();

  const vehicles = useVehicles();
  const packages = usePackages();
  const dealerships = useDealerships();
  const request = useQuery({
    queryKey: ['consultation', requestId],
    queryFn: () => get<Consultation>(`/api/consultations/${requestId}`),
    enabled: Boolean(requestId),
  });

  const [step, setStep] = useState<Step>(requestId ? 'time' : params.get('vehicle') ? 'service' : 'vehicle');
  const [vehicleId, setVehicleId] = useState<string | null>(params.get('vehicle'));
  const [packageId, setPackageId] = useState<string | null>(params.get('package'));
  const [dealershipId, setDealershipId] = useState<string | null>(remembered());
  const [date, setDate] = useState<string | null>(null);
  const [slot, setSlot] = useState<Slot | null>(null);
  const [note, setNote] = useState('');
  const [addingVehicle, setAddingVehicle] = useState(false);
  const [alternatives, setAlternatives] = useState<Slot[] | null>(null);
  const [booked, setBooked] = useState<Appointment | null>(null);

  // In consultation mode everything except the time is decided by the approved request.
  useEffect(() => {
    if (request.data) {
      setVehicleId(request.data.vehicle.id);
      setDealershipId(request.data.dealership.id);
    }
  }, [request.data]);

  // One vehicle? Choose it for them and start at the service step.
  useEffect(() => {
    if (!requestId && !vehicleId && vehicles.data?.length === 1) {
      setVehicleId(vehicles.data[0]!.id);
      setStep('service');
    }
  }, [requestId, vehicleId, vehicles.data]);

  const vehicle = vehicles.data?.find((v) => v.id === vehicleId) ?? null;
  const pkg = packages.data?.find((p) => p.id === packageId) ?? null;
  const dealership = dealerships.data?.find((d) => d.id === dealershipId) ?? null;
  const consult = requestId ? request.data ?? null : null;
  const fitsPackages = useMemo(
    () => (packages.data ?? []).filter((p) => p.vehicleType === vehicle?.vehicleType).sort((a, b) => Number(b.isRecommended) - Number(a.isRecommended)),
    [packages.data, vehicle?.vehicleType],
  );

  const source = consult ? `consultationRequestId=${consult.id}` : pkg ? `packageId=${pkg.id}` : null;
  // While autoPick is on, the page walks forward to the first day that still has a free time.
  const [autoPick, setAutoPick] = useState(true);
  const resetDay = () => {
    setDate(null);
    setSlot(null);
    setAutoPick(true);
  };

  const availability = useQuery({
    queryKey: ['availability', dealershipId, date ?? 'today', source],
    queryFn: () =>
      get<Availability>(`/api/availability?dealershipId=${dealershipId}&date=${date ?? todayIn(dealership?.timezone)}&${source}`),
    enabled: Boolean(dealershipId && source && (step === 'time' || step === 'confirm')),
    refetchInterval: 30_000, // availability is live: refresh while the customer is choosing
  });

  useEffect(() => {
    const a = availability.data;
    if (!autoPick || !a) return;
    const next = a.bookableDates.find((d) => d > a.date);
    if (a.slots.some((s) => s.available) || !a.offeredHere || !next) {
      setDate(a.date);
      setAutoPick(false);
    } else {
      setDate(next);
    }
  }, [availability.data, autoPick]);

  // A new idempotency key per distinct booking attempt: a double tap or a retry after a
  // network blip can never create two appointments.
  const idemKey = useRef<string>(crypto.randomUUID());
  useEffect(() => {
    idemKey.current = crypto.randomUUID();
  }, [slot?.start, vehicleId, packageId, dealershipId, requestId]);

  const book = useMutation({
    mutationFn: () =>
      post<Appointment>(
        '/api/appointments',
        {
          dealershipId,
          vehicleId,
          start: slot!.start,
          ...(consult ? { consultationRequestId: consult.id } : { packageId }),
          customerNote: note || undefined,
        },
        { 'Idempotency-Key': idemKey.current },
      ),
    onSuccess: (a) => {
      setBooked(a);
      qc.invalidateQueries({ queryKey: keys.myAppointments });
      qc.invalidateQueries({ queryKey: keys.myRequests });
      qc.invalidateQueries({ queryKey: ['availability'] });
      try {
        localStorage.setItem(LAST_DEALERSHIP, a.dealership.id);
      } catch {
        /* ignore */
      }
      window.scrollTo({ top: 0 });
    },
    onError: (err) => {
      if (err instanceof ApiError && err.code === 'SLOT_UNAVAILABLE') {
        setAlternatives(((err.details as { alternatives?: Slot[] })?.alternatives ?? []) as Slot[]);
        qc.invalidateQueries({ queryKey: ['availability'] });
      }
    },
  });

  const pickSlot = (s: Slot) => {
    setSlot(s);
    setAlternatives(null);
    book.reset();
  };

  if (vehicles.isLoading || packages.isLoading || dealerships.isLoading || request.isLoading) return <Loading what="Getting things ready" />;
  if (request.error) {
    return (
      <div className="page page-narrow">
        <ErrorNote error={request.error} />
      </div>
    );
  }

  const ticket = {
    plate: vehicle?.plate ?? consult?.vehicle.plate,
    vehicle: vehicle ? vehicleName(vehicle) : consult ? vehicleName(consult.vehicle) : undefined,
    service: consult ? (consult.kind === 'MODIFICATION' ? 'Customisation' : 'Inspection on request') : pkg?.name,
    durationMinutes: consult?.durationMinutes ?? pkg?.durationMinutes,
    place: dealership?.name,
    placeSub: dealership?.address,
    start: slot?.start,
    end: slot?.end,
    price: consult ? (consult.estimatedPrice !== null ? { amount: consult.estimatedPrice, currency: 'VND' } : null) : pkg?.price,
    reference: booked?.reference,
  };

  // ---------------------------------------------------------------- confirmed
  if (booked) {
    return (
      <div className="page">
        <div className="book">
          <div className="panel confirmed">
            <div className="tick" aria-hidden="true" />
            <h1>You're booked in</h1>
            <p className="ref">{booked.reference}</p>
            <p className="muted">
              {dayLong(booked.start)} at {time(booked.start)}, {booked.dealership.name}.
              <br />
              {booked.technician.name} will look after your {booked.vehicle.make}. Show this reference at the service desk.
            </p>
            <div className="row" style={{ justifyContent: 'center', marginTop: 24 }}>
              <button className="btn" onClick={() => downloadIcs(booked)}>
                Add to calendar
              </button>
              <Link className="btn btn-primary" to="/appointments">
                See my appointments
              </Link>
            </div>
          </div>
          <Ticket data={ticket} />
        </div>
      </div>
    );
  }

  const steps: { id: Step; label: string }[] = consult
    ? [
        { id: 'time', label: 'Time' },
        { id: 'confirm', label: 'Confirm' },
      ]
    : [
        { id: 'vehicle', label: 'Vehicle' },
        { id: 'service', label: 'Service' },
        { id: 'time', label: 'Place & time' },
        { id: 'confirm', label: 'Confirm' },
      ];
  const currentIndex = steps.findIndex((s) => s.id === step);
  const canOpen = (id: Step) =>
    id === 'vehicle' || (id === 'service' && vehicle) || (id === 'time' && (pkg || consult)) || (id === 'confirm' && slot);

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <h1>{consult ? 'Pick a time for your request' : 'Book a service'}</h1>
          {consult && (
            <p>
              {consult.advisorName ?? 'Your advisor'} approved request {consult.reference}: about {duration(consult.durationMinutes ?? 60)} at{' '}
              {consult.dealership.name}.
            </p>
          )}
        </div>
      </div>

      <ol className="steps">
        {steps.map((s, i) => (
          <li key={s.id} className={i < currentIndex ? 'done' : i === currentIndex ? 'current' : ''}>
            <button type="button" disabled={!canOpen(s.id)} onClick={() => canOpen(s.id) && setStep(s.id)} aria-current={i === currentIndex ? 'step' : undefined}>
              <span className="n">{i + 1}</span>
              {s.label}
            </button>
          </li>
        ))}
      </ol>

      <div className="book">
        <section className="panel" aria-live="polite">
          {/* ------------------------------------------------ 1. vehicle */}
          {step === 'vehicle' && (
            <div className="stack">
              <h2>Which vehicle?</h2>
              {addingVehicle || vehicles.data?.length === 0 ? (
                <VehicleForm
                  onAdded={(v) => {
                    setVehicleId(v.id);
                    setAddingVehicle(false);
                    setPackageId(null);
                    setStep('service');
                  }}
                  onCancel={vehicles.data?.length ? () => setAddingVehicle(false) : undefined}
                />
              ) : (
                <>
                  <div className="choices">
                    {vehicles.data?.map((v) => (
                      <button
                        key={v.id}
                        type="button"
                        className="choice"
                        aria-pressed={v.id === vehicleId}
                        onClick={() => {
                          if (v.id !== vehicleId) setPackageId(null);
                          setVehicleId(v.id);
                          setStep('service');
                        }}
                      >
                        <span className="radio" aria-hidden="true" />
                        <span>
                          <span className="title">{vehicleName(v)}</span>
                          <span className="sub">
                            {' '}
                            · {v.vehicleType === 'MOTORBIKE' ? 'Motorbike' : 'Car'}
                            {v.year ? `, ${v.year}` : ''}
                          </span>
                        </span>
                        <span className="aside">
                          <Plate text={v.plate} />
                        </span>
                      </button>
                    ))}
                  </div>
                  <button className="btn btn-ghost" onClick={() => setAddingVehicle(true)}>
                    Add another vehicle
                  </button>
                </>
              )}
            </div>
          )}

          {/* ------------------------------------------------ 2. service */}
          {step === 'service' && vehicle && (
            <div className="stack">
              <div className="spread">
                <h2>What should we do?</h2>
              </div>
              <div className="choices">
                {fitsPackages.map((p) => (
                  <button
                    key={p.id}
                    type="button"
                    className="choice"
                    aria-pressed={p.id === packageId}
                    onClick={() => {
                      setPackageId(p.id);
                      resetDay();
                    }}
                  >
                    <span className="radio" aria-hidden="true" />
                    <span>
                      <span className="title">
                        {p.name}
                        {p.isRecommended && <span className="tag">Recommended</span>}
                      </span>
                      <span className="sub" style={{ display: 'block' }}>
                        {p.description}
                      </span>
                      {p.id === packageId && (
                        <ul className="checklist" aria-label="What is included">
                          {p.checklist.map((c) => (
                            <li key={c}>{c}</li>
                          ))}
                        </ul>
                      )}
                    </span>
                    <span className="aside">
                      <span className="price">{money(p.price)}</span>
                      <br />
                      {duration(p.durationMinutes)}
                    </span>
                  </button>
                ))}
              </div>
              <div className="notice notice-info">
                Something specific, like a strange noise or a warning light?{' '}
                <Link to={`/requests/new?kind=INSPECTION&vehicle=${vehicle.id}`}>Describe it to an advisor first</Link>; they will tell you what
                it needs and how long it takes.
              </div>
              <div className="nav-buttons">
                <button className="btn" onClick={() => setStep('vehicle')}>
                  Back
                </button>
                <button className="btn btn-primary" disabled={!pkg} onClick={() => setStep('time')}>
                  Choose a time
                </button>
              </div>
            </div>
          )}

          {/* ------------------------------------------------ 3. place & time */}
          {step === 'time' && (
            <div className="stack">
              {!consult && (
                <>
                  <h2>Where?</h2>
                  <div className="choices">
                    {dealerships.data?.map((d) => (
                      <button
                        key={d.id}
                        type="button"
                        className="choice"
                        aria-pressed={d.id === dealershipId}
                        onClick={() => {
                          setDealershipId(d.id);
                          resetDay();
                        }}
                      >
                        <span className="radio" aria-hidden="true" />
                        <span>
                          <span className="title">{d.name}</span>
                          <span className="sub" style={{ display: 'block' }}>
                            {d.address}
                          </span>
                        </span>
                        <span className="aside">
                          {d.openingTime}–{d.closingTime}
                          <br />
                          {weekdays(d.openDays)}
                        </span>
                      </button>
                    ))}
                  </div>
                  <hr className="divider" />
                </>
              )}

              {dealership && (
                <>
                  <h2>When?</h2>
                  <ErrorNote error={availability.error} />
                  {availability.isLoading && <Loading what="Checking free bays and technicians" />}
                  {availability.data && !availability.data.offeredHere && (
                    <div className="notice notice-info">
                      {dealership.name} cannot do this job: it has no {vehicle?.vehicleType === 'CAR' ? 'car lift' : 'suitable bay'} or no
                      technician with the right skills. Please choose another dealership.
                    </div>
                  )}
                  {availability.data && availability.data.offeredHere && (
                    <>
                      <DayStrip
                        dates={availability.data.bookableDates}
                        zone={availability.data.timezone}
                        value={date ?? availability.data.date}
                        onChange={(d) => {
                          setAutoPick(false);
                          setDate(d);
                          setSlot(null);
                        }}
                      />
                      {availability.data.slots.some((s) => s.available) ? (
                        <SlotGrid slots={availability.data.slots} value={slot?.start ?? null} onChange={pickSlot} />
                      ) : (
                        <div className="empty">
                          <h3>Nothing free on {dayLong(`${availability.data.date}T12:00:00`)}</h3>
                          <p>Every bay or every qualified technician is taken. Try another day above.</p>
                        </div>
                      )}
                    </>
                  )}
                </>
              )}
              <div className="nav-buttons">
                {consult ? (
                  <Link className="btn" to="/requests">
                    Back
                  </Link>
                ) : (
                  <button className="btn" onClick={() => setStep('service')}>
                    Back
                  </button>
                )}
                <button className="btn btn-primary" disabled={!slot} onClick={() => setStep('confirm')}>
                  Review booking
                </button>
              </div>
            </div>
          )}

          {/* ------------------------------------------------ 4. confirm */}
          {step === 'confirm' && slot && (
            <div className="stack">
              <h2>Check and confirm</h2>
              <p className="muted">
                We will hold a {consult?.bayType === 'DIAGNOSTIC_BAY' ? 'diagnostic bay' : 'bay'} and a technician for you from {time(slot.start)} to{' '}
                {time(slot.end)}. You can cancel any time before then.
              </p>

              {alternatives !== null ? (
                <div className="stack">
                  <div className="notice notice-error" role="alert">
                    Someone booked {time(slot.start)} a moment ago.{' '}
                    {alternatives.length > 0 ? 'These are the nearest free times:' : 'There is nothing free nearby; please go back and pick another day.'}
                  </div>
                  {alternatives.length > 0 && (
                    <div className="slots">
                      {alternatives.map((s) => (
                        <button key={s.start} type="button" className="slot" onClick={() => pickSlot(s)}>
                          {time(s.start)}
                          <small>{dayShort(s.start)}</small>
                        </button>
                      ))}
                    </div>
                  )}
                </div>
              ) : (
                <ErrorNote error={book.error} />
              )}

              <div className="field">
                <label htmlFor="note">Anything the technician should know? (optional)</label>
                <textarea
                  id="note"
                  className="input"
                  maxLength={1000}
                  placeholder="For example: the front brake squeaks when it rains."
                  value={note}
                  onChange={(e) => setNote(e.target.value)}
                />
              </div>

              <div className="nav-buttons">
                <button className="btn" onClick={() => setStep('time')}>
                  Change time
                </button>
                <button className="btn btn-primary" disabled={book.isPending || alternatives !== null} onClick={() => book.mutate()}>
                  {book.isPending ? 'Booking…' : 'Confirm booking'}
                </button>
              </div>
            </div>
          )}
        </section>

        <Ticket data={ticket} />
      </div>

    </div>
  );
}
