import { Check, ChevronRight, Info, Lock } from 'lucide-react';
import { useState, type FormEvent, type ReactNode } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { SensitivityChip, StatusBadge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Checkbox, Field, Input } from '@/components/ui/form';
import { Avatar, Banner, Card, EvidenceQuote, Logo } from '@/components/ui/misc';
import { DEMO_ACCOUNTS, DEMO_PASSWORD, DEMO_WORKSPACE, HOME, lastWorkspace, useAuth } from '@/lib/auth';
import { ApiError } from '@/lib/http';
import { cn } from '@/lib/utils';

function BrandPanel({ over, title, sub, children }: { over: string; title: string; sub: string; children?: ReactNode }) {
  return (
    <aside className="hidden w-[640px] shrink-0 flex-col justify-between bg-inverse p-14 lg:flex">
      <Logo dark />
      <div className="flex flex-col gap-5">
        <p className="overline text-baton">{over}</p>
        <h1 className="max-w-[520px] text-display font-bold text-white">{title}</h1>
        <p className="max-w-[470px] text-body-l text-inverse-muted">{sub}</p>
        {children && <div className="max-w-[528px]">{children}</div>}
      </div>
      <p className="text-caption text-inverse-muted">Synthetic demo data · Project Annapurna · Team Bramhastra</p>
    </aside>
  );
}

