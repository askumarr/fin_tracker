# Play Store — SMS permission declaration

FinTracker uses `READ_SMS` and `RECEIVE_SMS` solely to detect bank and UPI transaction alerts and create on-device expense/income entries.

## Declared use

- **Core functionality:** automatic expense tracking from transactional SMS
- **Data handling:** parsed fields (amount, merchant, reference, timestamp) stored in local Room DB only
- **No upload:** SMS bodies are not sent to any server
- **User control:** onboarding explanation, optional inbox backfill, auto-capture toggle, review queue

## Play Console checklist

1. Complete the SMS/Call Log permissions declaration form
2. Attach a demo video showing: permission rationale → SMS arrives → transaction appears
3. Link to this privacy summary in the store listing
4. If Play rejects broad SMS access, fallback roadmap: Notification Listener for bank app notifications (secondary path; not implemented in v1)

## Limited use compliance

- Do not use SMS for advertising, analytics identity, or contact scraping
- Only process messages that match financial templates / heuristics
- OTP-only messages are ignored
