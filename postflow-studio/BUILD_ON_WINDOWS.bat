@echo off
setlocal
cd /d "%~dp0"
if not defined ANDROID_HOME (
  echo Android SDK not configured. Open this folder in Android Studio and install SDK Platform 35.
)
call gradlew.bat :app:assembleDebug
if errorlevel 1 (
  echo BUILD FAILED - check SDK/Gradle errors above.
  exit /b 1
)
echo APK: app\build\outputs\apk\debug\app-debug.apk
endlocal