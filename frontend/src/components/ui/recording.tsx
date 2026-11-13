import { AudioLines, Loader2, Play, X } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import { ApiError, fetchBlob } from '@/lib/http';

/**
 * Playing the moment a quote was said. Any quote from a meeting recording calls {@link playMoment}; one player,
 * mounted once in the app shell, fetches the audio with the session token (an <audio> tag cannot send it),
 * keeps it in memory for the visit and jumps to the second.
 */
type Request = { sourceId: string; at: number; label: string };
const listeners = new Set<(r: Request) => void>();
const urls = new Map<string, string>();

export function playMoment(sourceId: string, at: number, label: string) {
  listeners.forEach(f => f({ sourceId, at, label }));
}

export const clock = (s: number) => {
  const t = Math.max(0, Math.floor(s));
  const h = Math.floor(t / 3600);
  const m = Math.floor((t % 3600) / 60);
  const sec = String(t % 60).padStart(2, '0');
  return h ? `${h}:${String(m).padStart(2, '0')}:${sec}` : `${m}:${sec}`;
};

/** The small "▶ 0:50" button on a quote. */
export function PlayMomentButton({ sourceId, at, label }: { sourceId: string; at: number; label: string }) {
  return (
    <button type="button" onClick={() => playMoment(sourceId, at, label)}
      className="inline-flex items-center gap-1 rounded-full bg-brand-50 px-2 py-0.5 font-sans text-caption font-semibold text-brand-700 ring-1 ring-brand-100 hover:bg-brand-100 cursor-pointer"
      aria-label={`Play the recording from ${clock(at)}`}>
      <Play className="size-3 fill-current" aria-hidden />{clock(at)}
    </button>
  );
}

export function RecordingPlayer() {
  const [req, setReq] = useState<Request | null>(null);
  const [src, setSrc] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const audio = useRef<HTMLAudioElement>(null);

  useEffect(() => {
    const on = (r: Request) => setReq(r);
    listeners.add(on);
    return () => { listeners.delete(on); };
  }, []);

  useEffect(() => {
    if (!req) return;
    let cancelled = false;
    setError(null);
    const cached = urls.get(req.sourceId);
    if (cached) { setSrc(cached); return; }
    setSrc(null);
    fetchBlob(`/api/sources/${req.sourceId}/media`)
      .then(b => { if (cancelled) return; const u = URL.createObjectURL(b); urls.set(req.sourceId, u); setSrc(u); })
      .catch(e => { if (!cancelled) setError(e instanceof ApiError ? e.message : 'The recording could not be loaded'); });
    return () => { cancelled = true; };
  }, [req]);

  // Jump to the moment once the audio is ready (and again on every new request for the same recording).
  useEffect(() => {
    const a = audio.current;
    if (!a || !src || !req) return;
    const go = () => { a.currentTime = req.at; a.play().catch(() => undefined); };
    if (a.readyState >= 1) go(); else a.addEventListener('loadedmetadata', go, { once: true });
    return () => a.removeEventListener('loadedmetadata', go);
  }, [src, req]);

  if (!req) return null;
  return (
    <div role="region" aria-label="Recording player"
      className="fixed right-4 bottom-4 z-40 w-[min(380px,calc(100vw-2rem))] rounded-xl border border-line bg-card p-3 shadow-pop">
      <div className="flex items-center gap-2.5">
        <span className="grid size-8 shrink-0 place-items-center rounded-lg bg-brand-50 text-brand-700"><AudioLines className="size-4" aria-hidden /></span>
        <span className="min-w-0 flex-1">
          <span className="block truncate text-small font-medium text-ink-900">{req.label}</span>
          <span className="block text-caption text-ink-500">From {clock(req.at)}</span>
        </span>
        <button type="button" aria-label="Close player" onClick={() => { audio.current?.pause(); setReq(null); }} className="rounded-md p-1 text-ink-500 hover:bg-subtle cursor-pointer"><X className="size-4" /></button>
      </div>
      {error ? <p className="mt-2 text-caption text-conflict">{error}</p>
        : src ? <audio ref={audio} src={src} controls className="mt-2 h-9 w-full" />
          : <p className="mt-2 flex items-center gap-1.5 text-caption text-ink-500"><Loader2 className="size-3.5 animate-spin" aria-hidden />Loading the recording…</p>}
    </div>
  );
}
