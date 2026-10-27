import { useId } from 'react';
import { useDealerships } from '../api/hooks';

/** '' = the signed-in person's own workshop, 'all' = every workshop, otherwise one workshop's id. */
export type WorkshopScope = string;

/** The `dealershipId` query string for a scope ('' asks for the caller's own workshop). */
export const scopeQuery = (scope: WorkshopScope) => (scope ? `dealershipId=${encodeURIComponent(scope)}` : '');

/** Lets advisors, technicians and admins look at another workshop, or at all of them. */
export function WorkshopFilter({ value, ownId, onChange }: { value: WorkshopScope; ownId?: string | null; onChange: (v: WorkshopScope) => void }) {
  const dealerships = useDealerships();
  const id = useId();
  return (
    <div className="row small" style={{ gap: 8 }}>
      <label htmlFor={id} className="muted">
        Workshop
      </label>
      <select id={id} className="input" style={{ width: 'auto', minHeight: 36, padding: '4px 10px' }} value={value} onChange={(e) => onChange(e.target.value)}>
        <option value="">My workshop</option>
        <option value="all">All workshops</option>
        {(dealerships.data ?? [])
          .filter((d) => d.id !== ownId)
          .map((d) => (
            <option key={d.id} value={d.id}>
              {d.name}
            </option>
          ))}
      </select>
    </div>
  );
}
