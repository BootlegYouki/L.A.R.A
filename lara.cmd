@echo off
powershell.exe -NoProfile -File "%~dp0lara.ps1" %*
exit /b %ERRORLEVEL%
