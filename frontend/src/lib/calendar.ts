import type { Appointment } from '../api/types';

const stamp = (iso: string) => new Date(iso).toISOString().replace(/[-:]/g, '').replace(/\.\d{3}/, '');
const esc = (s: string) => s.replace(/[\\;,]/g, (c) => `\\${c}`).replace(/\n/g, '\\n');

/** Downloads an .ics file so the customer can drop the appointment into any calendar app. */
export function downloadIcs(a: Appointment) {
  const lines = [
    'BEGIN:VCALENDAR',
    'VERSION:2.0',
    'PRODID:-//Service Scheduler//EN',
    'BEGIN:VEVENT',
    `UID:${a.id}@service-scheduler`,
    `DTSTAMP:${stamp(new Date().toISOString())}`,
    `DTSTART:${stamp(a.start)}`,
    `DTEND:${stamp(a.end)}`,
    `SUMMARY:${esc(`${a.service.name} – ${a.vehicle.make} ${a.vehicle.model}`)}`,
    `LOCATION:${esc(`${a.dealership.name}, ${a.dealership.address}`)}`,
    `DESCRIPTION:${esc(`Booking ${a.reference}. Plate ${a.vehicle.plate}.`)}`,
    'BEGIN:VALARM',
    'TRIGGER:-PT2H',
    'ACTION:DISPLAY',
    'DESCRIPTION:Service appointment',
    'END:VALARM',
    'END:VEVENT',
    'END:VCALENDAR',
  ];
  const blob = new Blob([lines.join('\r\n')], { type: 'text/calendar' });
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = `service-${a.reference}.ics`;
  link.click();
  URL.revokeObjectURL(url);
}
