-- Payment step between the work and the end of a job:
--
--   IN_PROGRESS ──finish──▶ AWAITING_PAYMENT ──paid──▶ COMPLETED
--
-- The technician (or advisor) finishes the job once the customer has checked the vehicle;
-- the dealership admin marks it paid. The work is done, so AWAITING_PAYMENT no longer holds
-- the bay, the technician or the vehicle: the no-double-booking constraints are unchanged.

ALTER TABLE appointments DROP CONSTRAINT appointments_status_check;
ALTER TABLE appointments ADD CONSTRAINT appointments_status_check
  CHECK (status IN ('HELD', 'CONFIRMED', 'IN_PROGRESS', 'AWAITING_PAYMENT', 'COMPLETED', 'CANCELLED', 'NO_SHOW', 'EXPIRED'));

-- An approved request still has its one appointment while that appointment waits for payment.
DROP INDEX appointments_one_live_per_consultation;
CREATE UNIQUE INDEX appointments_one_live_per_consultation ON appointments (consultation_request_id)
  WHERE consultation_request_id IS NOT NULL AND status IN ('HELD', 'CONFIRMED', 'IN_PROGRESS', 'AWAITING_PAYMENT', 'COMPLETED');

-- The admin's job list reads one dealership's jobs by status.
CREATE INDEX appointments_dealership_status_idx ON appointments (dealership_id, status);
