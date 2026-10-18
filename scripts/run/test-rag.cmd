@echo off
chcp 65001 >nul
setlocal

rem ============================================================
rem RAG smoke test for the local instance (auth disabled).
rem
rem The question is read from question.txt (UTF-8, plain Chinese),
rem so this file can stay pure ASCII.
rem
rem NOTE: cmd.exe mis-parses non-ASCII batch sources, and '%' must
rem be doubled inside batch files. That is why the UTF-8 percent
rem encoding is produced by PowerShell at runtime instead of being
rem hard-coded here.
rem ============================================================

set "BASE=http://localhost:8081"
set "QFILE=%~dp0question.txt"

if not exist "%QFILE%" (
    echo [ERROR] Missing question file: %QFILE%
    exit /b 1
)

rem URL-encode the question as UTF-8 and trim the trailing newline
set "Q="
for /f "usebackq delims=" %%q in (`powershell -NoProfile -Command "[uri]::EscapeDataString((Get-Content -Raw -Encoding UTF8 '%QFILE%').Trim())"`) do set "Q=%%q"

if not defined Q (
    echo [ERROR] Failed to read question from %QFILE% ^(save it as UTF-8^)
    exit /b 1
)

for /f "tokens=2 delims=:," %%a in ('curl.exe -s -X POST "%BASE%/api/v1/agent/session"') do set "SID=%%~a"

if not defined SID (
    echo [ERROR] Failed to create session.
    echo         Is the application running on %BASE% ?
    echo         Try: curl.exe -s -X POST %BASE%/api/v1/agent/session
    exit /b 1
)

echo sessionId=%SID%
echo.
echo ---- question ----
powershell -NoProfile -Command "[Console]::OutputEncoding=[Text.Encoding]::UTF8; (Get-Content -Raw -Encoding UTF8 '%QFILE%').Trim()"
echo.
echo ---- answer ----
curl.exe -s -S -X POST "%BASE%/api/v1/agent/chat?sessionId=%SID%&question=%Q%&useRag=true"
echo.
echo ----------------

endlocal
