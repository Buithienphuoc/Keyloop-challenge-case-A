import type { ReactNode } from 'react';
import { ApiError } from '../api/client';
import type { AppointmentStatus, ConsultationStatus } from '../api/types';
import { REQUEST_LABEL, STATUS_LABEL } from '../lib/format';

export function Plate({ text, large = false }: { text: string; large?: boolean }) {
  return (
    <span className={large ? 'plate plate-lg' : 'plate'} aria-label={`Plate ${text}`}>
      {text}
    </span>
  );
}

export function StatusPill({ status }: { status: AppointmentStatus }) {
  return <span className={`pill pill-${status}`}>{STATUS_LABEL[status]}</span>;
}

export function RequestPill({ status }: { status: ConsultationStatus }) {
  return <span className={`pill pill-${status}`}>{REQUEST_LABEL[status]}</span>;
}

/** Shows what went wrong and, for anything unexpected, the reference to quote to support. */
export function ErrorNote({ error }: { error: unknown }) {
  if (!error) return null;
  const e = error instanceof ApiError ? error : null;
  const message = e?.message ?? (error instanceof Error ? error.message : 'Something went wrong.');
  const showRef = !e || e.status >= 500 || e.status === 0;
  return (
    <div className="notice notice-error" role="alert">
      {message}
      {showRef && e && <span className="ref">Reference for support: {e.requestId}</span>}
    </div>
  );
}

export function Loading({ what = 'Loading' }: { what?: string }) {
  return (
    <div className="loading" role="status">
      {what}…
    </div>
  );
}

export function Empty({ title, children, action }: { title: string; children?: ReactNode; action?: ReactNode }) {
  return (
    <div className="empty">
      <h3>{title}</h3>
      {children && <p>{children}</p>}
      {action}
    </div>
  );
}

export function PageHead({ title, children, actions }: { title: string; children?: ReactNode; actions?: ReactNode }) {
  return (
    <div className="page-head">
      <div>
        <h1>{title}</h1>
        {children && <p>{children}</p>}
      </div>
      {actions}
    </div>
  );
}
