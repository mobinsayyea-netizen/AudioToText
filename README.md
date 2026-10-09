# Audio to Text (Android)

Native Android app: transcribes many audio files or a ZIP with Gemini, keeps working in the background
(foreground service + notification), plays a progress sound (can be switched off), and saves one .txt to Downloads.

Build: push this folder to a GitHub repo (branch main). The workflow in .github/workflows/build.yml builds
app-debug.apk. Locally: ./gradlew assembleDebug  (JDK 17, Android SDK 34).
Needs Android 10 (API 29) or newer.
