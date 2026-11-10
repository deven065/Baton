import { Check, ChevronRight, Lock } from 'lucide-react';
import { useEffect, useState, type FormEvent, type ReactNode } from 'react';
import { Link, Navigate, useNavigate, useSearchParams } from 'react-router-dom';
import { SensitivityChip, StatusBadge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Checkbox, Field, Input } from '@/components/ui/form';
import { Avatar, Banner, Card, EvidenceQuote, Logo } from '@/components/ui/misc';
import { DEMO_ACCOUNTS, DEMO_PASSWORD, DEMO_WORKSPACE, HOME, lastWorkspace, useAuth } from '@/lib/auth';
import { ApiError, api, wakeApi } from '@/lib/http';

function BrandPanel({ over, title, sub, children }: { over: string; title: string; sub: string; children?: ReactNode }) {
  return (
    <aside className="aurora hidden w-[640px] shrink-0 flex-col justify-between p-14 lg:flex">
      <span className="animate-fade"><Logo dark animate /></span>
      <div className="stagger flex flex-col gap-5">
        <p className="overline text-baton">{over}</p>
        <h1 className="max-w-[520px] text-display font-bold text-white">{title}</h1>
        <p className="max-w-[470px] text-body-l text-inverse-muted">{sub}</p>
        {children && <div className="max-w-[528px]"><div className="animate-float">{children}</div></div>}
      </div>
      <p className="animate-fade text-caption text-inverse-muted [animation-delay:0.6s]">Synthetic demo data · Project Annapurna · Team Bramhastra</p>
    </aside>
  );
}

