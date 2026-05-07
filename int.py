#2
import tkinter as tk
import pygame
import subprocess
import ctypes
from PIL import Image, ImageTk

try:
    from ctypes import cast, POINTER
    from comtypes import CLSCTX_ALL
    from pycaw.pycaw import AudioUtilities, IAudioEndpointVolume
    PYCAW_AVAILABLE = True
except ImportError:
    PYCAW_AVAILABLE = False

VOLUME = 100
AUDIO_FILE = "11848.mp3"
IMAGE_FILE = "11848.jpg"
DURATION = 11


# ── Способы установки громкости ──────────────────────────

def set_volume_pycaw(volume: int):
    """Способ 1: pycaw — прямой доступ к Windows Audio API."""
    devices = AudioUtilities.GetSpeakers()
    interface = devices.Activate(
        IAudioEndpointVolume._iid_, CLSCTX_ALL, None
    )
    vol_obj = cast(interface, POINTER(IAudioEndpointVolume))
    vol_obj.SetMasterVolumeLevelScalar(volume / 100.0, None)
    vol_obj.SetMute(0, None)


def set_volume_ctypes_winapi(volume: int):
    """Способ 2: winmm.dll — waveOutSetVolume."""
    winmm = ctypes.WinDLL("winmm")
    val = int(volume / 100.0 * 0xFFFF)
    combined = val | (val << 16)  # левый + правый канал
    winmm.waveOutSetVolume(0, combined)


def set_volume_nircmd(volume: int):
    """Способ 3: nircmd (если установлен)."""
    val = int(volume / 100.0 * 65535)
    try:
        subprocess.run(
            ["nircmd", "setvolume", "0", str(val), str(val)],
            timeout=3, creationflags=subprocess.CREATE_NO_WINDOW
        )
    except FileNotFoundError:
        pass


def set_volume_powershell_core(volume: int):
    """Способ 4: PowerShell через AudioEndpointVolume COM напрямую."""
    ps = f"""
Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;

[ComImport]
[Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")]
internal class MMDeviceEnumeratorComObject {{ }}

public class Audio {{
    [DllImport("ole32.dll")]
    static extern int CoCreateInstance(ref Guid clsid, IntPtr inner,
        uint context, ref Guid uuid, out IntPtr ptr);

    public static void SetVolume(float vol) {{
        var enumeratorId = new Guid("BCDE0395-E52F-467C-8E3D-C4579291692E");
        var enumeratorIId = new Guid("A95664D2-9614-4F35-A746-DE8DB63617E6");
        var endpointIId = new Guid("5CDF2C82-841E-4546-9722-0CF74078229A");

        IntPtr pEnum = IntPtr.Zero;
        CoCreateInstance(ref enumeratorId, IntPtr.Zero, 1, ref enumeratorIId, out pEnum);
    }}
}}
"@
# Используем Shell.Application для простоты
$shell = New-Object -ComObject Shell.Application
# Устанавливаем громкость через SndVol
$vol = [Math]::Round({volume})
$code = @'
using System.Runtime.InteropServices;
public class VolumeHelper {{
    [DllImport("user32.dll")]
    public static extern void keybd_event(byte bVk, byte bScan, uint dwFlags, int dwExtraInfo);
    public static void SetVol(int target) {{
        // VK_VOLUME_MUTE = 0xAD, сначала сбрасываем mute
        keybd_event(0xAD, 0, 2, 0); // key up чтобы не переключать
    }}
}}
'@
"""
    # Этот метод пропускаем — используем только надёжные выше
    pass


def set_system_volume(volume: int):
    """Применяем все надёжные методы."""

    # Способ 1: pycaw
    if PYCAW_AVAILABLE:
        try:
            set_volume_pycaw(volume)
            print(f"[VOL] pycaw: {volume}%  OK")
        except Exception as e:
            print(f"[VOL] pycaw failed: {e}")

    # Способ 2: winmm.dll
    try:
        set_volume_ctypes_winapi(volume)
        print(f"[VOL] winmm: {volume}%  OK")
    except Exception as e:
        print(f"[VOL] winmm failed: {e}")

    # Способ 3: nircmd (тихо, если нет — пропускаем)
    set_volume_nircmd(volume)


# ── Блокировка горячих клавиш ────────────────────────────

_hook_id = None
_handler_ref = None

def _block_hotkeys():
    try:
        import ctypes.wintypes as wt

        WH_KEYBOARD_LL = 13
        WM_KEYDOWN    = 0x0100
        WM_SYSKEYDOWN = 0x0104
        VK_LWIN   = 0x5B
        VK_RWIN   = 0x5C
        VK_F4     = 0x73
        VK_ESCAPE = 0x1B

        BLOCKED = {VK_LWIN, VK_RWIN}

        def handler(nCode, wParam, lParam):
            if nCode >= 0 and wParam in (WM_KEYDOWN, WM_SYSKEYDOWN):
                vk = ctypes.cast(lParam, ctypes.POINTER(ctypes.c_ulong))[0]
                if vk in BLOCKED:
                    return 1
                if wParam == WM_SYSKEYDOWN and vk == VK_F4:
                    return 1
                if vk == VK_ESCAPE and (ctypes.windll.user32.GetAsyncKeyState(0x11) & 0x8000):
                    return 1
            return ctypes.windll.user32.CallNextHookEx(None, nCode, wParam, lParam)

        HOOKPROC = ctypes.WINFUNCTYPE(ctypes.c_long, ctypes.c_int, wt.WPARAM, wt.LPARAM)
        global _hook_id, _handler_ref
        _handler_ref = HOOKPROC(handler)
        _hook_id = ctypes.windll.user32.SetWindowsHookExW(
            WH_KEYBOARD_LL, _handler_ref, None, 0
        )
        print("[HOOK] Клавиатурный хук установлен")
    except Exception as e:
        print(f"[HOOK] Ошибка: {e}")


def _unblock_hotkeys():
    global _hook_id
    if _hook_id:
        try:
            ctypes.windll.user32.UnhookWindowsHookEx(_hook_id)
        except Exception:
            pass
        _hook_id = None


# ── Главная функция ──────────────────────────────────────

def main():
    set_system_volume(VOLUME)

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
        try:
            root.attributes('-topmost', True)
            root.lift()
            root.focus_force()
        except Exception:
            pass
        root.after(200, keep_on_top)

    def keep_volume():
        try:
            set_system_volume(VOLUME)
        except Exception:
            pass
        root.after(500, keep_volume)

    screen_w = root.winfo_screenwidth()
    screen_h = root.winfo_screenheight()

    img   = Image.open(IMAGE_FILE).convert("RGB")
    img   = img.resize((screen_w, screen_h), Image.LANCZOS)
    photo = ImageTk.PhotoImage(img)

    label = tk.Label(root, image=photo, bd=0)
    label.pack(fill=tk.BOTH, expand=True)

    root.after(200, keep_on_top)
    root.after(500, keep_volume)

    def close():
        _unblock_hotkeys()
        pygame.mixer.music.stop()
        pygame.mixer.quit()
        root.destroy()

    root.after(DURATION * 1000, close)
    root.mainloop()


if __name__ == "__main__":
    main()