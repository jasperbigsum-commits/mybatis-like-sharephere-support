@ECHO OFF
SETLOCAL
setlocal EnableDelayedExpansion

set MAVEN_PROJECTBASEDIR=%~dp0
if "%MAVEN_PROJECTBASEDIR:~-1%"=="\" set MAVEN_PROJECTBASEDIR=%MAVEN_PROJECTBASEDIR:~0,-1%

set WRAPPER_PROPS=%MAVEN_PROJECTBASEDIR%\.mvn\wrapper\maven-wrapper.properties
if not exist "%WRAPPER_PROPS%" (
  echo Missing %WRAPPER_PROPS%
  exit /b 1
)

for /f "tokens=1,* delims==" %%A in (%WRAPPER_PROPS%) do (
  if "%%A"=="distributionUrl" set DISTRIBUTION_URL=%%B
)

if "%DISTRIBUTION_URL%"=="" (
  echo distributionUrl not configured in %WRAPPER_PROPS%
  exit /b 1
)

set WRAPPER_DIR=%USERPROFILE%\.m2\wrapper\dists\apache-maven-3.9.9-bin
set MAVEN_HOME=%WRAPPER_DIR%

if not exist "%WRAPPER_DIR%\bin\mvn.cmd" (
  if not exist "%USERPROFILE%\.m2\wrapper\dists" mkdir "%USERPROFILE%\.m2\wrapper\dists"
  set ZIP_FILE=%TEMP%\apache-maven-3.9.9-bin-%RANDOM%%RANDOM%.zip
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-WebRequest -Uri '%DISTRIBUTION_URL%' -OutFile '!ZIP_FILE!'; Expand-Archive -Path '!ZIP_FILE!' -DestinationPath '%USERPROFILE%\.m2\wrapper\dists' -Force"
  if errorlevel 1 exit /b 1
  if exist "%USERPROFILE%\.m2\wrapper\dists\apache-maven-3.9.9" (
    move /Y "%USERPROFILE%\.m2\wrapper\dists\apache-maven-3.9.9" "%WRAPPER_DIR%" >NUL
  )
)

if not exist "%WRAPPER_DIR%\bin\mvn.cmd" (
  echo Failed to provision Maven wrapper distribution.
  exit /b 1
)

call "%WRAPPER_DIR%\bin\mvn.cmd" -f "%MAVEN_PROJECTBASEDIR%\pom.xml" %*
ENDLOCAL
