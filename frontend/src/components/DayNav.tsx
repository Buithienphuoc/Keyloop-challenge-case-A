import { DateTime } from 'luxon';
import { todayIn } from '../lib/format';

/** Previous / today / next day control, with a native date input for jumping. */
export function DayNav({ date, zone, onChange }: { date: string; zone: string; onChange: (d: string) => void }) {
  const shift = (days: number) => onChange(DateTime.fromISO(date, { zone }).plus({ days }).toISODate()!);
  const today = todayIn(zone);
  return (
    <div className="row" role="group" aria-label="Day">
      <button className="btn btn-sm" onClick={() => shift(-1)} aria-label="Previous day">
        ‹
      </button>
      <input
        className="input"
        style={{ width: 'auto', minHeight: 36, padding: '4px 10px' }}
        type="date"
        value={date}
        onChange={(e) => e.target.value && onChange(e.target.value)}
        aria-label="Date"
      />
      <button className="btn btn-sm" onClick={() => shift(1)} aria-label="Next day">
        ›
      </button>
      {date !== today && (
        <button className="btn btn-sm btn-ghost" onClick={() => onChange(today)}>
          Today
        </button>
      )}
    </div>
  );
}