export function LoginPage() {
  const { signIn } = useAuth();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  // Links in emails carry ?ws=acme (and ?next=/handover for a shared handover).
  const next = params.get('next');
  const safeNext = next && next.startsWith('/') && !next.startsWith('//') ? next : null;
  const [workspace, setWorkspace] = useState(() => params.get('ws') ?? lastWorkspace());
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [remember, setRemember] = useState(true);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState<string | null>(null);
  useEffect(() => { wakeApi(); }, []);

  const go = async (ws: string, e: string, p: string) => {
    setError('');
    setBusy(e);
    try {
      const s = await signIn(ws, e, p, remember);
      navigate(s.mustChangePassword ? '/change-password' : safeNext ?? HOME[s.role]);
    } catch (err) {
      setError(err instanceof ApiError && err.status === 401 ? 'That workspace, email and password don’t match an account.' : (err as Error).message);
    } finally {
      setBusy(null);
    }
  };
  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (!workspace.trim() || !email.trim() || !password) { setError('Enter your workspace, email and password.'); return; }
    go(workspace.trim().toLowerCase(), email.trim(), password);
  };

  return (
    <div className="flex min-h-dvh bg-card">
      <BrandPanel over="Knowledge handover" title="Every handover sentence carries its receipt." sub="Whatever has no receipt becomes a question for the person leaving.">
        <div className="flex flex-col gap-3 rounded-xl bg-inverse-raised p-5">
          <div className="flex items-center gap-2"><StatusBadge status="verified" label="Verified · 2 sources" /><span className="overline text-inverse-muted">Decision</span></div>
          <p className="text-body font-medium text-white">Push notifications moved from Firebase to OneSignal after the free-tier quota ran out during the Diwali food drive.</p>
          <EvidenceQuote source="slack/#dev · 12 Oct 2026 · chunk 214" quote="“we hit the FCM cap again during the drive — switching to OneSignal tonight”" />
        </div>
      </BrandPanel>
      <div className="flex flex-1 items-center justify-center px-4 py-10">
        <div className="stagger flex w-full max-w-[400px] flex-col gap-[18px]">
          <div className="lg:hidden"><Logo /></div>
          <div>
            <h1 className="text-h1 font-semibold">Sign in to Baton</h1>
            <p className="mt-1.5 text-ink-500">Use your club or team account.</p>
          </div>
          <form onSubmit={submit} className="flex flex-col gap-[18px]" noValidate>
            <Field label="Workspace" hint={<p className="text-caption text-ink-500">Your company’s workspace name, e.g. <span className="font-mono">acme</span>. Try <span className="font-mono">demo</span> for the sample project.</p>}>
              {id => <Input id={id} autoComplete="organization" autoCapitalize="none" spellCheck={false} value={workspace} onChange={e => setWorkspace(e.target.value)} placeholder="acme" invalid={!!error} />}
            </Field>
            <Field label="Email">{id => <Input id={id} type="email" autoComplete="email" value={email} onChange={e => setEmail(e.target.value)} invalid={!!error} />}</Field>
            <Field label="Password">{id => <Input id={id} type="password" autoComplete="current-password" value={password} onChange={e => setPassword(e.target.value)} />}</Field>
            {params.get('reset') && !error && <p role="status" className="text-small text-verified">Password saved. Sign in with your new password.</p>}
            {error && <p role="alert" className="text-small text-conflict">{error}</p>}
            <div className="flex items-center justify-between">
              <Checkbox checked={remember} onChange={setRemember} label="Keep me signed in" />
              <Link to={`/forgot-password${workspace.trim() ? `?ws=${encodeURIComponent(workspace.trim().toLowerCase())}` : ''}`} className="text-small font-medium text-brand-600 hover:underline">Forgot password?</Link>
            </div>
            <Button kind="primary" size="lg" type="submit" disabled={!!busy}>{busy === email.trim() ? 'Signing in…' : 'Sign in'}</Button>
          </form>
          {DEMO_PASSWORD && (
            <>
              <div className="flex items-center gap-3 text-caption text-ink-400"><span className="h-px flex-1 bg-line" />or explore the demo workspace<span className="h-px flex-1 bg-line" /></div>
              <ul className="flex flex-col gap-2" aria-label="Demo personas">
                {DEMO_ACCOUNTS.map((p, i) => (
                  <li key={p.email}>
                    <button type="button" disabled={!!busy} onClick={() => go(DEMO_WORKSPACE, p.email, DEMO_PASSWORD ?? "")} className="flex w-full items-center gap-3 rounded-xl border border-line px-3 py-2.5 text-left hover:border-brand-600 hover:bg-brand-50 cursor-pointer disabled:opacity-60">
                      <Avatar initials={p.name.split(' ').map(w => w[0]).join('')} tone={i} size="sm" />
                      <span className="flex-1">
                        <span className="block text-small font-medium">{p.name}</span>
                        <span className="block text-caption text-ink-500">{busy === p.email ? 'Signing in…' : p.role}</span>
                      </span>
                      <SensitivityChip level={p.clearance} />
                      <ChevronRight className="size-3.5 text-ink-400" aria-hidden />
                    </button>
                  </li>
                ))}
              </ul>
            </>
          )}
          <p className="text-center text-small text-ink-500">New to Baton? <Link to="/signup" className="font-medium text-brand-600 hover:underline">Create an account</Link></p>
        </div>
      </div>
    </div>
  );
}

/** Old invitation links: send people to sign-in, keeping the workspace and destination. */
export function InvitePage() {
  const [params] = useSearchParams();
  return <Navigate to={`/login?${params.toString()}`} replace />;
}

function SmallPage({ children }: { children: ReactNode }) {
  return (
    <div className="grid min-h-dvh place-items-center bg-canvas px-4 py-10">
      <Card className="stagger flex w-full max-w-[440px] flex-col gap-5 p-8 shadow-pop">
        <Logo />
        {children}
      </Card>
    </div>
  );
}

