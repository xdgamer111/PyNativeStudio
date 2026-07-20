# PyNative Studio

A native Android Python editor and runner written in Kotlin. It embeds CPython 3.13 through Chaquopy and builds as a normal Android APK.

## Implemented features

- Native Kotlin Android app with Material 3 UI
- Embedded 64-bit CPython 3.13 runtime
- Isolated runner process with hard stop support
- stdout, stderr, tracebacks, `input()`, and execution timing
- Python syntax highlighting, line numbers, auto-indent, auto-pairs, undo/redo
- Multiple editor tabs, font sizing, light/dark editor themes
- Find/replace and basic Python built-in/keyword highlighting
- Create/open/save/save-as/rename/delete using Android's Storage Access Framework
- Import/export multi-file projects as ZIP archives
- Built-in example programs
- Portrait and landscape support

## Build

1. Install Android Studio with Android SDK 35.
2. Open this folder as a project.
3. Let Gradle sync and download Chaquopy's CPython artifacts.
4. Choose **Build > Build APK(s)**, or run:

```bash
./gradlew assembleDebug
```

The APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

## Runtime notes

Python 3.12 and newer are 64-bit only in Chaquopy, so this project targets `arm64-v8a` phones and `x86_64` emulators. Most modern Android phones are arm64.

Some desktop-only standard-library modules are unavailable on Android, including `tkinter`, `curses`, and most of `multiprocessing`. Normal Python language semantics and the supported standard library run under actual CPython.
