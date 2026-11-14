import { Maximize2, MessageCircle, Mic, MicOff, PhoneOff, Send, Video, VideoOff, X } from 'lucide-react';
import { useEffect, useRef, useState, type FormEvent, type KeyboardEvent } from 'react';
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/form';
import { useAuth } from '@/lib/auth';
import { ApiError } from '@/lib/http';
import { cn } from '@/lib/utils';
import { QUALITIES, useCall, type QualityId, type Remote } from './useCall';
import { useRoom, type ChatMessage, type Participant } from './useRoom';

const time = (iso: string) => new Date(iso).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
const first = (n: string) => n.split(/\s+/)[0] ?? n;
const readKey = (projectId: string) => `baton.room.read.${projectId}`;
const loadRead = (projectId: string) => { try { return localStorage.getItem(readKey(projectId)) ?? new Date().toISOString(); } catch { return new Date().toISOString(); } };
const saveRead = (projectId: string, at: string) => { try { localStorage.setItem(readKey(projectId), at); } catch { /* private mode: unread just resets */ } };

/**
 * The team room: a button in the corner of every page that opens the project's chat and its video call. It lives in
 * the page shell, so a call carries on while you move between pages.
 */
export function RoomDock() {
  const { session, user } = useAuth();
  const projectId = session?.project?.id;
  if (!projectId || !user) return null;
  return <Dock key={projectId} projectId={projectId} selfId={user.id} selfName={user.name} />;
}

function Dock({ projectId, selfId, selfName }: { projectId: string; selfId: string; selfName: string }) {
  const [open, setOpen] = useState(false);
  const [tab, setTab] = useState<'chat' | 'call'>('chat');
  const call = useCall(projectId, selfId);
  const room = useRoom(projectId, { onSignal: call.onSignal, onPeerLeft: call.onPeerLeft, onReconnect: call.onReconnect });
  const [readAt, setReadAt] = useState(() => loadRead(projectId));
  const latest = room.messages.at(-1)?.createdAt;

  // Looking at the chat marks it read.
  useEffect(() => {
    if (open && tab === 'chat' && latest && latest > readAt) { setReadAt(latest); saveRead(projectId, latest); }
  }, [open, tab, latest, readAt, projectId]);

  const unread = room.messages.filter(m => m.senderId !== selfId && m.createdAt > readAt).length;
  const others = room.inCall.filter(p => p.userId !== selfId);
  const live = call.status !== 'idle';
  const wide = open && tab === 'call' && live;

  return (
    <div className="pointer-events-none fixed inset-x-3 bottom-3 z-40 flex flex-col items-end gap-3 sm:inset-x-auto sm:right-5 sm:bottom-5">
      <section
        aria-label="Team room" hidden={!open}
        className={cn('pointer-events-auto flex h-[min(640px,calc(100dvh-6.5rem))] w-full flex-col overflow-hidden rounded-2xl border border-line bg-card shadow-pop',
          wide ? 'sm:w-[min(980px,calc(100vw-2.5rem))]' : 'sm:w-[400px]')}
      >
        <header className="flex items-center gap-1 border-b border-line px-3 py-2">
          <div role="tablist" aria-label="Team room" className="flex flex-1 gap-1">
            {([['chat', 'Chat', MessageCircle], ['call', 'Video call', Video]] as const).map(([id, label, Icon]) => (
              <button key={id} role="tab" type="button" aria-selected={tab === id} onClick={() => setTab(id)}
                className={cn('flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-small font-medium cursor-pointer', tab === id ? 'bg-brand-50 text-brand-700' : 'text-ink-500 hover:bg-subtle hover:text-ink-900')}>
                <Icon className="size-4" aria-hidden />{label}
                {id === 'call' && (live || others.length > 0) && <span className="size-2 rounded-full bg-verified" aria-label="A call is on" />}
              </button>
            ))}
          </div>
          <button type="button" aria-label="Close team room" onClick={() => setOpen(false)} className="rounded-md p-1.5 text-ink-500 hover:bg-subtle cursor-pointer"><X className="size-4" /></button>
        </header>
        {!room.connected && <p role="status" className="bg-inferred/10 px-4 py-1.5 text-caption text-ink-700">Reconnecting…</p>}
        <div className="flex min-h-0 flex-1 flex-col">
          <div hidden={tab !== 'chat'} className="flex min-h-0 flex-1 flex-col"><Chat messages={room.messages} selfId={selfId} onSend={room.send} /></div>
          <div hidden={tab !== 'call'} className="flex min-h-0 flex-1 flex-col"><CallView call={call} inCall={others} selfName={selfName} /></div>
        </div>
      </section>

      <button type="button" onClick={() => { setOpen(o => !o); if (!open && others.length > 0 && !live) setTab('call'); }}
        aria-label={open ? 'Close team room' : unread ? `Open team room, ${unread} unread` : others.length && !live ? 'Open team room, a call is on' : 'Open team room'} aria-expanded={open}
        className="pointer-events-auto relative grid size-14 place-items-center rounded-full bg-brand-600 text-white shadow-pop transition-transform hover:bg-brand-700 active:scale-95 cursor-pointer">
        {live ? <Video className="size-6" aria-hidden /> : <MessageCircle className="size-6" aria-hidden />}
        {unread > 0 && <span className="absolute -top-1 -right-1 grid min-w-5 place-items-center rounded-full bg-conflict px-1 text-caption font-semibold text-white">{unread > 9 ? '9+' : unread}</span>}
        {unread === 0 && (live || others.length > 0) && <span className="absolute -top-0.5 -right-0.5 size-3.5 rounded-full border-2 border-white bg-verified" />}
      </button>
    </div>
  );
}

