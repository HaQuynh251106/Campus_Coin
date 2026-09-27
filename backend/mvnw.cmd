@echo off
setlocal
set "DIR=%~dp0"
set "MVN_BIN=C:\Users\thaid\.m2\wrapper\dists\apache-maven-3.9.16\56ba1f9f\bin\mvn.cmd"

if exist "%MVN_BIN%" (
    "%MVN_BIN%" %*
    exit /b %ERRORLEVEL%
)

for /f "delims=" %%I in ('where mvn 2^>nul') do (
    "%%I" %*
    exit /b %ERRORLEVEL%
)

echo [ERROR] Maven not found.
exit /b 1
