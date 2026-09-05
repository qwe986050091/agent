@REM ----------------------------------------------------------------------------
@REM Maven Wrapper start-up batch script
@REM ----------------------------------------------------------------------------
@ECHO OFF
SETLOCAL

@REM Auto-detect JDK if JAVA_HOME is not set
IF NOT DEFINED JAVA_HOME (
  IF EXIST "%USERPROFILE%\.jdks\temurin-24\bin\java.exe" SET "JAVA_HOME=%USERPROFILE%\.jdks\temurin-24"
)
IF NOT DEFINED JAVA_HOME (
  IF EXIST "%USERPROFILE%\.jdks\openjdk-26.0.2\bin\java.exe" SET "JAVA_HOME=%USERPROFILE%\.jdks\openjdk-26.0.2"
)
IF DEFINED JAVA_HOME SET "PATH=%JAVA_HOME%\bin;%PATH%"

SET "WRAPPER_JAR=%~dp0.mvn\wrapper\maven-wrapper.jar"
IF NOT EXIST "%WRAPPER_JAR%" (
  ECHO [mvnw ERROR] maven-wrapper.jar not found at %WRAPPER_JAR%
  ECHO Please download it once:
  ECHO   Invoke-WebRequest -Uri "https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/3.3.2/maven-wrapper-3.3.2.jar" -OutFile "%WRAPPER_JAR%" -UseBasicParsing
  EXIT /B 1
)

@REM %~dp0 ends with a backslash; strip it before quoting so the Java launcher
@REM does not treat the trailing \" as an escaped quote.
SET "MAVEN_PROJECTBASEDIR=%~dp0"
IF "%MAVEN_PROJECTBASEDIR:~-1%"=="\" SET "MAVEN_PROJECTBASEDIR=%MAVEN_PROJECTBASEDIR:~0,-1%"

java -classpath "%WRAPPER_JAR%" "-Dmaven.multiModuleProjectDirectory=%MAVEN_PROJECTBASEDIR%" org.apache.maven.wrapper.MavenWrapperMain %*
EXIT /B %ERRORLEVEL%
