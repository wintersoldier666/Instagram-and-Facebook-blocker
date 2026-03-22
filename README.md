# SocialGuard — Instagram & Facebook Blocker

A native Android app (Kotlin) that helps you take control of your social media usage.

## Features

- **Master Block** — Toggle to completely block Instagram or Facebook
- **Content Locks** — Block specific features:
  - Instagram: Reels, Explore, Direct Messages
  - Facebook: Marketplace, Watch/Videos, Gaming
- **Scheduled Time Lock** — Block apps during a specific time window (e.g., 10pm–7am)
- **Per-Session Limit** — Each time you open the app, you get a configurable number of minutes (1–60) before it's blocked
- **Daily Usage Popup** — A mindfulness popup shows your usage stats plus a motivational quote or social media fact every day when you open the app
- **Screentime Stats** — 7-day usage charts for both apps
- **Snooze** — Tap "5 more minutes" on the session limit screen to extend once

## How It Works

The app uses Android's **Accessibility Service** to detect when Instagram or Facebook comes into the foreground. When a blocking rule is triggered, it shows a full-screen overlay with:

- The reason it's blocked
- Your total usage today
- A self-help quote or social media fact
- A "Go Back" button (and optionally a snooze option)

For **content blocking** (Reels, Marketplace, etc.), the Accessibility Service traverses the UI element tree to detect when you navigate to those sections, then triggers the overlay.

## Setup (Required Permissions)

After installing, grant these three permissions:

1. **Accessibility Service** — Settings → Accessibility → SocialGuard → Enable
2. **Draw Over Other Apps** — Settings → Apps → SocialGuard → Display over other apps → Allow
3. **Usage Stats** — Settings → Digital Wellbeing (or Privacy) → Usage Access → SocialGuard → Allow

## Building

### Prerequisites
- Android Studio Hedgehog (2023.1.1) or newer
- JDK 17+
- Android SDK 34

### Steps
```bash
# Clone the repo
git clone <repo-url>
cd Instagram-and-Facebook-blocker

# Generate Gradle wrapper (if gradlew doesn't work)
gradle wrapper --gradle-version 8.4

# Build debug APK
./gradlew assembleDebug

# Install on connected device
./gradlew installDebug
```

Or open in Android Studio and click **Run**.

## Architecture

```
MVVM + Repository pattern
├── Accessibility Service   → Detects app foreground, traverses UI tree
├── Foreground Service      → Keeps monitoring alive, session timer
├── Room Database           → Stores settings, usage records, sessions
├── BlockingOverlayManager  → WindowManager overlay (SYSTEM_ALERT_WINDOW)
└── 4 UI Fragments          → Dashboard, Content Locks, Time Locks, Stats
```

## Package Structure

```
com.socialguard.blocker/
├── data/           Database, DAOs, models, repository
├── service/        AccessibilityService, ForegroundService
├── overlay/        BlockingOverlayManager
├── ui/             Fragments and ViewModels
├── util/           Helpers (permissions, usage stats, quotes, time)
└── receiver/       BootReceiver (auto-start on boot)
```

## Limitations

- Content blocking (Reels, Marketplace, etc.) relies on accessibility node content descriptions. If Instagram/Facebook updates their app and changes these labels, detection may need updating.
- Per-session timer is managed by the Accessibility Service; if the service is killed by the OS, the timer resets.
- No PIN lock — determined users can disable the Accessibility Service to bypass blocking. For stronger enforcement, consider adding a PIN to the accessibility settings.

## Permissions Used

| Permission | Why |
|---|---|
| `PACKAGE_USAGE_STATS` | Read app usage data from the OS |
| `SYSTEM_ALERT_WINDOW` | Draw blocking overlays over other apps |
| `FOREGROUND_SERVICE` | Keep monitoring service running in background |
| `RECEIVE_BOOT_COMPLETED` | Auto-start after device reboot |
| `BIND_ACCESSIBILITY_SERVICE` | Core detection mechanism |
| `POST_NOTIFICATIONS` | Show persistent monitoring notification |
