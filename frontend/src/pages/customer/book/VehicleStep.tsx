import type { Vehicle } from '../../../api/types';
import { Plate } from '../../../components/ui';
import { VehicleForm } from '../../../components/VehicleForm';
import { vehicleName } from '../../../lib/format';

interface Props {
  vehicles: Vehicle[];
  vehicleId: string | null;
  adding: boolean;
  onAdding: (adding: boolean) => void;
  onChoose: (vehicleId: string) => void;
}

/** Step 1: pick one of the customer's vehicles, or add one. */
export function VehicleStep({ vehicles, vehicleId, adding, onAdding, onChoose }: Props) {
  return (
    <div className="stack">
      <h2>Which vehicle?</h2>
      {adding || vehicles.length === 0 ? (
        <VehicleForm onAdded={(v) => onChoose(v.id)} onCancel={vehicles.length ? () => onAdding(false) : undefined} />
      ) : (
        <>
          <div className="choices">
            {vehicles.map((v) => (
              <button key={v.id} type="button" className="choice" aria-pressed={v.id === vehicleId} onClick={() => onChoose(v.id)}>
                <span className="radio" aria-hidden="true" />
                <span>
                  <span className="title">{vehicleName(v)}</span>
                  <span className="sub">
                    {' '}
                    · {v.vehicleType === 'MOTORBIKE' ? 'Motorbike' : 'Car'}
                    {v.year ? `, ${v.year}` : ''}
                  </span>
                </span>
                <span className="aside">
                  <Plate text={v.plate} />
                </span>
              </button>
            ))}
          </div>
          <button className="btn btn-ghost" onClick={() => onAdding(true)}>
            Add another vehicle
          </button>
        </>
      )}
    </div>
  );
}
