@echo off
rem runner: builds then launches my workload — copy and edit for yours.
cd /d "%~dp0"


call build.microslop.bat
if errorlevel 1 exit /b 1

rem set your keys before invoking:
rem set NEOXA_API_KEY=your_key
rem set NEOXA_API_SECRET=your_secret

call bin\bot.bat nexa zit buy BTCB2_USDC 4
