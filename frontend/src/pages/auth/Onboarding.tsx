import { AlertTriangle, Check, Database, KeyRound, Lock, ShieldCheck } from 'lucide-react';
import { useState, type FormEvent, type ReactNode } from 'react';
import { Link, Navigate, useNavigate } from 'react-router-dom';
import { Button } from '@/components/ui/button';
import { Field, Input } from '@/components/ui/form';
import { Banner, Card, Logo } from '@/components/ui/misc';
import { HOME, useAuth } from '@/lib/auth';
import { ApiError, api } from '@/lib/http';
import { cn } from '@/lib/utils';

const slugify = (s: string) => s.toLowerCase().normalize('NFKD').replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 40);
const SLUG = /^[a-z0-9](?:[a-z0-9-]{1,38}[a-z0-9])$/;
const STEPS = ['Company', 'Database', 'First admin'] as const;

function Shell({ children }: { children: ReactNode }) {
  return (
    <div className="flex min-h-dvh bg-card">
      <aside className="hidden w-[520px] shrink-0 flex-col justify-between bg-inverse p-14 lg:flex">
        <Logo dark />
        <div className="flex flex-col gap-5">
          <p className="overline text-baton">Your data stays yours</p>
          <h1 className="text-display font-bold text-white">Baton runs on your own database.</h1>
          <p className="text-body-l text-inverse-muted">
            Everything your team adds — people, sources, claims, exit interviews, the audit log — is stored in a Postgres
            database in <em>your</em> account. Baton keeps only your workspace name and the encrypted connection to it.
          </p>
          <ul className="flex flex-col gap-3 text-small text-white">
            {['Change your database password and Baton loses access instantly', 'Secrets and personal data are redacted before any AI call', 'Each workspace has its own encryption key'].map(t => (
              <li key={t} className="flex items-start gap-2.5"><Check className="mt-0.5 size-4 shrink-0 text-baton" aria-hidden />{t}</li>
            ))}
          </ul>
        </div>
        <p className="text-caption text-inverse-muted">Team Bramhastra</p>
      </aside>
      <main className="flex flex-1 items-start justify-center overflow-y-auto px-4 py-10 lg:items-center">{children}</main>
    </div>
  );
}

