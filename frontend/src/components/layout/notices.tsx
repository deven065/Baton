import { AlertTriangle, RefreshCw, X } from 'lucide-react';
import { Link, useNavigate } from 'react-router-dom';
import { Button } from '@/components/ui/button';
import { useDismissNotice, useDrift, useLiving, useNotices, type NoticeDto } from '@/data/live';
import { useAuth } from '@/lib/auth';
import { cn } from '@/lib/utils';

/**
 * What Baton's background sync found for this handover: new evidence in a connected tool, or a tool it could no
 * longer read. For the lead only. New evidence on an approved handover offers the next version; recipients keep the
 * approved one until it is approved and shared.
 */
export function EvidenceNotices({ handoverId }: { handoverId?: string | null }) {
  const { session, user } = useAuth();
  const lead = user?.appRole === 'lead' && !!session?.handover;
  const notices = useNotices(handoverId, lead);
  const dismiss = useDismissNotice(handoverId);
  const drift = useDrift(lead ? handoverId : null);
  const { reopen } = useLiving(handoverId);
  const navigate = useNavigate();
  if (!lead || !notices.data?.length) return null;
  const approved = drift.data?.versionNo != null && (drift.data.status === 'APPROVED' || drift.data.status === 'SHARED');

  return (
    <div className="flex flex-col gap-2" role="region" aria-label="New evidence">
      {notices.data.map((n: NoticeDto) => {
        const failed = n.kind === 'SYNC_FAILED';
        const updating = n.details.reconstructing === true;
        return (
          <div key={n.id} className={cn('flex flex-wrap items-center gap-3 rounded-xl border p-4', failed ? 'border-conflict/30 bg-conflict-bg' : 'border-brand-100 bg-brand-50')}>
            {failed ? <AlertTriangle className="size-5 shrink-0 text-conflict" aria-hidden /> : <RefreshCw className="size-5 shrink-0 text-brand-700" aria-hidden />}
            <p className="min-w-[220px] flex-1 text-small text-ink-900">{n.message}</p>
            <div className="flex items-center gap-2">
              {failed && <Link to="/app/sources"><Button>Check the source</Button></Link>}
              {!failed && approved && (
                <Button kind="primary" disabled={reopen.isPending}
                  onClick={() => reopen.mutate(undefined, { onSuccess: () => navigate('/app/review') })}>
                  {reopen.isPending ? 'Opening…' : `Start version ${(drift.data?.versionNo ?? 0) + 1}`}
                </Button>
              )}
              {!failed && !approved && <Link to={updating ? '/app/reconstruct' : '/app/review'}><Button kind="primary">{updating ? 'See the update' : 'Review claims'}</Button></Link>}
              <button type="button" aria-label="Dismiss" title="Dismiss" onClick={() => dismiss.mutate(n.id)} className="rounded-md p-1.5 text-ink-500 hover:bg-card cursor-pointer"><X className="size-4" /></button>
            </div>
          </div>
        );
      })}
    </div>
  );
}
