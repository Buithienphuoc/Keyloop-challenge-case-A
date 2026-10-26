import type { Slot } from '../../../api/types';

export type Step = 'vehicle' | 'service' | 'time' | 'confirm';

/** What the customer has chosen so far. Server-side state (the hold, the booking) lives in useHold. */
export interface WizardState {
  step: Step;
  vehicleId: string | null;
  packageId: string | null;
  dealershipId: string | null;
  /** The day shown in the time step; null until availability has answered. */
  date: string | null;
  slot: Slot | null;
  note: string;
  addingVehicle: boolean;
  /** While on, the page walks forward to the first day that still has a free time. */
  autoPick: boolean;
}

export type WizardAction =
  | { type: 'goTo'; step: Step }
  | { type: 'chooseVehicle'; vehicleId: string }
  | { type: 'autoChooseOnlyVehicle'; vehicleId: string }
  | { type: 'setAddingVehicle'; adding: boolean }
  | { type: 'choosePackage'; packageId: string }
  | { type: 'chooseDealership'; dealershipId: string }
  | { type: 'fixedByRequest'; vehicleId: string; dealershipId: string }
  | { type: 'autoPickDay'; date: string; settled: boolean }
  | { type: 'pickDay'; date: string }
  | { type: 'pickSlot'; slot: Slot }
  | { type: 'setNote'; note: string };

export function initialWizard(params: URLSearchParams, rememberedDealership: string | null): WizardState {
  const requestId = params.get('request');
  const vehicleId = params.get('vehicle');
  return {
    step: requestId ? 'time' : vehicleId ? 'service' : 'vehicle',
    vehicleId,
    packageId: params.get('package'),
    dealershipId: rememberedDealership,
    date: null,
    slot: null,
    note: '',
    addingVehicle: false,
    autoPick: true,
  };
}

/** A new service or place means a new search: forget the day and slot, and find the first free day again. */
const newSearch = { date: null, slot: null, autoPick: true } as const;

export function wizardReducer(state: WizardState, action: WizardAction): WizardState {
  switch (action.type) {
    case 'goTo':
      return { ...state, step: action.step };
    case 'chooseVehicle':
      // Another vehicle may need other packages, so the package choice is dropped.
      return {
        ...state,
        vehicleId: action.vehicleId,
        packageId: action.vehicleId === state.vehicleId ? state.packageId : null,
        addingVehicle: false,
        step: 'service',
      };
    case 'autoChooseOnlyVehicle':
      return { ...state, vehicleId: action.vehicleId, step: 'service' };
    case 'setAddingVehicle':
      return { ...state, addingVehicle: action.adding };
    case 'choosePackage':
      return { ...state, packageId: action.packageId, ...newSearch };
    case 'chooseDealership':
      return { ...state, dealershipId: action.dealershipId, ...newSearch };
    case 'fixedByRequest':
      return { ...state, vehicleId: action.vehicleId, dealershipId: action.dealershipId };
    case 'autoPickDay':
      return { ...state, date: action.date, autoPick: !action.settled };
    case 'pickDay':
      return { ...state, date: action.date, slot: null, autoPick: false };
    case 'pickSlot':
      return { ...state, slot: action.slot };
    case 'setNote':
      return { ...state, note: action.note };
  }
}
