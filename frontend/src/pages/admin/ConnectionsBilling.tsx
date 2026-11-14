import { AlertTriangle, Check, CreditCard, FolderOpen, GitBranch, KanbanSquare, Lock, MessagesSquare, Sparkles, Trash2, type LucideIcon } from 'lucide-react';
import { useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { StatusBadge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Field, Input, Textarea } from '@/components/ui/form';
import { Banner, Card, CardHeader, PageHeader, Progress, QueryState } from '@/components/ui/misc';
import { fmtDate, fmtDateTime, useBilling, useCheckout, useIntegrationMutations, useIntegrations, useOAuthAvailable, useStartOAuth, type IntegrationDto, type IntegrationKind, type PlanLimits } from '@/data/live';
import { ApiError } from '@/lib/http';
import { cn } from '@/lib/utils';

const TOOLS: Record<IntegrationKind, { name: string; icon: LucideIcon; what: string; secretLabel: string; placeholder: string; multiline?: boolean; steps: React.ReactNode[]; oneClick?: string }> = {
  GITHUB: {
    name: 'GitHub', icon: GitBranch, what: 'README, docs, issues, pull requests and recent commits of a repository. Public repositories work without a token.',
    secretLabel: 'Personal access token', placeholder: 'github_pat_…', oneClick: 'Connect with GitHub',
    steps: [
      <>On GitHub open <strong>Settings → Developer settings → Fine-grained tokens</strong> and generate a token.</>,
      <>Give it access to only the repositories you want Baton to read, with <strong>read-only</strong> permission for Contents, Issues, Pull requests and Metadata.</>,
      <>Paste the token here.</>,
    ],
  },
  SLACK: {
    name: 'Slack', icon: MessagesSquare, what: 'The last 90 days of the channels you choose, with thread replies. One document per day.',
    secretLabel: 'Bot User OAuth Token', placeholder: 'xoxb-…', oneClick: 'Add to Slack',
    steps: [
      <>At <a className="underline" href="https://api.slack.com/apps" target="_blank" rel="noreferrer">api.slack.com/apps</a> create an app in your Slack workspace (“From scratch”).</>,
      <>Under <strong>OAuth &amp; Permissions</strong> add the bot scopes <code>channels:history</code>, <code>channels:read</code>, <code>groups:history</code>, <code>groups:read</code> and <code>users:read</code>, then <strong>Install to Workspace</strong>.</>,
      <>Copy the <strong>Bot User OAuth Token</strong> and paste it here. In each channel to read, type <code>/invite @your-app</code>.</>,
    ],
  },
  GOOGLE_DRIVE: {
    name: 'Google Drive', icon: FolderOpen, what: 'Google Docs and text files in the folders you share with a service account.',
    secretLabel: 'Service-account key (JSON)', placeholder: '{ "type": "service_account", … }', multiline: true, oneClick: 'Connect with Google',
    steps: [
      <>In <a className="underline" href="https://console.cloud.google.com/iam-admin/serviceaccounts" target="_blank" rel="noreferrer">Google Cloud</a> create a service account in a project where the <strong>Google Drive API</strong> is enabled.</>,
      <>Open the account’s <strong>Keys</strong> tab, add a JSON key, and paste the whole file here.</>,
      <>In Drive, share each folder to read with the service account’s email address as <strong>Viewer</strong>.</>,
    ],
  },
  JIRA: {
    name: 'Jira', icon: KanbanSquare, what: 'The issues of a Jira Cloud project: open work, owners, due dates. Open issues owned by the person leaving, or by nobody, become handover items.',
    secretLabel: 'Atlassian API token', placeholder: 'ATATT…',
    steps: [
      <>At <a className="underline" href="https://id.atlassian.com/manage-profile/security/api-tokens" target="_blank" rel="noreferrer">id.atlassian.com → Security → API tokens</a> create a token. It can read whatever that account can read, so a read-only account is best.</>,
      <>Enter your Jira site address (like <code>https://yourteam.atlassian.net</code>), the account email and the token.</>,
    ],
  },
};

/** Organisation → Connections: the company's own credentials for the tools Baton reads from. */
export function Connections() {
  const { data = [], isLoading, error } = useIntegrations();
  const oauth = useOAuthAvailable();
  const [params, setParams] = useSearchParams();
  // Back from "Connect with …": say what happened once, then tidy the address.
  const connected = params.get('connected');
  const connectError = params.get('connectError');
  const clear = () => { params.delete('connected'); params.delete('connectError'); setParams(params, { replace: true }); };
  const tool = connected ? TOOLS[connected.toUpperCase() as IntegrationKind] : undefined;
  return (
    <>
      <PageHeader title="Connections" sub="Let Baton read straight from your tools. Read-only: Baton never writes back. Connected tools are read again in the background, so handovers stay current." />
      {connected && <Banner tone="verified" icon={Check}>Connected{tool ? ` to ${tool.name}` : ''}. Add a source from it on the Sources page. <button type="button" onClick={clear} className="underline cursor-pointer">Dismiss</button></Banner>}
      {connectError && <Banner tone="conflict" icon={AlertTriangle}>{connectError} <button type="button" onClick={clear} className="underline cursor-pointer">Dismiss</button></Banner>}
      <Banner icon={Lock}>Each credential is encrypted with your workspace key and stored in your own database. After saving it is never shown again.</Banner>
      <QueryState loading={isLoading} error={error} />
      <div className="reveal-list grid items-start gap-4 xl:grid-cols-2">
        {data.map(i => <ToolCard key={i.kind} i={i} oneClick={!!oauth.data?.[i.kind]} />)}
      </div>
    </>
  );
}

function ToolCard({ i, oneClick }: { i: IntegrationDto; oneClick: boolean }) {
  const tool = TOOLS[i.kind];
  const { save, remove, test } = useIntegrationMutations();
  const start = useStartOAuth();
  const [secret, setSecret] = useState('');
  const [open, setOpen] = useState(false);
  // Jira is three things (site, account, token), sent as one JSON secret.
  const [jira, setJira] = useState({ site: '', email: '', token: '' });
  const isJira = i.kind === 'JIRA';
  const value = isJira ? (jira.site.trim() && jira.email.trim() && jira.token.trim() ? JSON.stringify({ site: jira.site.trim(), email: jira.email.trim(), token: jira.token.trim() }) : '') : secret;
  const startErr = start.error instanceof ApiError ? start.error.message : null;
  const connect = () => start.mutate(i.kind, { onSuccess: r => { window.location.href = r.url; } });
  const err = save.error instanceof ApiError ? save.error : null;
  const fieldError = (err?.details as Record<string, string> | undefined)?.secret;
  const Icon = tool.icon;
  return (
    <Card className="flex flex-col gap-3">
      <div className="flex items-start justify-between gap-2">
        <h2 className="flex items-center gap-2 text-h3 font-semibold"><Icon className="size-5 text-ink-700" aria-hidden />{tool.name}</h2>
        <StatusBadge status={i.connected ? 'verified' : 'stale'} label={i.connected ? 'Connected' : 'Not connected'} />
      </div>
      <p className="text-small text-ink-700">{tool.what}</p>
      {i.connected && <p className="text-caption text-ink-500">{i.hint}{i.updatedAt && ` · ${fmtDateTime(i.updatedAt)}`}{i.updatedByName && ` by ${i.updatedByName}`}</p>}
      {!open && oneClick && !i.connected && (
        <div className="flex flex-col gap-1.5 rounded-xl border border-brand-100 bg-brand-50 p-3">
          <Button kind="primary" onClick={connect} disabled={start.isPending}><Sparkles className="size-4" aria-hidden />{start.isPending ? 'Opening…' : tool.oneClick}</Button>
          <span className="text-caption text-ink-500">One click: you approve read-only access on {tool.name}'s own page and come straight back. No keys to copy.</span>
          {startErr && <span className="text-caption text-conflict">{startErr}</span>}
        </div>
      )}
      {!open && (
        <div className="mt-auto flex flex-wrap gap-2">
          {oneClick && i.connected && <Button onClick={connect} disabled={start.isPending}><Sparkles className="size-4" aria-hidden />Reconnect</Button>}
          <Button kind={i.connected || oneClick ? 'secondary' : 'primary'} onClick={() => { setOpen(true); test.reset(); save.reset(); }}>{i.connected ? 'Replace with a key' : oneClick ? 'Paste a key instead' : 'Connect'}</Button>
          {i.connected && <Button onClick={() => test.mutate({ kind: i.kind })} disabled={test.isPending}>{test.isPending ? 'Testing…' : 'Test'}</Button>}
          {i.connected && <Button kind="ghost" onClick={() => { if (confirm(`Disconnect ${tool.name}? Sources already read stay; they can’t be synced again until you reconnect.`)) remove.mutate(i.kind); }}><Trash2 className="size-4" aria-hidden />Disconnect</Button>}
        </div>
      )}
      {open && (
        <form className="flex flex-col gap-3" onSubmit={e => { e.preventDefault(); save.mutate({ kind: i.kind, secret: value }, { onSuccess: () => { setSecret(''); setJira({ site: '', email: '', token: '' }); setOpen(false); } }); }}>
          <ol className="flex list-decimal flex-col gap-1.5 pl-5 text-small text-ink-700">{tool.steps.map((s, n) => <li key={n}>{s}</li>)}</ol>
          {isJira ? (
            <>
              <Field label="Jira site">{id => <Input id={id} value={jira.site} onChange={e => setJira({ ...jira, site: e.target.value })} placeholder="https://yourteam.atlassian.net" autoCapitalize="none" spellCheck={false} />}</Field>
              <Field label="Account email">{id => <Input id={id} type="email" value={jira.email} onChange={e => setJira({ ...jira, email: e.target.value })} placeholder="you@yourteam.com" autoComplete="off" />}</Field>
              <Field label={tool.secretLabel} hint={fieldError ? <p className="text-caption text-conflict">{fieldError}</p> : undefined}>
                {id => <Input id={id} type="password" value={jira.token} onChange={e => setJira({ ...jira, token: e.target.value })} placeholder={tool.placeholder} autoComplete="off" className="font-mono text-small" invalid={!!fieldError} />}
              </Field>
            </>
          ) : (
            <Field label={tool.secretLabel} hint={fieldError ? <p className="text-caption text-conflict">{fieldError}</p> : undefined}>
              {id => tool.multiline
                ? <Textarea id={id} rows={5} value={secret} onChange={e => setSecret(e.target.value)} placeholder={tool.placeholder} className="font-mono text-caption" spellCheck={false} />
                : <Input id={id} type="password" value={secret} onChange={e => setSecret(e.target.value)} placeholder={tool.placeholder} autoComplete="off" className="font-mono text-small" invalid={!!fieldError} />}
            </Field>
          )}
          {err && !fieldError && <Banner tone="conflict" icon={AlertTriangle}>{err.message}</Banner>}
          <div className="flex flex-wrap justify-end gap-2">
            <Button type="button" kind="ghost" onClick={() => { setOpen(false); setSecret(''); setJira({ site: '', email: '', token: '' }); }}>Cancel</Button>
            <Button type="button" onClick={() => test.mutate({ kind: i.kind, secret: value })} disabled={!value.trim() || test.isPending}>{test.isPending ? 'Testing…' : 'Test'}</Button>
            <Button type="submit" kind="primary" disabled={!value.trim() || save.isPending}>{save.isPending ? 'Saving…' : 'Save'}</Button>
          </div>
        </form>
      )}
      {test.data && <Banner tone={test.data.ok ? 'verified' : 'conflict'} icon={test.data.ok ? Check : AlertTriangle}>{test.data.message}</Banner>}
    </Card>
  );
}

const money = (minor: number, currency: string) => new Intl.NumberFormat('en-IN', { style: 'currency', currency, maximumFractionDigits: 0 }).format(minor / 100);

/** Organisation → Billing: the plan, what it allows, and paying for the Team plan. */
export function Billing() {
  const { data: b, isLoading, error } = useBilling();
  const checkout = useCheckout();
  const [params] = useSearchParams();
  const err = checkout.error instanceof ApiError ? checkout.error : null;
  const rows: { key: 'PEOPLE' | 'PROJECTS' | 'SOURCES'; label: string; limit: keyof PlanLimits }[] = [
    { key: 'PEOPLE', label: 'Active people', limit: 'people' }, { key: 'PROJECTS', label: 'Projects', limit: 'projects' }, { key: 'SOURCES', label: 'Sources', limit: 'sources' },
  ];
  return (
    <>
      <PageHeader title="Billing" sub="Your plan and what it allows. Running out of room never removes anything; it only stops new things being added." />
      <QueryState loading={isLoading} error={error} />
      {params.get('paid') && b?.plan !== 'TEAM' && <Banner icon={CreditCard}>Thanks. Your payment is being confirmed; this page updates within a minute. Refresh if it still says Free.</Banner>}
      {b && (
        <>
          <div className="grid items-start gap-4 lg:grid-cols-[1fr_1fr]">
            <Card className="flex flex-col gap-4">
              <div className="flex items-center justify-between">
                <h2 className="text-h3 font-semibold">Current plan</h2>
                <StatusBadge status={b.unlimited || b.plan === 'TEAM' ? 'verified' : 'single'} label={b.unlimited ? 'Demo · no limits' : b.plan === 'TEAM' ? 'Team' : 'Free'} />
              </div>
              {b.plan === 'TEAM' && b.paidUntil && !b.unlimited && <p className="text-small text-ink-700">Paid until <strong>{fmtDate(b.paidUntil)}</strong> {new Date(b.paidUntil).getFullYear()}. Pay again any time to add another month.</p>}
              {rows.map(r => {
                const used = b.usage[r.key];
                const limit = b.limits[r.limit];
                const full = !b.unlimited && used >= limit;
                return (
                  <div key={r.key} className="flex flex-col gap-1">
                    <span className="flex justify-between text-small"><span>{r.label}</span><span className={cn(full ? 'font-medium text-conflict' : 'text-ink-500')}>{used}{b.unlimited ? '' : ` of ${limit}`}</span></span>
                    {!b.unlimited && <Progress value={Math.min(1, used / limit)} tone={full ? 'bg-conflict' : 'bg-brand-600'} label={r.label} />}
                  </div>
                );
              })}
            </Card>
            <Card className="flex flex-col gap-3">
              <CardHeader title="Plans" />
              <div className="reveal-list grid gap-3 sm:grid-cols-2">
                {([['Free', b.freeLimits, 'Free', b.plan === 'FREE'], ['Team', b.teamLimits, `${money(b.teamPriceMinor, b.currency)} / month`, b.plan === 'TEAM']] as const).map(([name, l, price, current]) => (
                  <div key={name} className={cn('flex flex-col gap-1.5 rounded-xl border p-4', current && !b.unlimited ? 'border-brand-600 bg-brand-50' : 'border-line')}>
                    <span className="font-semibold">{name}</span>
                    <span className="text-h3 font-semibold">{price}</span>
                    <ul className="flex flex-col gap-1 text-small text-ink-700">
                      <li>{l.people} people</li><li>{l.projects} project{l.projects === 1 ? '' : 's'}</li><li>{l.sources} sources</li>
                      <li>All features, your own database</li>
                    </ul>
                  </div>
                ))}
              </div>
              {!b.unlimited && (b.paymentsEnabled
                ? <Button kind="primary" size="lg" disabled={checkout.isPending} onClick={() => checkout.mutate(undefined, { onSuccess: r => { window.location.href = r.url; } })}>
                    <CreditCard className="size-4" aria-hidden />{checkout.isPending ? 'Opening payment…' : b.plan === 'TEAM' ? 'Add another month' : `Upgrade to Team · ${money(b.teamPriceMinor, b.currency)}`}
                  </Button>
                : <Banner icon={CreditCard}>Online payment isn’t switched on yet. Contact the Baton team to move to the Team plan.</Banner>)}
              {err && <Banner tone="conflict" icon={AlertTriangle}>{err.message}</Banner>}
              <p className="text-caption text-ink-500">Payment happens on Razorpay’s page. Baton never sees or stores card details.</p>
            </Card>
          </div>
        </>
      )}
    </>
  );
}