/** "Forgot password": emails a one-time link when email is set up on this server; otherwise, ask an admin. */
export function ForgotPasswordPage() {
  const [params] = useSearchParams();
  const [workspace, setWorkspace] = useState(() => params.get('ws') ?? lastWorkspace());
  const [email, setEmail] = useState('');
  const [state, setState] = useState<'idle' | 'busy' | 'sent'>('idle');
  const [error, setError] = useState('');
  const [byEmail, setByEmail] = useState<boolean | null>(null);
  useEffect(() => {
    api<{ passwordResetByEmail: boolean }>('/api/auth/options').then(o => setByEmail(o.passwordResetByEmail)).catch(() => setByEmail(false));
  }, []);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (!workspace.trim() || !email.trim()) { setError('Enter your workspace and email.'); return; }
    setError('');
    setState('busy');
    try {
      await api('/api/auth/forgot-password', { method: 'POST', json: { workspace: workspace.trim().toLowerCase(), email: email.trim() } });
      setState('sent');
    } catch (err) {
      setError((err as Error).message);
      setState('idle');
    }
  };

  return (
    <SmallPage>
      <h1 className="text-h2 font-semibold">Reset your password</h1>
      {byEmail === false && (
        <>
          <Banner icon={Lock}>Ask an admin of your workspace to reset it. In Baton they open <strong>Organisation → People &amp; groups</strong>, choose you and click <strong>Reset password</strong>; you then pick a new one when you sign in.</Banner>
          <Link to="/login" className="text-small font-medium text-brand-600 hover:underline">Back to sign-in</Link>
        </>
      )}
      {byEmail && state === 'sent' && (
        <>
          <Banner tone="verified" icon={Check}>If <strong>{email.trim()}</strong> has an account in <strong>{workspace.trim()}</strong>, we’ve emailed it a link. It works once, for 30 minutes.</Banner>
          <Link to={`/login?ws=${encodeURIComponent(workspace.trim().toLowerCase())}`} className="text-small font-medium text-brand-600 hover:underline">Back to sign-in</Link>
        </>
      )}
      {byEmail && state !== 'sent' && (
        <form onSubmit={submit} className="flex flex-col gap-4" noValidate>
          <p className="text-small text-ink-500">We’ll email you a link to choose a new password.</p>
          <Field label="Workspace">{id => <Input id={id} value={workspace} onChange={e => setWorkspace(e.target.value)} autoCapitalize="none" spellCheck={false} placeholder="acme" />}</Field>
          <Field label="Email">{id => <Input id={id} type="email" autoComplete="email" value={email} onChange={e => setEmail(e.target.value)} autoFocus />}</Field>
          {error && <p role="alert" className="text-small text-conflict">{error}</p>}
          <Button kind="primary" size="lg" type="submit" disabled={state === 'busy'}>{state === 'busy' ? 'Sending…' : 'Email me a link'}</Button>
          <Link to="/login" className="text-center text-small text-ink-500 hover:underline">Back to sign-in</Link>
        </form>
      )}
    </SmallPage>
  );
}

/** Opened from the emailed link: /reset-password?ws=acme&token=… */
export function ResetPasswordPage() {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const ws = params.get('ws') ?? '';
  const token = params.get('token') ?? '';
  const [pw, setPw] = useState('');
  const [again, setAgain] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (pw.length < 12) { setError('Use at least 12 characters.'); return; }
    if (pw !== again) { setError('The two passwords don’t match.'); return; }
    setError('');
    setBusy(true);
    try {
      await api('/api/auth/reset-password', { method: 'POST', json: { workspace: ws, token, newPassword: pw } });
      navigate(`/login?ws=${encodeURIComponent(ws)}&reset=1`, { replace: true });
    } catch (err) {
      const d = err instanceof ApiError ? (err.details as Record<string, string> | undefined) : undefined;
      setError(d?.token ?? d?.newPassword ?? (err as Error).message);
    } finally {
      setBusy(false);
    }
  };

  if (!ws || !token) {
    return (
      <SmallPage>
        <h1 className="text-h2 font-semibold">This link is incomplete</h1>
        <p className="text-small text-ink-500">Open the link from your email again, or <Link to="/forgot-password" className="font-medium text-brand-600 underline">ask for a new one</Link>.</p>
      </SmallPage>
    );
  }
  return (
    <SmallPage>
      <div>
        <h1 className="text-h2 font-semibold">Choose a new password</h1>
        <p className="mt-1 text-small text-ink-500">For the <span className="font-mono">{ws}</span> workspace.</p>
      </div>
      <form onSubmit={submit} className="flex flex-col gap-4" noValidate>
        <Field label="New password" hint={<p className="text-caption text-ink-500">At least 12 characters.</p>}>{id => <Input id={id} type="password" autoComplete="new-password" value={pw} onChange={e => setPw(e.target.value)} autoFocus />}</Field>
        <Field label="Type it again">{id => <Input id={id} type="password" autoComplete="new-password" value={again} onChange={e => setAgain(e.target.value)} />}</Field>
        {error && <p role="alert" className="text-small text-conflict">{error}</p>}
        <Button kind="primary" size="lg" type="submit" disabled={busy}>{busy ? 'Saving…' : 'Save and sign in'}</Button>
      </form>
    </SmallPage>
  );
}
