-- Unified Service Scheduler: initial schema.
--
-- The single most important rule of the system lives here, not in application code:
-- a service bay, a technician and a vehicle can never be booked for overlapping
-- time ranges. The EXCLUDE constraints at the bottom of the appointments table
-- enforce that rule even when many requests race for the same slot.

CREATE EXTENSION IF NOT EXISTS btree_gist;

-- ---------------------------------------------------------------------------
-- Reference data
-- ---------------------------------------------------------------------------

CREATE TABLE skills (
  code        text PRIMARY KEY,
  name        text NOT NULL
);

CREATE TABLE bay_types (
  code          text PRIMARY KEY,
  name          text NOT NULL,
  -- which kinds of vehicle can be worked on in this type of bay
  vehicle_types text[] NOT NULL
);

-- ---------------------------------------------------------------------------
-- Dealerships (tenants)
-- ---------------------------------------------------------------------------

CREATE TABLE dealerships (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  code             text NOT NULL UNIQUE,
  name             text NOT NULL,
  address          text NOT NULL,
  phone            text,
  timezone         text NOT NULL,
  opening_time     time NOT NULL,
  closing_time     time NOT NULL,
  -- ISO weekdays the workshop is open: 1 = Monday … 7 = Sunday
  open_days        smallint[] NOT NULL,
  slot_minutes     smallint NOT NULL DEFAULT 30 CHECK (slot_minutes IN (15, 30, 60)),
  min_lead_minutes integer  NOT NULL DEFAULT 60 CHECK (min_lead_minutes >= 0),
  max_days_ahead   integer  NOT NULL DEFAULT 60 CHECK (max_days_ahead > 0),
  created_at       timestamptz NOT NULL DEFAULT now(),
  CHECK (opening_time < closing_time)
);

-- ---------------------------------------------------------------------------
-- Identity
-- ---------------------------------------------------------------------------

CREATE TABLE users (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  email          text NOT NULL,
  password_hash  text NOT NULL,
  full_name      text NOT NULL,
  phone          text,
  role           text NOT NULL CHECK (role IN ('CUSTOMER', 'SERVICE_ADVISOR', 'TECHNICIAN', 'ADMIN', 'SYSTEM_MANAGER')),
  dealership_id  uuid REFERENCES dealerships (id),
  is_active      boolean NOT NULL DEFAULT true,
  created_at     timestamptz NOT NULL DEFAULT now(),
  -- staff belong to exactly one dealership; customers and system managers to none
  CHECK (
    (role IN ('SERVICE_ADVISOR', 'TECHNICIAN', 'ADMIN') AND dealership_id IS NOT NULL)
    OR (role IN ('CUSTOMER', 'SYSTEM_MANAGER') AND dealership_id IS NULL)
  )
);
CREATE UNIQUE INDEX users_email_key ON users (lower(email));

CREATE TABLE sessions (
  token_hash  text PRIMARY KEY,           -- sha256 of the bearer token; the token itself is never stored
  user_id     uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  created_at  timestamptz NOT NULL DEFAULT now(),
  expires_at  timestamptz NOT NULL,
  revoked_at  timestamptz
);
CREATE INDEX sessions_user_idx ON sessions (user_id);

-- ---------------------------------------------------------------------------
-- Workshop resources
-- ---------------------------------------------------------------------------

CREATE TABLE service_bays (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  dealership_id  uuid NOT NULL REFERENCES dealerships (id),
  name           text NOT NULL,
  bay_type       text NOT NULL REFERENCES bay_types (code),
  is_active      boolean NOT NULL DEFAULT true,
  UNIQUE (dealership_id, name),
  UNIQUE (id, dealership_id)              -- target for composite FKs below
);

CREATE TABLE technicians (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  dealership_id  uuid NOT NULL REFERENCES dealerships (id),
  user_id        uuid UNIQUE REFERENCES users (id),
  display_name   text NOT NULL,
  shift_start    time NOT NULL,
  shift_end      time NOT NULL,
  work_days      smallint[] NOT NULL,
  is_active      boolean NOT NULL DEFAULT true,
  UNIQUE (id, dealership_id),
  CHECK (shift_start < shift_end)
);

CREATE TABLE technician_skills (
  technician_id  uuid NOT NULL REFERENCES technicians (id) ON DELETE CASCADE,
  skill_code     text NOT NULL REFERENCES skills (code),
  PRIMARY KEY (technician_id, skill_code)
);

-- ---------------------------------------------------------------------------
-- Catalogue
-- ---------------------------------------------------------------------------

CREATE TABLE service_packages (
  id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  code              text NOT NULL UNIQUE,
  name              text NOT NULL,
  description       text NOT NULL,
  vehicle_type      text NOT NULL CHECK (vehicle_type IN ('MOTORBIKE', 'CAR')),
  duration_minutes  integer NOT NULL CHECK (duration_minutes > 0 AND duration_minutes % 15 = 0),
  required_skill    text NOT NULL REFERENCES skills (code),
  bay_type          text NOT NULL REFERENCES bay_types (code),
  price_amount      integer NOT NULL CHECK (price_amount >= 0),
  currency          text NOT NULL DEFAULT 'VND',
  checklist         text[] NOT NULL,
  is_recommended    boolean NOT NULL DEFAULT false,
  sort_order        integer NOT NULL DEFAULT 0,
  is_active         boolean NOT NULL DEFAULT true
);

-- ---------------------------------------------------------------------------
-- Customers' vehicles
-- ---------------------------------------------------------------------------

