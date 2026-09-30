# Android release

The existing Play identity remains `com.colitulu`; changing it creates a different store application. Pull requests build and test without production signing material.

Protected release builds inject the following secrets through the CI secret store:

- `COLITU_ANDROID_KEYSTORE_PATH`
- `COLITU_ANDROID_STORE_PASSWORD`
- `COLITU_ANDROID_KEY_ALIAS`
- `COLITU_ANDROID_KEY_PASSWORD`
- `COLITU_REQUIRE_RELEASE_SIGNING=true`

Run `./gradlew testPlaystoreReleaseUnitTest lintPlaystoreRelease connectedPlaystoreDebugAndroidTest bundlePlaystoreRelease -PCOLITU_REQUIRE_RELEASE_SIGNING=true`. Upload the generated signed AAB only after `jarsigner -verify` succeeds. Never commit the keystore or `signing.properties`.
