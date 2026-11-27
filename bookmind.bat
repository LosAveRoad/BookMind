@echo off
setlocal
pushd "%~dp0" || exit /b 1
set "JAVA=java"
if defined JAVA_HOME set "JAVA=%JAVA_HOME%\bin\java.exe"
if not defined BOOKMIND_PORT set "BOOKMIND_PORT=8080"
echo BookMind: http://localhost:%BOOKMIND_PORT% (Ctrl+C to stop)
"%JAVA%" -jar bookmind.jar --server.address=127.0.0.1 %*
set "result=%ERRORLEVEL%"
popd
if not "%result%"=="0" (
    echo Startup failed. Check that Java 21 is installed and the port is available.
    pause
)
exit /b %result%
