# MyToken

Android app (Kotlin + Jetpack Compose) to track **KPLC prepaid tokens**.

It scans the phone's KPLC token messages (and captures new ones as they arrive), then lets you choose your meter number(s) from what it found.

## Features
- Automatic SMS scan and live capture of new token messages
- Meter picker, with names for each meter (Home, Shop, tenants)
- Purchase history: units, amount, KES per unit; copy token, mark as used
- Usage insights: average daily use, estimated units and days left
- Optional "my current balance" entry for a sharper forecast
- Monthly spend / units charts
- Low-balance notification (threshold is adjustable)
- Home-screen widget showing days left
- Manual token entry
- Export history to CSV or PDF
- Backup and restore (JSON)

## Install
Every push to `main` builds the app on GitHub Actions and publishes it here:

https://github.com/mohagenius29/MyToken/releases/download/latest/MyToken.apk

Open the link on your phone, download, and allow "install unknown apps" for your browser when asked.

## Privacy
All data stays on your phone. The app has no internet permission.
