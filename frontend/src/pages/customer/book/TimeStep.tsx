import { Link } from 'react-router-dom';
import type { Availability, Dealership, Slot } from '../../../api/types';
import { DayStrip, SlotGrid } from '../../../components/SlotPicker';
import { ErrorNote, Loading } from '../../../components/ui';
import { dayLong, weekdays } from '../../../lib/format';

interface Props {
  /** False when booking an approved request: the dealership is fixed by the request. */
  choosePlace: boolean;
  dealerships: Dealership[];
  dealership: Dealership | null;
  availability: { data?: Availability; error: unknown; isLoading: boolean };
  /** For the "cannot do this job here" message. */
  isCar: boolean;
  date: string | null;
  slot: Slot | null;
  note: string;
  onChooseDealership: (dealershipId: string) => void;
  onPickDay: (date: string) => void;
  onPickSlot: (slot: Slot) => void;
  onNote: (note: string) => void;
  onBack: () => void;
  onHold: (slot: Slot) => void;
}

/** Step 3: where and when. Shows only start times with a free bay and a qualified technician. */
export function TimeStep(props: Props) {
  const { choosePlace, dealerships, dealership, availability, date, slot } = props;
  const a = availability.data;
  return (
    <div className="stack">
      {choosePlace && (
        <>
          <h2>Where?</h2>
          <div className="choices">
            {dealerships.map((d) => (
              <button
                key={d.id}
                type="button"
                className="choice"
                aria-pressed={d.id === dealership?.id}
                onClick={() => props.onChooseDealership(d.id)}
              >
                <span className="radio" aria-hidden="true" />
                <span>
                  <span className="title">{d.name}</span>
                  <span className="sub" style={{ display: 'block' }}>
                    {d.address}
                  </span>
                </span>
                <span className="aside">
                  {d.openingTime}–{d.closingTime}
                  <br />
                  {weekdays(d.openDays)}
                </span>
              </button>
            ))}
          </div>
          <hr className="divider" />
        </>
      )}

      {dealership && (
        <>
          <h2>When?</h2>
          <ErrorNote error={availability.error} />
          {availability.isLoading && <Loading what="Checking free bays and technicians" />}
          {a && !a.offeredHere && (
            <div className="notice notice-info">
              {dealership.name} cannot do this job: it has no {props.isCar ? 'car lift' : 'suitable bay'} or no technician with the right
              skills. Please choose another dealership.
            </div>
          )}
          {a && a.offeredHere && (
            <>
              <DayStrip dates={a.bookableDates} zone={a.timezone} value={date ?? a.date} onChange={props.onPickDay} />
              {a.slots.some((s) => s.available) ? (
                <SlotGrid slots={a.slots} value={slot?.start ?? null} onChange={props.onPickSlot} />
              ) : (
                <div className="empty">
                  <h3>Nothing free on {dayLong(`${a.date}T12:00:00`)}</h3>
                  <p>Every bay or every qualified technician is taken. Try another day above.</p>
                </div>
              )}
            </>
          )}
        </>
      )}
      {slot && (
        <div className="field">
          <label htmlFor="note">Anything the technician should know? (optional)</label>
          <textarea
            id="note"
            className="input"
            maxLength={1000}
            placeholder="For example: the front brake squeaks when it rains."
            value={props.note}
            onChange={(e) => props.onNote(e.target.value)}
          />
        </div>
      )}
      <div className="nav-buttons">
        {choosePlace ? (
          <button className="btn" onClick={props.onBack}>
            Back
          </button>
        ) : (
          <Link className="btn" to="/requests">
            Back
          </Link>
        )}
        <button className="btn btn-primary" disabled={!slot} onClick={() => slot && props.onHold(slot)}>
          Hold this time and review
        </button>
      </div>
    </div>
  );
}