CREATE TABLE vehicles (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_id      uuid NOT NULL REFERENCES users (id),
  vehicle_type  text NOT NULL CHECK (vehicle_type IN ('MOTORBIKE', 'CAR')),
  make          text NOT NULL,
  model         text NOT NULL,
  year          smallint CHECK (year BETWEEN 1950 AND 2100),
  plate         text NOT NULL,
  vin           text CHECK (vin IS NULL OR vin ~ '^[A-HJ-NPR-Z0-9]{17}$'),
  mileage_km    integer CHECK (mileage_km >= 0),
  created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX vehicles_owner_plate_key ON vehicles (owner_id, upper(plate));

-- ---------------------------------------------------------------------------
-- Consultation requests ("check on request" and "customisation")
-- ---------------------------------------------------------------------------

CREATE TABLE consultation_requests (
  id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  reference         text NOT NULL UNIQUE,
  customer_id       uuid NOT NULL REFERENCES users (id),
  vehicle_id        uuid NOT NULL REFERENCES vehicles (id),
  dealership_id     uuid NOT NULL REFERENCES dealerships (id),
  kind              text NOT NULL CHECK (kind IN ('INSPECTION', 'MODIFICATION')),
  areas             text[] NOT NULL,
  description       text NOT NULL,
  status            text NOT NULL CHECK (status IN ('SUBMITTED', 'APPROVED', 'REJECTED', 'BOOKED', 'CANCELLED')),
  advisor_id        uuid REFERENCES users (id),
  advisor_note      text,
  duration_minutes  integer CHECK (duration_minutes > 0 AND duration_minutes % 15 = 0),
  required_skill    text REFERENCES skills (code),
  bay_type          text REFERENCES bay_types (code),
  estimated_price   integer CHECK (estimated_price >= 0),
  created_at        timestamptz NOT NULL DEFAULT now(),
  reviewed_at       timestamptz,
  -- an approved request must carry everything the scheduler needs to find a slot
  CHECK (
    status NOT IN ('APPROVED', 'BOOKED')
    OR (duration_minutes IS NOT NULL AND required_skill IS NOT NULL AND bay_type IS NOT NULL)
  )
);
CREATE INDEX consultation_dealership_status_idx ON consultation_requests (dealership_id, status);

-- ---------------------------------------------------------------------------
-- Appointments
-- ---------------------------------------------------------------------------

CREATE TABLE appointments (
  id                       uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  reference                text NOT NULL UNIQUE,
  dealership_id            uuid NOT NULL REFERENCES dealerships (id),
  customer_id              uuid NOT NULL REFERENCES users (id),
  vehicle_id               uuid NOT NULL REFERENCES vehicles (id),
  service_package_id       uuid REFERENCES service_packages (id),
  consultation_request_id  uuid REFERENCES consultation_requests (id),
  bay_id                   uuid NOT NULL,
  technician_id            uuid NOT NULL,
  period                   tstzrange NOT NULL,
  status                   text NOT NULL CHECK (status IN ('CONFIRMED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED', 'NO_SHOW')),
  customer_note            text,
  idempotency_key          text,
  created_by               uuid NOT NULL REFERENCES users (id),
  created_at               timestamptz NOT NULL DEFAULT now(),
  updated_at               timestamptz NOT NULL DEFAULT now(),
  cancel_reason            text,

  -- the bay and the technician must belong to the same dealership as the appointment
  FOREIGN KEY (bay_id, dealership_id)        REFERENCES service_bays (id, dealership_id),
  FOREIGN KEY (technician_id, dealership_id) REFERENCES technicians (id, dealership_id),

  -- an appointment is either for a catalogue package or for an approved consultation
  CHECK ((service_package_id IS NULL) <> (consultation_request_id IS NULL)),
  CHECK (NOT isempty(period) AND lower_inc(period) AND NOT upper_inc(period)),

  -- Core invariants: no double booking. Ranges are half-open [start, end), so
  -- 09:00-10:00 and 10:00-11:00 do not overlap. Only appointments that still
  -- hold the resource take part.
  CONSTRAINT appointments_no_bay_overlap EXCLUDE USING gist
    (bay_id WITH =, period WITH &&) WHERE (status IN ('CONFIRMED', 'IN_PROGRESS')),
  CONSTRAINT appointments_no_technician_overlap EXCLUDE USING gist
    (technician_id WITH =, period WITH &&) WHERE (status IN ('CONFIRMED', 'IN_PROGRESS')),
  CONSTRAINT appointments_no_vehicle_overlap EXCLUDE USING gist
    (vehicle_id WITH =, period WITH &&) WHERE (status IN ('CONFIRMED', 'IN_PROGRESS'))
);
CREATE UNIQUE INDEX appointments_idempotency_key ON appointments (customer_id, idempotency_key)
  WHERE idempotency_key IS NOT NULL;
-- one live booking per consultation request
CREATE UNIQUE INDEX appointments_one_live_per_consultation ON appointments (consultation_request_id)
  WHERE consultation_request_id IS NOT NULL AND status IN ('CONFIRMED', 'IN_PROGRESS', 'COMPLETED');
CREATE INDEX appointments_dealership_period_idx ON appointments USING gist (dealership_id, period);
CREATE INDEX appointments_customer_idx ON appointments (customer_id, created_at DESC);

-- Audit trail of every status change (who, when, from what, to what).
CREATE TABLE appointment_events (
  id              bigserial PRIMARY KEY,
  appointment_id  uuid NOT NULL REFERENCES appointments (id),
  from_status     text,
  to_status       text NOT NULL,
  actor_id        uuid NOT NULL REFERENCES users (id),
  note            text,
  at              timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX appointment_events_appointment_idx ON appointment_events (appointment_id, at);
