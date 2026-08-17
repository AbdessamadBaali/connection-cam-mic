import { contextBridge, ipcRenderer } from 'electron';

export interface Device {
  name: string;
  ip: string;
  port: number;
}

contextBridge.exposeInMainWorld('api', {
  onDeviceFound: (callback: (device: Device) => void) => {
    ipcRenderer.on('device-found', (_event, device: Device) => callback(device));
  },
  scanNetwork: (): Promise<Device[]> => ipcRenderer.invoke('scan-network'),
  saveSnapshot: (dataUrl: string): Promise<boolean> => ipcRenderer.invoke('save-snapshot', dataUrl),
  saveRecording: (data: ArrayBuffer): Promise<boolean> => ipcRenderer.invoke('save-recording', data),
  setAlwaysOnTop: (value: boolean): Promise<void> => ipcRenderer.invoke('set-always-on-top', value)
});
