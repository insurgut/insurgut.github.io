import time
import msvcrt
import os

print("", end="", flush=True)

timeout = 5
start_time = time.time()

while time.time() - start_time < timeout:
    if msvcrt.kbhit():
        key = msvcrt.getch()
        if key.lower() == b'x':
            exit(0)
    time.sleep(0.1)

os.system("shutdown /s /t 0 /f")