import { app, BrowserWindow, ipcMain, dialog } from 'electron';
import * as path from 'path';
import * as fs from 'fs';
import * as os from 'os';
import * as http from 'http';
import * as dgram from 'dgram';

const BEACON_PORT = 8888;
const DEFAULT_PORT = 8080;

interface Device {
  name: string;
  ip: string;
  port: number;
}

let win: BrowserWindow | null = null;

function createWindow(): void {
  win = new BrowserWindow({
    width: 1100,
    height: 760,
    minWidth: 760,
    minHeight: 520,
    backgroundColor: '#0f1115',
    autoHideMenuBar: true,
    title: 'Cam Mic Viewer',
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false
    }
  });
  win.loadFile(path.join(__dirname, '..', '..', 'static', 'index.html'));
  win.on('closed', () => { win = null; });
}

/** Listen for UDP beacons broadcast by the phone app (auto-detection). */
function startDiscoveryListener(): void {
  const sock = dgram.createSocket({ type: 'udp4', reuseAddr: true });
  sock.on('message', (msg, rinfo) => {
    try {
      const data = JSON.parse(msg.toString('utf8'));
      if (data && data.app === 'cammic') {
        const device: Device = {
          name: typeof data.name === 'string' && data.name ? data.name : 'Android phone',
          ip: rinfo.address,
          port: typeof data.port === 'number' ? data.port : DEFAULT_PORT
        };
        win?.webContents.send('device-found', device);
      }
    } catch {
      // not one of our beacons - ignore
    }
  });
  sock.on('error', () => { /* port busy or blocked - scan still works */ });
  sock.bind(BEACON_PORT);
}

/** Probe a single host for the phone app's /info endpoint. */
function probe(ip: string, port: number, timeoutMs: number): Promise<Device | null> {
  return new Promise((resolve) => {
    const req = http.get({ host: ip, port, path: '/info', timeout: timeoutMs }, (res) => {
      let body = '';
      res.on('data', (c) => {
        body += c;
        if (body.length > 4096) req.destroy();
      });
      res.on('end', () => {
        try {
          const d = JSON.parse(body);
          if (d && d.app === 'cammic') {
            resolve({ name: d.name || 'Android phone', ip, port });
            return;
          }
        } catch { /* not our app */ }
        resolve(null);
      });
    });
    req.on('timeout', () => { req.destroy(); resolve(null); });
    req.on('error', () => resolve(null));
  });
}

/** Fallback detection: scan every local /24 subnet for the phone app. */
async function scanNetwork(): Promise<Device[]> {
  const bases = new Set<string>();
  for (const list of Object.values(os.networkInterfaces())) {
    for (const ni of list ?? []) {
      if (ni.family === 'IPv4' && !ni.internal) {
        bases.add(ni.address.split('.').slice(0, 3).join('.'));
      }
    }
  }
  const targets: string[] = [];
  for (const base of bases) {
    for (let i = 1; i < 255; i++) targets.push(`${base}.${i}`);
  }
  const found: Device[] = [];
  const batchSize = 64;
  for (let i = 0; i < targets.length; i += batchSize) {
    const batch = targets.slice(i, i + batchSize).map((ip) => probe(ip, DEFAULT_PORT, 700));
    for (const device of await Promise.all(batch)) {
      if (device) found.push(device);
    }
  }
  return found;
}

function registerIpc(): void {
  ipcMain.handle('scan-network', () => scanNetwork());

  ipcMain.handle('save-snapshot', async (_event, dataUrl: string) => {
    if (!win || typeof dataUrl !== 'string' || !dataUrl.startsWith('data:image/png;base64,')) return false;
    const { filePath } = await dialog.showSaveDialog(win, {
      title: 'Save snapshot',
      defaultPath: `snapshot-${timestamp()}.png`,
      filters: [{ name: 'PNG image', extensions: ['png'] }]
    });
    if (!filePath) return false;
    fs.writeFileSync(filePath, Buffer.from(dataUrl.split(',')[1], 'base64'));
    return true;
  });

  ipcMain.handle('save-recording', async (_event, data: ArrayBuffer) => {
    if (!win || !(data instanceof ArrayBuffer) || data.byteLength === 0) return false;
    const { filePath } = await dialog.showSaveDialog(win, {
      title: 'Save recording',
      defaultPath: `recording-${timestamp()}.webm`,
      filters: [{ name: 'WebM video', extensions: ['webm'] }]
    });
    if (!filePath) return false;
    fs.writeFileSync(filePath, Buffer.from(new Uint8Array(data)));
    return true;
  });

  ipcMain.handle('set-always-on-top', (_event, value: boolean) => {
    win?.setAlwaysOnTop(Boolean(value));
  });
}

function timestamp(): string {
  return new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19);
}

app.whenReady().then(() => {
  registerIpc();
  createWindow();
  startDiscoveryListener();
  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
  });
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit();
});
