@echo off
rem enable-carplay.cmd - double-click this on Windows. It runs
rem enable-carplay.command in Git Bash, so the steps, prompts and log are the
rem same as on a Mac. Git for Windows and adb are installed with winget first
rem if they're missing (it asks).
rem ponytail: a launcher, not a port. The steps live once, in the bash script.
setlocal
cd /d "%~dp0"
rem winget puts adb here, and a window opened before that install has a stale PATH
set "PATH=%PATH%;%LOCALAPPDATA%\Microsoft\WinGet\Packages\Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe\platform-tools"

rem Git's own bash, never C:\Windows\System32\bash.exe (that one is WSL)
set "GITBASH=%ProgramFiles%\Git\bin\bash.exe"
if not exist "%GITBASH%" set "GITBASH=%LOCALAPPDATA%\Programs\Git\bin\bash.exe"
if not exist "%GITBASH%" (
  call :install Git.Git "Git for Windows"
  set "GITBASH=%ProgramFiles%\Git\bin\bash.exe"
)
if not exist "%GITBASH%" (
  echo.
  echo !! Git for Windows is needed. Install it from https://git-scm.com/download/win
  echo    then double-click this again.
  pause
  exit /b 1
)
where adb >nul 2>nul || call :install Google.PlatformTools adb

rem CHERE_INVOKING keeps bash in this folder
set CHERE_INVOKING=1
"%GITBASH%" enable-carplay.command %*
pause
exit /b

:install
choice /m "%~2 is missing. Install it with winget"
if errorlevel 2 exit /b
winget install -e --id %1 --source winget --accept-source-agreements --accept-package-agreements
exit /b