function Chat({ messages, selfId, onSend }: { messages: ChatMessage[]; selfId: string; onSend: (body: string) => Promise<void> }) {
  const [text, setText] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const end = useRef<HTMLDivElement>(null);
  useEffect(() => { end.current?.scrollIntoView({ block: 'end' }); }, [messages.length]);

  const submit = async (e?: FormEvent) => {
    e?.preventDefault();
    const body = text.trim();
    if (!body || busy) return;
    setBusy(true);
    setErr(null);
    try { await onSend(body); setText(''); }
    catch (x) {
      const d = x instanceof ApiError ? (x.details as Record<string, string> | undefined)?.body : undefined;
      setErr(d ?? (x instanceof Error ? x.message : 'Could not send'));
    } finally { setBusy(false); }
  };
  const onKey = (e: KeyboardEvent<HTMLTextAreaElement>) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); void submit(); } };

  return (
    <>
      <ol className="flex min-h-0 flex-1 flex-col gap-2 overflow-y-auto px-4 py-3" aria-label="Messages" aria-live="polite">
        {messages.length === 0 && <li className="m-auto max-w-[260px] text-center text-small text-ink-500">No messages yet. Say hello to the people on this project, or ask a quick question before a call.</li>}
        {messages.map((m, i) => {
          const mine = m.senderId === selfId;
          const newSender = messages[i - 1]?.senderId !== m.senderId;
          return (
            <li key={m.id} className={cn('flex max-w-[85%] flex-col', mine ? 'items-end self-end' : 'items-start self-start')}>
              {newSender && !mine && <span className="mb-0.5 px-1 text-caption font-medium text-ink-500">{m.senderName}</span>}
              <span className={cn('whitespace-pre-wrap break-words rounded-2xl px-3 py-2 text-small', mine ? 'rounded-br-md bg-brand-600 text-white' : 'rounded-bl-md bg-subtle text-ink-900')}>{m.body}</span>
              <span className="px-1 pt-0.5 text-caption text-ink-400">{time(m.createdAt)}</span>
            </li>
          );
        })}
        <div ref={end} />
      </ol>
      <form onSubmit={submit} className="border-t border-line p-3">
        {err && <p role="alert" className="pb-2 text-caption text-conflict">{err}</p>}
        <div className="flex items-end gap-2">
          <Textarea aria-label="Message" rows={1} value={text} maxLength={2000} onChange={e => { setText(e.target.value); setErr(null); }} onKeyDown={onKey}
            placeholder="Write a message" className="max-h-28 min-h-[40px] resize-none" />
          <Button type="submit" kind="primary" aria-label="Send message" disabled={!text.trim() || busy}><Send className="size-4" aria-hidden /></Button>
        </div>
        <p className="pt-1.5 text-caption text-ink-400">Enter to send. Visible to everyone on this project. Don’t paste keys or passwords.</p>
      </form>
    </>
  );
}

