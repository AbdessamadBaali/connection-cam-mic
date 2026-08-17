/** Renderer for Cam Mic Viewer: connection, video, audio, and all view options. */

interface Device {
  name: string;
  ip: string;
  port: number;
}

interface Api {
  onDeviceFound(callback: (device: Device) => void): void;
  scanNetwork(): Promise<Device[]>;
  saveSnapshot(dataUrl: string): Promise<boolean>;
  saveRecording(data: ArrayBuffer): Promise<boolean>;
  setAlwaysOnTop(value: boolean): Promise<void>;
}

declare global {
  interface Window { api: Api; }
}

const $ = <T extends HTMLElement>(id: string): T => document.getElementById(id) as T;

const deviceSelect = $<HTMLSelectElement>('deviceSelect');
const scanBtn = $<HTMLButtonElement>('scanBtn');
const ipInput = $<HTMLInputElement>('ipInput');
const connectBtn = $<HTMLButtonElement>('connectBtn');
const statusDot = $<HTMLSpanElement>('statusDot');
const statusText = $<HTMLSpanElement>('statusText');
const videoWrap = $<HTMLDivElement>('videoWrap');
const video = $<HTMLImageElement>('video');
const placeholder = $<HTMLDivElement>('placeholder');
const recBadge = $<HTMLDivElement>('recBadge');
const recTime = $<HTMLSpanElement>('recTime');
const switchBtn = $<HTMLButtonElement>('switchBtn');
const snapshotBtn = $<HTMLButtonElement>('snapshotBtn');
const recordBtn = $<HTMLButtonElement>('recordBtn');
const mirrorBtn = $<HTMLButtonElement>('mirrorBtn');
const rotateBtn = $<HTMLButtonElement>('rotateBtn');
const fullscreenBtn = $<HTMLButtonElement>('fullscreenBtn');
const autoToggle = $<HTMLInputElement>('autoToggle');
const audioToggle = $<HTMLInputElement>('audioToggle');
const volumeSlider = $<HTMLInputElement>('volumeSlider');
const topToggle = $<HTMLInputElement>('topToggle');

// ---------- state ----------

const devices = new Map<string, Device>();
let connected = false;
let connecting = false;
let baseUrl = '';
let deviceName = '';
let mirrored = false;
let rotation = 0;

// audio state
let audioCtx: AudioContext | null = null;
let gainNode: GainNode | null = null;
let audioAbort: AbortController | null = null;
let playhead = 0;

// recording state
let recorder: MediaRecorder | null = null;
let recChunks: Blob[] = [];
let recDrawTimer: number | null = null;
let recClockTimer: number | null = null;
let recStartedAt = 0;

// ---------- status helpers ----------

type StatusKind = 'off' | 'connecting' | 'on' | 'error';

function setStatus(kind: StatusKind, text: string): void {
  statusDot.className = `dot ${kind}`;
  statusText.textContent = text;
}

function setControlsEnabled(enabled: boolean): void {
  for (const btn of [switchBtn, snapshotBtn, recordBtn, mirrorBtn, rotateBtn, fullscreenBtn]) {
    btn.disabled = !enabled;
  }
}

// ---------- device discovery ----------

function upsertDevice(device: Device): void {
  const isNew = !devices.has(device.ip);
  devices.set(device.ip, device);
  if (isNew) {
    renderDeviceList(device.ip);
    if (!connected && !connecting) {
      ipInput.value = device.ip;
      if (autoToggle.checked) {
        setStatus('connecting', `Phone detected: ${device.name} (${device.ip}) - connecting…`);
        void connect();
      } else {
        setStatus('off', `Phone detected: ${device.name} (${device.ip}) - press Connect.`);
      }
    }
  }
}

function renderDeviceList(selectIp?: string): void {
  const current = selectIp ?? deviceSelect.value;
  deviceSelect.innerHTML = '';
  if (devices.size === 0) {
    const opt = document.createElement('option');
    opt.value = '';
    opt.textContent = 'No phone detected yet…';
    deviceSelect.appendChild(opt);
    return;
  }
  for (const device of devices.values()) {
    const opt = document.createElement('option');
    opt.value = device.ip;
    opt.textContent = `${device.name}  (${device.ip})`;
    deviceSelect.appendChild(opt);
  }
  if (current && devices.has(current)) deviceSelect.value = current;
}

window.api.onDeviceFound(upsertDevice);

scanBtn.addEventListener('click', async () => {
  scanBtn.disabled = true;
  scanBtn.textContent = 'Scanning…';
  setStatus(connected ? 'on' : 'connecting', 'Scanning the local network…');
  try {
    const found = await window.api.scanNetwork();
    for (const device of found) upsertDevice(device);
    if (!connected) {
      setStatus('off', found.length > 0
        ? `Found ${found.length} phone${found.length > 1 ? 's' : ''} - press Connect.`
        : 'No phone found. Is Cam Mic Streamer open and on the same Wi-Fi?');
    }
  } finally {
    scanBtn.disabled = false;
    scanBtn.textContent = 'Scan';
  }
});

