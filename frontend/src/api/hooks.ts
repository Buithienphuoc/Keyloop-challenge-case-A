import { useQuery } from '@tanstack/react-query';
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
