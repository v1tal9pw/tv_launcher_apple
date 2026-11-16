@echo off
setlocal
chcp 65001 >nul
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0tcl-button-helper.ps1"
set "RESULT=%ERRORLEVEL%"
echo.
echo Нажмите любую клавишу для выхода...
pause >nul
exit /b %RESULT%
