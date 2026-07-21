# Update this project from Termux

1. Download and extract `PyNativeStudio-Full-With-Icon.zip`.
2. Copy its contents over the existing local repository.
3. Commit and push:

```bash
cd ~/storage/downloads
unzip -o PyNativeStudio-Full-With-Icon.zip
cp -r PyNativeStudio/. ~/PyNativeStudio/
cd ~/PyNativeStudio
git add .
git commit -m "Upgrade UI and add launcher icon"
git push origin main
```

GitHub Actions will build a new debug APK automatically.
