@REM Maven wrapper script for Windows
@echo off
set MVN_VERSION=3.9.9
set MVN_HOME=%USERPROFILE%\.m2\wrapper\dists\apache-maven-%MVN_VERSION%
set MVN_URL=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/%MVN_VERSION%/apache-maven-%MVN_VERSION%-bin.tar.gz

if not exist "%MVN_HOME%\bin\mvn.cmd" (
    if not exist "%MVN_HOME%" mkdir "%MVN_HOME%"
    echo Downloading Maven %MVN_VERSION%...
    powershell -Command "& { Invoke-WebRequest -Uri '%MVN_URL%' -OutFile '%TEMP%\maven.tar.gz'; tar xzf '%TEMP%\maven.tar.gz' -C '%USERPROFILE%\.m2\wrapper\dists' }"
)

set MAVEN_PROJECTBASEDIR=%~dp0
call "%MVN_HOME%\bin\mvn.cmd" -f "%MAVEN_PROJECTBASEDIR%pom.xml" %*