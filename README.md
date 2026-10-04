# Ariana

Ariana is an offline-first Android MVP for Pashto-speaking beginners learning English.

## Build without Android Studio

1. Create a GitHub account if you do not have one.
2. Create a new repository.
3. Upload this whole `Ariana` folder to the repository.
4. Open the repository on GitHub.
5. Go to **Actions**.
6. Open **Build Ariana APK**.
7. Press **Run workflow**.
8. Wait until it finishes.
9. Open the finished workflow run and download the artifact named **Ariana-debug-apk**.
10. Unzip it and install `app-debug.apk` on your Android phone.

On the phone, Android may ask you to allow installing apps from the browser or file manager. This is normal for APK files installed outside the Play Store.

## Cost

GitHub Actions is free for public repositories using standard GitHub-hosted runners. Private repositories have a monthly free quota and can cost money if you go beyond the included usage.

## Play Store

For Google Play you should upload a signed Android App Bundle (`.aab`), not the debug APK. This project already targets Android API 36, but it still needs release signing, store screenshots, privacy policy, content rating, and Play Console setup before publishing.

## Add more lessons

Edit `app/src/main/assets/starter_content.json`.

Keep the same structure:

- unit id/title/order
- lesson id/title/order
- item English word or sentence
- Pashto translation
- optional transliteration
- English/Pashto examples
- audio text

Increment the top-level `version` number when bundled content changes.
