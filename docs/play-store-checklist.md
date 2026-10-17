# Google Play submission

## Identity and artifact

- Application ID: `com.colitulu`
- Upload the `playstore` AAB (`:app:bundlePlaystoreRelease`, see [playstore/README.md](playstore/README.md)); version code is `4000000 + versionCode`, like the colitu.com APK.
- Confirm the version code is greater than every previously uploaded build.
- Verify the AAB signature and retain its SHA-256 with the source tag.

## Listing and review

- Use the copy in [playstore/store-listing.md](playstore/store-listing.md).
- Supply current phone/tablet screenshots from a release build.
- Complete Data safety, VpnService, foreground-service and account-deletion declarations from [playstore/console-declarations.md](playstore/console-declarations.md).
- Publish from the COLITU LIMITED organization account (required for VPN apps) and give the reviewer a test account.
- Publish the privacy policy and support URLs on `https://colitu.com`.

## Release gate

Physical-device login, config retrieval, connect, egress, DNS, reconnect and revoke tests must pass against staging before staged rollout. Unknown native wrapper provenance blocks upload.
