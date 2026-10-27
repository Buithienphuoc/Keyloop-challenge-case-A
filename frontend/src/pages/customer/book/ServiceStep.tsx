import type { ServicePackage } from '../../../api/types';
import { duration, money } from '../../../lib/format';

interface Props {
  packages: ServicePackage[];
  packageId: string | null;
  /** A known package is chosen, so the time step can open. */
  ready: boolean;
  onChoose: (packageId: string) => void;
  onBack: () => void;
  onNext: () => void;
}

/** Step 2: pick a maintenance package that fits the vehicle. */
export function ServiceStep({ packages, packageId, ready, onChoose, onBack, onNext }: Props) {
  return (
    <div className="stack">
      <div className="spread">
        <h2>What should we do?</h2>
      </div>
      <div className="choices">
        {packages.map((p) => (
          <button key={p.id} type="button" className="choice" aria-pressed={p.id === packageId} onClick={() => onChoose(p.id)}>
            <span className="radio" aria-hidden="true" />
            <span>
              <span className="title">
                {p.name}
                {p.isRecommended && <span className="tag">Recommended</span>}
              </span>
              <span className="sub" style={{ display: 'block' }}>
                {p.description}
              </span>
              {p.id === packageId && (
                <ul className="checklist" aria-label="What is included">
                  {p.checklist.map((c) => (
                    <li key={c}>{c}</li>
                  ))}
                </ul>
              )}
            </span>
            <span className="aside">
              <span className="price">{money(p.price)}</span>
              <br />
              {duration(p.durationMinutes)}
            </span>
          </button>
        ))}
      </div>
      <div className="nav-buttons">
        <button className="btn" onClick={onBack}>
          Back
        </button>
        <button className="btn btn-primary" disabled={!ready} onClick={onNext}>
          Choose a time
        </button>
      </div>
    </div>
  );
}
