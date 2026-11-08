// Demo-only hooks for screens whose backend endpoints do not exist yet (milestone M5 and admin listings).
// Everything with a real endpoint lives in ./live.ts. Delete each hook here once its API ships.
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { AUDIT, CHECKLIST } from './mock';

const store = { checklist: structuredClone(CHECKLIST), acknowledged: false };
const delay = <T,>(v: T, ms = 120) => new Promise<T>(r => setTimeout(() => r(structuredClone(v)), ms));

const keys = { audit: ['demo', 'audit'] as const, checklist: ['demo', 'checklist'] as const, ack: ['demo', 'ack'] as const };

/** Audit log listing — the server records events but has no read API yet. */
export const useAudit = () => useQuery({ queryKey: keys.audit, queryFn: () => delay(AUDIT) });
/** Recipient checklist and acknowledgement — M5 (shares of an approved version). */
export const useChecklist = () => useQuery({ queryKey: keys.checklist, queryFn: () => delay(store.checklist) });
export const useAcknowledged = () => useQuery({ queryKey: keys.ack, queryFn: () => delay(store.acknowledged) });

export function useToggleChecklist() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id }: { id: string }) => {
      for (const list of Object.values(store.checklist)) {
        const item = list.find(x => x.id === id);
        if (item) item.done = !item.done;
      }
      return delay(true, 30);
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.checklist }),
  });
}

export function useAcknowledge() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async () => { store.acknowledged = true; return delay(true, 60); },
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.ack }),
  });
}
