# Build the APK with GitHub Actions

1. Upload every project file to the root of a GitHub repository.
2. Open the repository's **Actions** tab.
3. Select **Build Android APK**.
4. Tap **Run workflow**, then **Run workflow** again.
5. Open the finished workflow run.
6. Under **Artifacts**, download **PyNativeStudio-debug-apk**.
7. Extract the downloaded ZIP and install `app-debug.apk`.

The workflow also runs automatically whenever changes are pushed to the `main` or `master` branch.
