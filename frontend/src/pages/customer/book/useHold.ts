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

  const hold = useMutation({
    mutationFn: (s: Slot) =>
      post<Appointment>(
        '/api/appointments/holds',
        {
          dealershipId: request.dealershipId,
          vehicleId: request.vehicleId,
          start: s.start,
          ...(request.consultationRequestId ? { consultationRequestId: request.consultationRequestId } : { packageId: request.packageId }),
          customerNote: request.note || undefined,
        },
        { 'Idempotency-Key': idemKey.current },
      ),
    onSuccess: (a) => {
      setHeld(a);
      setLapsed(false);
      refreshAvailability();
    },
    onError: (err) => {
      if (err instanceof ApiError && err.code === 'SLOT_UNAVAILABLE') {
        setAlternatives(((err.details as { alternatives?: Slot[] })?.alternatives ?? []) as Slot[]);
        refreshAvailability();
      }
    },
  });

  const confirm = useMutation({
    mutationFn: () => post<Appointment>(`/api/appointments/${held!.id}/confirm`),
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
    if (held) {
      post(`/api/appointments/${held.id}/cancel`, { reason: 'Customer changed the time' }).catch(() => undefined);
      setHeld(null);
      refreshAvailability();
    }
    hold.reset();
  };

  /** Releases any earlier hold and tries to hold this time. */
  const placeHold = (s: Slot) => {
    release();
    idemKey.current = crypto.randomUUID();
    setAlternatives(null);
    setLapsed(false);
    hold.mutate(s);
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

  return { held, booked, alternatives, lapsed, hold, confirm, placeHold, release, slotChanged, lapse };
}
