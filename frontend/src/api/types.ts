// Mirrors the backend's API (see backend/openapi.yaml).

export type Role = 'CUSTOMER' | 'SERVICE_ADVISOR' | 'TECHNICIAN' | 'ADMIN' | 'SYSTEM_MANAGER';
export type VehicleType = 'MOTORBIKE' | 'CAR';
export type AppointmentStatus = 'CONFIRMED' | 'IN_PROGRESS' | 'COMPLETED' | 'CANCELLED' | 'NO_SHOW';
export type ConsultationKind = 'INSPECTION' | 'MODIFICATION';
export type ConsultationStatus = 'SUBMITTED' | 'APPROVED' | 'REJECTED' | 'BOOKED' | 'CANCELLED';

export interface User {
  id: string;
  email: string;
  fullName: string;
  phone: string | null;
  role: Role;
  dealershipId: string | null;
  dealershipName: string | null;
}

export interface Session {
  token: string;
  expiresAt: string;
  user: User;
}

export interface Dealership {
  id: string;
  code: string;
  name: string;
  address: string;
  phone: string | null;
  timezone: string;
  openingTime: string;
  closingTime: string;
  openDays: number[];
}

export interface Money {
  amount: number;
  currency: string;
}

export interface ServicePackage {
  id: string;
  code: string;
  name: string;
  description: string;
  vehicleType: VehicleType;
  durationMinutes: number;
  requiredSkill: string;
  bayType: string;
  price: Money;
  checklist: string[];
  isRecommended: boolean;
}

export interface Vehicle {
  id: string;
  vehicleType: VehicleType;
  make: string;
  model: string;
  year: number | null;
  plate: string;
  vin: string | null;
  mileageKm: number | null;
}

export interface Slot {
  start: string;
  end: string;
  available: boolean;
  capacity: number;
  reason?: 'PAST_OR_TOO_SOON' | 'FULLY_BOOKED' | 'TOO_FAR_AHEAD';
}

export interface Availability {
  dealershipId: string;
  date: string;
  timezone: string;
  durationMinutes: number;
  offeredHere: boolean;
  bookableDates: string[];
  slots: Slot[];
}

export interface Appointment {
  id: string;
  reference: string;
  status: AppointmentStatus;
  start: string;
  end: string;
  dealership: { id: string; name: string; address: string; phone: string | null; timezone: string };
  vehicle: { id: string; vehicleType: VehicleType; make: string; model: string; plate: string };
  customer: { id: string; fullName: string; phone: string | null; email: string };
  service: {
    kind: 'PACKAGE' | 'INSPECTION' | 'MODIFICATION';
    name: string;
    packageId: string | null;
    consultationRequestId: string | null;
    checklist: string[];
  };
  price: Money | null;
  bay: { id: string; name: string };
  technician: { id: string; name: string };
  customerNote: string | null;
  cancelReason: string | null;
  createdAt: string;
}

export interface Consultation {
  id: string;
  reference: string;
  kind: ConsultationKind;
  status: ConsultationStatus;
  areas: string[];
  description: string;
  vehicle: { id: string; vehicleType: VehicleType; make: string; model: string; plate: string };
  dealership: { id: string; name: string };
  customer: { id: string; fullName: string; phone: string | null; email: string };
  advisorNote: string | null;
  advisorName: string | null;
  durationMinutes: number | null;
  requiredSkill: string | null;
  bayType: string | null;
  estimatedPrice: number | null;
  createdAt: string;
  reviewedAt: string | null;
  appointment: { id: string; reference: string; start: string; status: AppointmentStatus } | null;
}

export interface ReferenceData {
  inspectionAreas: { code: string; name: string }[];
  skills: { code: string; name: string }[];
  bayTypes: { code: string; name: string; vehicleTypes: VehicleType[] }[];
}
