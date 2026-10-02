@echo off
rem Build bot (native windows binary) into bin\.
rem Requires a JDK (17+): the Kotlin/Native compiler itself is downloaded by Gradle.
cd /d "%~dp0"

where java >nul 2>nul
if errorlevel 1 (
  echo error: java ^(JDK 17+^) is not installed - Gradle needs it.
  echo install it for windows with one of:
  echo   winget install EclipseAdoptium.Temurin.21.JDK
  echo   choco install temurin21jdk
  echo   or download: https://adoptium.net/
  exit /b 1
)

call gradlew.bat -q linkReleaseExecutableMingwX64
if errorlevel 1 exit /b 1
if not exist bin mkdir bin
copy /y build\bin\mingwX64\releaseExecutable\bot.exe bin\bot.exe >nul
echo built: %CD%\bin\bot.exe
