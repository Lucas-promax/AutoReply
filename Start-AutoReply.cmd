@echo off
setlocal
pushd "%~dp0"
if not exist ".venv\Scripts\python.exe" (
    echo Python environment not found in .venv.
    pause
    exit /b 1
)
".venv\Scripts\python.exe" -m gui
if errorlevel 1 pause
popd
