@echo off
setlocal

if not exist .venv (
  py -m venv .venv
)

call .venv\Scripts\activate.bat
python -m pip install --upgrade pip
python -m pip install -r requirements.txt pyinstaller

pyinstaller --noconfirm --clean --onefile --windowed --name PASSPRESS-HID-Control-Center main.py

echo Portable executable created at dist\PASSPRESS-HID-Control-Center.exe
endlocal
