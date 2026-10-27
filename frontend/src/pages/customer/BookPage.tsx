import { useQuery } from '@tanstack/react-query';
import { useEffect, useMemo, useReducer } from 'react';
import { useSearchParams } from 'react-router-dom';
import { get } from '../../api/client';
import { useBusyVehicles, useDealerships, usePackages, useVehicles } from '../../api/hooks';
import type { Availability, Consultation, Slot } from '../../api/types';
import { BusyNote } from '../../components/BusyVehicle';
import { Ticket } from '../../components/Ticket';
import { ErrorNote, Loading } from '../../components/ui';
import { duration, todayIn, vehicleName } from '../../lib/format';
import { BookedPanel } from './book/BookedPanel';
import { ConfirmStep } from './book/ConfirmStep';
import { ServiceStep } from './book/ServiceStep';
import { TimeStep } from './book/TimeStep';
import { LAST_DEALERSHIP, useHold } from './book/useHold';
import { VehicleStep } from './book/VehicleStep';
import { initialWizard, wizardReducer, type Step } from './book/wizard';

const remembered = () => {
  try {
    return localStorage.getItem(LAST_DEALERSHIP);
  } catch {
    return null;
  }
};

/**
 * The main customer journey: vehicle → service → place & time → confirm.
 * Moving to the confirm step places a short hold on the chosen time (HELD), so nobody can
 * take it while the customer reviews; "Confirm booking" turns the hold into a booking.
 * With ?request=<id> it books an advisor-approved consultation instead, and only the
 * time and confirmation steps are shown (vehicle, service and dealership are already fixed).
 *
 * The choices live in one reducer (book/wizard.ts); the hold and the booking in useHold;
 * each step is its own component in book/.
 */
