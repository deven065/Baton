import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '@/lib/http';
import type { Participant, SignalEvent } from './useRoom';

/**
 * Video quality choices. The camera is asked for this size and each outgoing picture is allowed this much
 * bandwidth. What actually goes out is the smaller of that and what the camera and the connection can deliver,
 * and the call shows the real figure.
 */
export const QUALITIES = [
  { id: '720', label: 'HD · 720p', width: 1280, height: 720, kbps: 2500 },
  { id: '1080', label: 'Full HD · 1080p', width: 1920, height: 1080, kbps: 5000 },
  { id: '1440', label: '2K · 1440p', width: 2560, height: 1440, kbps: 9000 },
  { id: '2160', label: '4K · 2160p', width: 3840, height: 2160, kbps: 18000 },
] as const;
export type QualityId = (typeof QUALITIES)[number]['id'];

/**
 * Smoothness: how many frames a second the camera is asked for. The browser uses the closest rate the camera
 * really offers (most webcams do 30, many do 60, a few do 90) and the call shows the real figure. More frames
 * mean more work for the computer and more bandwidth, so the call steps down by itself when a computer struggles.
 */
export const FRAME_RATES = [
  { id: 30, label: 'Steady · 30 fps' },
  { id: 60, label: 'Smooth · 60 fps' },
  { id: 90, label: 'Max · 90 fps' },
] as const;
export type FpsId = (typeof FRAME_RATES)[number]['id'];
export const SMOOTH_FPS = 60;
const DEFAULT_FPS: FpsId = 60;
/** Faster motion needs about this much more bandwidth per picture than 30 fps does. */
const fpsBandwidth = (fps: number) => (fps > 30 ? 1.6 : 1);

export interface Remote { userId: string; name: string; stream: MediaStream | null; state: 'connecting' | 'connected' | 'failed' }
export interface Sent { width: number; height: number; fps: number }

interface JoinView {
  others: Participant[];
  maxInCall: number;
  iceServers: { urls: string[]; username?: string | null; credential?: string | null }[];
}
type Peer = { pc: RTCPeerConnection; pending: RTCIceCandidateInit[]; initiator: boolean; restarts: number; queue: Promise<void> };
type SendParams = RTCRtpSendParameters & { degradationPreference?: string };

const room = (projectId: string) => `/api/projects/${projectId}/room`;
const specOf = (id: QualityId) => QUALITIES.find(q => q.id === id)!;
const errName = (e: unknown) => (e instanceof DOMException || e instanceof Error ? e.name : '');

/** What the browser's refusal to open the camera means, in words someone can act on. */
function mediaProblem(e: unknown): string {
  switch (errName(e)) {
    case 'NotAllowedError':
    case 'SecurityError':
      return 'Camera and microphone are blocked for this site. Click the lock or camera icon beside the address, set Camera and Microphone to Allow, then try again.';
    case 'NotFoundError':
      return 'No camera or microphone was found. Plug one in, then try again.';
    case 'NotReadableError':
    case 'AbortError':
      return 'Your camera or microphone is being used by another app or browser tab (Zoom, Teams, another call). Close it and try again.';
    case 'TypeError':
      return 'This page cannot reach a camera. Video calls need a secure (https) address, or localhost.';
    default:
      return 'Could not start your camera and microphone.';
  }
}

/**
 * A video call between the people on a project. Every browser connects straight to every other (up to four),
 * so the picture never passes through Baton's server: it only relays the set-up messages. The person who joins
 * calls everyone already there.
 */
