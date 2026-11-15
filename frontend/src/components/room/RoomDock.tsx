import { Maximize2, MessageCircle, Mic, MicOff, Minimize2, Phone, PhoneOff, Send, SwitchCamera, Users, Video, VideoOff, X } from 'lucide-react';
import { useEffect, useRef, useState, type FormEvent, type KeyboardEvent } from 'react';
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/form';
import { useProject } from '@/data/live';
import { useAuth } from '@/lib/auth';
import { ApiError } from '@/lib/http';
import { cn } from '@/lib/utils';
import { FRAME_RATES, QUALITIES, SMOOTH_FPS, useCall, type FpsId, type QualityId, type Remote } from './useCall';
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
  const room = useRoom(projectId, { onSignal: call.onSignal, onPeerLeft: call.onPeerLeft, onReconnect: call.onReconnect, onDeclined: call.onDeclined });
  const project = useProject(projectId);
  const members = (project.data?.members ?? []).filter(m => m.userId !== selfId)
    .map(m => ({ userId: m.userId, name: m.displayName, lead: m.role === 'PROJECT_LEAD' }));
  const { ring, dismissRing } = room;
  // A ring that nobody answers stops after a while.
  useEffect(() => {
    if (!ring) return;
    const t = window.setTimeout(dismissRing, 45_000);
    return () => window.clearTimeout(t);
  }, [ring, dismissRing]);
  const answer = () => {
    if (!ring) return;
    const from = { userId: ring.from, name: ring.fromName };
    dismissRing();
    if (call.status !== 'idle') call.leave();
    setOpen(true);
    setTab('call');
    void call.start(from);
  };
  const refuse = () => {
    if (!ring) return;
    call.decline(ring.from);
    dismissRing();
  };
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
          <div hidden={tab !== 'call'} className="flex min-h-0 flex-1 flex-col"><CallView call={call} inCall={others} selfName={selfName} members={members} online={room.online} /></div>
        </div>
      </section>

      {ring && (
        <div role="alertdialog" aria-label={`${ring.fromName} is calling you`}
          className="pointer-events-auto w-full rounded-2xl border border-line bg-card p-4 shadow-pop sm:w-[340px]">
          <p className="flex items-center gap-3 text-body font-semibold">
            <span className="grid size-10 shrink-0 animate-pulse place-items-center rounded-full bg-verified/15 text-verified"><Phone className="size-5" aria-hidden /></span>
            <span className="min-w-0">{ring.fromName}<span className="block text-small font-normal text-ink-500">is calling you</span></span>
          </p>
          <div className="mt-3 flex gap-2">
            <Button kind="primary" onClick={answer}><Video className="size-4" aria-hidden />Answer</Button>
            <Button onClick={refuse}>Decline</Button>
          </div>
        </div>
      )}

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

/** Whether the browser has already blocked the camera for this site (null when it will not say). */
function useCameraBlocked(): boolean | null {
  const [blocked, setBlocked] = useState<boolean | null>(null);
  useEffect(() => {
    let status: PermissionStatus | undefined;
    const read = () => setBlocked(status ? status.state === 'denied' : null);
    navigator.permissions?.query({ name: 'camera' as PermissionName })
      .then(s => { status = s; s.onchange = read; read(); })
      .catch(() => setBlocked(null)); // Firefox and Safari do not answer for the camera
    return () => { if (status) status.onchange = null; };
  }, []);
  return blocked;
}

type Member = { userId: string; name: string; lead: boolean };

