@echo off
rem NOX - build the APK from the command line (no Android Studio window needed).
rem Put this file in the nox-android project folder and run it.

echo ===== NOX APK build =====

rem --- 0) build from a path without spaces/brackets (native build breaks on them)
if /I not "%CD%"=="C:\nox" (
  echo Copying project to C:\nox ...
  robocopy "%~dp0." "C:\nox" /E /NFL /NDL /NJH /NJS /NP >nul
  cd /d C:\nox
)
if /I not "%CD%"=="C:\nox" goto fail

rem --- 1) native pieces must be present
if not exist "app\src\main\jniLibs\arm64-v8a\libxray.so" (
  echo libxray.so is missing. Do the Xray download step first.
  goto fail
)
if not exist "app\src\main\jni\hev-socks5-tunnel\Android.mk" (
  echo Fetching hev-socks5-tunnel ...
  rmdir /s /q "app\src\main\jni\hev-socks5-tunnel" 2>nul
  git clone --recursive --depth 1 https://github.com/heiher/hev-socks5-tunnel "app\src\main\jni\hev-socks5-tunnel"
  if errorlevel 1 goto fail
)

rem --- 2) JDK
if not defined JAVA_HOME if exist "C:\Program Files\Android\Android Studio\jbr\bin\java.exe" set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
if not defined JAVA_HOME (
  echo JAVA_HOME not found. Install Android Studio or JDK 17, then run again.
  goto fail
)
set "PATH=%JAVA_HOME%\bin;%PATH%"
echo Using JDK at %JAVA_HOME%

rem --- 3) Android SDK
if defined ANDROID_HOME (set "SDK=%ANDROID_HOME%") else set "SDK=%LOCALAPPDATA%\Android\Sdk"
if not exist "%SDK%" mkdir "%SDK%"
set "ANDROID_HOME=%SDK%"
set "ANDROID_SDK_ROOT=%SDK%"
echo Using SDK at %SDK%

set "SM=%SDK%\cmdline-tools\latest\bin\sdkmanager.bat"
if not exist "%SM%" (
  echo Downloading Android command-line tools ...
  curl -L -o clt.zip https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip
  if errorlevel 1 goto fail
  rmdir /s /q clt_tmp 2>nul
  mkdir clt_tmp
  tar -xf clt.zip -C clt_tmp
  mkdir "%SDK%\cmdline-tools" 2>nul
  rmdir /s /q "%SDK%\cmdline-tools\latest" 2>nul
  move clt_tmp\cmdline-tools "%SDK%\cmdline-tools\latest" >nul
  del clt.zip
  rmdir /s /q clt_tmp
)
if not exist "%SM%" goto fail

echo Accepting licenses ...
(for /l %%i in (1,1,40) do @echo y) | "%SM%" --sdk_root="%SDK%" --licenses >nul

echo Installing SDK platform, build-tools and NDK - this can take a while ...
call "%SM%" --sdk_root="%SDK%" "platform-tools" "platforms;android-34" "build-tools;34.0.0" "ndk;26.1.10909125"
if errorlevel 1 goto fail

rem --- 4) Gradle
if not exist "gradle-dist\gradle-8.9\bin\gradle.bat" (
  echo Downloading Gradle 8.9 ...
  curl -L -o gradle.zip https://services.gradle.org/distributions/gradle-8.9-bin.zip
  if errorlevel 1 goto fail
  mkdir gradle-dist 2>nul
  tar -xf gradle.zip -C gradle-dist
  del gradle.zip
)
if not exist "gradle-dist\gradle-8.9\bin\gradle.bat" goto fail

rem --- 5) build
echo Building APK ...
call "gradle-dist\gradle-8.9\bin\gradle.bat" :app:assembleDebug --no-daemon
if errorlevel 1 goto fail

set "APK=app\build\outputs\apk\debug\app-debug.apk"
if not exist "%APK%" goto fail
copy /y "%APK%" "%USERPROFILE%\Downloads\NOX.apk" >nul
echo.
echo ===== DONE =====
echo APK: C:\nox\%APK%
echo Copy: %USERPROFILE%\Downloads\NOX.apk

rem --- 6) install on a USB-connected phone, if there is one
echo.
echo Trying to install on a connected phone ...
"%SDK%\platform-tools\adb.exe" devices
"%SDK%\platform-tools\adb.exe" install -r "%APK%"
echo.
echo If no phone was found, send NOX.apk from Downloads to your phone and open it.
pause
exit /b 0

:fail
echo.
echo ===== FAILED - copy the red/last lines above and send them to Claude =====
pause
exit /b 1
