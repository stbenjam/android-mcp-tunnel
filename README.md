# Phone Link — Android MCP Tunnel

A native Android app that exposes phone tools to ChatGPT through **OpenAI Secure MCP Tunnel**. The MCP dispatcher and tunnel runtime run on the phone. No laptop, public listener, ADB connection, or root is needed during normal use.

Version 0.2.0 uses Material 3 controls, follows the phone's light/dark theme, and uses dynamic system colors where available. Tunnel credentials are edited in a separate settings dialog.

Version 0.3.0 adds automatic Google Messages/SMS routing, explicit optimistic success responses, a visible control banner, and tools to find the phone, get its location, and control Do Not Disturb.

Version 0.3.1 fixes Google Messages title selection when message history is drawn behind the toolbar. It uses the identified title/Send controls on newer Messages versions and the title's avatar control on the older layout, excluding message history and other conversation panes. It reuses and verifies an open conversation, preserves drafts across recipient-details navigation, and waits for draft restoration before editing. A failure before Send is recorded as a definite failure instead of an indeterminate operation.

**Accessibility is mandatory.** Enable Phone Link control before connecting. Revoking Accessibility stops the tunnel. Screen controls and activity launches require an awake, unlocked phone; requests fail with an explanation when the screen is off or the device is locked. There is no per-action confirmation screen added by this app. Android and the destination app can still require user interaction.

## Install and connect

