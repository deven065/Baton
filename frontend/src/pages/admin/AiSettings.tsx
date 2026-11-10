import { AlertTriangle, Check, EyeOff, KeyRound, Lock, Server, Sparkles, Trash2 } from 'lucide-react';
import { useEffect, useState, type FormEvent } from 'react';
import { Button } from '@/components/ui/button';
import { Checkbox, Field, Input } from '@/components/ui/form';
import { Banner, Card, CardHeader, PageHeader, QueryState, StatCard } from '@/components/ui/misc';
import { fmtDateTime, useAiMutations, useAiSettings, type AiProvider } from '@/data/live';
import { ApiError } from '@/lib/http';
import { cn } from '@/lib/utils';

const PROVIDERS: { id: AiProvider; name: string; url: string; model: string; keys: string }[] = [
  { id: 'GROQ', name: 'Groq', url: 'https://api.groq.com/openai/v1', model: 'llama-3.3-70b-versatile', keys: 'https://console.groq.com/keys' },
  { id: 'OPENAI', name: 'OpenAI', url: 'https://api.openai.com/v1', model: 'gpt-4o-mini', keys: 'https://platform.openai.com/api-keys' },
  { id: 'OPENROUTER', name: 'OpenRouter', url: 'https://openrouter.ai/api/v1', model: 'meta-llama/llama-3.3-70b-instruct', keys: 'https://openrouter.ai/keys' },
  { id: 'CUSTOM', name: 'Other (OpenAI-compatible)', url: '', model: '', keys: '' },
];

