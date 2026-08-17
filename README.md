# FinTracker

India-focused Android expense tracker with **SMS-based auto-capture** as the primary feature.

## Stack

- Kotlin, Jetpack Compose, Material 3
- Room (on-device SQLite)
- Hilt, WorkManager
- Encrypted backup (AES-GCM + PBKDF2)

## Features

1. **SMS automation** — parses bank/UPI/card debit & credit SMS (HDFC, SBI, ICICI, Axis, Kotak, Federal/Scapia + generics), dedupes, and queues low-confidence items for review
   - Ignores informational SMS: OTPs, card statements and due reminders, and AMC/NPS unit-allotment and redemption notices
   - Backup screen has "Rescan SMS inbox" and "Clear SMS entries & rebuild" actions to re-read past messages with the current parsers
2. **Manual transactions** — add/edit with expense / income / transfer types and UPI / debit / credit / net banking modes
   - Credit-card bill payments are detected as **transfers** so they never count as income or as a second expense
3. **History** — month and year views with IST calendar periods, spent/credited/transfer totals
4. **Expense trend** — last-12-months bar+line chart on the home dashboard
5. **Categories** — India-relevant defaults + merchant memory
6. **Accounts** — lightweight bank/wallet labels
7. **CSV import** — column mapping + bank header presets
8. **Encrypted backup / restore** — passphrase-protected export; optional plain CSV

## Build

Open in Android Studio (Ladybug+) with JDK 17, or:

```bash
export JAVA_HOME=...   # JDK 17
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

SMS parser tests can also be run without a full Gradle sync (after local tool setup):

```bash
./scripts/run-sms-tests.sh
```

Requires Android SDK 35 and a device/emulator with SMS permissions for the highlight flow.

## Privacy

- SMS and transaction data stay on device
- No cloud sync or accounts
- Play Console: declare SMS restricted permissions (finance / transaction tracking use case). See `docs/PLAY_SMS_POLICY.md`
