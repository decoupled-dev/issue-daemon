# AAOS Issue Daemon (MVP)

A system service for **Android Automotive OS (Android 14+, API 34)** that continuously monitors
device health and logs. When it detects a problem it captures evidence (logs, dumps, tombstones,
ANR traces, dumpsys) and reports it to a **configurable ECU server**.

```
 ┌──────────────── monitors ────────────────┐     ┌──────────── report pipeline ────────────┐
 │ logcat rules   (crash, DeadObject, ANR,  │     │ IssueDispatcher                         │
 │                 Fatal signal, OOM, lmkd, │     │   settle window: merge duplicates       │
 │                 binder, watchdog, VHAL)  │     │   cooldown + hourly rate limit          │
 │ DropBox        (crash/ANR/tombstone/     │──▶  │ EvidenceCollector → bundle.zip          │──▶ ECU server
 │                 watchdog/system restart) │     │ ReportQueue (on disk, survives reboot)  │   POST /api/v1/issues
 │ CPU · memory · storage · thermal         │     │ Uploader (backoff, network callback)    │   POST /api/v1/heartbeat
 │ car service binder · abnormal boot       │     └─────────────────────────────────────────┘
 └──────────────────────────────────────────┘
```

## What it detects

| Issue type | Source | Default severity |
|---|---|---|
| `APP_CRASH` | logcat `FATAL EXCEPTION:` + DropBox `*_app_crash` | ERROR |
| `NATIVE_CRASH` | logcat `Fatal signal` + DropBox tombstones (text and proto) | ERROR |
| `ANR` | logcat `ANR in` + DropBox `*_anr` (includes traces) | ERROR |
| `DEAD_OBJECT` | logcat `DeadObjectException` | WARNING |
| `OUT_OF_MEMORY` | logcat `java.lang.OutOfMemoryError` | ERROR |
| `LOW_MEMORY_KILL` | logcat `lowmemorykiller: Kill` | WARNING |
| `BINDER_FAILURE` | logcat `FAILED BINDER TRANSACTION` / `TransactionTooLargeException` | WARNING |
| `SYSTEM_SERVER_CRASH` | logcat + DropBox `system_server_crash` | CRITICAL |
| `WATCHDOG` | logcat + DropBox `system_server_watchdog` | CRITICAL |
| `SYSTEM_RESTART` | DropBox `SYSTEM_RESTART` | CRITICAL |
| `CAR_SERVICE_DIED` | Car API lifecycle listener | CRITICAL |
| `ABNORMAL_BOOT` | `sys.boot.reason` matches panic/watchdog/... (once per boot) | CRITICAL |
| `HIGH_CPU` | `/proc/stat` or `dumpsys cpuinfo`, sustained over N samples | WARNING/ERROR |
| `HIGH_MEMORY` | `ActivityManager.MemoryInfo`, sustained over N samples | WARNING/ERROR |
| `LOW_STORAGE` | `StatFs(/data)` | WARNING/ERROR |
| `THERMAL` | `PowerManager` thermal status ≥ SEVERE | WARNING/CRITICAL |
| `LOG_PATTERN` | any custom regex rule you add to the config | configurable |

When one event shows up in several sources, the dispatcher merges the duplicates within a short
settle window. For example, a native crash appears as a logcat line, a `*_native_crash` entry, a text
tombstone and a proto tombstone; the server gets **one** report with all of them attached.

## What a report contains

`multipart/form-data` POST to `{server.url}/api/v1/issues`:

* `metadata`: JSON with the issue (type, severity, title, process, details, attributes), device
  identity (VIN when available, model, fingerprint, boot id), a health snapshot and
  `suppressed_since_last_report`.
* `bundle`: zip file:

```
issue.json
attachments/            evidence captured at detection time: DropBox entry (crash stack,
                        ANR traces, tombstone .txt / .bin proto), logcat context around the match
logcat/all.txt          last N lines of all buffers
logcat/crash.txt
logcat/events.txt
dumpsys/meminfo.txt, cpuinfo.txt, activity_processes.txt, activity_lastanr.txt,
        thermalservice.txt, car_service_....txt          (configurable list)
system/top.txt, ps.txt, getprop.txt, proc_meminfo.txt, df.txt, uptime.txt  (configurable)
```

Headers: `X-Device-Id`, plus `Authorization: Bearer <api_key>` when an API key is configured.
The server should answer 2xx to accept. Any 4xx except 408/429 drops the report. Anything else
leaves it queued and retried with exponential backoff (30 s up to 30 min).

Heartbeats (`POST /api/v1/heartbeat`, JSON) are sent every `heartbeat_interval_sec` with the
current health metrics and queue depth.

## Configuration

Layers are applied in order, and later layers win. Objects deep-merge; arrays replace.

1. `app/src/main/assets/default_config.json`: all defaults and logcat rules
2. `/system/etc`, `/system_ext/etc`, `/product/etc`, `/vendor/etc` → `issue-daemon/config.json`
   (the AOSP build installs `aosp/config.json` to `/product/etc/issue-daemon/config.json`)
3. Runtime override set over adb (persisted in device-protected storage)
4. System properties `persist.sys.issuedaemon.server_url` and `persist.sys.issuedaemon.api_key`

Key settings:

```jsonc
{
  "server":   { "url": "https://ecu.example:8443", "api_key": "", "heartbeat_interval_sec": 300 },
  "monitors": {
    "cpu":    { "threshold_percent": 85, "sustained_samples": 3, "interval_sec": 10 },
    "memory": { "available_min_percent": 10, "sustained_samples": 2 },
    "logcat": { "rules": [ { "id": "my_hal", "pattern": "MyHal.*timeout", "type": "LOG_PATTERN", "severity": "ERROR" } ] }
  },
  "reporting": { "cooldown_sec": 300, "max_reports_per_hour": 30, "max_queue_mb": 200, "dumpsys": ["meminfo", "cpuinfo"] }
}
```