export function LoginPage() {
  const { signIn } = useAuth();
  const navigate = useNavigate();
  const [workspace, setWorkspace] = useState(lastWorkspace);
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [remember, setRemember] = useState(true);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState<string | null>(null);

  const go = async (ws: string, e: string, p: string) => {
    setError('');
    setBusy(e);
    try {
      const s = await signIn(ws, e, p);
      navigate(HOME[s.role]);
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
        <div className="flex w-full max-w-[400px] flex-col gap-[18px]">
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
            {error && <p role="alert" className="text-small text-conflict">{error}</p>}
            <div className="flex items-center justify-between">
              <Checkbox checked={remember} onChange={setRemember} label="Keep me signed in" />
              <a href="#" className="text-small font-medium text-brand-600 hover:underline">Forgot password?</a>
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

const ROLES = [
  { id: 'lead', title: 'Project lead', desc: 'Reviews and approves' },
  { id: 'member', title: 'Team member', desc: 'Leaves or receives' },
  { id: 'admin', title: 'Org admin', desc: 'Users and groups' },
];

export function SignupPage() {
  const [submitted, setSubmitted] = useState(false);
  const [password, setPassword] = useState('');
  const [role, setRole] = useState('lead');
  const [agree, setAgree] = useState(false);
  const strength = Math.min(4, Math.floor(password.length / 4));
  const label = ['Too short', 'Weak', 'Fair', 'Good', 'Strong'][strength];

  return (
    <div className="flex min-h-dvh bg-card">
      <BrandPanel over="Get started" title="Set up a handover workspace in two minutes." sub="Baton turns records your team already has into a verified continuation plan — before anyone leaves.">
        <ol className="flex flex-col gap-3.5">
          {[['Create your organisation', 'Your club, startup or NGO.'], ['Invite people and set access groups', 'engineering · design · operations'], ['Connect a project’s sources', 'GitHub, task CSV, notes, chat export']].map(([t, d], i) => (
            <li key={t} className="flex items-center gap-3.5">
              <span className="grid size-8 place-items-center rounded-full bg-inverse-raised text-small font-medium text-baton">{i + 1}</span>
              <span><span className="block font-medium text-white">{t}</span><span className="block text-small text-inverse-muted">{d}</span></span>
            </li>
          ))}
        </ol>
      </BrandPanel>
      <div className="flex flex-1 items-center justify-center px-4 py-10">
        <form
          className="flex w-full max-w-[440px] flex-col gap-4"
          onSubmit={e => { e.preventDefault(); if (agree) setSubmitted(true); }}
        >
          <div className="lg:hidden"><Logo /></div>
          <div>
            <p className="overline text-brand-600">Step 1 of 2</p>
            <h1 className="text-h1 font-semibold">Create your account</h1>
            <p className="mt-1.5 text-ink-500">You’ll invite your team on the next step.</p>
          </div>
          <Field label="Full name">{id => <Input id={id} autoComplete="name" defaultValue="Priya Nair" />}</Field>
          <Field label="Club or work email">{id => <Input id={id} type="email" autoComplete="email" defaultValue="priya.nair@northfield.club" />}</Field>
          <Field
            label="Password"
            hint={
              <div className="flex flex-col gap-1.5">
                <div className="flex gap-1" aria-hidden>{[0, 1, 2, 3].map(i => <span key={i} className={cn('h-1 flex-1 rounded-full', i < strength ? 'bg-verified' : 'bg-line')} />)}</div>
                <p className={cn('text-caption font-medium', strength >= 3 ? 'text-verified' : 'text-ink-500')}>{label} · {password.length} characters, stored with BCrypt</p>
              </div>
            }
          >
            {id => <Input id={id} type="password" autoComplete="new-password" value={password} onChange={e => setPassword(e.target.value)} placeholder="At least 12 characters" />}
          </Field>
          <Field label="Organisation name">{id => <Input id={id} defaultValue="Northfield Tech Club" />}</Field>
          <fieldset className="flex flex-col gap-1.5">
            <legend className="mb-1.5 text-caption font-medium text-ink-700">Your role</legend>
            <div className="grid grid-cols-3 gap-2">
              {ROLES.map(r => (
                <label key={r.id} className={cn('flex cursor-pointer flex-col gap-0.5 rounded-xl border p-3', role === r.id ? 'border-brand-600 bg-brand-50 ring-1 ring-brand-600' : 'border-line hover:bg-canvas')}>
                  <input type="radio" name="role" className="sr-only" checked={role === r.id} onChange={() => setRole(r.id)} />
                  <span className={cn('text-small font-medium', role === r.id ? 'text-brand-700' : 'text-ink-900')}>{r.title}</span>
                  <span className="text-caption text-ink-500">{r.desc}</span>
                </label>
              ))}
            </div>
          </fieldset>
          <Checkbox checked={agree} onChange={setAgree} label="I agree to the terms. I will only connect sources I’m authorised to share, and I understand Baton redacts secrets before any AI call." />
          {submitted && (
            <Banner tone="inferred" icon={Info}>
              Self sign-up isn’t in the API yet: your organisation admin creates accounts for now. If you have one, <Link to="/login" className="font-medium text-brand-600 underline">sign in</Link>.
            </Banner>
          )}
          <Button kind="primary" size="lg" type="submit" disabled={!agree}>Create account</Button>
          <p className="text-center text-small text-ink-500">Already have an account? <Link to="/login" className="font-medium text-brand-600 hover:underline">Sign in</Link></p>
        </form>
      </div>
    </div>
  );
}

export function InvitePage() {
  const navigate = useNavigate();
  return (
    <div className="grid min-h-dvh place-items-center px-4 py-10">
      <Card className="flex w-full max-w-[500px] flex-col gap-5 p-8 shadow-pop">
        <Logo />
        <div className="flex items-center gap-3">
          <Avatar initials="PN" tone={0} size="lg" />
          <div>
            <h1 className="text-h2 font-semibold">Priya Nair shared a handover with you</h1>
            <p className="text-small text-ink-500">Project Annapurna · Engineer view · link expires 20 Dec 2026</p>
          </div>
        </div>
        <hr className="border-line" />
        <p className="overline text-ink-400">What you’ll get</p>
        <ul className="flex flex-col gap-3">
          {['Start here: goal, current phase, next deadline, top risks', 'A Day 1 / Week 1 / Month 1 checklist', 'Who to ask about what — the ownership map', 'Every statement linked to its source'].map(s => (
            <li key={s} className="flex items-center gap-2.5 text-ink-700">
              <span className="grid size-[18px] place-items-center rounded-full bg-verified text-white"><Check className="size-2.5" strokeWidth={3} aria-hidden /></span>{s}
            </li>
          ))}
        </ul>
        <Banner icon={Lock}>You’ll see claims up to Confidential in the engineering group. Anything above that stays hidden — filtering happens in the database.</Banner>
        <Field label="Signed in as">{id => <Input id={id} readOnly value="rohan.k@northfield.club" />}</Field>
        <Button kind="primary" size="lg" onClick={() => navigate('/login')}>Sign in to open</Button>
        <p className="text-center text-caption text-ink-500">This link is personal. Every view is recorded in the audit log for the project lead.</p>
      </Card>
    </div>
  );
}