export function useCall(projectId: string | undefined, selfId: string | undefined) {
  // asking = the browser's camera prompt is open and waiting for an answer.
  const [status, setStatus] = useState<'idle' | 'asking' | 'starting' | 'live'>('idle');
  const [remotes, setRemotes] = useState<Record<string, Remote>>({});
  const [local, setLocal] = useState<MediaStream | null>(null);
  const [micOn, setMicOn] = useState(true);
  const [camOn, setCamOn] = useState(true);
  const [quality, setQualityState] = useState<QualityId>('720');
  const [fps, setFpsState] = useState<FpsId>(DEFAULT_FPS);
  // Whether the server gave this call a relay (TURN) for networks that block direct video.
  const [relay, setRelay] = useState(false);
  const [sent, setSent] = useState<Sent | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const peers = useRef(new Map<string, Peer>());
  const stream = useRef<MediaStream | null>(null);
  const ice = useRef<RTCIceServer[]>([]);
  const live = useRef(false);
  const qualityRef = useRef<QualityId>('720');
  const fpsRef = useRef<FpsId>(DEFAULT_FPS);
  const attempt = useRef(0);

  const signal = useCallback((to: string, kind: 'offer' | 'answer' | 'ice', data: unknown) => {
    if (!projectId) return;
    api<void>(`${room(projectId)}/call/signal`, { method: 'POST', json: { to, kind, data: JSON.stringify(data) } }).catch(() => { /* the peer left */ });
  }, [projectId]);

  const readSent = useCallback(() => {
    const t = stream.current?.getVideoTracks()[0];
    const s = t?.getSettings();
    setSent(s?.width && s.height ? { width: s.width, height: s.height, fps: Math.round(s.frameRate ?? 0) } : null);
  }, []);

  /**
   * Lets every outgoing picture use the bandwidth of the chosen quality. When the connection is strained the
   * browser trades size and sharpness for smoothness ("balanced"), so the picture keeps moving instead of
   * freezing; keeping full size at any cost is what makes a weak connection look like a slideshow.
   * Everyone you send to gets a share of the budget, because every extra person is another upload on your
   * connection; without that, three people at once is what makes a home Wi-Fi stutter.
   */
  const tune = useCallback(async (q: QualityId) => {
    const spec = specOf(q);
    const budget = (spec.kbps * fpsBandwidth(fpsRef.current) * 1000) / Math.max(1, peers.current.size);
    for (const { pc } of peers.current.values()) {
      for (const sender of pc.getSenders()) {
        if (sender.track?.kind !== 'video') continue;
        const p: SendParams = sender.getParameters();
        if (!p.encodings?.length) p.encodings = [{}];
        p.encodings[0].maxBitrate = Math.round(budget);
        p.encodings[0].maxFramerate = fpsRef.current;
        p.degradationPreference = 'balanced';
        try { await sender.setParameters(p); } catch { /* the browser keeps its own limit */ }
      }
    }
  }, []);

  const makePeer = useCallback((userId: string, name: string, initiator = false): Peer => {
    const existing = peers.current.get(userId);
    if (existing) return existing;
    const pc = new RTCPeerConnection({ iceServers: ice.current });
    const peer: Peer = { pc, pending: [], initiator, restarts: 0, queue: Promise.resolve() };
    peers.current.set(userId, peer);
    stream.current?.getTracks().forEach(t => pc.addTrack(t, stream.current!));
    pc.onicecandidate = e => { if (e.candidate) signal(userId, 'ice', e.candidate.toJSON()); };
    pc.ontrack = e => {
      // A little buffering on the receiving side absorbs uneven network timing, which is what shows up as
      // stutter and sound glitches on Wi-Fi. A fraction of a second is not noticeable in conversation.
      (e.receiver as RTCRtpReceiver & { jitterBufferTarget?: number | null }).jitterBufferTarget = 150;
      setRemotes(r => ({
        ...r, [userId]: { ...(r[userId] ?? { userId, name, state: 'connecting' }), stream: e.streams[0] ?? new MediaStream([e.track]) },
      }));
    };
    pc.onconnectionstatechange = () => {
      const s = pc.connectionState;
      // A path that dropped is tried again (the person who called does it, twice at most) before giving up.
      const retry = s === 'failed' && peer.initiator && peer.restarts < 2;
      if (retry) {
        peer.restarts += 1;
        void (async () => {
          try {
            await pc.setLocalDescription(await pc.createOffer({ iceRestart: true }));
            signal(userId, 'offer', pc.localDescription?.toJSON());
          } catch { /* the peer is gone: the call-left event removes the tile */ }
        })();
      }
      setRemotes(r => (r[userId] ? { ...r, [userId]: { ...r[userId], state: s === 'connected' ? 'connected' : s === 'failed' && !retry ? 'failed' : 'connecting' } } : r));
    };
    setRemotes(r => ({ ...r, [userId]: r[userId] ?? { userId, name, stream: null, state: 'connecting' } }));
    void tune(qualityRef.current);
    return peer;
  }, [signal, tune]);

  const closePeer = useCallback((userId: string) => {
    peers.current.get(userId)?.pc.close();
    peers.current.delete(userId);
    setRemotes(r => { const { [userId]: _gone, ...rest } = r; return rest; });
    void tune(qualityRef.current); // fewer people to send to: each picture can have more
  }, [tune]);

  const cleanup = useCallback(() => {
    live.current = false;
    peers.current.forEach(p => p.pc.close());
    peers.current.clear();
    stream.current?.getTracks().forEach(t => t.stop());
    stream.current = null;
    setRemotes({});
    setLocal(null);
    setSent(null);
    setStatus('idle');
  }, []);

  const callPeer = useCallback(async (p: Participant) => {
    const { pc } = makePeer(p.userId, p.name, true);
    await pc.setLocalDescription(await pc.createOffer());
    signal(p.userId, 'offer', pc.localDescription?.toJSON());
  }, [makePeer, signal]);

  const start = useCallback(async () => {
    if (!projectId || live.current) return;
    setError(null);
    setNotice(null);
    setStatus('asking');
    const mine = ++attempt.current;
    const spec = specOf(qualityRef.current);
    // Echo cancelling matters most: two devices in one room, or speakers instead of headphones, otherwise feed
    // the other person's voice straight back and the call crackles or howls.
    const audio = { echoCancellation: true, noiseSuppression: true, autoGainControl: true, channelCount: 1 };
    const ask = (c: MediaStreamConstraints) => navigator.mediaDevices.getUserMedia(c);
    let media: MediaStream;
    try {
      try {
        media = await ask({ audio, video: { width: { ideal: spec.width }, height: { ideal: spec.height }, frameRate: { ideal: fpsRef.current } } });
      } catch (e) {
        // A camera that cannot do the size asked for still works at the size it has.
        if (errName(e) !== 'OverconstrainedError') throw e;
        media = await ask({ audio, video: true });
      }
    } catch (e) {
      const n = errName(e);
      // A missing or busy camera should not cost the call: carry on with sound. A refusal is not retried, so
      // the browser does not ask a second time.
      if (n === 'NotFoundError' || n === 'NotReadableError' || n === 'AbortError') {
        try {
          media = await ask({ audio });
          if (mine === attempt.current) setNotice(n === 'NotFoundError' ? 'No camera was found, so you joined with sound only.' : 'Your camera is in use by another app, so you joined with sound only.');
        } catch (e2) {
          if (mine === attempt.current) { setStatus('idle'); setError(mediaProblem(e2)); }
          return;
        }
      } else {
        if (mine === attempt.current) { setStatus('idle'); setError(mediaProblem(e)); }
        return;
      }
    }
    // Cancelled while the browser was still asking: let go of the camera again.
    if (mine !== attempt.current) { media.getTracks().forEach(t => t.stop()); return; }
    setStatus('starting');
    stream.current = media;
    setLocal(media);
    setMicOn(true);
    setCamOn(media.getVideoTracks().length > 0);
    live.current = true;
    try {
      const join = await api<JoinView>(`${room(projectId)}/call/join`, { method: 'POST' });
      ice.current = join.iceServers.map(i => ({ urls: i.urls, username: i.username ?? undefined, credential: i.credential ?? undefined }));
      setRelay(join.iceServers.some(i => i.urls.some(u => /^turns?:/i.test(u))));
      setStatus('live');
      for (const p of join.others) await callPeer(p);
      readSent();
    } catch (e) {
      cleanup();
      setError(e instanceof Error ? e.message : 'Could not join the call.');
    }
  }, [projectId, callPeer, cleanup, readSent]);

  /** Gives up waiting for the browser's camera prompt. */
  const cancel = useCallback(() => {
    attempt.current += 1;
    setStatus('idle');
  }, []);

  const leave = useCallback(() => {
    attempt.current += 1; // also ends a camera prompt that is still open
    if (projectId && live.current) api<void>(`${room(projectId)}/call/leave`, { method: 'POST' }).catch(() => { /* already out */ });
    cleanup();
  }, [projectId, cleanup]);

  const onSignal = useCallback((s: SignalEvent) => {
    if (!live.current) return;
    const peer = makePeer(s.from, s.fromName);
    const { pc } = peer;
    const handle = async () => {
      try {
        const payload = JSON.parse(s.data);
        if (s.kind === 'ice') {
          if (pc.remoteDescription) await pc.addIceCandidate(payload); else peer.pending.push(payload);
          return;
        }
        // Two people joining at the same moment both call each other: the one with the larger id lets the other win.
        if (s.kind === 'offer' && pc.signalingState !== 'stable' && (selfId ?? '') > s.from) return;
        await pc.setRemoteDescription(payload);
        for (const c of peer.pending.splice(0)) await pc.addIceCandidate(c);
        if (s.kind === 'offer') {
          await pc.setLocalDescription(await pc.createAnswer());
          signal(s.from, 'answer', pc.localDescription?.toJSON());
        }
      } catch { /* a late or repeated message: the connection state shows if it mattered */ }
    };
    // One message at a time per person, in the order sent. Set-up messages that overlap (an offer still being
    // applied while its network candidates arrive) are a classic cause of calls that connect only now and then.
    peer.queue = peer.queue.then(handle, handle);
  }, [makePeer, signal, selfId]);

  /** After the live stream reconnects: if the server forgot us the call is over; otherwise drop anyone who left. */
  const onReconnect = useCallback((inCall: Participant[]) => {
    if (!live.current) return;
    if (selfId && !inCall.some(p => p.userId === selfId)) {
      cleanup();
      setError('The connection to the call was interrupted. Join again to continue.');
      return;
    }
    [...peers.current.keys()].filter(id => !inCall.some(p => p.userId === id)).forEach(closePeer);
  }, [selfId, cleanup, closePeer]);

  const toggleMic = useCallback(() => {
    const on = !micOn;
    stream.current?.getAudioTracks().forEach(t => { t.enabled = on; });
    setMicOn(on);
  }, [micOn]);

  const toggleCam = useCallback(() => {
    const tracks = stream.current?.getVideoTracks() ?? [];
    if (!tracks.length) return;
    const on = !camOn;
    tracks.forEach(t => { t.enabled = on; });
    setCamOn(on);
  }, [camOn]);

  /** Asks the camera for the chosen size and rate, and lets the senders use the matching bandwidth. */
  const applyCamera = useCallback(() => {
    const spec = specOf(qualityRef.current);
    const track = stream.current?.getVideoTracks()[0];
    void track?.applyConstraints({ width: { ideal: spec.width }, height: { ideal: spec.height }, frameRate: { ideal: fpsRef.current } })
      .catch(() => { /* the camera keeps the closest size it has */ })
      .finally(readSent);
    void tune(qualityRef.current);
  }, [tune, readSent]);

  const setQuality = useCallback((q: QualityId) => {
    qualityRef.current = q;
    setQualityState(q);
    applyCamera();
  }, [applyCamera]);

  const setFps = useCallback((f: FpsId) => {
    fpsRef.current = f;
    setFpsState(f);
    applyCamera();
  }, [applyCamera]);

  // Glitches usually mean the computer cannot keep up with the picture it was asked for. Every few seconds the
  // outgoing video is checked, and if the browser reports it is held back by the processor twice in a row, the
  // frame rate steps down (90, 60, 30), then the size, instead of leaving the call to stutter.
  useEffect(() => {
    if (status !== 'live') return;
    let strained = 0;
    const id = window.setInterval(async () => {
      let cpu = false;
      for (const { pc } of peers.current.values()) {
        const sender = pc.getSenders().find(s => s.track?.kind === 'video');
        if (!sender) continue;
        try {
          (await sender.getStats()).forEach((r: { type?: string; kind?: string; qualityLimitationReason?: string }) => {
            if (r.type === 'outbound-rtp' && r.kind === 'video' && r.qualityLimitationReason === 'cpu') cpu = true;
          });
        } catch { /* the connection is closing */ }
      }
      strained = cpu ? strained + 1 : 0;
      if (strained < 2) return;
      strained = 0;
      if (fpsRef.current > 30) {
        const next: FpsId = fpsRef.current === 90 ? 60 : 30;
        setNotice(`Your computer was struggling, so the call lowered the smoothness to ${next} fps to stop the glitches.`);
        setFps(next);
      } else {
        const i = QUALITIES.findIndex(q => q.id === qualityRef.current);
        if (i > 0) {
          setNotice(`Your computer was struggling, so the call lowered the quality to ${QUALITIES[i - 1].label} to stop the glitches.`);
          setQuality(QUALITIES[i - 1].id);
        }
      }
    }, 3000);
    return () => window.clearInterval(id);
  }, [status, setFps, setQuality]);

  // Leaving the page ends the call: the camera light goes off and the others see you go.
  const leaveRef = useRef(leave);
  leaveRef.current = leave;
  useEffect(() => () => leaveRef.current(), []);

  return {
    status, local, remotes: Object.values(remotes), micOn, camOn, quality, fps, relay, sent, error, notice,
    start, cancel, leave, toggleMic, toggleCam, setQuality, setFps, onSignal, onPeerLeft: closePeer, onReconnect,
    hasCamera: (local?.getVideoTracks().length ?? 0) > 0,
  };
}
