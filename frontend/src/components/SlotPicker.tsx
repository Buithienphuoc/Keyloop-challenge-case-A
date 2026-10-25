import { DateTime } from 'luxon';
import type { Slot } from '../api/types';
import { relativeDay, time } from '../lib/format';

export function DayStrip({
  dates,
  zone,
  value,
  onChange,
}: {
  dates: string[];
  zone: string;
  value: string;
  onChange: (d: string) => void;
}) {
  return (
    <div className="days" role="group" aria-label="Day">
      {dates.map((d) => {
        const dt = DateTime.fromISO(d, { zone });
        return (
          <button key={d} type="button" className="day" aria-pressed={d === value} onClick={() => onChange(d)}>
            <div className="w">{relativeDay(d, zone)}</div>
            <div className="n">{dt.day}</div>
            <div className="mo">{dt.toFormat('LLL')}</div>
          </button>
        );
      })}
    </div>
  );
}

/** Start times grouped into morning and afternoon. Full times stay visible (struck through) so the day's shape is clear. */
export function SlotGrid({ slots, value, onChange }: { slots: Slot[]; value: string | null; onChange: (s: Slot) => void }) {
  const groups = [
    { label: 'Morning', slots: slots.filter((s) => DateTime.fromISO(s.start, { setZone: true }).hour < 12) },
    { label: 'Afternoon', slots: slots.filter((s) => DateTime.fromISO(s.start, { setZone: true }).hour >= 12) },
  ].filter((g) => g.slots.length > 0);

  return (
    <div>
      {groups.map((g) => (
        <div className="slot-group" key={g.label}>
          <h4>{g.label}</h4>
          <div className="slots" role="group" aria-label={`${g.label} start times`}>
            {g.slots.map((s) => {
              const label = !s.available ? (s.reason === 'FULLY_BOOKED' ? 'Full' : s.reason === 'TOO_FAR_AHEAD' ? 'Not open yet' : 'Too soon') : s.capacity === 1 ? '1 place left' : '';
              return (
                <button
                  key={s.start}
                  type="button"
                  className={`slot${s.available && s.capacity === 1 ? ' few' : ''}`}
                  disabled={!s.available}
                  aria-pressed={value === s.start}
                  aria-label={`${time(s.start)}${label ? `, ${label}` : ''}`}
                  onClick={() => onChange(s)}
                >
                  {time(s.start)}
                  {label && <small>{label}</small>}
                </button>
              );
            })}
          </div>
        </div>
      ))}
      <div className="legend" aria-hidden="true">
        <span className="l-pick">Your choice</span>
        <span className="l-full">Full or too soon</span>
      </div>
    </div>
  );
}
