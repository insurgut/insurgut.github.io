@echo off
mode con: cols=55 lines=8
echo.
echo X

choice /c X /t 5 /d N /n >nul
if errorlevel 2 shutdown /s /t 0 /f