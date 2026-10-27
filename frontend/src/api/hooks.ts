import { useQuery } from '@tanstack/react-query';
import { useMemo } from 'react';
import { get } from './client';
import type { Appointment, Consultation, Dealership, ReferenceData, ServicePackage, Vehicle } from './types';

export const keys = {
  vehicles: ['me', 'vehicles'] as const,
  myAppointments: ['me', 'appointments'] as const,
  myRequests: ['me', 'consultations'] as const,
  dealerships: ['dealerships'] as const,
  packages: ['packages'] as const,
  reference: ['reference'] as const,
};

export const useVehicles = () => useQuery({ queryKey: keys.vehicles, queryFn: () => get<Vehicle[]>('/api/me/vehicles') });
export const useMyAppointments = () =>
  useQuery({ queryKey: keys.myAppointments, queryFn: () => get<Appointment[]>('/api/me/appointments') });
export const useMyRequests = () =>
  useQuery({ queryKey: keys.myRequests, queryFn: () => get<Consultation[]>('/api/me/consultations') });
export const useDealerships = () =>
  useQuery({ queryKey: keys.dealerships, queryFn: () => get<Dealership[]>('/api/dealerships'), staleTime: 5 * 60_000 });
export const usePackages = () =>
  useQuery({ queryKey: keys.packages, queryFn: () => get<ServicePackage[]>('/api/service-packages'), staleTime: 5 * 60_000 });
export const useReferenceData = () =>
  useQuery({ queryKey: keys.reference, queryFn: () => get<ReferenceData>('/api/reference-data'), staleTime: Infinity });

/**
 * The customer's vehicles that cannot be booked again yet, each with the appointment that
 * claims it: on a hold that has not run out, booked, or in the workshop. The server refuses
 * a second booking for such a vehicle (409 VEHICLE_HAS_APPOINTMENT) until that one is
 * cancelled or done, so the pages disable booking for it instead of offering a dead end.
 */
export function useBusyVehicles(): Map<string, Appointment> {
  const appointments = useMyAppointments();
  return useMemo(() => {
    const now = Date.now();
    const busy = new Map<string, Appointment>();
    for (const a of appointments.data ?? []) {
      const live =
        a.status === 'CONFIRMED' || a.status === 'IN_PROGRESS' || (a.status === 'HELD' && a.holdExpiresAt !== null && Date.parse(a.holdExpiresAt) > now);
      if (live && !busy.has(a.vehicle.id)) busy.set(a.vehicle.id, a);
    }
    return busy;
  }, [appointments.data]);
}