1. Install the APK on Android 11 or later. This project compiles and targets Android 17 / API 37. The development APK uses the local Android debug signing key; use it for personal testing.
2. Open **Phone Link → Phone control → Open settings → Phone Link control** and enable it. For a sideloaded APK, Android may first require **App info → ⋮ → Allow restricted settings**. Grant only for an app and tunnel you control.
3. In [OpenAI Platform tunnel settings](https://platform.openai.com/settings/organization/tunnels), create or select a tunnel. Associate it with the intended ChatGPT workspace. Obtain a runtime key with **Tunnels Read + Use**. Creating a tunnel requires **Tunnels Read + Manage**.
4. Open **Tunnel settings**, enter the tunnel ID and runtime key **in the Android app**, then save. Do not put credentials in chat, source files, or shell arguments.
5. Enable the capabilities you want. Contacts, Messaging, calls, and Location request their Android runtime permissions. Do Not Disturb opens Android's special-access settings. Find phone needs no additional runtime permission. SMS uses the default SMS SIM; select one in Android settings if needed. Android's installer and permission rules may restrict SMS permission on some devices.
6. Tap **Connect** and allow connection notifications. A notification provides a Disconnect action. The app must remain running and online. Device battery restrictions can interrupt long-running connections; adjust them in Android app settings if needed. Reopen and reconnect after a reboot or force-stop.
7. In **ChatGPT Plugins → Add custom MCP server → Connection: Tunnel**, select this tunnel and finish the private connection setup. Account/workspace permissions determine availability.
8. Ask ChatGPT to call **device_status**. This is the first complete end-to-end verification. **Connection diagnostics** inside the app shows local activity and cannot by itself verify the ChatGPT caller's connection.

Official references: [Secure MCP Tunnel](https://developers.openai.com/api/docs/guides/secure-mcp-tunnels), [tunnel wire protocol](https://github.com/openai/tunnel-client/blob/master/docs/protocol.md).

## Tools

| Tool | Behavior |
| --- | --- |
| `device_status` | Model, Android version, local time/timezone, battery, lock state, enabled capabilities and permissions. |
| `set_timer` | Ask the clock app to start a labeled countdown (1 second–24 hours). |
| `set_alarm` | Ask the clock app for a one-time alarm at the next occurrence of an hour/minute in the phone's timezone. |
| `search_contacts` | Search contacts by name or number; bounded results with names and phone numbers. |
| `send_message` | One call: direct SMS when locked or the screen is off; Google Messages when awake and unlocked, using its RCS/SMS selection. |
| `send_sms` | Send SMS from the default SMS SIM, with multipart support and Android send-result callbacks. |
| `place_call` | Start a call through Android. |
| `operation_status` | Inspect an earlier operation with a request ID without repeating it. |
| `find_phone` | Ring at maximum alarm volume for 1–120 seconds (default 30), or stop. Restores the previous volume unless manually changed. |
| `get_location` | Current latitude/longitude, accuracy, fix age and timestamp; waits up to 15 seconds for a fix no older than 30 seconds. |
| `set_do_not_disturb` | Set Phone Link's mode to `off`, `priority`, `alarms_only`, or `total_silence`. |
| `list_apps` | List launchable apps and package names. |
| `open_app` | Launch an app by package name. |
| `get_screen` | Return bounded accessible UI text, descriptions, and pixel bounds. Password text is redacted. |
| `tap` | Tap current screen pixel coordinates. |
| `swipe` | Swipe between screen coordinates. |
| `type_text` | Replace the focused editable field's text without submitting it. |
| `press_key` | Back, Home, Recents, notifications, or quick settings. |
| `set_media_volume` | Set media playback volume. |

The **Screen & app control** capability can operate other messaging/calling apps. Turning off dedicated SMS/call tools does not block those actions through general UI automation. Disable screen control too when you want to prevent that route.

The automatic **“MCP is controlling this phone”** banner appears during tool activity and disappears two seconds after the last active action. It does not intercept taps and uses the existing Accessibility permission. Health checks and operation-status checks do not show it.

### Messaging

Enable **Messaging** and **Screen control**, and use Google Messages as the default messaging app. `send_message` chooses its route once: locked/screen-off sends use the default SMS SIM; awake/unlocked sends open Google Messages, verify the recipient and draft, and tap Send internally. No separate compose, read-screen, or tap tool calls are needed. Existing different drafts are preserved and reported as an error.

Google Messages selects RCS or SMS based on the conversation and its settings. Enable RCS and configure **Automatically resend as text (SMS/MMS)** in Messages if desired ([Google's instructions](https://support.google.com/messages/answer/9487020?hl=en)). Phone Link never attempts a second SMS send after an uncertain Messages action. `send_sms` remains available for an explicitly SMS-only request.

The UI flow currently recognizes English Google Messages controls. It fails if it cannot verify the recipient or recognize the interface; a Messages update may require an adapter update. A successful Send tap reports “Text sent.” without waiting for delivery.

### Phone utilities

- **Find phone:** enable the capability, then use `find_phone` with `action: "ring"` or `"stop"`. Its notification also has **Stop sound**. Do Not Disturb must allow alarms; the tool reports an error otherwise. Disconnecting stops the sound. The previous alarm volume is saved for recovery after a process restart.
- **Location:** enable the capability and grant approximate or precise location. Enable it while Phone Link is open, then connect or reconnect. The foreground connection retains location access while the phone is locked; no always-on background-location permission is requested. Results include accuracy and age rather than implying an exact GPS position.
- **Do Not Disturb:** enable the capability and grant Android's Do Not Disturb access. On Android 15 and later, this controls Phone Link's own mode. Turning it off cannot disable another app's active mode or a system schedule ([Android API behavior](https://developer.android.com/reference/android/app/NotificationManager#setInterruptionFilter(int))).

## Result semantics and limits

- Successful tool calls return `success: true` and `status: "completed"`. For timers, alarms, calls, and app launches, Android accepting the handoff counts as success (`completion_basis: "android_accepted"`). Messages such as “Timer set.” and “Alarm set.” are intended for direct user-facing confirmation, without routine caveats or extra screen checks. This is optimistic success, not a separate observation of the destination app's state.
- SMS submission also reports optimistic success and “Text sent.” The `sms_status` field distinguishes `submitted` from `sent` after Android callbacks. Later send failures are still surfaced by `operation_status` as `success: false`, `status: "failed"`, with the MCP error flag. Neither submission nor a send callback confirms recipient delivery. SMS does not provide RCS, WhatsApp, or MMS.
- Explicit tool failures consistently return `success: false`, `status: "failed"`, and an explanation. Journal states `unknown`, `indeterminate`, and `in_progress` return `success: null` instead of inventing a completed action. Older persisted `requested`/`submitted`/`sent` results are normalized to the new format without repeating the action.
- A Messages failure before the Send action returns `action_performed: false` and is saved with its error. Both `operation_status` and an identical retry return that same failure without executing again. After resolving the cause, an intentional new attempt needs a new request ID. A timeout races an atomic cancellation guard, so a delayed UI callback cannot send after a definite failure is reported. Once Send has started, an unconfirmed result remains `indeterminate`. Older indeterminate records are retained: the update cannot reconstruct what happened during those requests.
- `set_timer`, `set_alarm`, `send_message`, `send_sms`, `place_call`, `find_phone`, and `set_do_not_disturb` require a unique `request_id` (8–128 ASCII letters, digits, hyphens or underscores). Reuse it only for an identical retry. It is reserved on disk before acting. Retries return the existing result; changed arguments are rejected. After an interrupted operation, **indeterminate** means check the phone before creating a new request ID. Clearing app storage or uninstalling clears this protection.
- Screen content varies by app. Custom drawing, protected surfaces, secure fields, permission prompts, and lock screens may be unavailable. This app does not unlock the phone or provide arbitrary shell access. Phone Link's own UI is excluded from screen reading, tapping, and text entry.
- The phone initiates HTTPS polling to `api.openai.com`. The app does not open an HTTP port. One active installation should use a given tunnel ID.
- The app implements JSON-RPC initialization, ping, tool discovery/calls, notification acknowledgments and session termination, negotiating MCP through `2025-11-25`. It does not advertise unsupported newer MCP capabilities, tool-list change notifications, subscriptions, OAuth forwarding, or streaming progress.
- A dedicated poller feeds eight independent tool workers, one ordered screen-action worker, and eight reply-upload workers. A slow tool or upload does not monopolize the poller. Screen reads, gestures, navigation, and activity launches share the ordered lane so they cannot cancel each other's gestures. Dependent sequences still need to await each preceding result.
- At most 32 commands are admitted across execution and delivery. Polling pauses at capacity instead of growing an unbounded queue. All commands from an oversized returned batch are retained until admitted or expired. Independent operation-journal writes reserve an ID before execution without holding a global lock across Android work.
- Network retries resend the already-computed result, never the operation. Each upload attempt is capped at 10 seconds, within the remaining server deadline. Retries use exponential backoff with jitter and respect `Retry-After`. Deadlines use the poll's monotonic receipt time, including queue and execution time. Missing or malformed deadlines use a local 120-second ceiling. Expired queued actions never execute; authentication failures stop the connection and cancel queued work.
- The app cannot extend ChatGPT's own timeout or guarantee hosted-tunnel latency. If a call times out after a side effect, use `operation_status` or the same operation ID to check it; do not issue a new ID merely to retry.

## Connection diagnostics

Open **Connection diagnostics** to see active/queued work, successful reply uploads, retries, expired requests, and recent queue/execution/upload timings. `device_status` also returns these numeric counters and the app version. Counters reset on reconnect. The connection summary shows the age of the most recent successful reply, separately from polling health.

For USB debugging, `adb logcat -s PhoneLinkTunnel` shows phase names, elapsed milliseconds, and HTTP status codes only. It never logs raw tunnel payloads, credentials, request IDs, shard tokens, tool arguments, or results. Successful polling alone does not establish successful response delivery.

## Privacy and storage

Runtime credentials use AES-GCM with an Android Keystore key. Backup/device transfer are disabled. No raw tunnel traffic, messages, contacts, screen contents, credentials, or phone numbers are written to app logs. The operation journal stores request IDs, argument hashes, and bounded outcomes, not message bodies or numbers. The credential screen blocks screenshots and recent-app previews. Forget connection removes the locally stored credential; revoke the key in Platform to revoke it remotely.

Only explicitly requested tool results leave the phone through the tunnel. ChatGPT and OpenAI's normal processing policies still apply to those results.

## Build

Prerequisites: Android SDK platform 37, build tools 36, JDK 17+ supported by the bundled Gradle version. This workspace was built with JDK 25. The wrapper pins Gradle 9.7.1; AGP is 9.3.2 with its built-in Kotlin support.

Set `ANDROID_HOME` to the SDK, or put `sdk.dir=/absolute/path/to/sdk` in an untracked `local.properties`.

For a cloud-synced source directory, set `phoneLink.buildRoot=/absolute/path/outside/sync` in `local.properties`; the APK is then under `<buildRoot>/app/outputs/apk/debug/`. This workspace uses that override to avoid sync-generated duplicates in intermediate files. Gradle's `--project-cache-dir` can also relocate its project cache. To prefill a tunnel ID in a personal APK, set `phoneLink.defaultTunnelId=tunnel_...` in the same untracked file. Never put a runtime credential there; credentials are entered on the phone.

```sh
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Run instrumentation tests only on a disposable emulator, explicitly selecting its ADB serial. They clear the app's saved connection and capability settings. They temporarily enable Accessibility, contacts, location and DND permissions, seed then remove a fixture contact and mock location, exercise a separate test-only activity, and intercept clock intents and the final Messages Send click. They do not send texts or place calls. The test activity is in the separate test APK and is never packaged into Phone Link. The Messages UI tests require Google Messages installed, initialized, set as the default messaging app and using English.

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w dev.androidmcp.test/androidx.test.runner.AndroidJUnitRunner
```

Source layout:

- `core/`: MCP validation/dispatch, tunnel protocol/runtime, persistent operation journal.
- `PhoneTools.kt`: Android capability adapters.
- `PhoneAccessibilityService.kt`: required Accessibility service and UI actions.
- `BridgeService.kt`: foreground connection lifecycle, notification, stop-on-revocation.
- `MainActivity.kt`: Material 3 connection and capability settings.
- `SmsStatusReceiver.kt`: SMS send-result tracking.
- `GoogleMessagesSender.kt`: bounded recipient/draft verification and one Send action.
- `core/MessagesUi.kt`, `core/SendAttempt.kt`: structural Messages selectors and cancellation/send coordination.
- `FindPhoneSound.kt`, `PhoneLocation.kt`: timed audio playback and location acquisition.

Live ChatGPT connectivity and real SIM behavior require a user-provided tunnel and physical phone. A build, local readiness check, or mock protocol test does not establish that live connection.

## Verification in this workspace

Version 0.3.1 passed 43 JVM tests and 14 Android integration tests, with zero lint errors (24 warnings). Added tests cover history text behind the title, split-pane recipient selection, modern and legacy Send controls, definite failure persistence, timeout/Send races, error status through MCP, and switching recipients without overwriting the other conversation's draft. Real Google Messages tests intercept the final Send action. Newer UI selectors also use regression fixtures based on the Android 17 phone's observed structure. The 0.3.1 APK still needs installation and a check on the physical phone; USB was disconnected during this fix. Existing indeterminate journal records are not rewritten.

Version 0.3.0 passed 32 JVM tests and 13 Android integration tests, with zero Android lint errors (24 warnings). New coverage includes optimistic result formatting, locked/screen-off routing, no duplicate fallback after a Messages error, real Google Messages recipient/draft checks with the final Send click intercepted, named-contact verification, banner visibility and touch-through behavior, a recent mock location, DND on/off requests, and alarm-volume restoration. The emulator uses Google Messages `messages.android_20240528_00_RC01_alldpi.arm64-v8a.phone`; real RCS/SMS delivery, physical-phone GPS and audible finder playback remain device checks. No test transmits a message or places a call.

Version 0.2.0 passed 25 JVM tests and seven Android integration tests. Android lint reported zero errors. Both light and dark Material layouts were rendered and inspected on the emulator.

The app targets Android 17 / API 37. JVM tests cover protocol validation, eight simultaneous calls, independent polling and uploads during a stalled response, ordered screen actions, bounded admission with oversized batches, expiry, cancellation, credential-free diagnostics, retrying a timed-out upload without re-execution, and concurrent journal reservations/callbacks. Android integration tests run on an Android 15 Pixel Fold emulator and verify Accessibility taps and text entry against a separate fixture app, clock-intent dispatch without duplicate execution, contact lookup, secure credential storage, disabled SMS/call rejection, Accessibility requirements, and Material UI rendering. No test sends a real text or makes a real phone call.

Version 0.2.0 was installed on the physical Android 17 phone with its existing settings retained. The first live health check completed in 11.4 seconds; a subsequent burst of eight simultaneous `device_status` calls all succeeded in 1.4–6.2 seconds. Runtime diagnostics reported no errors, retries, or expired commands during that burst. This verifies concurrent live requests, not a guarantee of hosted-service latency or removal of ChatGPT's own timeout. The earlier 0.1.0 build had intermittent 20-second hosted-call timeouts.
