import { EyeOff } from 'lucide-react';
import { useState } from 'react';
import { SensitivityChip, StatusBadge } from '@/components/ui/badge';
import { Avatar, Banner, PageHeader, QueryState, Table, Td } from '@/components/ui/misc';
import { CLAIM_TYPE_LABEL, STATUS, STATUS_LABEL, sens, useClaimList, usePreview, useProject } from '@/data/live';
import { useSession } from '@/lib/auth';
import { cn } from '@/lib/utils';

const initials = (n: string) => n.split(/\s+/).map(w => w[0]).join('').slice(0, 2).toUpperCase();

export default function ViewAs() {
  const { project, handover, user } = useSession();
  const proj = useProject(project?.id);
  const members = (proj.data?.members ?? []).filter(m => m.userId !== user.id);
  const [asId, setAsId] = useState<string | null>(null);
  const as = asId ?? members.find(m => m.userId !== handover?.departingUserId)?.userId ?? members[0]?.userId ?? null;
  const preview = usePreview(handover?.id, as);
  const mine = useClaimList(handover?.id);
  const asMember = members.find(m => m.userId === as);
  const first = asMember?.displayName.split(' ')[0] ?? 'They';

  const visible = (preview.data?.claims ?? []);
  const leadVisible = (mine.data ?? []).filter(c => c.reviewState !== 'REJECTED');
  const types = Object.keys(CLAIM_TYPE_LABEL).filter(t => leadVisible.some(c => c.type === t));

  return (
    <>
      <PageHeader title="Preview what each recipient sees" sub="Same handover, different views. Filtering runs in database queries with the recipient’s own clearance and groups." />
      <QueryState loading={proj.isLoading} error={proj.error} empty={!proj.isLoading && members.length === 0} emptyText="No other members in this project." />
      <div role="radiogroup" aria-label="Preview as" className="grid gap-3 md:grid-cols-3">
        {members.map((m, i) => {
          const on = m.userId === as;
          return (
            <button key={m.userId} type="button" role="radio" aria-checked={on} onClick={() => setAsId(m.userId)} className={cn('flex items-start gap-3 rounded-xl border p-3.5 text-left cursor-pointer', on ? 'border-brand-600 bg-brand-50 ring-1 ring-brand-600' : 'border-line bg-card hover:bg-canvas')}>
              <Avatar initials={initials(m.displayName)} tone={i + 1} />
              <span className="flex flex-col gap-1">
                <span className="text-small font-medium">{m.displayName}</span>
                <span className="text-caption text-ink-500">{m.userId === handover?.departingUserId ? 'Departing member' : m.role === 'PROJECT_LEAD' ? 'Project lead' : 'Member'}</span>
                <SensitivityChip level={sens(m.clearance)} />
              </span>
            </button>
          );
        })}
      </div>
      {preview.data && (
        <Banner icon={EyeOff}>
          {first} sees {preview.data.visibleCount} of {preview.data.visibleCount + preview.data.hiddenFromThemCount} claims. {preview.data.hiddenFromThemCount} {preview.data.hiddenFromThemCount === 1 ? 'is' : 'are'} hidden
          {' '}by clearance ({sens(preview.data.as.clearance)}) or groups ({preview.data.as.groups.join(', ') || 'none'}). Rejected claims are never shown to recipients.
        </Banner>
      )}
      <div className="grid items-start gap-6 xl:grid-cols-[1fr_380px]">
        <section aria-label={`Preview for ${asMember?.displayName ?? 'recipient'}`} className="overflow-hidden rounded-xl border border-line-strong bg-canvas">
          <div className="flex items-center gap-1.5 bg-subtle px-3.5 py-2.5" aria-hidden>
            <span className="size-2 rounded-full bg-line-strong" /><span className="size-2 rounded-full bg-line-strong" /><span className="size-2 rounded-full bg-line-strong" />
            <span className="ml-3 text-caption text-ink-500">Recipient view · {asMember?.displayName} · read-only preview</span>
          </div>
          <div className="flex flex-col gap-2.5 p-5">
            <QueryState loading={preview.isLoading} error={preview.error} empty={!!preview.data && visible.length === 0} emptyText={`${first} would see no claims.`} />
            {visible.map(c => (
              <div key={c.id} className="flex flex-wrap items-center gap-2.5 rounded-lg border border-line bg-card p-3">
                <StatusBadge status={STATUS[c.status]} label={STATUS_LABEL[c.status]} />
                <span className="flex-1 text-small">{c.title}</span>
                <SensitivityChip level={sens(c.sensitivity)} />
              </div>
            ))}
            {!!preview.data?.hiddenFromThemCount && (
              <div className="flex items-center gap-2.5 rounded-lg border border-dashed border-line-strong p-3 text-small text-ink-500">
                <EyeOff className="size-4 shrink-0 text-ink-400" aria-hidden />
                {preview.data.hiddenFromThemCount} more claim{preview.data.hiddenFromThemCount > 1 ? 's are' : ' is'} hidden for {first}.
              </div>
            )}
          </div>
        </section>
        <section className="flex flex-col gap-3">
          <h2 className="text-h3 font-semibold">By claim type</h2>
          <Table label="Visibility by claim type" columns={[{ label: 'Type' }, { label: 'You' }, { label: first }]}>
            {types.map(t => {
              const you = leadVisible.filter(c => c.type === t).length;
              const them = visible.filter(c => c.type === t).length;
              return <tr key={t}><Td className="text-ink-900">{CLAIM_TYPE_LABEL[t]}</Td><Td>{you}</Td><Td className={them < you ? 'font-medium text-conflict' : ''}>{them}</Td></tr>;
            })}
          </Table>
        </section>
      </div>
    </>
  );
}