export function BookPage() {
  const [params] = useSearchParams();
  const requestId = params.get('request');

  const vehicles = useVehicles();
  const packages = usePackages();
  const dealerships = useDealerships();
  const allBusy = useBusyVehicles();
  const request = useQuery({
    queryKey: ['consultation', requestId],
    queryFn: () => get<Consultation>(`/api/consultations/${requestId}`),
    enabled: Boolean(requestId),
  });

  const [w, dispatch] = useReducer(wizardReducer, undefined, () => initialWizard(params, remembered()));

  // In consultation mode everything except the time is decided by the approved request.
  useEffect(() => {
    if (request.data) dispatch({ type: 'fixedByRequest', vehicleId: request.data.vehicle.id, dealershipId: request.data.dealership.id });
  }, [request.data]);

  // One vehicle? Choose it for them and start at the service step.
  useEffect(() => {
    if (!requestId && !w.vehicleId && vehicles.data?.length === 1) dispatch({ type: 'autoChooseOnlyVehicle', vehicleId: vehicles.data[0]!.id });
  }, [requestId, w.vehicleId, vehicles.data]);

  const vehicle = vehicles.data?.find((v) => v.id === w.vehicleId) ?? null;
  const pkg = packages.data?.find((p) => p.id === w.packageId) ?? null;
  const dealership = dealerships.data?.find((d) => d.id === w.dealershipId) ?? null;
  const consult = requestId ? request.data ?? null : null;
  const fitsPackages = useMemo(
    () => (packages.data ?? []).filter((p) => p.vehicleType === vehicle?.vehicleType).sort((a, b) => Number(b.isRecommended) - Number(a.isRecommended)),
    [packages.data, vehicle?.vehicleType],
  );

  const source = consult ? `consultationRequestId=${consult.id}` : pkg ? `packageId=${pkg.id}` : null;
  const availability = useQuery({
    queryKey: ['availability', w.dealershipId, w.date ?? 'today', source],
    queryFn: () =>
      get<Availability>(`/api/availability?dealershipId=${w.dealershipId}&date=${w.date ?? todayIn(dealership?.timezone)}&${source}`),
    enabled: Boolean(w.dealershipId && source && (w.step === 'time' || w.step === 'confirm')),
    refetchInterval: 30_000, // availability is live: refresh while the customer is choosing
  });

  // While autoPick is on, walk forward to the first day that still has a free time.
  useEffect(() => {
    const a = availability.data;
    if (!w.autoPick || !a) return;
    const next = a.bookableDates.find((d) => d > a.date);
    const settled = a.slots.some((s) => s.available) || !a.offeredHere || !next;
    dispatch({ type: 'autoPickDay', date: settled ? a.date : next, settled });
  }, [availability.data, w.autoPick]);

  const booking = useHold(
    { dealershipId: w.dealershipId, vehicleId: w.vehicleId, packageId: w.packageId, consultationRequestId: consult?.id ?? null, note: w.note },
    w.slot?.start,
  );

  // A vehicle with a live appointment is booked again only once that one is cancelled or
  // done (the server refuses it too). This page's own hold does not count.
  const busy = new Map([...allBusy].filter(([, a]) => !booking.isMine(a.id)));
  const blockedBy = w.vehicleId ? busy.get(w.vehicleId) : undefined;

  const placeHold = (s: Slot) => {
    dispatch({ type: 'goTo', step: 'confirm' });
    booking.placeHold(s);
  };
  const go = (next: Step) => {
    if (w.step === 'confirm' && next !== 'confirm') booking.release();
    dispatch({ type: 'goTo', step: next });
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
    start: w.slot?.start,
    end: w.slot?.end,
    price: consult ? (consult.estimatedPrice !== null ? { amount: consult.estimatedPrice, currency: 'VND' } : null) : pkg?.price,
    reference: booking.booked?.reference,
  };

  if (booking.booked) return <BookedPanel booked={booking.booked} ticket={ticket} />;

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
  const currentIndex = steps.findIndex((s) => s.id === w.step);
  const canOpen = (id: Step) =>
    id === 'vehicle' || (id === 'service' && vehicle) || (id === 'time' && (pkg || consult)) || (id === 'confirm' && w.slot);
  const openStep = (id: Step) => {
    if (!canOpen(id)) return;
    if (id === 'confirm' && w.slot) {
      if (w.step !== 'confirm') placeHold(w.slot);
    } else go(id);
  };

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
            <button type="button" disabled={!canOpen(s.id)} onClick={() => openStep(s.id)} aria-current={i === currentIndex ? 'step' : undefined}>
              <span className="n">{i + 1}</span>
              {s.label}
            </button>
          </li>
        ))}
      </ol>

      <div className="book">
        <section className="panel" aria-live="polite">
          {blockedBy && w.step !== 'vehicle' && (
            <div className="stack">
              <h2>This vehicle already has an appointment</h2>
              <div className="notice notice-info" role="status">
                <BusyNote appointment={blockedBy} />
              </div>
              {!consult && (vehicles.data?.length ?? 0) > 1 && (
                <div className="nav-buttons">
                  <button className="btn" onClick={() => go('vehicle')}>
                    Choose another vehicle
                  </button>
                </div>
              )}
            </div>
          )}

          {w.step === 'vehicle' && (
            <VehicleStep
              vehicles={vehicles.data ?? []}
              vehicleId={w.vehicleId}
              busy={busy}
              adding={w.addingVehicle}
              onAdding={(adding) => dispatch({ type: 'setAddingVehicle', adding })}
              onChoose={(vehicleId) => dispatch({ type: 'chooseVehicle', vehicleId })}
            />
          )}

          {!blockedBy && w.step === 'service' && vehicle && (
            <ServiceStep
              vehicle={vehicle}
              packages={fitsPackages}
              packageId={w.packageId}
              ready={Boolean(pkg)}
              onChoose={(packageId) => dispatch({ type: 'choosePackage', packageId })}
              onBack={() => go('vehicle')}
              onNext={() => dispatch({ type: 'goTo', step: 'time' })}
            />
          )}

          {!blockedBy && w.step === 'time' && (
            <TimeStep
              choosePlace={!consult}
              dealerships={dealerships.data ?? []}
              dealership={dealership}
              availability={availability}
              isCar={vehicle?.vehicleType === 'CAR'}
              date={w.date}
              slot={w.slot}
              note={w.note}
              onChooseDealership={(dealershipId) => dispatch({ type: 'chooseDealership', dealershipId })}
              onPickDay={(date) => dispatch({ type: 'pickDay', date })}
              onPickSlot={(slot) => {
                dispatch({ type: 'pickSlot', slot });
                booking.slotChanged();
              }}
              onNote={(note) => dispatch({ type: 'setNote', note })}
              onBack={() => go('service')}
              onHold={placeHold}
            />
          )}

          {!blockedBy && w.step === 'confirm' && w.slot && (
            <ConfirmStep
              slot={w.slot}
              diagnosticBay={consult?.bayType === 'DIAGNOSTIC_BAY'}
              held={booking.held}
              holding={booking.hold.isPending}
              lapsed={booking.lapsed}
              alternatives={booking.alternatives}
              error={booking.confirm.error ?? booking.hold.error}
              confirming={booking.confirm.isPending}
              onLapse={booking.lapse}
              onHoldAgain={() => placeHold(w.slot!)}
              onPickAlternative={(s) => {
                dispatch({ type: 'pickSlot', slot: s });
                placeHold(s);
              }}
              onChangeTime={() => go('time')}
              onConfirm={() => booking.confirm.mutate()}
            />
          )}
        </section>

        <Ticket data={ticket} />
      </div>
    </div>
  );
}