deviceSelect.addEventListener('change', () => {
  if (deviceSelect.value) ipInput.value = deviceSelect.value;
});

// ---------- connect / disconnect ----------

function targetIp(): string {
  return (ipInput.value.trim() || deviceSelect.value).trim();
}

async function fetchJson(url: string, timeoutMs: number): Promise<Record<string, unknown>> {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), timeoutMs);
  try {
    const res = await fetch(url, { signal: ctrl.signal });
    return (await res.json()) as Record<string, unknown>;
  } finally {
    clearTimeout(timer);
  }
}

async function connect(): Promise<void> {
  if (connecting || connected) return;
  const ip = targetIp();
  if (!ip) {
    setStatus('error', 'Pick a detected phone or type its IP address.');
    return;
  }
  connecting = true;
  const port = devices.get(ip)?.port ?? 8080;
  baseUrl = `http://${ip}:${port}`;
  setStatus('connecting', `Connecting to ${ip}…`);
  connectBtn.disabled = true;
  try {
    const info = await fetchJson(`${baseUrl}/info`, 4000);
    deviceName = typeof info.name === 'string' && info.name ? info.name : 'Phone';
  } catch {
    setStatus('error', `Could not reach ${ip}. Check that the phone app is open and on the same Wi-Fi.`);
    connectBtn.disabled = false;
    connecting = false;
    return;
  }

  video.crossOrigin = 'anonymous';
  video.src = `${baseUrl}/video?t=${Date.now()}`;
  video.style.display = 'block';
  placeholder.classList.add('hidden');

  connected = true;
  connecting = false;
  connectBtn.disabled = false;
  connectBtn.textContent = 'Disconnect';
  connectBtn.classList.add('disconnect');
  setControlsEnabled(true);
  setStatus('on', `Connected to ${deviceName} (${ip})`);

  if (audioToggle.checked) void startAudio();
}

function disconnect(showStatus = true): void {
  connected = false;
  connecting = false;
  stopRecording(false);
  stopAudio();
  video.removeAttribute('src');
  video.style.display = 'none';
  placeholder.classList.remove('hidden');
  connectBtn.textContent = 'Connect';
  connectBtn.classList.remove('disconnect');
  connectBtn.disabled = false;
  setControlsEnabled(false);
  if (showStatus) setStatus('off', 'Disconnected.');
}

connectBtn.addEventListener('click', () => {
  if (connected) disconnect();
  else void connect();
});

ipInput.addEventListener('keydown', (event) => {
  if (event.key === 'Enter' && !connected) void connect();
});

video.addEventListener('error', () => {
  if (connected) {
    disconnect(false);
    setStatus('error', 'Lost connection to the phone.');
  }
});

// ---------- audio (streaming WAV over fetch -> WebAudio) ----------

async function startAudio(): Promise<void> {
  stopAudio();
  audioCtx = new AudioContext({ sampleRate: 44100 });
  gainNode = audioCtx.createGain();
  gainNode.gain.value = Number(volumeSlider.value) / 100;
  gainNode.connect(audioCtx.destination);
  audioAbort = new AbortController();
  playhead = 0;

  try {
    const res = await fetch(`${baseUrl}/audio`, { signal: audioAbort.signal });
    if (!res.body) return;
    const reader = res.body.getReader();
    let skipped = 0; // skip the 44-byte WAV header
    let leftover = new Uint8Array(0);

    for (;;) {
      const { done, value } = await reader.read();
      if (done || !audioCtx || !gainNode) break;
      let chunk = value;
      if (skipped < 44) {
        const s = Math.min(44 - skipped, chunk.length);
        chunk = chunk.subarray(s);
        skipped += s;
        if (chunk.length === 0) continue;
      }
      const data = new Uint8Array(leftover.length + chunk.length);
      data.set(leftover);
      data.set(chunk, leftover.length);
      const usable = data.length - (data.length % 2);
      leftover = data.subarray(usable);
      if (usable === 0) continue;

      const samples = new Int16Array(data.buffer.slice(0, usable));
      const floats = new Float32Array(samples.length);
      for (let i = 0; i < samples.length; i++) floats[i] = samples[i] / 32768;

      const buffer = audioCtx.createBuffer(1, floats.length, 44100);
      buffer.copyToChannel(floats, 0);
      const source = audioCtx.createBufferSource();
      source.buffer = buffer;
      source.connect(gainNode);
      if (playhead < audioCtx.currentTime) playhead = audioCtx.currentTime + 0.08;
      source.start(playhead);
      playhead += buffer.duration;
    }
  } catch {
    // aborted or stream ended
  }
}

function stopAudio(): void {
  audioAbort?.abort();
  audioAbort = null;
  if (audioCtx) {
    void audioCtx.close().catch(() => undefined);
    audioCtx = null;
  }
  gainNode = null;
}

audioToggle.addEventListener('change', () => {
  if (!connected) return;
  if (audioToggle.checked) void startAudio();
  else stopAudio();
});