function CallView({ call, inCall, selfName }: { call: ReturnType<typeof useCall>; inCall: Participant[]; selfName: string }) {
  if (call.status === 'idle') {
    const names = inCall.map(p => first(p.name));
    return (
      <div className="flex flex-1 flex-col items-center justify-center gap-4 overflow-y-auto p-6 text-center">
        <span className="grid size-14 place-items-center rounded-full bg-brand-50 text-brand-700"><Video className="size-7" aria-hidden /></span>
        <div>
          <h2 className="text-h3 font-semibold">{inCall.length ? `${names.join(', ')} ${inCall.length === 1 ? 'is' : 'are'} on a call` : 'Start a video call'}</h2>
          <p className="mx-auto mt-1 max-w-[320px] text-small text-ink-500">Talk it through with the people on this project. Up to four can be on a call, and video goes straight between your browsers.</p>
        </div>
        <label className="flex flex-col gap-1.5 text-left">
          <span className="text-caption font-medium text-ink-700">Video quality</span>
          <QualityPicker value={call.quality} onChange={call.setQuality} />
        </label>
        {call.error && <p role="alert" className="max-w-[340px] text-small text-conflict">{call.error}</p>}
        <Button kind="primary" size="lg" onClick={() => void call.start()} disabled={call.status !== 'idle'}><Video className="size-5" aria-hidden />{inCall.length ? 'Join the call' : 'Start the call'}</Button>
        <p className="max-w-[320px] text-caption text-ink-400">Your browser will ask to use your camera and microphone. 4K needs a 4K camera and a fast connection; the call shows what is really being sent.</p>
      </div>
    );
  }
  const tiles = call.remotes.length + 1;
  return (
    <div className="flex min-h-0 flex-1 flex-col bg-ink-900">
      <div className={cn('grid min-h-0 flex-1 auto-rows-fr gap-2 overflow-y-auto p-2', tiles > 1 ? 'sm:grid-cols-2' : 'grid-cols-1')}>
        {call.remotes.map(r => <Tile key={r.userId} remote={r} />)}
        {call.remotes.length === 0 && (
          <div className="grid place-items-center rounded-xl border border-dashed border-ink-500 p-6 text-center text-small text-ink-300">
            {call.status === 'starting' ? 'Joining…' : 'Waiting for others to join. They can open the team room and tap Join the call.'}
          </div>
        )}
        <Tile local stream={call.local} name={`${selfName} (you)`} off={!call.camOn || !call.hasCamera} muted />
      </div>
      {call.notice && <p className="bg-inferred/20 px-4 py-1.5 text-caption text-white">{call.notice}</p>}
      {call.error && <p role="alert" className="bg-conflict/30 px-4 py-1.5 text-caption text-white">{call.error}</p>}
      <div className="flex flex-wrap items-center justify-center gap-2 border-t border-white/10 bg-ink-900 px-3 py-2.5">
        <Control on={call.micOn} label={call.micOn ? 'Mute microphone' : 'Unmute microphone'} onClick={call.toggleMic} icon={call.micOn ? Mic : MicOff} />
        {call.hasCamera && <Control on={call.camOn} label={call.camOn ? 'Turn camera off' : 'Turn camera on'} onClick={call.toggleCam} icon={call.camOn ? Video : VideoOff} />}
        <QualityPicker value={call.quality} onChange={call.setQuality} dark />
        <Button kind="danger" aria-label="Leave the call" onClick={call.leave}><PhoneOff className="size-4" aria-hidden />Leave</Button>
        <p className="basis-full text-center text-caption text-ink-300">
          {call.sent ? `Sending ${call.sent.width}×${call.sent.height}${call.sent.fps ? ` at ${call.sent.fps} fps` : ''}` : 'Sound only'}
          {call.quality === '2160' && call.remotes.length > 1 ? ' · 4K to several people needs a very fast connection; the picture steps down by itself if it struggles' : ''}
        </p>
      </div>
    </div>
  );
}

