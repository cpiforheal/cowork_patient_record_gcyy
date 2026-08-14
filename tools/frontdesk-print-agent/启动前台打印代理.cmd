@echo off
setlocal
set "AGENT_DIR=%~dp0"

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%AGENT_DIR%clinic-print-agent.ps1" -Port 18848 -ConfigPath "%AGENT_DIR%clinic-print-agent.json"

if errorlevel 1 pause
