import { ChevronRight, Info, Layers, Users } from 'lucide-react';
import { useState } from 'react';
import { Chip, FilterChip, SensitivityChip, StatusBadge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Avatar, Banner, Card, DemoNotice, IconTile, PageHeader, Table, Td } from '@/components/ui/misc';
import { useAudit } from '@/data/api';
import { GROUPS, PEOPLE } from '@/data/mock';
import type { Person } from '@/data/types';
import { cn } from '@/lib/utils';

const USERS: (Pick<Person, 'name' | 'initials' | 'tone' | 'email' | 'clearance' | 'groups'> & { roleLabel: string })[] = [
  ...['priya', 'aarav', 'rohan', 'sara', 'kabir'].map(id => {
    const p = PEOPLE[id];
    const roleLabel = { priya: 'Project lead', aarav: 'Member · leaving', rohan: 'Member', sara: 'Volunteer', kabir: 'Org admin' }[id]!;
    return { ...p, roleLabel };
  }),
  { name: 'Ishita Rao', initials: 'IR', tone: 4, email: 'ishita.r@northfield.club', clearance: 'Internal', groups: ['events'], roleLabel: 'Member · leaving' },
];

export function UsersGroups() {
  const [tab, setTab] = useState('Users');
  return (
    <>
      <PageHeader title="Users & groups" sub="Clearance and groups decide what each person can see. Changes are recorded in the audit log." right={<><Button>New group</Button><Button kind="primary">Invite people</Button></>} />
      <DemoNotice what="There is no users-and-groups API yet. Real accounts and groups are seeded on the server (see README)." />
      <div role="tablist" className="flex gap-1 border-b border-line">
        {[['Users', 24], ['Groups', 4], ['Pending invites', 2]].map(([t, n]) => (
          <button key={t} role="tab" type="button" aria-selected={tab === t} onClick={() => setTab(t as string)} className={cn('-mb-px border-b-2 px-3 py-2.5 text-small font-medium cursor-pointer', tab === t ? 'border-brand-600 text-brand-700' : 'border-transparent text-ink-500 hover:text-ink-900')}>
            {t} · {n}
          </button>
        ))}
      </div>
      <div className="grid items-start gap-6 xl:grid-cols-[1fr_360px]">
        <Table label="Users" columns={[{ label: 'Name' }, { label: 'Role' }, { label: 'Clearance' }, { label: 'Groups' }]}>
          {USERS.map(u => (
            <tr key={u.email} className="hover:bg-canvas">
              <Td><span className="flex items-center gap-2.5"><Avatar initials={u.initials} tone={u.tone} size="sm" /><span><span className="block font-medium text-ink-900">{u.name}</span><span className="block text-caption text-ink-500">{u.email}</span></span></span></Td>
              <Td className="whitespace-nowrap">{u.roleLabel}</Td>
              <Td><SensitivityChip level={u.clearance} /></Td>
              <Td><span className="flex flex-wrap gap-1">{u.groups.map(g => <Chip key={g}>{g}</Chip>)}</span></Td>
            </tr>
          ))}
        </Table>
        <Card className="flex flex-col gap-2.5">
          <h2 className="text-h3 font-semibold">Access groups</h2>
          {GROUPS.map(g => (
            <button key={g.name} type="button" className="flex items-center gap-2.5 rounded-xl border border-line p-3 text-left hover:bg-canvas cursor-pointer">
              <IconTile icon={Users} size={32} />
              <span className="flex-1"><span className="block text-small font-medium">{g.name}</span><span className="block text-caption text-ink-500">{g.detail}</span></span>
              <ChevronRight className="size-3.5 text-ink-400" aria-hidden />
            </button>
          ))}
          <Banner icon={Info}>Sensitivity order: Public &lt; Internal &lt; Confidential &lt; Restricted. A person sees a claim only if their clearance and groups cover all of its evidence.</Banner>
        </Card>
      </div>
    </>
  );
}

const EVENT_FILTERS = ['All events', 'Views', 'Edits', 'Approvals', 'AI requests', 'Redaction', 'Access changes'] as const;
const MATCH: Record<string, string[]> = {
  Views: ['Viewed share'], Edits: ['Edited claim', 'Answered'], Approvals: ['Approved'], 'AI requests': ['AI request'], Redaction: ['Redacted'], 'Access changes': ['Access change', 'Connected'],
};

export function AuditLog() {
  const { data = [] } = useAudit();
  const [f, setF] = useState<string>('All events');
  const rows = f === 'All events' ? data : data.filter(r => MATCH[f]?.includes(r.event));
  return (
    <>
      <PageHeader title="Audit log" sub="Every change, every AI request and every view of a share. Append-only." right={<><Button>Filters</Button><Button>Export CSV</Button></>} />
      <DemoNotice what="The server already records every event in its audit table; a read API for this page is still to come." />
      <div className="flex flex-wrap gap-2" role="toolbar" aria-label="Event type">
        {EVENT_FILTERS.map(e => <FilterChip key={e} active={f === e} onClick={() => setF(e)}>{e}</FilterChip>)}
      </div>
      <Table label="Audit events" columns={[{ label: 'Time' }, { label: 'Actor' }, { label: 'Event' }, { label: 'Target' }, { label: 'Detail' }]}>
        {rows.map(r => {
          const p = r.actor ? PEOPLE[r.actor] : null;
          return (
            <tr key={r.at + r.event}>
              <Td className="font-mono text-caption whitespace-nowrap text-ink-500">{r.at}</Td>
              <Td>
                <span className="flex items-center gap-2 whitespace-nowrap">
                  {p ? <Avatar initials={p.initials} tone={p.tone} size="sm" /> : <span className="grid size-6 place-items-center rounded-full bg-subtle text-ink-500"><Layers className="size-3" aria-hidden /></span>}
                  <span className="text-ink-900">{p ? p.name : 'System'}</span>
                </span>
              </Td>
              <Td><StatusBadge status={r.tone} label={r.event} /></Td>
              <Td>{r.target}</Td>
              <Td>{r.detail}</Td>
            </tr>
          );
        })}
        {rows.length === 0 && <tr><Td className="text-ink-500">No events of this type.</Td></tr>}
      </Table>
    </>
  );
}