function QualityPicker({ value, onChange, dark }: { value: QualityId; onChange: (q: QualityId) => void; dark?: boolean }) {
  return (
    <select aria-label="Video quality" value={value} onChange={e => onChange(e.target.value as QualityId)}
      className={cn('rounded-lg border px-2.5 py-2 text-small', dark ? 'border-white/20 bg-white/10 text-white' : 'border-line-strong bg-card text-ink-900')}>
      {QUALITIES.map(q => <option key={q.id} value={q.id} className="text-ink-900">{q.label}</option>)}
    </select>
  );
}

function Control({ on, label, onClick, icon: Icon }: { on: boolean; label: string; onClick: () => void; icon: typeof Mic }) {
  return (
    <button type="button" aria-label={label} aria-pressed={!on} title={label} onClick={onClick}
      className={cn('grid size-10 place-items-center rounded-full cursor-pointer', on ? 'bg-white/15 text-white hover:bg-white/25' : 'bg-white text-ink-900')}>
      <Icon className="size-5" aria-hidden />
    </button>
  );
}

function Tile({ remote, local, stream, name, muted, off }: { remote?: Remote; local?: boolean; stream?: MediaStream | null; name?: string; muted?: boolean; off?: boolean }) {
  const ref = useRef<HTMLVideoElement>(null);
  const [size, setSize] = useState('');
  const media = local ? stream ?? null : remote?.stream ?? null;
  useEffect(() => {
    const v = ref.current;
    if (!v) return;
    if (v.srcObject !== media) v.srcObject = media;
    const update = () => setSize(v.videoWidth ? `${v.videoWidth}×${v.videoHeight}` : '');
    v.addEventListener('resize', update);
    v.addEventListener('loadedmetadata', update);
    return () => { v.removeEventListener('resize', update); v.removeEventListener('loadedmetadata', update); };
  }, [media]);
  const label = local ? name : remote?.name;
  const status = remote && remote.state !== 'connected' ? (remote.state === 'failed' ? 'Couldn’t connect. This network may block direct video; ask your admin to set up a TURN relay.' : 'Connecting…') : null;
  return (
    <div className="relative min-h-[140px] overflow-hidden rounded-xl bg-black">
      <video ref={ref} autoPlay playsInline muted={muted} className={cn('size-full object-contain', local && 'scale-x-[-1]', off && 'invisible')} />
      {(off || !media) && !status && <div className="absolute inset-0 grid place-items-center text-small text-ink-300">{off ? 'Camera off' : ''}</div>}
      {status && <div className="absolute inset-0 grid place-items-center p-4 text-center text-small text-white/90">{status}</div>}
      <span className="absolute bottom-2 left-2 rounded-md bg-black/60 px-2 py-0.5 text-caption text-white">{label}{size ? ` · ${size}` : ''}</span>
      {!local && media && (
        <button type="button" aria-label={`Full screen: ${label}`} onClick={() => void ref.current?.requestFullscreen?.()}
          className="absolute top-2 right-2 rounded-md bg-black/60 p-1.5 text-white hover:bg-black/80 cursor-pointer"><Maximize2 className="size-3.5" /></button>
      )}
    </div>
  );
}