export function SignupWizard() {
  const { register } = useAuth();
  const navigate = useNavigate();
  const [step, setStep] = useState(0);
  const [company, setCompany] = useState('');
  const [slug, setSlug] = useState('');
  const [slugTouched, setSlugTouched] = useState(false);
  const [dbUrl, setDbUrl] = useState('');
  const [dbUser, setDbUser] = useState('');
  const [dbPass, setDbPass] = useState('');
  const [name, setName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [slugState, setSlugState] = useState<'idle' | 'checking' | 'taken' | 'free'>('idle');
  const [error, setError] = useState<{ message: string; field?: string } | null>(null);
  const [busy, setBusy] = useState(false);

  const effectiveSlug = slugTouched ? slug : slugify(company);

  const checkSlug = async () => {
    if (!SLUG.test(effectiveSlug)) return false;
    setSlugState('checking');
    try {
      await api(`/api/workspaces/${effectiveSlug}`);
      setSlugState('taken');
      return false;
    } catch (e) {
      const free = e instanceof ApiError && e.status === 404;
      setSlugState(free ? 'free' : 'idle');
      return free;
    }
  };

  const next = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    if (step === 0) {
      if (!company.trim()) return setError({ message: 'Enter your company or team name.', field: 'company' });
      if (!SLUG.test(effectiveSlug)) return setError({ message: 'Use 3–40 lowercase letters, digits or hyphens.', field: 'slug' });
      if (!(await checkSlug())) return setError({ message: 'That workspace name is taken. Choose another.', field: 'slug' });
      setSlug(effectiveSlug);
      setSlugTouched(true);
      return setStep(1);
    }
    if (step === 1) {
      if (!/^(jdbc:)?postgres(ql)?:\/\//.test(dbUrl.trim())) return setError({ message: 'Paste a PostgreSQL connection URL (postgresql://…).', field: 'db' });
      return setStep(2);
    }
    if (password.length < 12) return setError({ message: 'Use at least 12 characters.', field: 'password' });
    setBusy(true);
    try {
      const s = await register({
        slug: effectiveSlug, name: company.trim(),
        database: { url: dbUrl.trim(), username: dbUser.trim() || undefined, password: dbPass || undefined },
        admin: { displayName: name.trim(), email: email.trim(), password },
      });
      navigate(HOME[s.role]);
    } catch (err) {
      const ae = err instanceof ApiError ? err : null;
      const details = (ae?.details ?? {}) as Record<string, string>;
      // Database problems send you back to the database step with the server's explanation.
      if (ae && (ae.code.startsWith('DATABASE_') || details.database)) {
        setStep(1);
        setError({ message: details.database ?? ae.message, field: 'db' });
      } else if (ae?.code === 'WORKSPACE_TAKEN' || details.slug) {
        setStep(0);
        setError({ message: details.slug ?? ae?.message ?? 'Workspace name taken', field: 'slug' });
      } else {
        setError({ message: details.password ?? details.email ?? ae?.message ?? (err as Error).message });
      }
    } finally {
      setBusy(false);
    }
  };

  return (
    <Shell>
      <form onSubmit={next} className="flex w-full max-w-[480px] flex-col gap-5" noValidate>
        <div className="lg:hidden"><Logo /></div>
        <ol className="flex items-center gap-2" aria-label="Sign-up steps">
          {STEPS.map((s, i) => (
            <li key={s} className="flex items-center gap-2" aria-current={i === step ? 'step' : undefined}>
              {i > 0 && <span className={cn('h-0.5 w-6 rounded', i <= step ? 'bg-brand-600' : 'bg-line-strong')} aria-hidden />}
              <span className={cn('grid size-6 place-items-center rounded-full text-caption font-medium', i < step ? 'bg-verified text-white' : i === step ? 'bg-brand-600 text-white' : 'border border-line-strong text-ink-500')}>
                {i < step ? <Check className="size-3" strokeWidth={3} /> : i + 1}
              </span>
              <span className={cn('text-small', i === step ? 'font-medium' : 'text-ink-500')}>{s}</span>
            </li>
          ))}
        </ol>

        {step === 0 && (
          <>
            <div><h1 className="text-h1 font-semibold">Create your workspace</h1><p className="mt-1 text-ink-500">One workspace per company, club or team.</p></div>
            <Field label="Company or team name">{id => <Input id={id} autoFocus value={company} onChange={e => { setCompany(e.target.value); setSlugState('idle'); }} placeholder="Acme Robotics" invalid={error?.field === 'company'} />}</Field>
            <Field label="Workspace name" hint={<p className="text-caption text-ink-500">People type this when they sign in. Lowercase letters, digits and hyphens.{slugState === 'free' && <span className="ml-1 text-verified">Available.</span>}</p>}>
              {id => <Input id={id} value={effectiveSlug} onChange={e => { setSlugTouched(true); setSlug(e.target.value.toLowerCase()); setSlugState('idle'); }} className="font-mono" placeholder="acme" invalid={error?.field === 'slug'} autoCapitalize="none" spellCheck={false} />}
            </Field>
          </>
        )}

        {step === 1 && (
          <>
            <div><h1 className="text-h1 font-semibold">Connect your database</h1><p className="mt-1 text-ink-500">An <strong>empty</strong> PostgreSQL 14+ database with pgvector, in your own account.</p></div>
            <Banner icon={Database}>
              Easiest: create a free project on <a className="font-medium underline" href="https://neon.tech" target="_blank" rel="noreferrer">Neon</a> or{' '}
              <a className="font-medium underline" href="https://supabase.com" target="_blank" rel="noreferrer">Supabase</a> and copy its connection string. Baton sets up the tables itself.{' '}
              <Link to="/connect-database" target="_blank" className="font-medium underline">Step-by-step guide</Link>
            </Banner>
            <Field label="Connection URL" hint={<p className="text-caption text-ink-500">e.g. <span className="font-mono">postgresql://user:password@host/dbname?sslmode=require</span></p>}>
              {id => <Input id={id} autoFocus value={dbUrl} onChange={e => setDbUrl(e.target.value)} className="font-mono text-small" placeholder="postgresql://…" invalid={error?.field === 'db'} autoCapitalize="none" spellCheck={false} autoComplete="off" />}
            </Field>
            <details className="text-small text-ink-700">
              <summary className="cursor-pointer font-medium">User and password separately (if not in the URL)</summary>
              <div className="mt-3 grid gap-3 sm:grid-cols-2">
                <Field label="Database user">{id => <Input id={id} value={dbUser} onChange={e => setDbUser(e.target.value)} autoComplete="off" />}</Field>
                <Field label="Database password">{id => <Input id={id} type="password" value={dbPass} onChange={e => setDbPass(e.target.value)} autoComplete="new-password" />}</Field>
              </div>
            </details>
            <p className="flex items-start gap-2 text-caption text-ink-500"><Lock className="mt-px size-3.5 shrink-0 text-verified" aria-hidden />The connection is encrypted before Baton stores it, and used only to read and write your workspace.</p>
          </>
        )}

        {step === 2 && (
          <>
            <div><h1 className="text-h1 font-semibold">Your admin account</h1><p className="mt-1 text-ink-500">You’ll manage people, groups and projects in <span className="font-mono">{effectiveSlug}</span>.</p></div>
            <Field label="Your name">{id => <Input id={id} autoFocus autoComplete="name" value={name} onChange={e => setName(e.target.value)} />}</Field>
            <Field label="Work email">{id => <Input id={id} type="email" autoComplete="email" value={email} onChange={e => setEmail(e.target.value)} />}</Field>
            <Field label="Password" hint={<p className={cn('text-caption', password.length >= 12 ? 'text-verified' : 'text-ink-500')}>{password.length >= 12 ? 'Good length' : `At least 12 characters (${password.length})`}</p>}>
              {id => <Input id={id} type="password" autoComplete="new-password" value={password} onChange={e => setPassword(e.target.value)} invalid={error?.field === 'password'} />}
            </Field>
          </>
        )}

        {error && <Banner tone="conflict" icon={AlertTriangle}><span role="alert">{error.message}</span></Banner>}
        <div className="flex gap-2">
          {step > 0 && <Button type="button" onClick={() => { setError(null); setStep(step - 1); }} disabled={busy}>Back</Button>}
          <Button kind="primary" size="lg" type="submit" className="flex-1" disabled={busy || slugState === 'checking'}>
            {step < 2 ? 'Continue' : busy ? 'Setting up your database…' : 'Create workspace'}
          </Button>
        </div>
        {busy && <p className="text-caption text-ink-500" role="status">Checking the connection, creating tables and your account. This takes a few seconds.</p>}
        <p className="text-center text-small text-ink-500">Already have a workspace? <Link to="/login" className="font-medium text-brand-600 hover:underline">Sign in</Link></p>
      </form>
    </Shell>
  );
}

/** Shown right after sign-in when an admin created or reset the account. */
export function ChangePasswordPage() {
  const { session, refresh, loading } = useAuth();
  const navigate = useNavigate();
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [confirm, setConfirm] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  if (loading) return null;
  if (!session) return <Navigate to="/login" replace />;

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setError('');
    if (next.length < 12) return setError('Use at least 12 characters.');
    if (next !== confirm) return setError('The two new passwords don’t match.');
    setBusy(true);
    try {
      await api('/api/me/password', { method: 'POST', json: { currentPassword: current, newPassword: next } });
      const s = await refresh();
      navigate(s ? HOME[s.role] : '/login', { replace: true });
    } catch (err) {
      const d = (err instanceof ApiError ? err.details : null) as Record<string, string> | null;
      setError(d?.currentPassword ?? d?.newPassword ?? (err as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="grid min-h-dvh place-items-center px-4 py-10">
      <Card className="flex w-full max-w-[440px] flex-col gap-5 p-8 shadow-pop">
        <Logo />
        <div className="flex items-start gap-3">
          <span className="grid size-10 shrink-0 place-items-center rounded-lg bg-brand-50 text-brand-700"><KeyRound className="size-5" aria-hidden /></span>
          <div><h1 className="text-h2 font-semibold">Choose your own password</h1><p className="text-small text-ink-500">Your admin gave you a temporary one for {session.workspace.name}. Replace it before you continue.</p></div>
        </div>
        <form onSubmit={submit} className="flex flex-col gap-4">
          <Field label="Temporary password">{id => <Input id={id} type="password" autoComplete="current-password" value={current} onChange={e => setCurrent(e.target.value)} />}</Field>
          <Field label="New password">{id => <Input id={id} type="password" autoComplete="new-password" value={next} onChange={e => setNext(e.target.value)} />}</Field>
          <Field label="Repeat new password">{id => <Input id={id} type="password" autoComplete="new-password" value={confirm} onChange={e => setConfirm(e.target.value)} />}</Field>
          {error && <Banner tone="conflict" icon={AlertTriangle}><span role="alert">{error}</span></Banner>}
          <Button kind="primary" size="lg" type="submit" disabled={busy || !current || !next}>{busy ? 'Saving…' : 'Save and continue'}</Button>
        </form>
        <p className="flex items-center gap-2 text-caption text-ink-500"><ShieldCheck className="size-3.5 text-verified" aria-hidden />Stored only as a BCrypt hash in your company’s database.</p>
      </Card>
    </div>
  );
}
