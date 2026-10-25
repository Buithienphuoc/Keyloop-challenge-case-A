import { DateTime } from 'luxon';
import type { AppointmentStatus, ConsultationStatus, PartOrderStatus, Role } from '../api/types';

const vnd = new Intl.NumberFormat('vi-VN', { style: 'currency', currency: 'VND', maximumFractionDigits: 0 });

export function money(m: { amount: number; currency: string } | null | undefined): string {
  if (!m) return 'Quoted after inspection';
  return m.currency === 'VND' ? vnd.format(m.amount) : `${m.amount} ${m.currency}`;
}

/** Times are shown in the dealership's own time zone (that is where the customer will be). */
export const dt = (iso: string) => DateTime.fromISO(iso, { setZone: true });
export const time = (iso: string) => dt(iso).toFormat('HH:mm');
export const dayLong = (iso: string) => dt(iso).toFormat('cccc d LLLL');
export const dayShort = (iso: string) => dt(iso).toFormat('ccc d LLL');
/** Record timestamps (createdAt, …) arrive in UTC; show them in the dealership's zone too. */
export const stamp = (iso: string, zone = 'Asia/Ho_Chi_Minh') => DateTime.fromISO(iso).setZone(zone).toFormat('d LLL, HH:mm');
/** A UTC timestamp as a wall-clock time in the given zone. */
export const timeIn = (iso: string, zone = 'Asia/Ho_Chi_Minh') => DateTime.fromISO(iso).setZone(zone).toFormat('HH:mm');
export const whenLong = (iso: string) => `${dayLong(iso)}, ${time(iso)}`;

export function duration(minutes: number): string {
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  if (h === 0) return `${m} min`;
  return m === 0 ? `${h} h` : `${h} h ${m} min`;
}

export function relativeDay(isoDate: string, zone: string): string {
  const today = DateTime.now().setZone(zone).startOf('day');
  const d = DateTime.fromISO(isoDate, { zone });
  const diff = Math.round(d.diff(today, 'days').days);
  if (diff === 0) return 'Today';
  if (diff === 1) return 'Tomorrow';
  return d.toFormat('ccc');
}

export const todayIn = (zone = 'Asia/Ho_Chi_Minh') => DateTime.now().setZone(zone).toISODate()!;

export const STATUS_LABEL: Record<AppointmentStatus, string> = {
  HELD: 'On hold',
  CONFIRMED: 'Confirmed',
  IN_PROGRESS: 'In the workshop',
  COMPLETED: 'Done',
  CANCELLED: 'Cancelled',
  NO_SHOW: 'Missed',
  EXPIRED: 'Hold ran out',
};

export const REQUEST_LABEL: Record<ConsultationStatus, string> = {
  SUBMITTED: 'Waiting for an advisor',
  APPROVED: 'Ready to book',
  REJECTED: 'Not possible',
  BOOKED: 'Booked',
  CANCELLED: 'Withdrawn',
};

export const ROLE_LABEL: Record<Role, string> = {
  CUSTOMER: 'Customer',
  SERVICE_ADVISOR: 'Service advisor',
  TECHNICIAN: 'Technician',
  ADMIN: 'Dealership admin',
  SYSTEM_MANAGER: 'System manager',
  WORKSHOP_CONTROLLER: 'Workshop controller',
  PARTS_STAFF: 'Parts staff',
};

export const PART_ORDER_LABEL: Record<PartOrderStatus, string> = {
  REQUESTED: 'Requested',
  ORDERED: 'Ordered from supplier',
  RECEIVED: 'Received',
  ISSUED: 'Issued',
  CANCELLED: 'Cancelled',
};

export const vnd0 = (amount: number) => vnd.format(amount);

export const WEEKDAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
export const weekdays = (days: number[]) =>
  days.length === 7 ? 'Every day' : days.map((d) => WEEKDAYS[d - 1]).join(', ');

export const vehicleName = (v: { make: string; model: string }) => `${v.make} ${v.model}`;