### Runtime control (adb)

The control receiver requires the sender to hold `android.permission.DUMP`. `adb shell` holds it,
ordinary apps do not. AAOS runs the daemon in user 0, so pass `--user 0`.

```sh
R="--user 0 -n com.aaos.issuedaemon/.ControlReceiver -a com.aaos.issuedaemon.action"

adb shell am broadcast $R.SET_SERVER_URL --es url http://192.168.1.10:8080
adb shell "am broadcast $R.SET_CONFIG --es json '{\"monitors\":{\"cpu\":{\"threshold_percent\":70}}}'"
adb shell am broadcast $R.RESET_CONFIG      # drop runtime overrides
adb shell am broadcast $R.STATUS            # prints config, queue depth, health, last upload
adb shell am broadcast $R.TEST_ISSUE        # sends a MANUAL test report end to end
adb shell am broadcast $R.FLUSH             # retry queued uploads now
```

## Building and deploying

### In the AAOS image (production)

Put this repo in your tree (for example `vendor/<oem>/apps/IssueDaemon`) and add:

```make
$(call inherit-product, vendor/<oem>/apps/IssueDaemon/aosp/issue_daemon.mk)   # PRODUCT_PACKAGES += IssueDaemon
```

`Android.bp` builds the same sources as a **platform-signed, privileged, persistent** app that runs
as **`android.uid.system`**. The genrule injects `sharedUserId` into the manifest. The system uid
is a requirement, not a convenience:

* **Logs.** Since Android 13, logd shows a user-consent dialog before giving full logs to *any*
  uid ≥ 10000, privileged apps included (`system/logging/logd/LogPermissions.cpp`,
  `clientIsExemptedFromUserConsent`). A headless daemon can't accept that dialog.
* **dumpsys.** The `system_app` SELinux domain can look up `meminfo`, `cpuinfo` and the other
  system services. `priv_app` / `untrusted_app` get "Can't find service".

`android:persistent="true"` makes the system start the process at boot and restart it if it dies.
It is direct-boot aware, so monitoring starts before the user unlocks, and it only runs in the
headless system user (user 0).

Also installed: `/system_ext/etc/permissions/privapp_allowlist_com.aaos.issuedaemon.xml` and
`/product/etc/issue-daemon/config.json`. Set your server URL in `aosp/config.json`.

### Development build (Gradle)

```sh
./gradlew assembleDebug testDebugUnitTest      # needs a full JDK 17+ and the Android SDK (API 35)
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant com.aaos.issuedaemon android.permission.READ_LOGS
adb shell pm grant com.aaos.issuedaemon android.permission.DUMP
adb shell appops set com.aaos.issuedaemon GET_USAGE_STATS allow
adb shell am start-foreground-service -n com.aaos.issuedaemon/.DaemonService
```

A dev install runs as an ordinary uid, which limits it:

* the **logcat monitor only sees the daemon's own logs** (see the logd consent rule above)
* CPU usage and `dumpsys meminfo/cpuinfo` are unavailable (SELinux)
* car APIs are missing on non-automotive emulators, so VIN and car-service monitoring switch off

DropBox crash/ANR/tombstone capture, memory, storage, thermal, evidence bundles, the queue and
uploads all work on a dev install. For full coverage, install it as a system-uid app (AOSP build,
or sign it with your platform key and push it to `/system_ext/priv-app/`).

### Mock ECU server

```sh
python3 server/mock_ecu_server.py --port 8080 [--api-key SECRET]
# emulator → host machine
adb shell am broadcast --user 0 -n com.aaos.issuedaemon/.ControlReceiver \
    -a com.aaos.issuedaemon.action.SET_SERVER_URL --es url http://10.0.2.2:8080
# try it
adb shell am crash com.android.settings
curl localhost:8080/api/v1/issues
```

Reports are saved to `received/<device_id>/<time>_<type>_<id>/{metadata.json,bundle.zip}`.

## Layout

```
app/src/main/java/com/aaos/issuedaemon/
  IssueDaemonApp, DaemonService, DaemonRuntime   lifecycle and wiring
  BootReceiver, ControlReceiver                  boot start, adb control
  config/      DaemonConfig (typed), ConfigManager (layered loading, overrides)
  monitor/     Cpu, Memory, Storage, Thermal, CarService, Boot, DropBox, Logcat, TombstoneProto
  report/      IssueDispatcher, EvidenceCollector, ReportQueue, ServerClient, Uploader
  util/        Shell, DeviceInfo, HealthState
aosp/          privapp allowlist, product config, product makefile snippet
server/        mock ECU server (Python stdlib)
Android.bp     AOSP build
```

## MVP limitations and next steps

* Cleartext HTTP is allowed (`res/xml/network_security_config.xml`) for in-vehicle networks.
  For production, use HTTPS with a pinned ECU CA, or mTLS with a per-vehicle client certificate.
* No full `bugreport` capture. That needs `dumpstate` privileges; it could be triggered from the
  server for CRITICAL issues.
* A per-process memory/CPU threshold (for example, "VHAL above 300 MB") would need `readproc`
  access or dumpsys parsing per process.
* No AIDL API yet for other system components to report their own issues.
* Kernel logs, `last_kmsg` and pstore need extra sepolicy for `system_app`.