volumeSlider.addEventListener('input', () => {
  if (gainNode) gainNode.gain.value = Number(volumeSlider.value) / 100;
});

// ---------- camera / view options ----------

switchBtn.addEventListener('click', async () => {
  if (!connected) return;
  switchBtn.disabled = true;
  try {
    await fetchJson(`${baseUrl}/switch`, 4000);
  } catch {
    // stream keeps running either way
  } finally {
    switchBtn.disabled = false;
  }
});

function applyTransform(): void {
  video.style.transform = `rotate(${rotation}deg) scaleX(${mirrored ? -1 : 1})`;
}

mirrorBtn.addEventListener('click', () => {
  mirrored = !mirrored;
  mirrorBtn.classList.toggle('active', mirrored);
  applyTransform();
});

rotateBtn.addEventListener('click', () => {
  rotation = (rotation + 90) % 360;
  rotateBtn.classList.toggle('active', rotation !== 0);
  applyTransform();
});

fullscreenBtn.addEventListener('click', () => {
  if (document.fullscreenElement) void document.exitFullscreen();
  else void videoWrap.requestFullscreen();
});

topToggle.addEventListener('change', () => {
  void window.api.setAlwaysOnTop(topToggle.checked);
});

// ---------- snapshot ----------

function drawCurrentFrame(): HTMLCanvasElement | null {
  if (!video.naturalWidth) return null;
  const canvas = document.createElement('canvas');
  canvas.width = video.naturalWidth;
  canvas.height = video.naturalHeight;
  const ctx = canvas.getContext('2d');
  if (!ctx) return null;
  ctx.drawImage(video, 0, 0);
  return canvas;
}

snapshotBtn.addEventListener('click', async () => {
  const canvas = drawCurrentFrame();
  if (!canvas) return;
  const saved = await window.api.saveSnapshot(canvas.toDataURL('image/png'));
  if (saved) setStatus('on', `Snapshot saved. Connected to ${deviceName}.`);
});

// ---------- recording ----------

function startRecording(): void {
  if (!video.naturalWidth) return;
  const canvas = document.createElement('canvas');
  canvas.width = video.naturalWidth;
  canvas.height = video.naturalHeight;
  const ctx = canvas.getContext('2d');
  if (!ctx) return;

  const stream = canvas.captureStream(30);
  if (audioCtx && gainNode) {
    const dest = audioCtx.createMediaStreamDestination();
    gainNode.connect(dest);
    const track = dest.stream.getAudioTracks()[0];
    if (track) stream.addTrack(track);
  }

  recDrawTimer = window.setInterval(() => {
    if (!video.naturalWidth) return;
    if (canvas.width !== video.naturalWidth || canvas.height !== video.naturalHeight) {
      canvas.width = video.naturalWidth;
      canvas.height = video.naturalHeight;
    }
    ctx.drawImage(video, 0, 0);
  }, 33);

  recChunks = [];
  recorder = new MediaRecorder(stream, { mimeType: 'video/webm;codecs=vp8,opus' });
  recorder.ondataavailable = (event) => {
    if (event.data.size > 0) recChunks.push(event.data);
  };
  recorder.onstop = async () => {
    const blob = new Blob(recChunks, { type: 'video/webm' });
    recChunks = [];
    if (blob.size > 0) {
      const saved = await window.api.saveRecording(await blob.arrayBuffer());
      if (saved && connected) setStatus('on', `Recording saved. Connected to ${deviceName}.`);
    }
  };
  recorder.start(1000);

  recStartedAt = Date.now();
  recBadge.classList.remove('hidden');
  recClockTimer = window.setInterval(() => {
    const total = Math.floor((Date.now() - recStartedAt) / 1000);
    const mm = String(Math.floor(total / 60)).padStart(2, '0');
    const ss = String(total % 60).padStart(2, '0');
    recTime.textContent = `${mm}:${ss}`;
  }, 500);

  recordBtn.textContent = '⏹ Stop';
  recordBtn.classList.add('active');
}

function stopRecording(save = true): void {
  if (recDrawTimer !== null) { clearInterval(recDrawTimer); recDrawTimer = null; }
  if (recClockTimer !== null) { clearInterval(recClockTimer); recClockTimer = null; }
  recBadge.classList.add('hidden');
  recTime.textContent = '00:00';
  recordBtn.textContent = '⏺ Record';
  recordBtn.classList.remove('active');
  if (recorder) {
    const r = recorder;
    recorder = null;
    if (r.state !== 'inactive') {
      if (save) r.stop();
      else { r.ondataavailable = null; r.onstop = null; r.stop(); }
    }
  }
}

recordBtn.addEventListener('click', () => {
  if (recorder) stopRecording();
  else startRecording();
});

// ---------- remember preferences ----------

autoToggle.checked = localStorage.getItem('autoConnect') !== 'off';
autoToggle.addEventListener('change', () => {
  localStorage.setItem('autoConnect', autoToggle.checked ? 'on' : 'off');
});

export {};
