import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { post } from '../../api/client';
import { keys, useDealerships, useReferenceData, useVehicles } from '../../api/hooks';
import type { Consultation, ConsultationKind } from '../../api/types';
import { ErrorNote, Loading, PageHead, Plate } from '../../components/ui';
import { VehicleForm } from '../../components/VehicleForm';
import { vehicleName } from '../../lib/format';

const COPY: Record<ConsultationKind, { title: string; intro: string; areas: string; placeholder: string }> = {
  INSPECTION: {
    title: 'Check something specific',
    intro: 'Tell us what you notice. An advisor replies with what it needs, how long it takes and a price, then you pick a time.',
    areas: 'Where is the problem?',
    placeholder: 'For example: a knocking noise from the engine above 60 km/h, and the oil light flickers when I brake.',
  },
  MODIFICATION: {
    title: 'Customise my vehicle',
    intro: 'Tell us what you have in mind. An advisor checks it is safe and legal, suggests parts, and gives you a quote before you book.',
    areas: 'What would you like to change?',
    placeholder: 'For example: a sportier exhaust that is still street-legal, and brighter LED headlights.',
  },
};

export function ConsultPage() {
  const [params] = useSearchParams();
  const kind = (params.get('kind') === 'MODIFICATION' ? 'MODIFICATION' : 'INSPECTION') as ConsultationKind;
  const copy = COPY[kind];
  const navigate = useNavigate();
  const qc = useQueryClient();

  const vehicles = useVehicles();
  const dealerships = useDealerships();
  const ref = useReferenceData();
  const [vehicleId, setVehicleId] = useState<string | null>(params.get('vehicle'));
  const [dealershipId, setDealershipId] = useState<string>('');
  const [areas, setAreas] = useState<string[]>([]);
  const [description, setDescription] = useState('');
  const [adding, setAdding] = useState(false);

  const chosenVehicle = vehicleId ?? (vehicles.data?.length === 1 ? vehicles.data[0]!.id : null);

  const submit = useMutation({
    mutationFn: () =>
      post<Consultation>('/api/consultations', {
        kind,
        vehicleId: chosenVehicle,
        dealershipId: dealershipId || dealerships.data?.[0]?.id,
        areas,
        description,
      }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: keys.myRequests });
      navigate('/requests?sent=1');
    },
  });

  if (vehicles.isLoading || dealerships.isLoading || ref.isLoading) return <Loading />;

  const toggle = (code: string) => setAreas((a) => (a.includes(code) ? a.filter((x) => x !== code) : [...a, code]));
  const onSubmit = (e: FormEvent) => {
    e.preventDefault();
    submit.mutate();
  };

  return (
    <div className="page page-narrow">
      <PageHead title={copy.title}>{copy.intro}</PageHead>
      <div className="panel">
        {vehicles.data?.length === 0 || adding ? (
          <div className="stack">
            <h2>First, add your vehicle</h2>
            <VehicleForm
              onAdded={(v) => {
                setVehicleId(v.id);
                setAdding(false);
              }}
              onCancel={vehicles.data?.length ? () => setAdding(false) : undefined}
            />
          </div>
        ) : (
          <form className="stack-lg" onSubmit={onSubmit}>
            <ErrorNote error={submit.error} />
            <div className="field">
              <span className="label">Vehicle</span>
              <div className="choices">
                {vehicles.data?.map((v) => (
                  <button key={v.id} type="button" className="choice" aria-pressed={v.id === chosenVehicle} onClick={() => setVehicleId(v.id)}>
                    <span className="radio" aria-hidden="true" />
                    <span className="title">{vehicleName(v)}</span>
                    <span className="aside">
                      <Plate text={v.plate} />
                    </span>
                  </button>
                ))}
              </div>
              <button type="button" className="btn btn-ghost" style={{ alignSelf: 'flex-start' }} onClick={() => setAdding(true)}>
                Add another vehicle
              </button>
            </div>

            <fieldset className="field fieldset">
              <legend className="label" style={{ marginBottom: 8 }}>
                {copy.areas}
              </legend>
              <div className="checks">
                {ref.data?.inspectionAreas.map((a) => (
                  <label className="check" key={a.code}>
                    <input type="checkbox" checked={areas.includes(a.code)} onChange={() => toggle(a.code)} />
                    <span>{a.name}</span>
                  </label>
                ))}
              </div>
            </fieldset>

            <div className="field">
              <label htmlFor="desc">Describe it in your own words</label>
              <textarea
                id="desc"
                className="input"
                required
                minLength={10}
                maxLength={2000}
                placeholder={copy.placeholder}
                value={description}
                onChange={(e) => setDescription(e.target.value)}
              />
              <span className="hint">When it happens, since when, and anything you have already tried.</span>
            </div>

            <div className="field">
              <label htmlFor="dealer">Which dealership?</label>
              <select id="dealer" className="input" value={dealershipId || dealerships.data?.[0]?.id} onChange={(e) => setDealershipId(e.target.value)}>
                {dealerships.data?.map((d) => (
                  <option key={d.id} value={d.id}>
                    {d.name}
                  </option>
                ))}
              </select>
            </div>

            <div className="nav-buttons">
              <Link className="btn" to="/">
                Back
              </Link>
              <button className="btn btn-primary" disabled={submit.isPending || !chosenVehicle || areas.length === 0 || description.trim().length < 10}>
                {submit.isPending ? 'Sending…' : 'Send to an advisor'}
              </button>
            </div>
          </form>
        )}
      </div>
    </div>
  );
}
