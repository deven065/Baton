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

export interface Remote { userId: string; name: string; stream: MediaStream | null; state: 'connecting' | 'connected' | 'failed' }
export interface Sent { width: number; height: number; fps: number }

interface JoinView {
  others: Participant[];
  maxInCall: number;
  iceServers: { urls: string[]; username?: string | null; credential?: string | null }[];
}
type Peer = { pc: RTCPeerConnection; pending: RTCIceCandidateInit[] };
type SendParams = RTCRtpSendParameters & { degradationPreference?: string };

const room = (projectId: string) => `/api/projects/${projectId}/room`;
const specOf = (id: QualityId) => QUALITIES.find(q => q.id === id)!;

/**
 * A video call between the people on a project. Every browser connects straight to every other (up to four),
 * so the picture never passes through Baton's server: it only relays the set-up messages. The person who joins
 * calls everyone already there.
 */
export function useCall(projectId: string | undefined, selfId: string | undefined) {
  const [status, setStatus] = useState<'idle' | 'starting' | 'live'>('idle');
  const [remotes, setRemotes] = useState<Record<string, Remote>>({});
  const [local, setLocal] = useState<MediaStream | null>(null);
  const [micOn, setMicOn] = useState(true);
  const [camOn, setCamOn] = useState(true);
  const [quality, setQualityState] = useState<QualityId>('1080');
  const [sent, setSent] = useState<Sent | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const peers = useRef(new Map<string, Peer>());
  const stream = useRef<MediaStream | null>(null);
  const ice = useRef<RTCIceServer[]>([]);
  const live = useRef(false);
  const qualityRef = useRef<QualityId>('1080');

  const signal = useCallback((to: string, kind: 'offer' | 'answer' | 'ice', data: unknown) => {
    if (!projectId) return;
    api<void>(`${room(projectId)}/call/signal`, { method: 'POST', json: { to, kind, data: JSON.stringify(data) } }).catch(() => { /* the peer left */ });
  }, [projectId]);

  const readSent = useCallback(() => {
    const t = stream.current?.getVideoTracks()[0];
    const s = t?.getSettings();
    setSent(s?.width && s.height ? { width: s.width, height: s.height, fps: Math.round(s.frameRate ?? 0) } : null);
  }, []);

  /** Lets every outgoing picture use the bandwidth of the chosen quality, and keep its sharpness when strained. */
  const tune = useCallback(async (q: QualityId) => {
    const spec = specOf(q);
    for (const { pc } of peers.current.values()) {
      for (const sender of pc.getSenders()) {
        if (sender.track?.kind !== 'video') continue;
        const p: SendParams = sender.getParameters();
        if (!p.encodings?.length) p.encodings = [{}];
        p.encodings[0].maxBitrate = spec.kbps * 1000;
        p.degradationPreference = spec.height >= 1080 ? 'maintain-resolution' : 'balanced';
        try { await sender.setParameters(p); } catch { /* the browser keeps its own limit */ }
      }
    }
  }, []);

  const makePeer = useCallback((userId: string, name: string): Peer => {
    const existing = peers.current.get(userId);
    if (existing) return existing;
    const pc = new RTCPeerConnection({ iceServers: ice.current });
    const peer: Peer = { pc, pending: [] };
    peers.current.set(userId, peer);
    stream.current?.getTracks().forEach(t => pc.addTrack(t, stream.current!));
    pc.onicecandidate = e => { if (e.candidate) signal(userId, 'ice', e.candidate.toJSON()); };
    pc.ontrack = e => setRemotes(r => ({
      ...r, [userId]: { ...(r[userId] ?? { userId, name, state: 'connecting' }), stream: e.streams[0] ?? new MediaStream([e.track]) },
    }));
    pc.onconnectionstatechange = () => {
      const s = pc.connectionState;
      setRemotes(r => (r[userId] ? { ...r, [userId]: { ...r[userId], state: s === 'connected' ? 'connected' : s === 'failed' ? 'failed' : 'connecting' } } : r));
    };
    setRemotes(r => ({ ...r, [userId]: r[userId] ?? { userId, name, stream: null, state: 'connecting' } }));
    void tune(qualityRef.current);
    return peer;
  }, [signal, tune]);

  const closePeer = useCallback((userId: string) => {
    peers.current.get(userId)?.pc.close();
    peers.current.delete(userId);
    setRemotes(r => { const { [userId]: _gone, ...rest } = r; return rest; });
  }, []);

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
    const { pc } = makePeer(p.userId, p.name);
    await pc.setLocalDescription(await pc.createOffer());
    signal(p.userId, 'offer', pc.localDescription?.toJSON());
  }, [makePeer, signal]);

  const start = useCallback(async () => {
    if (!projectId || live.current) return;
    setError(null);
    setNotice(null);
    setStatus('starting');
    const spec = specOf(qualityRef.current);
    let media: MediaStream;
    try {
      media = await navigator.mediaDevices.getUserMedia({
        audio: { echoCancellation: true, noiseSuppression: true },
        video: { width: { ideal: spec.width }, height: { ideal: spec.height }, frameRate: { ideal: 30 } },
      });
    } catch {
      try {
        media = await navigator.mediaDevices.getUserMedia({ audio: true });
        setNotice('No camera was available, so you joined with sound only.');
      } catch {
        setStatus('idle');
        setError('Baton needs your microphone or camera to start a call. Allow access in the browser, then try again.');
        return;
      }
    }
    stream.current = media;
    setLocal(media);
    setMicOn(true);
    setCamOn(media.getVideoTracks().length > 0);
    live.current = true;
    try {
      const join = await api<JoinView>(`${room(projectId)}/call/join`, { method: 'POST' });
      ice.current = join.iceServers.map(i => ({ urls: i.urls, username: i.username ?? undefined, credential: i.credential ?? undefined }));
      setStatus('live');
      for (const p of join.others) await callPeer(p);
      readSent();
    } catch (e) {
      cleanup();
      setError(e instanceof Error ? e.message : 'Could not join the call.');
    }
  }, [projectId, callPeer, cleanup, readSent]);

  const leave = useCallback(() => {
    if (projectId && live.current) api<void>(`${room(projectId)}/call/leave`, { method: 'POST' }).catch(() => { /* already out */ });
    cleanup();
  }, [projectId, cleanup]);

  const onSignal = useCallback(async (s: SignalEvent) => {
    if (!live.current) return;
    const peer = makePeer(s.from, s.fromName);
    const { pc } = peer;
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

  const setQuality = useCallback((q: QualityId) => {
    qualityRef.current = q;
    setQualityState(q);
    const spec = specOf(q);
    const track = stream.current?.getVideoTracks()[0];
    void track?.applyConstraints({ width: { ideal: spec.width }, height: { ideal: spec.height }, frameRate: { ideal: 30 } })
      .catch(() => { /* the camera keeps the closest size it has */ })
      .finally(readSent);
    void tune(q);
  }, [tune, readSent]);

  // Leaving the page ends the call: the camera light goes off and the others see you go.
  const leaveRef = useRef(leave);
  leaveRef.current = leave;
  useEffect(() => () => leaveRef.current(), []);

  return {
    status, local, remotes: Object.values(remotes), micOn, camOn, quality, sent, error, notice,
    start, leave, toggleMic, toggleCam, setQuality, onSignal, onPeerLeft: closePeer, onReconnect,
    hasCamera: (local?.getVideoTracks().length ?? 0) > 0,
  };
}
