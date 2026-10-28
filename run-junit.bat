@echo off
rem Opens the JUnit window for the BlockBuddy tests. Press "Run" in the window.
rem Needs: Java 17+, Maven, internet. Optional argument: bb.M1HandleTest, bb.M3AcceptTest or bb (all).
cd /d "%~dp0"
echo Compiling the tests...
call mvn -q test-compile dependency:build-classpath -Dmdep.outputFile=cp.txt -Dmdep.includeScope=test
if errorlevel 1 (
  echo.
  echo Compile failed. Check that Java and Maven are installed: java -version and mvn -v
  pause
  exit /b 1
)
set /p CP=<cp.txt
set TARGET=%1
if "%TARGET%"=="" set TARGET=bb.M1HandleTest
echo Opening the JUnit window...
start "" javaw -cp "target\test-classes;%CP%" bb.JUnitWindow %TARGET%
