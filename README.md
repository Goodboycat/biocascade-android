# BioCascade Android — CI/CD & Firebase Setup TODO

BioCascade is a Jetpack Compose + OpenGL ES 2.0 3D human anatomy and multi-system physiology simulator (`applicationId`: `com.aistudio.biocascade.qxvnrt`).

By default, GitHub Actions builds `app-debug.apk` cleanly without `app/google-services.json` and launches the app in offline local mode (3D hologram, 114-ingredient physiology simulator, medical search, local AES-256-GCM vault, and encrypted PDF export). Complete the TODO below to enable **Google Sign-In** and **Cloud Firestore sync** in GitHub-built APKs.

---

## TODO: Enable Firebase & Google Sign-In in GitHub-Built APKs

### 1. Obtain `google-services.json`
- [ ] Download or copy `app/google-services.json` from the Firebase Console (for Android package `com.aistudio.biocascade.qxvnrt`) or from the original AI Studio project workspace under `app/google-services.json`.
- [ ] Verify the OAuth Android client (`client_type: 1`) in `google-services.json` includes the SHA-1 fingerprint of the repository's `debug.keystore` (decoded during CI from `debug.keystore.base64`).
  - To inspect the SHA-1 of the repo's debug keystore:
    ```bash
    base64 -d debug.keystore.base64 > debug.keystore
    keytool -list -v -keystore debug.keystore -alias androiddebugkey -storepass android -keypass android | grep SHA1
    ```
- [ ] Verify the Web OAuth client (`client_type: 3`) is present in `oauth_client` so `default_web_client_id` is generated for Jetpack `CredentialManager` Google Sign-In.

### 2. Add the GitHub Actions Repository Secret
- [ ] Do **not** commit `app/google-services.json` directly into Git (GitHub Secret Scanning blocks exposed Google API keys).
- [ ] Go to the GitHub repository **Settings → Secrets and variables → Actions → New repository secret**.
- [ ] Create a secret with:
  - **Name:** `GOOGLE_SERVICES_JSON`
  - **Secret value:** Either the raw JSON contents of `google-services.json` OR its base64-encoded string (`base64 -w 0 app/google-services.json`).
  - *(Note: `.github/workflows/build-apk.yml` automatically detects whether the secret is raw JSON or base64 and writes it to `app/google-services.json` before testing and building.)*

### 3. Verify Firestore Database & Rules
- [ ] Confirm `app/src/main/res/values/firebase_applet_config.xml` contains the matching `firestore_database_id`.
- [ ] Confirm `firestore.rules` is deployed to that Firebase project/database (`users/{userId}/profiles`, `records`, and `auditLogs` gated by `request.auth.uid == userId`).
- [ ] Confirm **Google** sign-in provider is enabled in Firebase Console → Authentication → Sign-in method.

### 4. Trigger & Verify the CI Build
- [ ] Push a commit or trigger **Actions → Build BioCascade APK → Run workflow**.
- [ ] Confirm the `Restore google-services.json (if secret configured)` step succeeds and `processDebugGoogleServices` runs without `File google-services.json is missing`.
- [ ] Download the `BioCascade-debug-apk` artifact, install it on an Android device, and verify Google Sign-In + Firestore cloud sync succeed.

---

## Local Build & Test Commands

```bash
# Run PhysiologyEngine unit tests (includes 114-ingredient catalog & simulation tests)
gradle :app:testDebugUnitTest --tests com.example.PhysiologyEngineTest

# Build Debug APK
gradle assembleDebug
```
