@echo off
setlocal
cd /d "%~dp0"

if not exist build mkdir build
javac --add-modules jdk.httpserver -Xlint:all -d build Main.java
if errorlevel 1 (
  echo.
  echo Build failed. Install JDK 17 or newer and try again.
  pause
  exit /b 1
)

java --add-modules jdk.httpserver -cp build Main