/** Organisation → AI: the company's own AI account for reconstruction. */
export function AiSettings() {
  const { data, isLoading, error } = useAiSettings();
  const { save, remove, test } = useAiMutations();
  const s = data?.settings;
  const [provider, setProvider] = useState<AiProvider>('GROQ');
  const [baseUrl, setBaseUrl] = useState('');
  const [model, setModel] = useState('');
  const [apiKey, setApiKey] = useState('');
  const [enabled, setEnabled] = useState(true);

  useEffect(() => {
    if (s?.configured && s.provider) {
      setProvider(s.provider);
      setBaseUrl(s.provider === 'CUSTOM' ? s.baseUrl ?? '' : '');
      setModel(s.model ?? '');
      setEnabled(s.enabled);
    }
  }, [s?.configured, s?.provider, s?.baseUrl, s?.model, s?.enabled]);

  const preset = PROVIDERS.find(p => p.id === provider)!;
  const body = () => ({ provider, baseUrl: provider === 'CUSTOM' ? baseUrl.trim() : undefined, model: model.trim() || undefined, apiKey: apiKey.trim() || undefined, enabled });
  const err = (save.error ?? test.error) instanceof ApiError ? (save.error ?? test.error) as ApiError : null;
  const details = (err?.details ?? null) as Record<string, string> | null;
  const submit = (e: FormEvent) => {
    e.preventDefault();
    test.reset();
    save.mutate(body(), { onSuccess: () => setApiKey('') });
  };

  return (
    <>
      <PageHeader title="AI" sub="Use your company’s own AI account to reconstruct handovers." />
      <QueryState loading={isLoading} error={error} />
      {data && (
        <>
          <div className="reveal-list grid gap-4 md:grid-cols-3">
            <Card className="flex flex-col gap-2">
              <h2 className="flex items-center gap-2 text-h3 font-semibold"><Sparkles className="size-5 text-brand-600" aria-hidden />Status</h2>
              {s?.configured && s.enabled
                ? <p className="text-small text-ink-700"><strong>On.</strong> Reconstruction uses {PROVIDERS.find(p => p.id === s.provider)?.name} · <span className="font-mono">{s.model}</span>, billed to your account.</p>
                : <p className="text-small text-ink-700"><strong>Off.</strong> Baton uses its built-in rules to find claims. No text leaves Baton, but results are simpler.</p>}
              {s?.updatedAt && <p className="text-caption text-ink-500">Changed {fmtDateTime(s.updatedAt)}{s.updatedByName ? ` by ${s.updatedByName}` : ''}</p>}
            </Card>
            <Card className="flex flex-col gap-2 md:col-span-2">
              <h2 className="flex items-center gap-2 text-h3 font-semibold"><Lock className="size-5 text-verified" aria-hidden />What gets sent, and where</h2>
              <ul className="flex flex-col gap-1.5 text-small text-ink-700">
                <li className="flex gap-2"><EyeOff className="mt-0.5 size-4 shrink-0 text-ink-400" aria-hidden />Only passages of the sources you uploaded, after secrets and personal data (emails, phone numbers, keys) are replaced with placeholders.</li>
                <li className="flex gap-2"><Server className="mt-0.5 size-4 shrink-0 text-ink-400" aria-hidden />Straight from Baton’s server to your provider, under your account. Your provider’s data terms apply, so check its retention settings.</li>
                <li className="flex gap-2"><KeyRound className="mt-0.5 size-4 shrink-0 text-ink-400" aria-hidden />The key is encrypted with your workspace key and stored in your own database. After saving it is never shown again, only its last 4 characters.</li>
              </ul>
            </Card>
          </div>

          <Card className="flex flex-col gap-4 p-6">
            <CardHeader title="Your provider" sub="Any OpenAI-compatible chat API works." />
            <form onSubmit={submit} className="flex flex-col gap-4" noValidate>
              <div role="radiogroup" aria-label="Provider" className="flex flex-wrap gap-2">
                {PROVIDERS.map(p => (
                  <button key={p.id} type="button" role="radio" aria-checked={provider === p.id}
                    onClick={() => { setProvider(p.id); setModel(s?.provider === p.id ? s.model ?? '' : ''); test.reset(); }}
                    className={cn('rounded-lg border px-3 py-2 text-small font-medium cursor-pointer', provider === p.id ? 'border-brand-600 bg-brand-50 text-brand-700' : 'border-line-strong text-ink-700 hover:bg-canvas')}>
                    {p.name}
                  </button>
                ))}
              </div>
              <div className="grid gap-3 md:grid-cols-2">
                {provider === 'CUSTOM'
                  ? <Field label="API address" hint={details?.baseUrl ? <p className="text-caption text-conflict">{details.baseUrl}</p> : <p className="text-caption text-ink-500">Public HTTPS only, e.g. https://api.together.xyz/v1</p>}>{id => <Input id={id} value={baseUrl} onChange={e => setBaseUrl(e.target.value)} placeholder="https://…/v1" className="font-mono text-small" autoCapitalize="none" spellCheck={false} invalid={!!details?.baseUrl} />}</Field>
                  : <Field label="API address">{id => <Input id={id} value={preset.url} readOnly className="font-mono text-small text-ink-500" />}</Field>}
                <Field label="Model" hint={details?.model ? <p className="text-caption text-conflict">{details.model}</p> : preset.model ? <p className="text-caption text-ink-500">Leave empty for {preset.model}</p> : undefined}>
                  {id => <Input id={id} value={model} onChange={e => setModel(e.target.value)} placeholder={preset.model || 'model-name'} className="font-mono text-small" autoCapitalize="none" spellCheck={false} invalid={!!details?.model} />}
                </Field>
              </div>
              <Field label="API key" hint={details?.apiKey ? <p className="text-caption text-conflict">{details.apiKey}</p> : <p className="text-caption text-ink-500">{s?.configured ? `A key ending in ${s.keyHint} is stored. Leave empty to keep it.` : 'Create one in your provider’s console'}{preset.keys && <> · <a href={preset.keys} target="_blank" rel="noreferrer" className="underline">{preset.name} keys</a></>}</p>}>
                {id => <Input id={id} type="password" value={apiKey} onChange={e => setApiKey(e.target.value)} placeholder={s?.configured ? `•••• ${s.keyHint}` : 'sk-…'} autoComplete="off" className="font-mono text-small" invalid={!!details?.apiKey} />}
              </Field>
              <Checkbox checked={enabled} onChange={setEnabled} label="Use this provider when reconstructing handovers" />
              {test.data && (test.data.ok
                ? <Banner tone="verified" icon={Check}>Connected to <span className="font-mono">{test.data.model}</span> in {test.data.latencyMs} ms. Nothing from your workspace was sent.</Banner>
                : <Banner tone="conflict" icon={AlertTriangle}>{test.data.message}</Banner>)}
              {save.isSuccess && !save.isPending && <Banner tone="verified" icon={Check}>Saved.</Banner>}
              {err && !details && <Banner tone="conflict" icon={AlertTriangle}>{err.message}</Banner>}
              <div className="flex flex-wrap items-center justify-between gap-2">
                {s?.configured
                  ? <Button type="button" kind="ghost" onClick={() => { if (confirm('Delete the stored key? Reconstruction goes back to the built-in rules.')) remove.mutate(); }} disabled={remove.isPending}><Trash2 className="size-4" aria-hidden />Remove key</Button>
                  : <span />}
                <div className="flex gap-2">
                  <Button type="button" onClick={() => { save.reset(); test.mutate(body()); }} disabled={test.isPending}>{test.isPending ? 'Testing…' : 'Test connection'}</Button>
                  <Button type="submit" kind="primary" disabled={save.isPending}>{save.isPending ? 'Saving…' : 'Save'}</Button>
                </div>
              </div>
            </form>
          </Card>

          <section className="flex flex-col gap-3">
            <CardHeader title="Usage, last 30 days" sub="Calls that reached your provider. Replays from Baton’s cache are free and not counted." />
            <div className="grid gap-4 sm:grid-cols-3">
              <StatCard label="Calls" value={String(data.usage.calls)} note={data.usage.failed ? `${data.usage.failed} failed` : 'None failed'} valueClass={data.usage.failed ? 'text-inferred' : undefined} />
              <StatCard label="Tokens sent" value={data.usage.tokensIn.toLocaleString()} note="Prompt tokens" />
              <StatCard label="Tokens received" value={data.usage.tokensOut.toLocaleString()} note="Completion tokens" />
            </div>
            {data.usage.byDay.length > 0 && (
              <Card className="flex flex-col gap-2">
                {data.usage.byDay.map(d => {
                  const max = Math.max(...data.usage.byDay.map(x => x.tokens), 1);
                  return (
                    <div key={d.day} className="grid grid-cols-[96px_1fr_120px] items-center gap-3 text-caption">
                      <span className="text-ink-500">{d.day}</span>
                      <span className="h-2 rounded bg-brand-600" style={{ width: `${Math.max(2, (d.tokens / max) * 100)}%` }} aria-hidden />
                      <span className="text-right text-ink-700">{d.calls} calls · {d.tokens.toLocaleString()} tokens</span>
                    </div>
                  );
                })}
              </Card>
            )}
          </section>
        </>
      )}
    </>
  );
}
