# RK Calculator Vault + RK Home

A lightweight native Android calculator/private-file vault with **RK Home**, an optional home-screen launcher inspired by the soft surfaces, rounded shapes, spacious layout and clear app navigation of modern Android launchers.

## RK Home design

- A large live clock and Indonesian date widget.
- Soft lavender/grey background, rounded search bar and clean app spacing.
- Home-screen shortcuts plus a fixed bottom dock.
- A dedicated **Semua aplikasi** drawer with instant text filtering.
- Original installed app icons are used, so no icon pack downloads or network requests are needed.
- Long-press an app for **Buka aplikasi** or **Info aplikasi**.
- Launcher app discovery is cached and runs on a background executor; the UI uses Android framework widgets only.
- The app list still respects the hidden-icon list used by the vault's app-hider page.

RK Home is an original, lightweight launcher inspired by this visual direction; it is not Samsung One UI itself and does not include Samsung proprietary widgets, code or assets.

## Build APK with GitHub Actions

1. Extract this ZIP.
2. Upload the **contents** of the extracted project to your GitHub repository. Keep `.github/workflows/build-apk.yml` in that exact path.
3. Commit and push to `main` or `master`, or open **Actions** and run **Build RK Calculator APK** manually.
4. When the workflow completes successfully, download the `RK-Calculator-APK` artifact.

The workflow creates a debug APK. Build success and launcher behaviour must still be checked through GitHub Actions and on the target Android device; this workspace does not have an Android SDK installed to run a full Android build locally.

## Set RK Home as the default Home app

1. Install the APK.
2. Open Android **Settings → Apps → Default apps → Home app** (the exact wording differs by phone).
3. Choose **RK Home**.
4. If a phone's stock launcher does not show RK Home, return to the app and tap **Atur** to open Home settings.

To return to the previous launcher, choose it again in Android's Home app settings.

## Important app-hiding limitation

Android does not give a normal third-party app supported permission to remove another app's icon from a phone manufacturer's launcher. The vault's Hide/Restore function filters icons only within RK Home. Hidden apps remain installed and may still be visible in Android Settings or other launchers.

## Private vault and older phones

- The calculator/PIN entry and private-file vault are retained from the original project.
- PIN-derived cryptography and app-list discovery run off the UI thread to reduce UI stalls.
- `minSdk` is Android 8.0 (API 26).
- Export/back up important vault files before replacing or uninstalling the app. Uninstalling deletes its private app data.
- If an APK update is rejected because its signing certificate differs from an earlier build, back up/export vault contents first; uninstalling the old copy may be required.
