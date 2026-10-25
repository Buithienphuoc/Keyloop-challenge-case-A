import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { post } from '../api/client';
import { keys } from '../api/hooks';
import type { Vehicle, VehicleType } from '../api/types';
import { ErrorNote } from './ui';

/** Add a vehicle. Used on its own page and inline inside the booking flow. */
export function VehicleForm({ onAdded, onCancel }: { onAdded: (v: Vehicle) => void; onCancel?: () => void }) {
  const qc = useQueryClient();
  const [form, setForm] = useState({ vehicleType: 'MOTORBIKE' as VehicleType, make: '', model: '', year: '', plate: '', mileageKm: '', vin: '' });
  const set = (k: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) =>
    setForm({ ...form, [k]: e.target.value });

  const add = useMutation({
    mutationFn: () =>
      post<Vehicle>('/api/me/vehicles', {
        vehicleType: form.vehicleType,
        make: form.make,
        model: form.model,
        plate: form.plate,
        year: form.year ? Number(form.year) : undefined,
        mileageKm: form.mileageKm ? Number(form.mileageKm) : undefined,
        vin: form.vin || undefined,
      }),
    onSuccess: (v) => {
      qc.invalidateQueries({ queryKey: keys.vehicles });
      onAdded(v);
    },
  });

  const submit = (e: FormEvent) => {
    e.preventDefault();
    add.mutate();
  };

  return (
    <form className="stack" onSubmit={submit}>
      <ErrorNote error={add.error} />
      <div className="field">
        <span className="label">Type</span>
        <div className="checks" role="radiogroup">
          {(['MOTORBIKE', 'CAR'] as const).map((t) => (
            <label className="check" key={t}>
              <input type="radio" name="vehicleType" value={t} checked={form.vehicleType === t} onChange={set('vehicleType')} />
              <span>{t === 'MOTORBIKE' ? 'Motorbike' : 'Car'}</span>
            </label>
          ))}
        </div>
      </div>
      <div className="grid-2">
        <div className="field">
          <label htmlFor="v-make">Make</label>
          <input id="v-make" className="input" required placeholder="Honda" value={form.make} onChange={set('make')} />
        </div>
        <div className="field">
          <label htmlFor="v-model">Model</label>
          <input id="v-model" className="input" required placeholder="Air Blade 160" value={form.model} onChange={set('model')} />
        </div>
        <div className="field">
          <label htmlFor="v-plate">Plate number</label>
          <input id="v-plate" className="input" required placeholder="59-X1 234.56" value={form.plate} onChange={set('plate')} />
        </div>
        <div className="field">
          <label htmlFor="v-year">Year (optional)</label>
          <input id="v-year" className="input" inputMode="numeric" pattern="[0-9]{4}" placeholder="2023" value={form.year} onChange={set('year')} />
        </div>
        <div className="field">
          <label htmlFor="v-km">Mileage in km (optional)</label>
          <input id="v-km" className="input" inputMode="numeric" pattern="[0-9]*" value={form.mileageKm} onChange={set('mileageKm')} />
        </div>
        <div className="field">
          <label htmlFor="v-vin">VIN (optional)</label>
          <input id="v-vin" className="input" maxLength={17} value={form.vin} onChange={set('vin')} />
          <span className="hint">17 characters, on your registration papers.</span>
        </div>
      </div>
      <div className="row">
        <button className="btn btn-primary" disabled={add.isPending}>
          {add.isPending ? 'Adding…' : 'Add vehicle'}
        </button>
        {onCancel && (
          <button type="button" className="btn btn-ghost" onClick={onCancel}>
            Cancel
          </button>
        )}
      </div>
    </form>
  );
}
