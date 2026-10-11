@echo off
rem Build bot (JVM fat jar) into bin\.
rem Requires a JDK (17+): Gradle and Kotlin are downloaded by the wrapper.
cd /d "%~dp0"

where java >nul 2>nul
if errorlevel 1 (
  echo error: java ^(JDK 17+^) is not installed.
  echo install it for windows with one of:
  echo   winget install EclipseAdoptium.Temurin.21.JDK
  echo   choco install temurin21jdk
  echo   or download: https://adoptium.net/
  exit /b 1
)

call gradlew.bat -q jar
if errorlevel 1 exit /b 1
if not exist bin mkdir bin
copy /y build\libs\bot.jar bin\bot.jar >nul
> bin\bot.bat echo @java -jar "%%~dp0bot.jar" %%*
echo built: %CD%\bin\bot.bat
