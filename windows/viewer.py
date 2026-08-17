"""Cam Mic Viewer - Windows companion app for Cam Mic Streamer (Android).

Connects to the phone over Wi-Fi, shows the camera video (MJPEG) and
plays the microphone audio (live WAV stream).
"""

import io
import queue
import threading
import tkinter as tk
from tkinter import ttk
from urllib.request import urlopen

from PIL import Image, ImageTk

try:
    import sounddevice as sd
except Exception:  # audio is optional; video still works without it
    sd = None

DEFAULT_PORT = "8080"
SAMPLE_RATE = 44100


class ViewerApp:
    def __init__(self, root: tk.Tk):
        self.root = root
        root.title("Cam Mic Viewer")
        root.geometry("900x640")
        root.configure(bg="#101318")

        self.stop_event = threading.Event()
        self.frame_queue = queue.Queue(maxsize=2)
        self.video_thread = None
        self.audio_thread = None
        self.connected = False
        self.photo = None  # keep a reference so Tk doesn't GC the image

        bar = tk.Frame(root, bg="#1a1f27")
        bar.pack(fill="x", padx=8, pady=8)

        tk.Label(bar, text="Phone IP:", fg="#e8eaed", bg="#1a1f27").pack(side="left", padx=(8, 4))
        self.ip_entry = tk.Entry(bar, width=16)
        self.ip_entry.insert(0, "192.168.1.")
        self.ip_entry.pack(side="left")

        tk.Label(bar, text="Port:", fg="#e8eaed", bg="#1a1f27").pack(side="left", padx=(12, 4))
        self.port_entry = tk.Entry(bar, width=6)
        self.port_entry.insert(0, DEFAULT_PORT)
        self.port_entry.pack(side="left")

        self.audio_var = tk.BooleanVar(value=True)
        self.audio_check = tk.Checkbutton(
            bar, text="Audio", variable=self.audio_var,
            fg="#e8eaed", bg="#1a1f27", selectcolor="#101318",
            activebackground="#1a1f27", activeforeground="#e8eaed",
        )
        self.audio_check.pack(side="left", padx=12)

        self.connect_btn = ttk.Button(bar, text="Connect", command=self.toggle_connection)
        self.connect_btn.pack(side="left", padx=8)

        self.status = tk.Label(root, text="Enter your phone's IP address (shown in the Android app) and press Connect.",
                               fg="#9aa0a6", bg="#101318", anchor="w")
        self.status.pack(fill="x", padx=12)

        self.video_label = tk.Label(root, bg="#000000")
        self.video_label.pack(fill="both", expand=True, padx=8, pady=8)

        self.ip_entry.bind("<Return>", lambda _e: self.toggle_connection() if not self.connected else None)
        root.protocol("WM_DELETE_WINDOW", self.on_close)
        self.poll_frames()

    # ---------- connection handling ----------

    def toggle_connection(self):
        if self.connected:
            self.disconnect()
        else:
            self.connect()

    def connect(self):
        ip = self.ip_entry.get().strip()
        port = self.port_entry.get().strip() or DEFAULT_PORT
        if not ip:
            self.set_status("Please enter the phone's IP address.")
            return
        base = f"http://{ip}:{port}"
        self.stop_event.clear()
        self.connected = True
        self.connect_btn.config(text="Disconnect")
        self.set_status(f"Connecting to {base} ...")

        self.video_thread = threading.Thread(target=self.video_loop, args=(base,), daemon=True)
        self.video_thread.start()
        if self.audio_var.get():
            if sd is None:
                self.set_status("Audio library unavailable - video only.")
            else:
                self.audio_thread = threading.Thread(target=self.audio_loop, args=(base,), daemon=True)
                self.audio_thread.start()

    def disconnect(self):
        self.stop_event.set()
        self.connected = False
        self.connect_btn.config(text="Connect")
        self.set_status("Disconnected.")

    def on_close(self):
        self.stop_event.set()
        self.root.destroy()

    def set_status(self, text):
        self.root.after(0, lambda: self.status.config(text=text))

    # ---------- video ----------

    def video_loop(self, base):
        url = base + "/video"
        try:
            stream = urlopen(url, timeout=10)
        except Exception as exc:
            self.set_status(f"Could not connect to {url}  ({exc})")
            self.root.after(0, self.disconnect)
            return
        self.set_status(f"Connected to {base}")
        buffer = b""
        try:
            while not self.stop_event.is_set():
                chunk = stream.read(8192)
                if not chunk:
                    break
                buffer += chunk
                # Extract complete JPEG frames (SOI .. EOI)
                while True:
                    start = buffer.find(b"\xff\xd8")
                    end = buffer.find(b"\xff\xd9", start + 2) if start != -1 else -1
                    if start == -1 or end == -1:
                        # avoid unbounded growth if the stream is garbage
                        if start == -1 and len(buffer) > 1_000_000:
                            buffer = b""
                        break
                    jpeg = buffer[start:end + 2]
                    buffer = buffer[end + 2:]
                    try:
                        self.frame_queue.put_nowait(jpeg)
                    except queue.Full:
                        try:
                            self.frame_queue.get_nowait()
                            self.frame_queue.put_nowait(jpeg)
                        except queue.Empty:
                            pass
        except Exception as exc:
            if not self.stop_event.is_set():
                self.set_status(f"Video stream ended ({exc})")
        finally:
            try:
                stream.close()
            except Exception:
                pass
            if not self.stop_event.is_set():
                self.root.after(0, self.disconnect)

    def poll_frames(self):
        try:
            jpeg = self.frame_queue.get_nowait()
        except queue.Empty:
            jpeg = None
        if jpeg is not None:
            try:
                image = Image.open(io.BytesIO(jpeg))
                image.load()
                w = max(self.video_label.winfo_width(), 32)
                h = max(self.video_label.winfo_height(), 32)
                image.thumbnail((w, h))
                self.photo = ImageTk.PhotoImage(image)
                self.video_label.config(image=self.photo)
            except Exception:
                pass
        self.root.after(15, self.poll_frames)

    # ---------- audio ----------

    def audio_loop(self, base):
        url = base + "/audio"
        try:
            stream = urlopen(url, timeout=10)
            stream.read(44)  # skip the WAV header
        except Exception:
            self.set_status("Connected (video only - audio stream unavailable).")
            return
        try:
            with sd.RawOutputStream(samplerate=SAMPLE_RATE, channels=1, dtype="int16") as out:
                while not self.stop_event.is_set():
                    data = stream.read(4096)
                    if not data:
                        break
                    out.write(data)
        except Exception:
            if not self.stop_event.is_set():
                self.set_status("Audio playback stopped (video continues).")
        finally:
            try:
                stream.close()
            except Exception:
                pass


def main():
    root = tk.Tk()
    ViewerApp(root)
    root.mainloop()


if __name__ == "__main__":
    main()
