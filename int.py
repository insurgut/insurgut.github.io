import time
import tkinter as tk
import pygame
from PIL import Image, ImageTk
from ctypes import cast, POINTER
from comtypes import CLSCTX_ALL
from pycaw.pycaw import AudioUtilities, IAudioEndpointVolume

VOLUME = 80
AUDIO_FILE = "11848.mp3"
IMAGE_FILE = "11848.png"
DURATION = 11

def set_system_volume(volume):
    devices = AudioUtilities.GetSpeakers()
    interface = devices.Activate(IAudioEndpointVolume._iid_, CLSCTX_ALL, None)
    volume_obj = cast(interface, POINTER(IAudioEndpointVolume))
    volume_obj.SetMasterVolumeLevelScalar(volume / 100.0, None)

def main():
    try:
        set_system_volume(VOLUME)
    except:
        pass

    pygame.mixer.init()
    pygame.mixer.music.load(AUDIO_FILE)
    pygame.mixer.music.play()

    root = tk.Tk()
    root.attributes('-fullscreen', True)
    root.attributes('-topmost', True)
    root.overrideredirect(True)

    screen_w = root.winfo_screenwidth()
    screen_h = root.winfo_screenheight()

    img = Image.open(IMAGE_FILE)
    img = img.resize((screen_w, screen_h), Image.LANCZOS)
    photo = ImageTk.PhotoImage(img)

    label = tk.Label(root, image=photo, bd=0)
    label.pack(fill=tk.BOTH, expand=True)

    def close():
        pygame.mixer.music.stop()
        pygame.mixer.quit()
        root.destroy()

    root.after(DURATION * 1000, close)
    root.mainloop()

if __name__ == "__main__":
    main()