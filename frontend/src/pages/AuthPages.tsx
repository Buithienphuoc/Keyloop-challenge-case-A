import { useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { ErrorNote, Plate } from '../components/ui';

const DEMO_PASSWORD = 'Demo@2026';
const DEMO_ACCOUNTS = [
  { email: 'customer@demo.vn', role: 'Customer' },
  { email: 'advisor.d1@demo.vn', role: 'Service advisor' },
  { email: 'tech.hung@demo.vn', role: 'Technician' },
  { email: 'admin.d1@demo.vn', role: 'Dealership admin' },
  { email: 'sysadmin@demo.vn', role: 'System manager' },
];

function AuthShell({ children }: { children: React.ReactNode }) {
  return (
    <div className="auth">
      <aside className="auth-side">
        <div className="brand" style={{ color: '#fff' }}>
          <span className="brand-mark" aria-hidden="true" />
          Service Scheduler
        </div>
        <div>
          <h1>Book your service in a minute, not a phone call.</h1>
          <p className="hide-sm">
            Pick a time that really is free. We check that a service bay and a technician with the right skills are both
            available for the whole job before we confirm.
          </p>
        </div>
        <Plate text="59-X1 234.56" large />
      </aside>
      <main className="auth-main">{children}</main>
    </div>
  );
}

export function LoginPage() {
  const { signIn } = useAuth();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await signIn(email, password);
    } catch (err) {
      setError(err);
      setBusy(false);
    }
  };

  return (
    <AuthShell>
      <form className="auth-form stack" onSubmit={submit}>
        <h2>Sign in</h2>
        <ErrorNote error={error} />
        <div className="field">
          <label htmlFor="email">Email</label>
          <input id="email" className="input" type="email" autoComplete="username" required value={email} onChange={(e) => setEmail(e.target.value)} />
        </div>
        <div className="field">
          <label htmlFor="password">Password</label>
          <input
            id="password"
            className="input"
            type="password"
            autoComplete="current-password"
            required
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
        </div>
        <button className="btn btn-primary btn-block" disabled={busy}>
          {busy ? 'Signing in…' : 'Sign in'}
        </button>
        <p className="small muted">
          New here? <Link to="/register">Create an account</Link>
        </p>

        <div className="demo">
          <p className="small muted">Demo accounts (password {DEMO_PASSWORD}). Tap one to fill the form.</p>
          <div className="demo-list">
            {DEMO_ACCOUNTS.map((a) => (
              <button
                type="button"
                key={a.email}
                onClick={() => {
                  setEmail(a.email);
                  setPassword(DEMO_PASSWORD);
                }}
              >
                <span>{a.email}</span>
                <span>{a.role}</span>
              </button>
            ))}
          </div>
        </div>
      </form>
    </AuthShell>
  );
}

export function RegisterPage() {
  const { register } = useAuth();
  const [form, setForm] = useState({ fullName: '', email: '', phone: '', password: '' });
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const set = (k: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement>) => setForm({ ...form, [k]: e.target.value });

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await register({ ...form, phone: form.phone || undefined });
    } catch (err) {
      setError(err);
      setBusy(false);
    }
  };

  return (
    <AuthShell>
      <form className="auth-form stack" onSubmit={submit}>
        <h2>Create your account</h2>
        <ErrorNote error={error} />
        <div className="field">
          <label htmlFor="fullName">Your name</label>
          <input id="fullName" className="input" required autoComplete="name" value={form.fullName} onChange={set('fullName')} />
        </div>
        <div className="field">
          <label htmlFor="r-email">Email</label>
          <input id="r-email" className="input" type="email" required autoComplete="email" value={form.email} onChange={set('email')} />
        </div>
        <div className="field">
          <label htmlFor="phone">Phone (optional)</label>
          <input id="phone" className="input" type="tel" autoComplete="tel" value={form.phone} onChange={set('phone')} />
          <span className="hint">The workshop calls this number if anything changes.</span>
        </div>
        <div className="field">
          <label htmlFor="r-password">Password</label>
          <input
            id="r-password"
            className="input"
            type="password"
            required
            minLength={8}
            autoComplete="new-password"
            value={form.password}
            onChange={set('password')}
          />
          <span className="hint">At least 8 characters, with a letter and a number.</span>
        </div>
        <button className="btn btn-primary btn-block" disabled={busy}>
          {busy ? 'Creating account…' : 'Create account'}
        </button>
        <p className="small muted">
          Already have an account? <Link to="/login">Sign in</Link>
        </p>
      </form>
    </AuthShell>
  );
}
