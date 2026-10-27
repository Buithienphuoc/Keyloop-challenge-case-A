import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import { ApiError, post } from '../../../api/client';
import { keys } from '../../../api/hooks';
import type { Appointment, Slot } from '../../../api/types';

export const LAST_DEALERSHIP = 'scheduler.lastDealership';

interface HoldRequest {
  dealershipId: string | null;
  vehicleId: string | null;
  packageId: string | null;
  consultationRequestId: string | null;
  note: string;
}

/**
 * The server side of booking: hold a time (HELD), then confirm it (CONFIRMED). Keeps the
 * held appointment, the booking, the alternatives offered after a lost race, and whether the
 * hold ran out.
 */
export function useHold(request: HoldRequest, slotStart: string | undefined) {
  const qc = useQueryClient();
  const [held, setHeld] = useState<Appointment | null>(null);
  const [booked, setBooked] = useState<Appointment | null>(null);
  const [alternatives, setAlternatives] = useState<Slot[] | null>(null);
  const [lapsed, setLapsed] = useState(false);
  const refreshAvailability = () => qc.invalidateQueries({ queryKey: ['availability'] });

  // A new idempotency key per distinct hold attempt: a double tap or a retry after a
  // network blip can never hold (or book) twice.
  const idemKey = useRef<string>(crypto.randomUUID());
  useEffect(() => {
    idemKey.current = crypto.randomUUID();
  }, [slotStart, request.vehicleId, request.packageId, request.dealershipId, request.consultationRequestId]);

  // Guards against a second press while the first is still on its way. Refs, not state:
  // several clicks can arrive before React re-renders and disables anything.
  // `attempt` numbers hold attempts; an answer for an attempt the customer has since left
  // (released, or replaced by another time) is given back to the server straight away.
  // `settling` queues each hold behind the cancels of the ones before it: the server keeps
  // one live appointment per vehicle, so a new hold sent while the old one still stands
  // would be refused.
  const attempt = useRef(0);
  const holding = useRef<string | null>(null);
  const confirming = useRef(false);
  const settling = useRef<Promise<unknown>>(Promise.resolve());
  const mine = useRef(new Set<string>());
  const cancelHold = (id: string) =>
    post(`/api/appointments/${id}/cancel`, { reason: 'Customer changed the time' })
      .catch(() => undefined)
      .finally(() => qc.invalidateQueries({ queryKey: keys.myAppointments }));

  const hold = useMutation({
    mutationFn: ({ slot, n, key }: { slot: Slot; n: number; key: string }) => {
      const run = settling.current.then(async () => {
        const a = await post<Appointment>(
          '/api/appointments/holds',
          {
            dealershipId: request.dealershipId,
            vehicleId: request.vehicleId,
            start: slot.start,
            ...(request.consultationRequestId ? { consultationRequestId: request.consultationRequestId } : { packageId: request.packageId }),
            customerNote: request.note || undefined,
          },
          { 'Idempotency-Key': key },
        );
        mine.current.add(a.id);
        if (n !== attempt.current) {
          await cancelHold(a.id);
          return null;
        }
        return a;
      });
      settling.current = run.catch(() => undefined);
      return run.then((a) => ({ a, n }));
    },
    onSuccess: ({ a, n }) => {
      if (!a) return;
      if (n !== attempt.current) {
        settling.current = settling.current.then(() => cancelHold(a.id));
        return;
      }
      setHeld(a);
      setLapsed(false);
      refreshAvailability();
    },
    onSettled: (_data, _err, { n }) => {
      if (n === attempt.current) holding.current = null;
    },
    onError: (err, { n }) => {
      if (n !== attempt.current) return;
      // Booked or held meanwhile (another tab): the page then shows which appointment and how to free it.
      if (err instanceof ApiError && err.code === 'VEHICLE_HAS_APPOINTMENT') qc.invalidateQueries({ queryKey: keys.myAppointments });
      if (err instanceof ApiError && err.code === 'SLOT_UNAVAILABLE') {
        setAlternatives(((err.details as { alternatives?: Slot[] })?.alternatives ?? []) as Slot[]);
        refreshAvailability();
      }
    },
  });

  const confirmMutation = useMutation({
    mutationFn: (id: string) => post<Appointment>(`/api/appointments/${id}/confirm`),
    onSettled: () => {
      confirming.current = false;
    },
    onSuccess: (a) => {
      setHeld(null);
      setBooked(a);
      qc.invalidateQueries({ queryKey: keys.myAppointments });
      qc.invalidateQueries({ queryKey: keys.myRequests });
      refreshAvailability();
      try {
        localStorage.setItem(LAST_DEALERSHIP, a.dealership.id);
      } catch {
        /* ignore */
      }
      window.scrollTo({ top: 0 });
    },
    onError: (err) => {
      if (err instanceof ApiError && err.code === 'HOLD_EXPIRED') {
        setHeld(null);
        setLapsed(true);
        refreshAvailability();
      }
    },
  });

  /** Gives the held time back (the customer went back to change something). */
  const release = () => {
    attempt.current += 1; // a hold still on its way is given back when it arrives
    holding.current = null;
    if (held) {
      const id = held.id;
      settling.current = settling.current.then(() => cancelHold(id));
      setHeld(null);
      refreshAvailability();
    }
    hold.reset();
  };

  /** Releases any earlier hold and tries to hold this time. Further presses for the same time are ignored until it is answered. */
  const placeHold = (s: Slot) => {
    if (holding.current === s.start) return;
    release();
    holding.current = s.start;
    idemKey.current = crypto.randomUUID();
    setAlternatives(null);
    setLapsed(false);
    hold.mutate({ slot: s, n: attempt.current, key: idemKey.current });
  };

  /** Confirms the held time once, however often Confirm is pressed. */
  const confirm = {
    mutate: () => {
      if (!held || confirming.current) return;
      confirming.current = true;
      confirmMutation.mutate(held.id);
    },
    isPending: confirmMutation.isPending,
    error: confirmMutation.error,
    reset: confirmMutation.reset,
  };

  /** The customer picked another time on the grid: forget the last race and the last confirm error. */
  const slotChanged = () => {
    setAlternatives(null);
    confirm.reset();
  };

  /** The countdown reached zero before the customer confirmed. */
  const lapse = () => {
    setHeld(null);
    setLapsed(true);
  };

  /** Whether this page placed that appointment (its own hold must not count as the vehicle being taken). */
  const isMine = (id: string) => mine.current.has(id);

  return { held, booked, alternatives, lapsed, hold, confirm, placeHold, release, slotChanged, lapse, isMine };
}
