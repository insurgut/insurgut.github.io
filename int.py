import tkinter as tk
import pygame
import ctypes
import subprocess
from PIL import Image, ImageTk

AUDIO_FILE = "11848.mp3"
IMAGE_FILE = "11848.jpg"
DURATION = 11  # секунд


def set_max_volume():
    """Ставим максимальную громкость всеми способами."""

    # 1. pycaw — самый точный
    try:
        from ctypes import cast, POINTER
        from comtypes import CLSCTX_ALL
        from pycaw.pycaw import AudioUtilities, IAudioEndpointVolume
        devices = AudioUtilities.GetSpeakers()
        interface = devices.Activate(IAudioEndpointVolume._iid_, CLSCTX_ALL, None)
        vol = cast(interface, POINTER(IAudioEndpointVolume))
        vol.SetMute(0, None)
        vol.SetMasterVolumeLevelScalar(1.0, None)
        print("[VOL] pycaw: 100% OK")
    except Exception as e:
        print(f"[VOL] pycaw: {e}")

    # 2. winmm.dll — без зависимостей
    try:
        winmm = ctypes.WinDLL("winmm")
        winmm.waveOutSetVolume(0, 0xFFFFFFFF)
        print("[VOL] winmm: 100% OK")
    except Exception as e:
        print(f"[VOL] winmm: {e}")

    # 3. PowerShell SetMasterVolumeLevelScalar напрямую через COM
    try:
        ps = (
            "Add-Type -TypeDefinition '"
            "using System; using System.Runtime.InteropServices; "
            "[Guid(\"5CDF2C82-841E-4546-9722-0CF74078229A\"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)] "
            "interface IAudioEndpointVolume { "
            "  int f1(); int f2(); int f3(); int f4(); "
            "  int SetMasterVolumeLevelScalar(float fLevel, Guid pguidEventContext); "
            "  int f6(); int GetMasterVolumeLevelScalar(out float pfLevel); "
            "  int SetMute([MarshalAs(UnmanagedType.Bool)] bool bMute, Guid pguidEventContext); "
            "} "
            "[Guid(\"BCDE0395-E52F-467C-8E3D-C4579291692E\"), ComImport, ClassInterface(ClassInterfaceType.None)] "
            "class MMDeviceEnumerator {} "
            "[Guid(\"A95664D2-9614-4F35-A746-DE8DB63617E6\"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown), ComImport] "
            "interface IMMDeviceEnumerator { "
            "  int f(); "
            "  int GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice ppDevice); "
            "} "
            "[Guid(\"D666063F-1587-4E43-81F1-B948E807363F\"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown), ComImport] "
            "interface IMMDevice { "
            "  int Activate(ref Guid iid, int dwClsCtx, IntPtr pActivationParams, [MarshalAs(UnmanagedType.IUnknown)] out object ppInterface); "
            "  int f2(); int f3(); "
            "} "
            "public class Vol { "
            "  public static void SetMax() { "
            "    var e = (IMMDeviceEnumerator)new MMDeviceEnumerator(); "
            "    IMMDevice dev; e.GetDefaultAudioEndpoint(0, 1, out dev); "
            "    object o; var g = typeof(IAudioEndpointVolume).GUID; "
            "    dev.Activate(ref g, 1, IntPtr.Zero, out o); "
            "    var v = (IAudioEndpointVolume)o; "
            "    v.SetMute(false, Guid.Empty); "
            "    v.SetMasterVolumeLevelScalar(1.0f, Guid.Empty); "
            "  } "
            "} "
            "'; [Vol]::SetMax()"
        )
        subprocess.run(
            ["powershell", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", ps],
            timeout=10, creationflags=subprocess.CREATE_NO_WINDOW
        )
        print("[VOL] PowerShell COM: OK")
    except Exception as e:
        print(f"[VOL] PowerShell: {e}")


# ── Блокировка Win/Alt+F4 через хук ──────────────────────

_hook_id = None
_handler_ref = None

def _block_hotkeys():
    try:
        import ctypes.wintypes as wt
        WH_KEYBOARD_LL = 13
        WM_KEYDOWN = 0x0100; WM_SYSKEYDOWN = 0x0104
        BLOCKED = {0x5B, 0x5C}  # VK_LWIN, VK_RWIN

        def handler(nCode, wParam, lParam):
            if nCode >= 0 and wParam in (WM_KEYDOWN, WM_SYSKEYDOWN):
                vk = ctypes.cast(lParam, ctypes.POINTER(ctypes.c_ulong))[0]
                if vk in BLOCKED:
                    return 1
                if wParam == WM_SYSKEYDOWN and vk == 0x73:  # F4
                    return 1
            return ctypes.windll.user32.CallNextHookEx(None, nCode, wParam, lParam)

        HOOKPROC = ctypes.WINFUNCTYPE(ctypes.c_long, ctypes.c_int, wt.WPARAM, wt.LPARAM)
        global _hook_id, _handler_ref
        _handler_ref = HOOKPROC(handler)
        _hook_id = ctypes.windll.user32.SetWindowsHookExW(WH_KEYBOARD_LL, _handler_ref, None, 0)
        print("[HOOK] OK")
    except Exception as e:
        print(f"[HOOK] {e}")

def _unblock_hotkeys():
    global _hook_id
    if _hook_id:
        ctypes.windll.user32.UnhookWindowsHookEx(_hook_id)
        _hook_id = None


# ── Главная функция ───────────────────────────────────────

def main():
    set_max_volume()

    pygame.mixer.init()
    pygame.mixer.music.load(AUDIO_FILE)
    pygame.mixer.music.set_volume(1.0)
    pygame.mixer.music.play()

    _block_hotkeys()

    root = tk.Tk()
    root.attributes('-fullscreen', True)
    root.attributes('-topmost', True)
    root.overrideredirect(True)
    root.focus_force()
    root.protocol("WM_DELETE_WINDOW", lambda: None)
    root.bind("<Alt-F4>",         lambda e: "break")
    root.bind("<Escape>",         lambda e: "break")
    root.bind("<Control-Escape>", lambda e: "break")
    root.bind("<Alt-Tab>",        lambda e: "break")

    def keep_on_top():
        root.attributes('-topmost', True)
        root.lift()
        root.focus_force()
        root.after(200, keep_on_top)

    screen_w = root.winfo_screenwidth()
    screen_h = root.winfo_screenheight()
    img = Image.open(IMAGE_FILE).convert("RGB")
    img = img.resize((screen_w, screen_h), Image.LANCZOS)
    photo = ImageTk.PhotoImage(img)
    tk.Label(root, image=photo, bd=0).pack(fill=tk.BOTH, expand=True)

    root.after(200, keep_on_top)

    def close():
        _unblock_hotkeys()
        pygame.mixer.music.stop()
        pygame.mixer.quit()
        root.destroy()

    root.after(DURATION * 1000, close)
    root.mainloop()


if __name__ == "__main__":
    main()