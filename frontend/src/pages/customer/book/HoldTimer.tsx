import { useEffect, useState } from 'react';

/** Minutes and seconds left on a hold; tells the page when it runs out. */
export function HoldTimer({ until, onLapse }: { until: string; onLapse: () => void }) {
  const [now, setNow] = useState(() => Date.now());
  const end = Date.parse(until);
  useEffect(() => {
    const t = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(t);
  }, []);
  const left = Math.max(0, Math.round((end - now) / 1000));
  useEffect(() => {
    if (left === 0) onLapse();
  }, [left, onLapse]);
  const mm = Math.floor(left / 60);
  const ss = String(left % 60).padStart(2, '0');
  return (
    <div className={`hold-timer${left < 60 ? ' urgent' : ''}`} role="timer" aria-live="off">
      <span>This time is reserved for you while you check the details. Nobody else can book it.</span>
      <span className="sign" aria-label={`${mm} minutes ${ss} seconds left`}>
        {mm}:{ss}
      </span>
    </div>
  );
}
