import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useVehicles } from '../../api/hooks';
import { Empty, ErrorNote, Loading, PageHead, Plate } from '../../components/ui';
import { VehicleForm } from '../../components/VehicleForm';
import { vehicleName } from '../../lib/format';

export function VehiclesPage() {
  const vehicles = useVehicles();
  const [adding, setAdding] = useState(false);

  return (
    <div className="page page-narrow">
      <PageHead
        title="My vehicles"
        actions={
          !adding && (
            <button className="btn" onClick={() => setAdding(true)}>
              Add a vehicle
            </button>
          )
        }
      />
      <ErrorNote error={vehicles.error} />
      {vehicles.isLoading && <Loading />}
      {adding && (
        <div className="panel" style={{ marginBottom: 20 }}>
          <VehicleForm onAdded={() => setAdding(false)} onCancel={() => setAdding(false)} />
        </div>
      )}
      {vehicles.data?.length === 0 && !adding && (
        <Empty title="No vehicles yet" action={<button className="btn btn-primary" onClick={() => setAdding(true)}>Add your first vehicle</button>}>
          Add your motorbike or car once and booking takes only a few taps.
        </Empty>
      )}
      <div className="list">
        {vehicles.data?.map((v) => (
          <div className="item" key={v.id}>
            <Plate text={v.plate} />
            <div>
              <strong>{vehicleName(v)}</strong>
              <div className="meta">
                {v.vehicleType === 'MOTORBIKE' ? 'Motorbike' : 'Car'}
                {v.year ? ` · ${v.year}` : ''}
                {v.mileageKm !== null ? ` · ${v.mileageKm.toLocaleString('en-GB')} km` : ''}
                {v.vin ? ` · VIN ${v.vin}` : ''}
              </div>
            </div>
            <div className="actions">
              <Link className="btn btn-sm btn-primary" to={`/book?vehicle=${v.id}`}>
                Book a service
              </Link>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
