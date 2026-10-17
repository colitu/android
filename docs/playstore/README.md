# Google Play build

The Play build is the `playstore` product flavor. Everything Play-specific is
kept apart so the colitu.com APK (`direct`) and the F-Droid build (`fdroid`)
behave exactly as before:

| Where | What |
|---|---|
| `app/build.gradle.kts`, flavor `playstore` | `COLITU_PLAY_POLICY = true`; version code `4000000 + versionCode` (the same as the colitu.com APK, so Play offers the next update to people who installed the APK) |
| `app/src/playstore/AndroidManifest.xml` | removes `REQUEST_INSTALL_PACKAGES`, `QUERY_ALL_PACKAGES` and `MANAGE_EXTERNAL_STORAGE`, even if a library adds them |
| `PLAY_POLICY` (`screens/ShellCommon.kt`) | read by the shared screens; `false` in every other build |
| `docs/playstore/` | console declarations and the store listing |

## What the Play build hides or changes (payments policy)

Nothing in the Play build sells, shows a price or links to buying on the website:

- Plan tab: no "Upgrade your privacy" hero, no "managed on colitu.com" card or account button. It keeps the current plan and "Refresh status".
- Trial-end banner: no "Get Premium" link. Paused device: no "Get Premium" button.
- Servers tab: no "Worldwide coverage with Colitu Pro" card.
- Account tab: "Plan details" instead of "Go unlimited / Extend / Choose a plan". There is no "Manage on colitu.com" link.
- Panel notices: `promo` notices are not shown. A notice whose link points to `/pricing`, `/billing`, `/subscription`, `/checkout` or `/pay` is not shown either.
- Added: **Delete account**, which opens `https://app.colitu.com/account`. Play requires that an account created in the app can be deleted from the app.
- About row: the version only, without "check for updates" (Play updates this build; the self-updater is `direct` only).

People who already pay (on the website) keep their plan. The app only reads its status.

## Build

From PowerShell, through the ASCII junction (AGP refuses the non-ASCII real path):

```powershell
Set-Location F:\DEV\Projects\colitu-android\android
.\gradlew.bat :app:bundlePlaystoreRelease
```

Output: `app/build/outputs/bundle/playstoreRelease/app-playstore-release.aab`.
Per-ABI APK splits are turned off automatically for `bundle*` tasks; Play
splits by ABI itself. APK builds (`assemble*`) are unchanged.

The AAB is signed with the Colitu release key (the key that signs the
colitu.com APK). When enrolling in Play App Signing, choose **"Use existing
app signing key"** and upload that key (Play's PEPK tool). If Google generates
a new key instead, Play builds and the colitu.com APK can no longer update
each other: users would have to uninstall first.

Next: [console-declarations.md](console-declarations.md), [store-listing.md](store-listing.md).
