import { AlertTriangle, Check, Copy, Database, Download, KeyRound, Unplug } from 'lucide-react';
import { useState, type FormEvent } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { Button } from '@/components/ui/button';
import { Field, Input } from '@/components/ui/form';
import { Banner, Card, CardHeader, Logo, PageHeader, QueryState } from '@/components/ui/misc';
import { fmtDate, useDisconnectWorkspace, useWorkspaceSettings } from '@/data/live';
import { useAuth } from '@/lib/auth';
import { ApiError } from '@/lib/http';

/** Organisation → Workspace: where the data lives, and disconnecting from Baton. */
export function WorkspaceSettings() {
  const { data: ws, isLoading, error } = useWorkspaceSettings();
  const disconnect = useDisconnectWorkspace();
  const { signOut } = useAuth();
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const [confirm, setConfirm] = useState('');
  const [password, setPassword] = useState('');
  const err = disconnect.error instanceof ApiError ? disconnect.error : null;
  const details = (err?.details ?? null) as Record<string, string> | null;

  const submit = (e: FormEvent) => {
    e.preventDefault();
    disconnect.mutate({ confirm: confirm.trim(), password }, {
      // Leave the signed-in area first: every request after this would be refused.
      onSuccess: r => { navigate('/disconnected', { replace: true, state: { key: r.recoveryKey, slug: ws?.slug, name: ws?.name } }); signOut(); },
    });
  };

  return (
    <>
      <PageHeader title="Workspace" sub="Where your company’s data lives, and how to take it away from Baton." />
      <QueryState loading={isLoading} error={error} />
      {ws && (
        <>
          <div className="reveal-list grid gap-4 md:grid-cols-2">
            <Card className="flex flex-col gap-3">
              <CardHeader title={ws.name} sub={`Workspace name: ${ws.slug} · created ${fmtDate(ws.createdAt)}`} />
              <p className="text-small text-ink-700">{ws.people} active {ws.people === 1 ? 'person' : 'people'} · {ws.projects} project{ws.projects === 1 ? '' : 's'}</p>
            </Card>
            <Card className="flex flex-col gap-3">
              <h2 className="flex items-center gap-2 text-h3 font-semibold"><Database className="size-5 text-brand-600" aria-hidden />Your database</h2>
              {ws.database
                ? <p className="font-mono text-small text-ink-700">{ws.database.host}:{ws.database.port}/{ws.database.database}</p>
                : <p className="text-small text-ink-700">The demo workspace lives in Baton’s own database.</p>}
              <p className="text-caption text-ink-500">Everything in this workspace is stored there. Baton keeps only the encrypted connection details and the workspace’s encryption key. <Link to="/connect-database" className="underline">How it works</Link></p>
            </Card>
          </div>

          <Card className="flex flex-col gap-4 border-conflict/40">
            <div className="flex flex-wrap items-start justify-between gap-3">
              <div className="flex flex-col gap-1">
                <h2 className="flex items-center gap-2 text-h3 font-semibold text-conflict"><Unplug className="size-5" aria-hidden />Disconnect from Baton</h2>
                <p className="max-w-[640px] text-small text-ink-700">
                  Baton deletes the connection details and the workspace key, and everyone is signed out. Your database keeps all its data. You get the workspace key once, so you can still decrypt the original files stored in it.
                </p>
              </div>
              {!open && <Button kind="danger" disabled={ws.demo} onClick={() => setOpen(true)} title={ws.demo ? 'The demo workspace cannot be disconnected' : undefined}>Disconnect…</Button>}
            </div>
            {ws.demo && <p className="text-caption text-ink-500">The demo workspace cannot be disconnected.</p>}
            {open && (
              <form onSubmit={submit} className="flex flex-col gap-3 rounded-xl border border-line bg-canvas p-4">
                <Banner tone="conflict" icon={AlertTriangle}>This cannot be undone from Baton. To use Baton again you would create a new workspace with a new, empty database.</Banner>
                <div className="grid gap-3 sm:grid-cols-2">
                  <Field label={`Type ${ws.slug} to confirm`} hint={details?.confirm ? <p className="text-caption text-conflict">{details.confirm}</p> : undefined}>
                    {id => <Input id={id} value={confirm} onChange={e => setConfirm(e.target.value)} autoComplete="off" autoCapitalize="none" spellCheck={false} invalid={!!details?.confirm} />}
                  </Field>
                  <Field label="Your password" hint={details?.password ? <p className="text-caption text-conflict">{details.password}</p> : undefined}>
                    {id => <Input id={id} type="password" value={password} onChange={e => setPassword(e.target.value)} autoComplete="current-password" invalid={!!details?.password} />}
                  </Field>
                </div>
                {err && !details && <Banner tone="conflict" icon={AlertTriangle}>{err.message}</Banner>}
                <div className="flex justify-end gap-2">
                  <Button type="button" kind="ghost" onClick={() => { setOpen(false); disconnect.reset(); }}>Cancel</Button>
                  <Button type="submit" kind="danger" disabled={confirm.trim().toLowerCase() !== ws.slug || !password || disconnect.isPending}>
                    {disconnect.isPending ? 'Disconnecting…' : 'Disconnect workspace'}
                  </Button>
                </div>
              </form>
            )}
          </Card>
        </>
      )}
    </>
  );
}

/** Shown once after disconnecting: the workspace key, which Baton no longer has. */
export function DisconnectedPage() {
  const { state } = useLocation() as { state: { key?: string; slug?: string; name?: string } | null };
  const [copied, setCopied] = useState(false);
  const key = state?.key;
  const download = () => {
    const text = `Baton workspace key for ${state?.name ?? ''} (${state?.slug ?? ''})\nAES-256-GCM, base64:\n${key}\n\nBaton no longer has this key. Keep it with your database credentials.\n`;
    const a = document.createElement('a');
    a.href = URL.createObjectURL(new Blob([text], { type: 'text/plain' }));
    a.download = `baton-${state?.slug ?? 'workspace'}-key.txt`;
    a.click();
    URL.revokeObjectURL(a.href);
  };
  return (
    <div className="grid min-h-dvh place-items-center bg-canvas p-4">
      <Card className="stagger flex w-full max-w-[560px] flex-col gap-4 p-6">
        <Logo />
        <h1 className="flex items-center gap-2 text-h1 font-semibold"><Check className="size-6 text-verified" aria-hidden />Workspace disconnected</h1>
        <p className="text-small text-ink-700">{state?.name ?? 'Your workspace'} is no longer connected to Baton. All of its data is still in your database, and Baton has deleted its copy of the connection details.</p>
        {key ? (
          <>
            <Banner tone="inferred" icon={KeyRound}>This is the only time this key is shown. You need it to decrypt the original files Baton stored in your database.</Banner>
            <p className="break-all rounded-lg bg-inverse p-3.5 font-mono text-small text-white">{key}</p>
            <div className="flex flex-wrap gap-2">
              <Button onClick={() => { navigator.clipboard?.writeText(key).then(() => setCopied(true)).catch(() => undefined); }}><Copy className="size-4" aria-hidden />{copied ? 'Copied' : 'Copy key'}</Button>
              <Button onClick={download}><Download className="size-4" aria-hidden />Download as a file</Button>
            </div>
          </>
        ) : <p className="text-small text-ink-500">The key was shown once, right after disconnecting.</p>}
        <Link to="/" className="text-small font-medium text-brand-600 hover:underline">Back to Baton</Link>
      </Card>
    </div>
  );
}
