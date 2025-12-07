import requests
import oss
import tkinter as tk
from tkinter import filedialog
import base64
import json
import re

class ApiLibClient:
    def __init__(self, api_key):
        self.api_key = api_key
        self.gateway_exec = "https://exec.lisurgut.ru" 
        self.headers = {"x-api-key": self.api_key}

    def execute_script(self, file_path):
        url = f"{self.gateway_exec}/api/exec"
        if not os.path.exists(file_path):
            return {"status": "error", "message": "Файл не найден"}

        try:
            print(f"📤 Отправка: {os.path.basename(file_path)}...")
            with open(file_path, 'rb') as f:
                files = {'file': f}
                # Таймаут 60 сек для установки библиотек
                response = requests.post(url, files=files, headers=self.headers, timeout=60)
            try:
                return response.json()
            except:
                return {"status": "error", "output": f"Ошибка сервера: {response.text}"}
        except Exception as e:
            return {"status": "error", "output": str(e)}

def main():
    API_KEY = "sk_fgkMFA6BjD6FsKsVyaZZKD86lAJLsthD"
    client = ApiLibClient(API_KEY)

    # 1. Выбор файла
    root = tk.Tk()
    root.withdraw()
    root.attributes('-topmost', True)
    file_path = filedialog.askopenfilename(title="Выберите Python скрипт (.py)")
    root.destroy()

    if not file_path:
        print("Отмена.")
        return

    res = client.execute_script(file_path)

    # 2. Обработка ответа
    raw_output = res.get('output', '')
    
    # --- ПОИСК СКРЫТОГО ФАЙЛА (TUNNELING) ---
    file_info = None
    
    # Регулярка для поиска блока ---FILE_START--- ... ---FILE_END---
    pattern = r"\n---FILE_START---\n(.*?)\n---FILE_END---"
    match = re.search(pattern, raw_output, re.DOTALL)
    
    if match:
        json_str = match.group(1)
        try:
            file_info = json.loads(json_str)
            # Удаляем технический блок из вывода, чтобы не мусорить в консоль
            raw_output = raw_output.replace(match.group(0), "")
        except Exception as e:
            print(f"Ошибка парсинга файла из output: {e}")
    
    # Если файл пришел по старинке (в ключе file)
    if not file_info and res.get('file'):
        file_info = res['file']

    # 3. Вывод очищенного текста
    print("\n" + "="*40)
    print("ОТВЕТ СЕРВЕРА:")
    print("="*40)
    print(raw_output.strip())
    print("-" * 40)

    # 4. Сохранение файла
    if file_info:
        file_name = file_info.get('name', 'downloaded.dat')
        b64_data = file_info.get('data', '')

        if b64_data:
            try:
                file_bytes = base64.b64decode(b64_data)
                save_path = os.path.join(os.getcwd(), file_name)
                
                with open(save_path, "wb") as f:
                    f.write(file_bytes)
                
                print(f"\n✅ ФАЙЛ СКАЧАН: {file_name}")
                print(f"📁 Путь: {save_path}")
                
                if os.name == 'nt': # Открыть файл (Windows)
                    os.startfile(save_path)
            except Exception as e:
                print(f"❌ Ошибка сохранения: {e}")
    else:
        print("ℹ️ Файлов для скачивания нет.")

if __name__ == "__main__":
    main()
