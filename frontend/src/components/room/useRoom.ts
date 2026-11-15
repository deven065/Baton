import { useCallback, useEffect, useRef, useState } from 'react';
import { api, streamEvents } from '@/lib/http';

export interface ChatMessage { id: string; senderId: string | null; senderName: string; body: string; createdAt: string }
export interface Participant { userId: string; name: string }
export interface SignalEvent { from: string; fromName: string; kind: 'offer' | 'answer' | 'ice'; data: string }

interface Handlers {
  onSignal: (s: SignalEvent) => void;
  onPeerLeft: (userId: string) => void;
  /** Called when the live connection comes back after a break, so the call can drop peers that went away. */
  onReconnect?: (inCall: Participant[]) => void;
  /** Someone you were calling said no. */
  onDeclined?: (userId: string, name: string) => void;
}

/** Someone is calling you directly and has not been answered yet. */
export interface Ring { from: string; fromName: string }

const base = (projectId: string) => `/api/projects/${projectId}/room`;
const wait = (ms: number, signal: AbortSignal) => new Promise<void>(res => {
  const t = window.setTimeout(res, ms);
  signal.addEventListener('abort', () => { window.clearTimeout(t); res(); }, { once: true });
});

/**
 * The project's team room as the browser sees it: the chat history, new messages as they arrive, and who is on the
 * call. One live stream carries all of it and is reopened if it drops, reloading the chat so nothing is missed.
 */
export function useRoom(projectId: string | undefined, handlers: Handlers) {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [inCall, setInCall] = useState<Participant[]>([]);
  const [connected, setConnected] = useState(false);
  const [ring, setRing] = useState<Ring | null>(null);
  const [online, setOnline] = useState<string[]>([]);
  const handlersRef = useRef(handlers);
  handlersRef.current = handlers;

  const add = useCallback((m: ChatMessage) => setMessages(list => (list.some(x => x.id === m.id) ? list : [...list, m])), []);

  useEffect(() => {
    if (!projectId) return;
    const ctl = new AbortController();
    setMessages([]);
    setInCall([]);
    (async () => {
      let attempt = 0;
      while (!ctl.signal.aborted) {
        try {
          const history = await api<ChatMessage[]>(`${base(projectId)}/chat`, { signal: ctl.signal });
          setMessages(list => {
            const seen = new Set(history.map(h => h.id));
            return [...history, ...list.filter(m => !seen.has(m.id))].sort((a, b) => a.createdAt.localeCompare(b.createdAt));
          });
          await streamEvents(`${base(projectId)}/events`, (event, data) => {
            attempt = 0;
            setConnected(true);
            if (event === 'hello') {
              const now = (data as { inCall: Participant[] }).inCall;
              setInCall(now);
              handlersRef.current.onReconnect?.(now);
            } else if (event === 'chat') {
              add(data as ChatMessage);
            } else if (event === 'call-joined') {
              // Only the general call is listed as "on a call"; a direct call is private to its two people.
              const p = data as Participant & { room?: string };
              if (!p.room || p.room === 'general') setInCall(list => (list.some(x => x.userId === p.userId) ? list : [...list, p]));
              else setRing(r => (r && r.from === p.userId ? null : r)); // they picked up (or we did): stop ringing
            } else if (event === 'call-left') {
              const { userId, room } = data as { userId: string; room?: string };
              if (!room || room === 'general') setInCall(list => list.filter(x => x.userId !== userId));
              handlersRef.current.onPeerLeft(userId);
            } else if (event === 'call-ring') {
              setRing(data as Ring);
            } else if (event === 'call-cancelled') {
              const { from } = data as { from: string };
              setRing(r => (r && r.from === from ? null : r));
            } else if (event === 'call-declined') {
              const d = data as { from: string; fromName: string };
              handlersRef.current.onDeclined?.(d.from, d.fromName);
            } else if (event === 'signal') {
              handlersRef.current.onSignal(data as SignalEvent);
            }
          }, ctl.signal);
        } catch { /* offline or the stream was refused: try again below */ }
        setConnected(false);
        if (ctl.signal.aborted) break;
        await wait(Math.min(1000 * 2 ** attempt++, 15_000), ctl.signal);
      }
    })();
    return () => ctl.abort();
  }, [projectId, add]);

  // Who has the room open right now, for the "call someone" list.
  useEffect(() => {
    if (!projectId) return;
    let stop = false;
    const load = () => api<string[]>(`${base(projectId)}/online`).then(ids => { if (!stop) setOnline(ids); }).catch(() => { /* try again next time */ });
    load();
    const id = window.setInterval(load, 10_000);
    return () => { stop = true; window.clearInterval(id); };
  }, [projectId]);

  const dismissRing = useCallback(() => setRing(null), []);

  const send = useCallback(async (body: string) => {
    if (!projectId) return;
    add(await api<ChatMessage>(`${base(projectId)}/chat`, { method: 'POST', json: { body } }));
  }, [projectId, add]);

  return { messages, inCall, connected, send, ring, dismissRing, online };
}
