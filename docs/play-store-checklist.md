# Google Play submission

## Identity and artifact

- Application ID: `com.colitulu`
- Upload an AAB produced by the protected Android release workflow.
- Confirm the version code is greater than every previously uploaded build.
- Verify the AAB signature and retain its SHA-256 with the source tag.

## Listing and review

- Use the Turkish copy in [store-listing.md](store-listing.md).
- Supply current phone/tablet screenshots from a release build.
- Complete Data safety from observed release behavior and bundled SDKs.
- Declare VPNService use accurately and provide reviewer login/test instructions.
- Publish the privacy policy and support URLs on `https://colitu.com`.

## Release gate

Physical-device login, config retrieval, connect, egress, DNS, reconnect and revoke tests must pass against staging before staged rollout. Unknown native wrapper provenance blocks upload.
