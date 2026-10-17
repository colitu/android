# Play Console declarations

Draft answers for the Play build. Re-check every answer against the final
AAB before submitting. Data safety answers are a legal statement.

## Developer account

- VPN apps (apps that use `VpnService`) must be published from an
  **organization** developer account. Use COLITU LIMITED (company no. 17520342)
  with its D-U-N-S number. A personal account will be rejected.

## App content

| Item | Answer |
|---|---|
| Privacy policy | `https://colitu.com/legal/privacy` |
| Ads | No ads |
| App access | Restricted: sign-in required. Give the reviewer a dedicated test account with an active plan (create it in the admin panel; never a real customer's account) |
| Content rating | Utility / tools questionnaire: no violence, gambling, user-generated content or sharing of location between users |
| Target audience | 18+ |
| News app | No |
| Government app | No |
| Financial features | None |
| Health | None |

## VpnService declaration

- Core functionality: yes. The app is a VPN client; every user-facing feature
  depends on the tunnel.
- Traffic: device traffic goes, encrypted, to Colitu's VPN servers. The app
  does not change ads, does not collect browsing data for sale, and does not
  redirect traffic for monetisation.
- Short text for the form:
  > Colitu is a VPN client. It uses VpnService to create an encrypted tunnel
  > from the device to the user's chosen Colitu server, so that all or
  > selected app traffic (split tunnelling) leaves through that server. The
  > tunnel starts only when the user connects (or with Android always-on VPN
  > that the user enables). No traffic is inspected, modified or monetised.

## Foreground service (Android 14+)

- Type: `specialUse`, subtype `vpn` (`CoreVpnService`).
- Justification:
  > The VPN tunnel must keep running while the user is connected, including
  > when the app is in the background. The foreground service holds the
  > tunnel and shows the ongoing "connected" notification. It runs only while
  > a VPN session started by the user is active.
- Video: record connect, then home screen with the notification visible, then disconnect.

## Sensitive permissions in the Play build

| Permission | Why |
|---|---|
| `CAMERA` | Scanning the sign-in QR code shown on a TV (only when the user opens the scanner) |
| `POST_NOTIFICATIONS` | Connection status notification |
| `FOREGROUND_SERVICE_SPECIAL_USE` | See above |

`REQUEST_INSTALL_PACKAGES`, `QUERY_ALL_PACKAGES` and storage permissions are
not in the Play build (`app/src/playstore/AndroidManifest.xml`).

## Data safety (draft)

No third-party SDKs (no Firebase, analytics or ads libraries). All data goes to Colitu's own API over HTTPS.
Encrypted in transit: yes. Users can request deletion: yes.

| Data type | Collected | Purpose | Optional | Notes |
|---|---|---|---|---|
| Email address | Yes | Account management | No | Sign-in and account e-mails |
| User IDs (account and device ID) | Yes | Account management, app functionality | No | Device ID binds VPN credentials to the device |
| Device or other IDs: device name/model | Yes | App functionality | No | Shown in the device list |
| App interactions / diagnostics (connection success, timings, features used) | Yes | Analytics, app functionality | Yes (Settings → anonymous statistics) | Server keeps daily totals only, not linked to the account |
| Crash logs / diagnostics: app logs in a connection report | Yes | App functionality (support) | See note | Masked by the app; kept 90 days. **Check before submitting:** if a report can be uploaded without the user's action, declare it as not optional |
| Approximate location | Check | Analytics | Yes | Network country and provider in connection events. Declare as approximate location if Colitu stores it |
| Web browsing history, contacts, photos, files, precise location | No | | | Visited sites are never recorded |
| Financial info | No | | | No payment in the app |

## Account deletion

- In the app: Account tab → **Delete account** (Play build) opens `https://app.colitu.com/account`.
- Web link for the console: `https://docs.colitu.com/en/account-management` (section "Deleting your account").
  That help text still says "send a deletion request from the support section".
  Update it to describe the delete button in account settings before submitting.

## Pricing and countries

- The app is free on Play and has no in-app purchases. Plans bought on the
  website are only read by the app. Do not mention prices, Premium or the
  website shop in the listing or screenshots.
- Countries (recommendation, owner decides): leave out Russia. Google removes VPN apps there on Roskomnadzor
  request, and the colitu.com APK remains the channel for Russian users.
