@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0tcl-button-helper.ps1"
set "RESULT=%ERRORLEVEL%"
echo.
pause
exit /b %RESULT%
