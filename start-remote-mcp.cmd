@echo off
setlocal EnableExtensions DisableDelayedExpansion
title Remote MCP

for %%I in ("%~dp0.") do set "ROOT=%%~fI"
cd /d "%ROOT%"

if not defined REMOTE_MCP_ENV_FILE set "REMOTE_MCP_ENV_FILE=%ROOT%\remote-mcp.env"

set "ARCH=amd64"
if /I "%PROCESSOR_ARCHITECTURE%"=="ARM64" set "ARCH=arm64"
set "EXE=%ROOT%\dist\windows-%ARCH%\remote-mcp.exe"

if exist "%EXE%" goto run

echo Prebuilt remote-mcp was not found. Building it now...
set "GOEXE="
where go.exe >nul 2>&1
if not errorlevel 1 set "GOEXE=go.exe"
if not defined GOEXE for /f "delims=" %%G in ('where /r Go installation directory go.exe 2^>nul') do if not defined GOEXE set "GOEXE=%%G"
if not defined GOEXE goto missing_go

if not exist "%ROOT%\dist\windows-%ARCH%" mkdir "%ROOT%\dist\windows-%ARCH%"
set "CGO_ENABLED=0"
set "GOOS=windows"
set "GOARCH=%ARCH%"
"%GOEXE%" build -mod=mod -trimpath -ldflags "-s -w -X main.version=0.1.0" -o "%EXE%" ./cmd/remote-mcp
if errorlevel 1 goto build_failed

:run
"%EXE%"
set "EXIT_CODE=%ERRORLEVEL%"
if not "%EXIT_CODE%"=="0" (
  echo.
  echo remote-mcp exited with code %EXIT_CODE%.
  pause
)
exit /b %EXIT_CODE%

:missing_go
echo.
echo No prebuilt binary or Go toolchain was found.
echo Run scripts\build-all.ps1 on a build machine, then copy this folder.
pause
exit /b 1

:build_failed
echo.
echo Build failed.
pause
exit /b 1