function CallView({ call, inCall, selfName, members, online }: { call: ReturnType<typeof useCall>; inCall: Participant[]; selfName: string; members: Member[]; online: string[] }) {
  const blocked = useCameraBlocked();
  const stage = useRef<HTMLDivElement>(null);
  const [full, setFull] = useState(false);
  useEffect(() => {
    const on = () => setFull(document.fullscreenElement === stage.current);
    document.addEventListener('fullscreenchange', on);
    return () => document.removeEventListener('fullscreenchange', on);
  }, []);
  // When the call ends (you leave, the other person hangs up, or it drops) full screen ends too, so you land back on
  // the team room instead of on a black full-screen page.
  useEffect(() => {
    if ((call.status === 'idle' || call.status === 'asking') && document.fullscreenElement) void document.exitFullscreen().catch(() => { /* already out */ });
  }, [call.status]);
  const canFullscreen = typeof document !== 'undefined' && document.fullscreenEnabled === true;
  // The whole call goes full screen, not one person's picture: everyone stays visible, with yourself in a corner.
  const toggleFull = () => {
    if (document.fullscreenElement) void document.exitFullscreen();
    else void stage.current?.requestFullscreen?.();
  };
  if (call.status === 'asking') {
    return (
      <div className="flex flex-1 flex-col items-center justify-center gap-4 overflow-y-auto p-6 text-center" role="status">
        <span className="grid size-14 animate-pulse place-items-center rounded-full bg-brand-50 text-brand-700"><Video className="size-7" aria-hidden /></span>
        <div>
          <h2 className="text-h3 font-semibold">Allow your camera and microphone</h2>
          <p className="mx-auto mt-1 max-w-[340px] text-small text-ink-500">Your browser is asking for permission, usually in a box near the address bar. Choose <strong>Allow</strong> and the call will start. Baton only uses them while you are on a call.</p>
        </div>
        <Button onClick={call.cancel}>Cancel</Button>
      </div>
    );
  }
  if (call.status === 'idle') {
    const names = inCall.map(p => first(p.name));
    const onlineSet = new Set(online);
    const sorted = [...members].sort((x, y) => Number(onlineSet.has(y.userId)) - Number(onlineSet.has(x.userId)) || x.name.localeCompare(y.name));
    return (
      <div className="flex flex-1 flex-col gap-5 overflow-y-auto p-5">
        {(call.error || blocked) && (
          <p role="alert" className="rounded-lg bg-conflict/10 px-3 py-2 text-small text-conflict">
            {call.error ?? 'Camera and microphone are blocked for this site. Click the lock or camera icon beside the address, set them to Allow, then try again.'}
          </p>
        )}
        {call.notice && !call.error && <p role="status" className="rounded-lg bg-subtle px-3 py-2 text-small text-ink-700">{call.notice}</p>}

        <section aria-label="Group call" className="rounded-xl border border-line p-4">
          <div className="flex items-start gap-3">
            <span className="grid size-11 shrink-0 place-items-center rounded-full bg-brand-50 text-brand-700"><Users className="size-5" aria-hidden /></span>
            <div className="min-w-0 flex-1">
              <h2 className="text-h3 font-semibold">Group call</h2>
              <p className="text-small text-ink-500">
                {inCall.length ? `${names.join(', ')} ${inCall.length === 1 ? 'is' : 'are'} on it now.` : 'A call anyone on this project can join. Up to four at once.'}
              </p>
            </div>
          </div>
          <Button className="mt-3" kind="primary" onClick={() => void call.start()}>
            <Video className="size-4" aria-hidden />{inCall.length ? 'Join the group call' : 'Start a group call'}
          </Button>
        </section>

        <section aria-label="Call one person">
          <h2 className="text-small font-semibold text-ink-900">Call one person</h2>
          <p className="mb-2 text-caption text-ink-500">A private call with just them. They are rung if they have Baton open.</p>
          <ul className="divide-y divide-line rounded-xl border border-line">
            {sorted.map(m => {
              const here = onlineSet.has(m.userId);
              return (
                <li key={m.userId} className="flex items-center gap-3 px-3 py-2.5">
                  <span className="relative grid size-9 shrink-0 place-items-center rounded-full bg-brand-50 text-small font-semibold text-brand-700">
                    {m.name.slice(0, 1).toUpperCase()}
                    <span className={cn('absolute -right-0.5 -bottom-0.5 size-3 rounded-full border-2 border-white', here ? 'bg-verified' : 'bg-ink-300')} aria-hidden />
                  </span>
                  <span className="min-w-0 flex-1">
                    <span className="block truncate text-small font-medium text-ink-900">{m.name}</span>
                    <span className="block text-caption text-ink-500">{m.lead ? 'Project lead · ' : ''}{here ? 'Online now' : 'Not online'}</span>
                  </span>
                  <Button aria-label={`Call ${m.name}`} onClick={() => void call.start({ userId: m.userId, name: m.name })}>
                    <Phone className="size-4" aria-hidden />Call
                  </Button>
                </li>
              );
            })}
            {sorted.length === 0 && <li className="px-3 py-3 text-small text-ink-500">No one else is on this project yet.</li>}
          </ul>
        </section>

        <div className="flex flex-wrap gap-3 text-left">
          <label className="flex flex-col gap-1.5">
            <span className="text-caption font-medium text-ink-700">Video quality</span>
            <QualityPicker value={call.quality} onChange={call.setQuality} />
          </label>
          <label className="flex flex-col gap-1.5">
            <span className="text-caption font-medium text-ink-700">Smoothness</span>
            <FpsPicker value={call.fps} onChange={call.setFps} />
          </label>
        </div>
        <p className="text-caption text-ink-400">Your browser will ask to use your camera and microphone. Video goes straight between browsers. Smooth 60 fps is the default, as far as your camera can do it; the call lowers it by itself if your computer struggles. Testing with two devices in one room? Use headphones on both, or mute one, so the sound doesn’t echo.</p>
      </div>
    );
  }
  const tiles = call.remotes.length + 1;
  return (
    <div key="live-call" ref={stage} className="flex min-h-0 flex-1 flex-col bg-ink-900">
      <p className="flex items-center gap-2 border-b border-white/10 px-3 py-1.5 text-caption text-ink-300">
        {call.peer ? <><Phone className="size-3.5" aria-hidden />Call with {first(call.peer.name)}</> : <><Users className="size-3.5" aria-hidden />Group call</>}
      </p>
      <div className={cn('relative grid min-h-0 flex-1 auto-rows-fr gap-2 overflow-y-auto p-2',
        full ? (call.remotes.length > 1 ? 'grid-cols-2' : 'grid-cols-1') : tiles > 1 ? 'sm:grid-cols-2' : 'grid-cols-1')}>
        {call.remotes.map(r => <Tile key={r.userId} remote={r} relay={call.relay} />)}
        {call.remotes.length === 0 && (
          <div className="grid place-items-center rounded-xl border border-dashed border-ink-500 p-6 text-center text-small text-ink-300">
            {call.status === 'starting' ? 'Joining…' : call.peer ? (call.waiting ? `Calling ${first(call.peer.name)}…` : 'Connecting…') : 'Waiting for others to join. They can open the team room and tap Join the group call.'}
          </div>
        )}
        <Tile local stream={call.local} name={`${selfName} (you)`} off={!call.camOn || !call.hasCamera} muted mirror={call.facing === 'user'}
          className={full ? 'absolute bottom-3 right-3 z-10 h-[22%] min-h-[96px] w-[28%] min-w-[140px] max-w-[260px] border border-white/30 shadow-lg' : undefined} />
      </div>
      {call.notice && <p className="bg-inferred/20 px-4 py-1.5 text-caption text-white">{call.notice}</p>}
      {call.error && <p role="alert" className="bg-conflict/30 px-4 py-1.5 text-caption text-white">{call.error}</p>}
      <div className="flex flex-wrap items-center justify-center gap-2 border-t border-white/10 bg-ink-900 px-3 py-2.5">
        <Control on={call.micOn} label={call.micOn ? 'Mute microphone' : 'Unmute microphone'} onClick={call.toggleMic} icon={call.micOn ? Mic : MicOff} />
        {call.hasCamera && <Control on={call.camOn} label={call.camOn ? 'Turn camera off' : 'Turn camera on'} onClick={call.toggleCam} icon={call.camOn ? Video : VideoOff} />}
        {call.hasCamera && call.canSwitchCamera && (
          <Control on label={call.facing === 'user' ? 'Switch to the back camera' : 'Switch to the front camera'} onClick={() => void call.switchCamera()} icon={SwitchCamera} />
        )}
        {canFullscreen && <Control on label={full ? 'Exit full screen' : 'Full screen'} onClick={toggleFull} icon={full ? Minimize2 : Maximize2} />}
        <QualityPicker value={call.quality} onChange={call.setQuality} dark />
        <FpsPicker value={call.fps} onChange={call.setFps} dark />
        <Button kind="danger" aria-label="Leave the call" onClick={call.leave}><PhoneOff className="size-4" aria-hidden />Leave</Button>
        <p className="basis-full text-center text-caption text-ink-300">
          {call.sent ? `Sending ${call.sent.width}×${call.sent.height}${call.sent.fps ? ` at ${call.sent.fps} fps` : ''}` : 'Sound only'}
          {call.sent && call.sent.fps > 0 && call.sent.fps < SMOOTH_FPS ? ` · your camera tops out at ${call.sent.fps} fps; 60 to 90 needs a camera that supports it` : ''}
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

function FpsPicker({ value, onChange, dark }: { value: FpsId; onChange: (f: FpsId) => void; dark?: boolean }) {
  return (
    <select aria-label="Smoothness" value={value} onChange={e => onChange(Number(e.target.value) as FpsId)}
      className={cn('rounded-lg border px-2.5 py-2 text-small', dark ? 'border-white/20 bg-white/10 text-white' : 'border-line-strong bg-card text-ink-900')}>
      {FRAME_RATES.map(f => <option key={f.id} value={f.id} className="text-ink-900">{f.label}</option>)}
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

function Tile({ remote, local, stream, name, muted, off, relay, mirror, className }: { remote?: Remote; local?: boolean; stream?: MediaStream | null; name?: string; muted?: boolean; off?: boolean; relay?: boolean; mirror?: boolean; className?: string }) {
  const ref = useRef<HTMLVideoElement>(null);
  const [size, setSize] = useState('');
  const [stuck, setStuck] = useState(false);
  const media = local ? stream ?? null : remote?.stream ?? null;
  const play = () => { void ref.current?.play().then(() => setStuck(false)).catch(() => setStuck(true)); };
  useEffect(() => {
    const v = ref.current;
    if (!v) return;
    if (v.srcObject !== media) v.srcObject = media;
    // Browsers may refuse to start a picture with sound until the page is clicked; say so instead of freezing.
    if (media) v.play().then(() => setStuck(false)).catch(() => setStuck(true));
    const update = () => setSize(v.videoWidth ? `${v.videoWidth}×${v.videoHeight}` : '');
    v.addEventListener('resize', update);
    v.addEventListener('loadedmetadata', update);
    return () => { v.removeEventListener('resize', update); v.removeEventListener('loadedmetadata', update); };
  }, [media]);
  const label = local ? name : remote?.name;
  const status = remote && remote.state !== 'connected' ? (remote.state === 'failed'
    ? (relay
      ? 'Couldn’t connect, even through the relay. Check the TURN settings on the server, or try another network.'
      : 'Couldn’t connect. One of your networks blocks direct video, and this workspace has no relay server yet. An admin can add one by setting TURN_URL, TURN_USERNAME and TURN_CREDENTIAL on the API (see the documentation).')
    : 'Connecting…') : null;
  return (
    <div className={cn('relative min-h-[140px] overflow-hidden rounded-xl bg-black', className)}>
      <video ref={ref} autoPlay playsInline muted={muted} className={cn('size-full object-contain', local && mirror !== false && 'scale-x-[-1]', off && 'invisible')} />
      {(off || !media) && !status && <div className="absolute inset-0 grid place-items-center text-small text-ink-300">{off ? 'Camera off' : ''}</div>}
      {status && <div className="absolute inset-0 grid place-items-center p-4 text-center text-small text-white/90">{status}</div>}
      {stuck && !status && (
        <button type="button" onClick={play} className="absolute inset-0 grid place-items-center bg-black/60 text-small font-medium text-white cursor-pointer">Click to play</button>
      )}
      <span className="absolute bottom-2 left-2 rounded-md bg-black/60 px-2 py-0.5 text-caption text-white">{label}{size ? ` · ${size}` : ''}</span>
    </div>
  );
}
