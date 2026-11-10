import { ArrowLeft, Check, KeyRound, LogOut, UserRound } from 'lucide-react';
import { useState, type FormEvent, type ReactNode } from 'react';
import { Link, Navigate, useNavigate } from 'react-router-dom';
import { SensitivityChip } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Field, Input } from '@/components/ui/form';
import { Avatar, Banner, Card, CardHeader } from '@/components/ui/misc';
import { AccountMenu, FocusShell } from '@/components/layout/shells';
import { HOME, useAuth } from '@/lib/auth';
import { ApiError, api } from '@/lib/http';

/** Your own account: who Baton thinks you are, what you may see, your password, and signing out. */
export function AccountPage() {
  const { session, user, loading, signOut } = useAuth();
  const navigate = useNavigate();
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [confirm, setConfirm] = useState('');
  const [error, setError] = useState('');
  const [saved, setSaved] = useState(false);
  const [busy, setBusy] = useState(false);
  if (loading) return null;
  if (!session || !user) return <Navigate to="/login" replace />;
  const demo = session.workspace.slug === 'demo';

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setError('');
    setSaved(false);
    if (next.length < 12) return setError('Use at least 12 characters.');
    if (next !== confirm) return setError('The two new passwords don’t match.');
    setBusy(true);
    try {
      await api('/api/me/password', { method: 'POST', json: { currentPassword: current, newPassword: next } });
      setCurrent(''); setNext(''); setConfirm('');
      setSaved(true);
    } catch (err) {
      const d = (err instanceof ApiError ? err.details : null) as Record<string, string> | null;
      setError(d?.currentPassword ?? d?.newPassword ?? (err as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const rows: [string, ReactNode][] = [
    ['Name', user.name],
    ['Email', user.email],
    ['Workspace', `${session.workspace.name} (${session.workspace.slug})`],
    ['Role', user.role + (session.isAdmin && user.appRole !== 'admin' ? ' · organisation admin' : '')],
    ['Clearance', <SensitivityChip key="c" level={user.clearance} />],
    ['Groups', user.groups.length ? user.groups.join(', ') : 'None'],
  ];

  return (
    <FocusShell title="Account settings" right={<div className="flex items-center gap-3"><Link to={HOME[user.appRole]} className="flex items-center gap-1.5 text-small font-medium text-ink-700 hover:text-ink-900"><ArrowLeft className="size-4" aria-hidden />Back</Link><AccountMenu /></div>}>
      <div className="mx-auto flex max-w-[640px] flex-col gap-6">
        <Card>
          <CardHeader title={<span className="flex items-center gap-2"><UserRound className="size-4 text-brand-600" aria-hidden />Your account</span>}
            sub="Only an organisation admin can change your name, clearance or groups." />
          <div className="flex items-center gap-3 pt-4 pb-4">
            <Avatar initials={user.initials} tone={user.tone} size="lg" />
            <div><p className="text-body font-semibold text-ink-900">{user.name}</p><p className="text-small text-ink-500">{user.email}</p></div>
          </div>
          <dl className="grid grid-cols-[120px_1fr] gap-x-4 gap-y-2.5 border-t border-line pt-4 text-small">
            {rows.map(([k, v]) => (
              <div key={k} className="contents"><dt className="text-ink-500">{k}</dt><dd className="text-ink-900">{v}</dd></div>
            ))}
          </dl>
        </Card>

        <Card>
          <CardHeader title={<span className="flex items-center gap-2"><KeyRound className="size-4 text-brand-600" aria-hidden />Change password</span>}
            sub="At least 12 characters. Stored only as a BCrypt hash in your company’s database." />
          {demo ? (
            <div className="pt-4"><Banner tone="single" icon={KeyRound}>The demo workspace is shared, so its passwords stay as published. Create your own workspace to try this.</Banner></div>
          ) : (
            <form onSubmit={submit} className="flex flex-col gap-4 pt-4" noValidate>
              <Field label="Current password">{id => <Input id={id} type="password" autoComplete="current-password" value={current} onChange={e => setCurrent(e.target.value)} />}</Field>
              <Field label="New password">{id => <Input id={id} type="password" autoComplete="new-password" value={next} onChange={e => setNext(e.target.value)} />}</Field>
              <Field label="Repeat new password">{id => <Input id={id} type="password" autoComplete="new-password" value={confirm} onChange={e => setConfirm(e.target.value)} />}</Field>
              {error && <p role="alert" className="text-small text-conflict">{error}</p>}
              {saved && <p role="status" className="flex items-center gap-1.5 text-small text-verified"><Check className="size-4" aria-hidden />Password changed.</p>}
              <div><Button kind="primary" type="submit" disabled={busy || !current || !next}>{busy ? 'Saving…' : 'Change password'}</Button></div>
            </form>
          )}
        </Card>

        <Card>
          <CardHeader title={<span className="flex items-center gap-2"><LogOut className="size-4 text-conflict" aria-hidden />Sign out</span>}
            sub="Signs you out of Baton on this browser." />
          <div className="pt-4"><Button kind="secondary" onClick={() => { signOut(); navigate('/login'); }}>Sign out</Button></div>
        </Card>
      </div>
    </FocusShell>
  );
}
