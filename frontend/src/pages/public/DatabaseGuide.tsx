import { ArrowLeft, Check, Database, KeyRound, Lock, Server, ShieldCheck, Unplug, X } from 'lucide-react';
import { useState, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { Button } from '@/components/ui/button';
import { Banner, Card, Logo } from '@/components/ui/misc';
import { cn } from '@/lib/utils';

const PROVIDERS = ['Neon', 'Supabase', 'Your own Postgres'] as const;
type Provider = (typeof PROVIDERS)[number];

function Code({ children }: { children: ReactNode }) {
  return <code className="rounded bg-subtle px-1.5 py-0.5 font-mono text-[13px] text-ink-900">{children}</code>;
}
function Block({ children }: { children: string }) {
  return <pre className="overflow-x-auto rounded-lg bg-inverse p-3.5 font-mono text-[13px] leading-relaxed text-white">{children}</pre>;
}
function Steps({ items }: { items: ReactNode[] }) {
  return (
    <ol className="flex flex-col gap-3">
      {items.map((s, i) => (
        <li key={i} className="grid grid-cols-[28px_1fr] gap-3">
          <span className="grid size-7 place-items-center rounded-full bg-brand-50 text-caption font-semibold text-brand-700">{i + 1}</span>
          <div className="flex min-w-0 flex-col gap-2 pt-0.5 text-small text-ink-700">{s}</div>
        </li>
      ))}
    </ol>
  );
}

const GUIDE: Record<Provider, ReactNode[]> = {
  Neon: [
    <>Sign in at <a className="font-medium text-brand-600 underline" href="https://console.neon.tech" target="_blank" rel="noreferrer">console.neon.tech</a> with your company account and create a project (the free plan is enough to start). Pick the region closest to your team.</>,
    <>Neon creates a database called <Code>neondb</Code>. It is empty, which is what Baton needs. You can also create a separate one named <Code>baton</Code>.</>,
    <>Open <strong>Connect</strong>, copy the connection string. It looks like <Code>postgresql://user:password@ep-…neon.tech/neondb?sslmode=require</Code>. Pooled or direct both work: Baton switches a pooled address to the direct one by itself.</>,
    <>pgvector is already available on Neon; Baton turns it on when it sets up the tables.</>,
  ],
  Supabase: [
    <>Create a project at <a className="font-medium text-brand-600 underline" href="https://supabase.com/dashboard" target="_blank" rel="noreferrer">supabase.com/dashboard</a>. Save the database password you choose.</>,
    <>Go to <strong>Database → Extensions</strong> and enable <Code>vector</Code>.</>,
    <>Click <strong>Connect</strong> and copy the <strong>Session pooler</strong> connection string (port 5432). The direct address only works over IPv6, so it may not be reachable from Baton.</>,
    <>Replace <Code>[YOUR-PASSWORD]</Code> in the string with your database password. Supabase’s own schemas are fine; Baton only needs the <Code>public</Code> schema to be empty.</>,
  ],
  'Your own Postgres': [
    <>Use PostgreSQL 14 or newer with the <a className="font-medium text-brand-600 underline" href="https://github.com/pgvector/pgvector" target="_blank" rel="noreferrer">pgvector</a> extension installed (AWS RDS, Google Cloud SQL and Azure all offer it).</>,
    <>Create an empty database and a user that owns it:
      <Block>{`CREATE DATABASE baton;
CREATE USER baton_app WITH PASSWORD 'a-long-random-password';
ALTER DATABASE baton OWNER TO baton_app;
\\c baton
CREATE EXTENSION IF NOT EXISTS vector;`}</Block>
    </>,
    <>Make it reachable from the internet over TLS. Baton refuses private and local addresses, and always connects with <Code>sslmode=require</Code>.</>,
    <>If you restrict access by IP, allow Baton’s outbound addresses (ask us for the current list), then use <Code>postgresql://baton_app:password@your-host:5432/baton</Code>.</>,
  ],
};

export function DatabaseGuide() {
  const [provider, setProvider] = useState<Provider>('Neon');
  return (
    <div className="min-h-dvh bg-canvas">
      <header className="border-b border-line bg-card">
        <div className="mx-auto flex max-w-[960px] items-center justify-between px-4 py-3">
          <Link to="/"><Logo /></Link>
          <Link to="/signup"><Button kind="primary">Create a workspace</Button></Link>
        </div>
      </header>
      <main className="stagger mx-auto flex max-w-[960px] flex-col gap-8 px-4 py-8 sm:py-12">
        <div className="flex flex-col gap-3">
          <Link to="/signup" className="flex items-center gap-1.5 text-small text-ink-500 hover:text-ink-900"><ArrowLeft className="size-4" aria-hidden />Back to sign-up</Link>
          <h1 className="text-display font-bold">Connect your own database</h1>
          <p className="max-w-[680px] text-body text-ink-700">
            Baton keeps your company’s handovers in a PostgreSQL database that <strong>you</strong> own. Your documents, claims, people and audit log never sit in Baton’s storage. This page shows how to set one up in about five minutes.
          </p>
        </div>

        <div className="grid gap-4 md:grid-cols-2">
          <Card className="flex flex-col gap-3">
            <h2 className="flex items-center gap-2 text-h3 font-semibold"><Database className="size-5 text-brand-600" aria-hidden />In your database</h2>
            <ul className="flex flex-col gap-2 text-small text-ink-700">
              {['People, groups and projects', 'Uploaded sources (secrets redacted; originals encrypted with your workspace key)', 'Claims, evidence, exit-interview answers', 'Approved versions, shares and checklists', 'The full audit log'].map(t => (
                <li key={t} className="flex gap-2"><Check className="mt-0.5 size-4 shrink-0 text-verified" aria-hidden />{t}</li>
              ))}
            </ul>
          </Card>
          <Card className="flex flex-col gap-3">
            <h2 className="flex items-center gap-2 text-h3 font-semibold"><Server className="size-5 text-ink-500" aria-hidden />With Baton</h2>
            <ul className="flex flex-col gap-2 text-small text-ink-700">
              <li className="flex gap-2"><Check className="mt-0.5 size-4 shrink-0 text-verified" aria-hidden />Your workspace name</li>
              <li className="flex gap-2"><Lock className="mt-0.5 size-4 shrink-0 text-verified" aria-hidden />The connection details, encrypted</li>
              <li className="flex gap-2"><KeyRound className="mt-0.5 size-4 shrink-0 text-verified" aria-hidden />Your workspace’s encryption key, encrypted</li>
              <li className="flex gap-2"><X className="mt-0.5 size-4 shrink-0 text-conflict" aria-hidden />No documents, no claims, no names, and nothing about them in our logs</li>
            </ul>
          </Card>
        </div>

        <Card className="flex flex-col gap-5 p-6">
          <div className="flex flex-col gap-1">
            <h2 className="text-h2 font-semibold">What Baton needs</h2>
            <p className="text-small text-ink-500">Baton creates its own tables the first time it connects.</p>
          </div>
          <ul className="grid gap-2 text-small text-ink-700 sm:grid-cols-2">
            <li className="flex gap-2"><Check className="mt-0.5 size-4 shrink-0 text-verified" aria-hidden />PostgreSQL 14 or newer</li>
            <li className="flex gap-2"><Check className="mt-0.5 size-4 shrink-0 text-verified" aria-hidden />The pgvector extension available</li>
            <li className="flex gap-2"><Check className="mt-0.5 size-4 shrink-0 text-verified" aria-hidden />An empty <Code>public</Code> schema (a new database)</li>
            <li className="flex gap-2"><Check className="mt-0.5 size-4 shrink-0 text-verified" aria-hidden />A user that can create tables, reachable over TLS</li>
          </ul>
          <div role="tablist" aria-label="Database provider" className="flex flex-wrap gap-2 border-b border-line">
            {PROVIDERS.map(p => (
              <button key={p} role="tab" type="button" aria-selected={p === provider} onClick={() => setProvider(p)}
                className={cn('-mb-px cursor-pointer border-b-2 px-3 py-2 text-small font-medium', p === provider ? 'border-brand-600 text-brand-700' : 'border-transparent text-ink-500 hover:text-ink-900')}>
                {p}
              </button>
            ))}
          </div>
          <div role="tabpanel"><Steps items={GUIDE[provider]} /></div>
          <Banner tone="verified" icon={ShieldCheck}>Then paste the connection string into step 2 of <Link to="/signup" className="font-medium underline">sign-up</Link>. Baton checks the version, pgvector and that the database is empty before creating anything.</Banner>
        </Card>

        <Card className="flex flex-col gap-3 p-6">
          <h2 className="flex items-center gap-2 text-h2 font-semibold"><Unplug className="size-5 text-ink-500" aria-hidden />Taking access away</h2>
          <ul className="flex flex-col gap-2 text-small text-ink-700">
            <li><strong>Instantly:</strong> change the database user’s password, or delete the user. Baton can no longer read or write anything.</li>
            <li><strong>Cleanly:</strong> an admin opens <strong>Organisation → Workspace</strong> in Baton and disconnects. Baton deletes the encrypted connection details and the workspace key, and hands the key to you once so you can still read the encrypted originals.</li>
            <li>Either way, all your data stays in your database.</li>
          </ul>
        </Card>
      </main>
    </div>
  );
}
