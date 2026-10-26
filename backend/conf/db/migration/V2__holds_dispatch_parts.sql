-- Unified Service Scheduler: features added after the first release.
--
--  1. Temporary holds (HELD → CONFIRMED): a customer's chosen slot is reserved for a few
--     minutes while they review and confirm, so nobody else can take it in the meantime.
--  2. New staff roles: workshop controller (moves jobs between bays and technicians) and
--     parts staff (stock and parts orders).
--  3. Replacement parts: a per-dealership stock list and parts orders, optionally linked
--     to an appointment.

-- ---------------------------------------------------------------------------
-- 1. Holds
-- ---------------------------------------------------------------------------

ALTER TABLE appointments ADD COLUMN hold_expires_at timestamptz;

ALTER TABLE appointments DROP CONSTRAINT appointments_status_check;
ALTER TABLE appointments ADD CONSTRAINT appointments_status_check
  CHECK (status IN ('HELD', 'CONFIRMED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED', 'NO_SHOW', 'EXPIRED'));

-- A hold always says when it runs out.
ALTER TABLE appointments ADD CONSTRAINT appointments_hold_has_expiry
  CHECK (status <> 'HELD' OR hold_expires_at IS NOT NULL);

-- A hold takes its bay, technician and vehicle exactly like a confirmed booking, so the
-- no-double-booking constraints now include HELD. An expired hold is moved to EXPIRED
-- (by the booking flow before it allocates, and by a background sweeper), which frees it.
ALTER TABLE appointments DROP CONSTRAINT appointments_no_bay_overlap;
ALTER TABLE appointments DROP CONSTRAINT appointments_no_technician_overlap;
ALTER TABLE appointments DROP CONSTRAINT appointments_no_vehicle_overlap;
ALTER TABLE appointments ADD CONSTRAINT appointments_no_bay_overlap EXCLUDE USING gist
  (bay_id WITH =, period WITH &&) WHERE (status IN ('HELD', 'CONFIRMED', 'IN_PROGRESS'));
ALTER TABLE appointments ADD CONSTRAINT appointments_no_technician_overlap EXCLUDE USING gist
  (technician_id WITH =, period WITH &&) WHERE (status IN ('HELD', 'CONFIRMED', 'IN_PROGRESS'));
ALTER TABLE appointments ADD CONSTRAINT appointments_no_vehicle_overlap EXCLUDE USING gist
  (vehicle_id WITH =, period WITH &&) WHERE (status IN ('HELD', 'CONFIRMED', 'IN_PROGRESS'));

DROP INDEX appointments_one_live_per_consultation;
CREATE UNIQUE INDEX appointments_one_live_per_consultation ON appointments (consultation_request_id)
  WHERE consultation_request_id IS NOT NULL AND status IN ('HELD', 'CONFIRMED', 'IN_PROGRESS', 'COMPLETED');

-- The sweeper looks for holds that have run out.
CREATE INDEX appointments_held_expiry_idx ON appointments (hold_expires_at) WHERE status = 'HELD';

-- ---------------------------------------------------------------------------
-- 2. Staff roles
-- ---------------------------------------------------------------------------

ALTER TABLE users DROP CONSTRAINT users_role_check;
ALTER TABLE users ADD CONSTRAINT users_role_check CHECK (role IN (
  'CUSTOMER', 'SERVICE_ADVISOR', 'TECHNICIAN', 'ADMIN', 'SYSTEM_MANAGER', 'WORKSHOP_CONTROLLER', 'PARTS_STAFF'));

ALTER TABLE users DROP CONSTRAINT users_check;
ALTER TABLE users ADD CONSTRAINT users_dealership_by_role CHECK (
  (role IN ('SERVICE_ADVISOR', 'TECHNICIAN', 'ADMIN', 'WORKSHOP_CONTROLLER', 'PARTS_STAFF') AND dealership_id IS NOT NULL)
  OR (role IN ('CUSTOMER', 'SYSTEM_MANAGER') AND dealership_id IS NULL)
);

-- ---------------------------------------------------------------------------
-- 3. Replacement parts
-- ---------------------------------------------------------------------------

CREATE TABLE parts (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  dealership_id  uuid NOT NULL REFERENCES dealerships (id),
  sku            text NOT NULL,
  name           text NOT NULL,
  unit_price     integer NOT NULL CHECK (unit_price >= 0),
  currency       text NOT NULL DEFAULT 'VND',
  -- Never negative: issuing more than is on the shelf fails here, even when two people
  -- issue the last unit at the same moment.
  stock_qty      integer NOT NULL DEFAULT 0 CHECK (stock_qty >= 0),
  is_active      boolean NOT NULL DEFAULT true,
  created_at     timestamptz NOT NULL DEFAULT now(),
  UNIQUE (dealership_id, sku),
  UNIQUE (id, dealership_id)
);

CREATE TABLE part_orders (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  reference       text NOT NULL UNIQUE,
  dealership_id   uuid NOT NULL REFERENCES dealerships (id),
  appointment_id  uuid REFERENCES appointments (id),
  status          text NOT NULL CHECK (status IN ('REQUESTED', 'ORDERED', 'RECEIVED', 'ISSUED', 'CANCELLED')),
  note            text,
  requested_by    uuid NOT NULL REFERENCES users (id),
  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX part_orders_dealership_status_idx ON part_orders (dealership_id, status, created_at DESC);
CREATE INDEX part_orders_appointment_idx ON part_orders (appointment_id) WHERE appointment_id IS NOT NULL;

CREATE TABLE part_order_lines (
  order_id       uuid NOT NULL REFERENCES part_orders (id) ON DELETE CASCADE,
  part_id        uuid NOT NULL,
  dealership_id  uuid NOT NULL,
  quantity       integer NOT NULL CHECK (quantity > 0),
  unit_price     integer NOT NULL CHECK (unit_price >= 0),
  PRIMARY KEY (order_id, part_id),
  -- a line can only use a part of the order's own dealership
  FOREIGN KEY (part_id, dealership_id) REFERENCES parts (id, dealership_id)
);

-- Audit trail of parts order status changes.
CREATE TABLE part_order_events (
  id           bigserial PRIMARY KEY,
  order_id     uuid NOT NULL REFERENCES part_orders (id) ON DELETE CASCADE,
  from_status  text,
  to_status    text NOT NULL,
  actor_id     uuid NOT NULL REFERENCES users (id),
  at           timestamptz NOT NULL DEFAULT now()
);
