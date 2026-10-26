import type { Appointment, Slot } from '../../../api/types';
import { ErrorNote, Loading } from '../../../components/ui';
import { dayShort, time } from '../../../lib/format';
import { HoldTimer } from './HoldTimer';

interface Props {
  slot: Slot;
  diagnosticBay: boolean;
  held: Appointment | null;
  holding: boolean;
  lapsed: boolean;
  alternatives: Slot[] | null;
  error: unknown;
  confirming: boolean;
  onLapse: () => void;
  onHoldAgain: () => void;
  onPickAlternative: (slot: Slot) => void;
  onChangeTime: () => void;
  onConfirm: () => void;
}

/** Step 4: the time is held with a countdown; confirm it, or pick an alternative after a lost race. */
export function ConfirmStep(props: Props) {
  const { slot, held, lapsed, alternatives } = props;
  return (
    <div className="stack">
      <h2>Check and confirm</h2>
      <p className="muted">
        A {props.diagnosticBay ? 'diagnostic bay' : 'bay'} and a technician are kept for you from {time(slot.start)} to {time(slot.end)}. You
        can cancel any time before then.
      </p>

      {props.holding && <Loading what={`Holding ${time(slot.start)} for you`} />}
      {held?.holdExpiresAt && <HoldTimer until={held.holdExpiresAt} onLapse={props.onLapse} />}
      {lapsed && (
        <div className="notice notice-error" role="alert">
          We could only keep {time(slot.start)} for you for a few minutes, and that time has run out.{' '}
          <button className="btn btn-sm" onClick={props.onHoldAgain}>
            Try to hold it again
          </button>
        </div>
      )}

      {alternatives !== null ? (
        <div className="stack">
          <div className="notice notice-error" role="alert">
            Someone booked {time(slot.start)} a moment ago.{' '}
            {alternatives.length > 0 ? 'These are the nearest free times:' : 'There is nothing free nearby; please go back and pick another day.'}
          </div>
          {alternatives.length > 0 && (
            <div className="slots">
              {alternatives.map((s) => (
                <button key={s.start} type="button" className="slot" onClick={() => props.onPickAlternative(s)}>
                  {time(s.start)}
                  <small>{dayShort(s.start)}</small>
                </button>
              ))}
            </div>
          )}
        </div>
      ) : (
        <ErrorNote error={lapsed ? null : props.error} />
      )}

      <div className="nav-buttons">
        <button className="btn" onClick={props.onChangeTime}>
          Change time
        </button>
        <button className="btn btn-primary" disabled={!held || props.confirming || alternatives !== null} onClick={props.onConfirm}>
          {props.confirming ? 'Booking…' : 'Confirm booking'}
        </button>
      </div>
    </div>
  );
}
