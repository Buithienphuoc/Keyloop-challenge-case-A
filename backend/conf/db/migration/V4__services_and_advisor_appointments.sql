-- Service jobs and advisor appointments are two different things:
--
--   Service      the work on the vehicle: customer, time, workshop, technician.
--                Stored in the existing `appointments` table (bay + technician + period).
--   Appointment  a meeting with a service advisor: customer, time, workshop, the message to
--                be advised, advisor. Stored in the new `advisor_appointments` table.
--
-- Additive only: no column is dropped or renamed and every existing row keeps its data.

-- ---------------------------------------------------------------------------
-- 1. Who has taken a service job
-- ---------------------------------------------------------------------------
--
-- Booking still reserves a qualified technician who is free for the whole job, so the
-- no-double-booking constraints keep working. That reservation is only provisional until a
-- technician takes the job themselves or an advisor assigns one: until then the job is
-- "open" and shows on the technicians' list of jobs to take.

ALTER TABLE appointments ADD COLUMN technician_assigned_at timestamptz;
ALTER TABLE appointments ADD COLUMN technician_assigned_by uuid REFERENCES users (id);

-- Jobs booked before this release already had their technician: keep them assigned.
UPDATE appointments SET technician_assigned_at = created_at WHERE technician_assigned_at IS NULL;

-- The "open jobs" list: confirmed jobs of a dealership that nobody has taken yet.
CREATE INDEX appointments_open_services_idx ON appointments (dealership_id, lower(period))
  WHERE technician_assigned_at IS NULL AND status = 'CONFIRMED';

-- ---------------------------------------------------------------------------
-- 2. Advisor appointments
-- ---------------------------------------------------------------------------

CREATE TABLE advisor_appointments (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  reference      text NOT NULL UNIQUE,
  dealership_id  uuid NOT NULL REFERENCES dealerships (id),
  customer_id    uuid NOT NULL REFERENCES users (id),
  starts_at      timestamptz NOT NULL,
  -- what the customer wants to be advised about
  message        text NOT NULL CHECK (length(message) BETWEEN 1 AND 2000),
  -- empty until an advisor takes the appointment
  advisor_id     uuid REFERENCES users (id),
  status         text NOT NULL CHECK (status IN ('SCHEDULED', 'DONE', 'CANCELLED')),
  cancel_reason  text,
  created_by     uuid NOT NULL REFERENCES users (id),
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now(),
  -- an appointment can only be done by the advisor who held it
  CHECK (status <> 'DONE' OR advisor_id IS NOT NULL)
);

-- An advisor cannot hold two meetings that start at the same moment.
CREATE UNIQUE INDEX advisor_appointments_advisor_slot ON advisor_appointments (advisor_id, starts_at)
  WHERE advisor_id IS NOT NULL AND status = 'SCHEDULED';
CREATE INDEX advisor_appointments_dealership_idx ON advisor_appointments (dealership_id, starts_at);
CREATE INDEX advisor_appointments_customer_idx ON advisor_appointments (customer_id, starts_at DESC);
