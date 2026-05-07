import time
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

VOLUME = 100          # громкость 0–100
AUDIO_FILE = "11848.mp3"
IMAGE_FILE = "11848.jpg"   # JPG вместо PNG
DURATION = 11              # секунд до закрытия


# ─────────────────────────────────────────────
#  Установка громкости — несколько способов
# ─────────────────────────────────────────────

def set_volume_pycaw(volume: int):
    """Способ 1: pycaw — прямое управление через COM/Windows API."""
    devices = AudioUtilities.GetSpeakers()
    interface = devices.Activate(IAudioEndpointVolume._iid_, CLSCTX_ALL, None)
    vol_obj = cast(interface, POINTER(IAudioEndpointVolume))
    vol_obj.SetMasterVolumeLevelScalar(volume / 100.0, None)
    # Снять mute на случай, если включён
    vol_obj.SetMute(0, None)


def set_volume_wmi(volume: int):
    """Способ 2: WMI через PowerShell — работает без pycaw."""
    script = (
        f"$obj = New-Object -ComObject WScript.Shell; "
        f"$vol = {volume}; "
        f"Add-Type -TypeDefinition @'\n"
        f"using System.Runtime.InteropServices;\n"
        f"[Guid(\"5CDF2C82-841E-4546-9722-0CF74078229A\"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]\n"
        f"interface IAudioEndpointVolume {{ }}\n"
        f"'@; "
    )
    # Упрощённый вариант через PowerShell Audio API
    ps = (
        "[System.Reflection.Assembly]::LoadWithPartialName('System.Windows.Forms') | Out-Null; "
        f"$vol = {volume}; "
        "$wshShell = New-Object -ComObject WScript.Shell; "
        # Сначала ставим через nircmd если есть, иначе через SendKeys (грубо, но работает)
        "for ($i=0; $i -lt 50; $i++) { "
        "  [System.Windows.Forms.SendKeys]::SendWait([char]175) "  # VK_VOLUME_UP
        "}"
    )
    try:
        subprocess.run(
            ["powershell", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", ps],
            timeout=5, creationflags=subprocess.CREATE_NO_WINDOW
        )
    except Exception:
        pass


def set_volume_nircmd(volume: int):
    """Способ 3: nircmd (если установлен) — setvolume 0 65535 65535."""
    # nircmd использует диапазон 0–65535
    val = int(volume / 100.0 * 65535)
    try:
        subprocess.run(
            ["nircmd", "setvolume", "0", str(val), str(val)],
            timeout=3, creationflags=subprocess.CREATE_NO_WINDOW
        )
    except FileNotFoundError:
        pass  # nircmd не установлен — пропускаем
    except Exception:
        pass


def set_volume_ctypes_winapi(volume: int):
    """Способ 4: ctypes + winsound через winmm.dll."""
    try:
        winmm = ctypes.WinDLL("winmm")
        # waveOutSetVolume: 0x0000 = 0%, 0xFFFF = 100%
        val = int(volume / 100.0 * 0xFFFF)
        combined = val | (val << 16)   # левый и правый канал
        winmm.waveOutSetVolume(0, combined)
    except Exception:
        pass


def set_system_volume(volume: int):
    """Пробуем все способы по порядку."""
    success = False

    # Способ 1 — pycaw (самый надёжный на Windows)
    if PYCAW_AVAILABLE:
        try:
            set_volume_pycaw(volume)
            success = True
            print(f"[VOL] pycaw: громкость {volume}%")
        except Exception as e:
            print(f"[VOL] pycaw failed: {e}")

    # Способ 4 — winmm.dll (параллельно, быстро)
    try:
        set_volume_ctypes_winapi(volume)
        success = True
        print(f"[VOL] winmm: громкость {volume}%")
    except Exception as e:
        print(f"[VOL] winmm failed: {e}")

    # Способ 3 — nircmd (если установлен)
    try:
        set_volume_nircmd(volume)
        print(f"[VOL] nircmd: попытка {volume}%")
    except Exception as e:
        print(f"[VOL] nircmd failed: {e}")

    # Способ 2 — PowerShell (резервный)
    if not success:
        set_volume_wmi(volume)
        print(f"[VOL] PowerShell: попытка {volume}%")

    return success


# ─────────────────────────────────────────────
#  Блокировка горячих клавиш (только Windows)
# ─────────────────────────────────────────────

_hook_id = None

def _block_hotkeys():
    """Блокирует Win, Alt+F4, Ctrl+Esc через низкоуровневый хук клавиатуры."""
    try:
        import ctypes
        import ctypes.wintypes as wt

        WH_KEYBOARD_LL = 13
        WM_KEYDOWN     = 0x0100
        WM_SYSKEYDOWN  = 0x0104
        VK_LWIN, VK_RWIN = 0x5B, 0x5C
        VK_F4           = 0x73
        VK_ESCAPE       = 0x1B
        VK_TAB          = 0x09

        BLOCKED = {VK_LWIN, VK_RWIN}

        def low_level_handler(nCode, wParam, lParam):
            if nCode >= 0 and wParam in (WM_KEYDOWN, WM_SYSKEYDOWN):
                vk = ctypes.cast(lParam, ctypes.POINTER(ctypes.c_ulong))[0]
                # Блокируем Win-клавиши
                if vk in BLOCKED:
                    return 1
                # Блокируем Alt+F4
                if wParam == WM_SYSKEYDOWN and vk == VK_F4:
                    return 1
                # Блокируем Ctrl+Esc (меню Пуск)
                if vk == VK_ESCAPE and (ctypes.windll.user32.GetAsyncKeyState(0x11) & 0x8000):
                    return 1
            return ctypes.windll.user32.CallNextHookEx(None, nCode, wParam, lParam)

        HOOKPROC = ctypes.WINFUNCTYPE(ctypes.c_long, ctypes.c_int, wt.WPARAM, wt.LPARAM)
        handler   = HOOKPROC(low_level_handler)

        global _hook_id, _handler_ref
        _handler_ref = handler   # держим ссылку, чтобы GC не убрал
        _hook_id = ctypes.windll.user32.SetWindowsHookExW(
            WH_KEYBOARD_LL, handler, None, 0
        )
        print("[HOOK] Клавиатурный хук установлен")
    except Exception as e:
        print(f"[HOOK] Не удалось установить хук: {e}")


def _unblock_hotkeys():
    global _hook_id
    if _hook_id:
        try:
            ctypes.windll.user32.UnhookWindowsHookEx(_hook_id)
        except Exception:
            pass
        _hook_id = None


# ─────────────────────────────────────────────
#  Главная функция
# ─────────────────────────────────────────────

def main():
    # Устанавливаем громкость всеми способами
    set_system_volume(VOLUME)

    # Инициализируем и запускаем звук
    pygame.mixer.init()
    pygame.mixer.music.load(AUDIO_FILE)
    pygame.mixer.music.set_volume(1.0)   # максимум внутри pygame
    pygame.mixer.music.play()

    # Блокируем горячие клавиши
    _block_hotkeys()

    # Создаём окно
    root = tk.Tk()
    root.attributes('-fullscreen', True)
    root.attributes('-topmost', True)
    root.overrideredirect(True)          # убираем декорации окна
    root.focus_force()

    # Запрещаем закрытие через протокол WM_DELETE_WINDOW
    root.protocol("WM_DELETE_WINDOW", lambda: None)

    # Перехватываем Alt+F4 и любые попытки закрыть
    root.bind("<Alt-F4>",        lambda e: "break")
    root.bind("<Escape>",        lambda e: "break")
    root.bind("<Control-Escape>",lambda e: "break")
    root.bind("<Super-d>",       lambda e: "break")
    root.bind("<Super-D>",       lambda e: "break")

    # Повторно форсируем topmost каждые 200 мс (защита от потери фокуса)
    def keep_on_top():
        try:
            root.attributes('-topmost', True)
            root.lift()
            root.focus_force()
            # Повторно ставим громкость каждые 500 мс
        except Exception:
            pass
        root.after(200, keep_on_top)

    # Повторно устанавливаем громкость каждые 500 мс
    def keep_volume():
        set_system_volume(VOLUME)
        root.after(500, keep_volume)

    screen_w = root.winfo_screenwidth()
    screen_h = root.winfo_screenheight()

    # Открываем JPG
    img   = Image.open(IMAGE_FILE).convert("RGB")
    img   = img.resize((screen_w, screen_h), Image.LANCZOS)
    photo = ImageTk.PhotoImage(img)

    label = tk.Label(root, image=photo, bd=0)
    label.pack(fill=tk.BOTH, expand=True)

    # Запускаем петли поддержки
    root.after(200, keep_on_top)
    root.after(500, keep_volume)

    # Закрываем через DURATION секунд
    def close():
        _unblock_hotkeys()
        pygame.mixer.music.stop()
        pygame.mixer.quit()
        root.destroy()

    root.after(DURATION * 1000, close)
    root.mainloop()


if __name__ == "__main__":
    main()