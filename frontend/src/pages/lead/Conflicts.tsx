import { AlertTriangle, CheckCircle2, MessageCircle } from 'lucide-react';
import { useState, type ReactNode } from 'react';
import { SensitivityChip, StatusBadge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Radio, Textarea } from '@/components/ui/form';
import { Banner, Card, EvidenceQuote, PageHeader, QueryState } from '@/components/ui/misc';
import { STATUS, evidenceLabel, fmtDate, sens, useClaim, useGapList, useResolveConflictLive, type ClaimDto, type GapDto } from '@/data/live';
import { useSession } from '@/lib/auth';
import { ApiError } from '@/lib/http';

export default function Conflicts() {
  const { handover } = useSession();
  const gaps = useGapList(handover?.id);
  const pairs = (gaps.data ?? []).filter(g => g.kind === 'CONFLICT' && g.claimId && g.relatedClaimId);
  return (
    <>
      <PageHeader title="Resolve conflicts" sub="When sources disagree, pick the one that is current. The other becomes Stale: hidden from recipients and kept in history." />
      <QueryState loading={gaps.isLoading} error={gaps.error} empty={!gaps.isLoading && pairs.length === 0} emptyText="No conflicts. Nothing is blocking approval here." />
      {pairs.map((g, i) => <ConflictCard key={g.id} gap={g} index={i + 1} total={pairs.length} />)}
    </>
  );
}

function ConflictCard({ gap, index, total }: { gap: GapDto; index: number; total: number }) {
  const { handover, user } = useSession();
  const a = useClaim(gap.claimId);
  const b = useClaim(gap.relatedClaimId);
  const resolve = useResolveConflictLive(handover?.id);
  const [choice, setChoice] = useState<'A' | 'B' | null>(null);
  const [reason, setReason] = useState('');
  if (a.isLoading || b.isLoading) return <Card><QueryState loading /></Card>;
  if (!a.data || !b.data) return <Card><QueryState error={a.error ?? b.error ?? new Error('One side of this conflict is hidden from you.')} /></Card>;
  const A = a.data, B = b.data;
  const resolved = A.status === 'STALE' || B.status === 'STALE' || (A.status !== 'CONFLICTING' && B.status !== 'CONFLICTING');
  const newer = (A.evidence[0]?.source.date ?? '') > (B.evidence[0]?.source.date ?? '') ? 'A' : 'B';
  const pick = choice ?? newer; // default to the newer source
  const err = resolve.error instanceof ApiError ? resolve.error.message : null;
  const order = ['PUBLIC', 'INTERNAL', 'CONFIDENTIAL', 'RESTRICTED'];
  const level = sens(order.indexOf(A.sensitivity) >= order.indexOf(B.sensitivity) ? A.sensitivity : B.sensitivity);

  return (
    <Card className="flex flex-col gap-[18px] p-6">
      <div className="flex items-start justify-between gap-3">
        <div>
          <div className="flex items-center gap-2"><StatusBadge status={resolved ? 'verified' : 'conflicting'} label={resolved ? 'Resolved' : 'Conflicting'} /><span className="text-caption text-ink-400">{index} of {total}</span></div>
          <h2 className="mt-1 text-h2 font-semibold">{A.subject ? A.subject[0].toUpperCase() + A.subject.slice(1) : 'Sources disagree'}</h2>
        </div>
        <SensitivityChip level={level} />
      </div>
      <div className="grid items-center gap-4 md:grid-cols-[1fr_36px_1fr]">
        <Side label="Source A" claim={A} newer={newer === 'A'} />
        <span className="mx-auto grid size-9 place-items-center rounded-full bg-subtle text-small font-medium text-ink-500" aria-hidden>vs</span>
        <Side label="Source B" claim={B} newer={newer === 'B'} />
      </div>
      {gap.status === 'ANSWERED' ? (
        <div className="flex flex-col gap-2 rounded-xl bg-verified-bg p-4">
          <p className="flex items-center gap-2 text-small font-medium"><MessageCircle className="size-4 text-verified" aria-hidden />{handover?.departingName}’s answer · exit interview{gap.answeredAt ? ` · ${fmtDate(gap.answeredAt)}` : ''}<StatusBadge status="verified" label="Cited testimony" /></p>
          <EvidenceQuote source="exit interview" quote={`“${gap.answer}”`} />
        </div>
      ) : (
        <Banner tone="missing" icon={MessageCircle}>{handover?.departingName} has not answered this in the exit interview yet{gap.status === 'SKIPPED' ? ' (skipped and routed to you)' : ''}.</Banner>
      )}

      {resolved ? (
        <Banner tone="verified" icon={CheckCircle2}>Resolved. The losing claim is Stale — hidden from recipients, kept in history.</Banner>
      ) : (
        <fieldset className="flex flex-col gap-2.5">
          <legend className="overline mb-2.5 text-ink-400">Resolution</legend>
          {(['A', 'B'] as const).map(k => {
            const c = k === 'A' ? A : B;
            return (
              <Radio key={k} name={`resolution-${gap.id}`} checked={pick === k} onChange={() => setChoice(k)}>
                <span className="text-small font-medium">Keep {k} — {c.title}</span>
                <span className="text-caption text-ink-500">{newer === k ? 'Newer source. ' : ''}The other claim becomes Stale.</span>
              </Radio>
            );
          })}
          <Textarea aria-label="Reason" rows={2} placeholder="Why (saved in the revision history)" value={reason} onChange={e => setReason(e.target.value)} />
          {err && <Banner tone="conflict" icon={AlertTriangle}>{err}</Banner>}
          <div className="mt-1 flex justify-end gap-2">
            <Button
              kind="primary"
              disabled={resolve.isPending}
              onClick={() => {
                const [w, l] = pick === 'A' ? [A, B] : [B, A];
                resolve.mutate({ winnerId: w.id, loserId: l.id, reason: reason.trim() || `Resolved by ${user.name}${gap.answer ? ' using the exit-interview answer' : ''}` });
              }}
            >
              {resolve.isPending ? 'Resolving…' : 'Resolve conflict'}
            </Button>
          </div>
        </fieldset>
      )}
    </Card>
  );
}

function Side({ label, claim, newer }: { label: string; claim: ClaimDto; newer: boolean }) {
  const ev = claim.evidence[0];
  const badge: ReactNode = <StatusBadge status={STATUS[claim.status]} label={claim.status === 'STALE' ? 'Stale' : newer ? 'Newer' : 'Older'} />;
  return (
    <div className="flex h-full flex-col gap-2.5 rounded-xl border border-line p-4">
      <div className="flex items-center gap-2"><span className="overline text-ink-500">{label}</span>{badge}</div>
      <p className="text-small font-medium">{claim.title}</p>
      {ev ? <EvidenceQuote source={evidenceLabel(ev)} quote={`“${ev.quote}”`} /> : <p className="text-caption text-ink-500">No visible quote.</p>}
      <p className="text-caption text-ink-500">Confidence {claim.confidence.toFixed(2)}</p>
    </div>
  );
}
