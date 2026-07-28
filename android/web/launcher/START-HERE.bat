@echo off
setlocal enabledelayedexpansion
title Gohil Bookkeeper

rem ---------------------------------------------------------------------------
rem  Double-click launcher for Windows.
rem
rem  The generated bin\gohil-bookkeeper.bat exits the instant anything is wrong,
rem  which when double-clicked shows a console window for half a second and then
rem  nothing at all — the single most common way this looks "broken" when it is
rem  really just a missing Java. Everything here exists to make a failure
rem  readable: check Java first, say plainly what to do about it, and never let
rem  the window close on an error without a keypress.
rem ---------------------------------------------------------------------------

echo.
echo   Gohil Bookkeeper
echo   ================
echo.

set "JAVA_CMD="

rem Prefer JAVA_HOME when it is set, then fall back to whatever is on PATH.
if defined JAVA_HOME (
    if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_CMD=%JAVA_HOME%\bin\java.exe"
)
if not defined JAVA_CMD (
    where java >nul 2>nul
    if not errorlevel 1 set "JAVA_CMD=java"
)

if not defined JAVA_CMD goto :no_java

rem Report the version so a too-old Java is obvious in the log above any error.
echo   Using Java:
for /f "tokens=*" %%v in ('"%JAVA_CMD%" -version 2^>^&1') do echo     %%v
echo.

echo   Starting the server. Your browser should open in a few seconds.
echo   Leave this window open while you use the app.
echo   Close it, or press Ctrl+C, to stop.
echo.

call "%~dp0bin\gohil-bookkeeper.bat" %*
set "EXIT_CODE=%ERRORLEVEL%"

echo.
if not "%EXIT_CODE%"=="0" (
    echo   ------------------------------------------------------------------
    echo   The server stopped with an error ^(code %EXIT_CODE%^).
    echo.
    echo   If the message above mentions "UnsupportedClassVersionError",
    echo   your Java is older than 17. Install Java 17 or newer from:
    echo       https://adoptium.net/temurin/releases/?version=17
    echo.
    echo   If it mentions "Address already in use", something else is on
    echo   port 8080. Start it on another port instead:
    echo       START-HERE.bat --port 8090
    echo   ------------------------------------------------------------------
) else (
    echo   Server stopped.
)
echo.
pause
exit /b %EXIT_CODE%

:no_java
echo   ------------------------------------------------------------------
echo   Java was not found on this computer.
echo.
echo   This app needs Java 17 or newer. It is a free, one-time install:
echo.
echo     1. Go to  https://adoptium.net/temurin/releases/?version=17
echo     2. Choose  Operating System: Windows,  Architecture: x64
echo     3. Download the  .msi  installer and run it
echo     4. Accept the defaults ^(make sure "Set JAVA_HOME" is enabled^)
echo     5. Close this window, then double-click START-HERE.bat again
echo.
echo   To check it worked, open Command Prompt and type:  java -version
echo   ------------------------------------------------------------------
echo.
pause
exit /b 1
