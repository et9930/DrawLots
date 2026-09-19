# DrawLots

[中文](README.md) | **English**

[![Android CI](https://github.com/et9930/DrawLots/actions/workflows/android.yml/badge.svg)](https://github.com/et9930/DrawLots/actions/workflows/android.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-7.0%2B%20(API%2024)-3DDC84?logo=android&logoColor=white)](https://developer.android.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)

An Android app for **drawing lots / raffles**: a lot can be text or an image (picked from the gallery or
taken with the camera), and every lot can be given a quantity; drawn lots are listed in order; you can
choose "with replacement" or "without replacement".

Kotlin + Jetpack Compose (Material 3), minimum Android 7.0 (API 24), target API 36.

---

## 1. Features

### Making lots (the "Pool" tab)

| Capability | Description |
| --- | --- |
| Text lot | Type the content by hand; turn on "one lot per line" to enter many lines at once, one lot per line |
| Gallery image lot | Opens the system photo picker (multi-select, up to 9 images); each image becomes one lot |
| Camera lot | Opens the system camera; the photo is stored straight into the pool as a lot |
| Quantity | Every lot's quantity can be adjusted with `− / +` (1 – 999); more copies means a higher chance of being drawn |
| Edit / delete | Change the text or the image note, change the quantity, delete a single lot, or clear the whole pool in one tap |

### Drawing lots (the "Draw" tab)

| Capability | Description |
| --- | --- |
| With / without replacement | A toggle at the top. **Without replacement**: drawn lots no longer take part in later draws; **with replacement**: every draw happens from the complete pool |
| Several lots per draw | "Draw N lots at a time"; without replacement the final draw is trimmed automatically to the remaining quantity |
| Draw animation | After tapping draw, lots scroll for about 0.7 seconds before the result settles, with vibration feedback |
| Ordered layout | Results are numbered in draw order in a grid; the top-left corner of each card shows its current position |
| Zoom in | Tap any result to see the image/text enlarged, together with the draw mode and time |
| Note | Tap "Add note" in the dialog to write a note for **that single** result (for example "drawn by Xiao Ming"); it saves as you type. A small note strip appears at the bottom of the result card, and notes are included in the shared/copied result text |
| Return a single lot | Tap "Return this lot" in the zoom dialog to put **any** drawn lot back into the pool (not just the last one); the remaining results are renumbered automatically |
| Undo | A row of four action buttons: undo (return the last one) / reset / share / copy |
| Reset | Clears the results and returns every lot to the pool (quantities unchanged) |
| Export | Share the draw results as text, or copy them to the clipboard |
| Progress | Without replacement it shows "remaining x / total y" and a progress bar; each row in the pool shows "drawn / remaining" |

### Other

- **Auto-save**: the pool, the remaining counts and the draw results are all stored locally, so you can close the app and carry on later.
- **Zero permissions**: the gallery uses the system Photo Picker and the camera uses the system camera's `ACTION_IMAGE_CAPTURE`, so **no storage or camera permission is requested**.
- **Local images**: a selected image is compressed (longest edge 1600px) and copied into the app's private directory, so deleting the original or losing access to it does not affect the app.
- **Dark mode**: follows the system.

---

## 2. Build and install

Requires JDK 17+ and the Android SDK (compileSdk 36 / build-tools 36.0.0). The local environment already has this:

```powershell
# local JDK and SDK
#   JDK:        <JDK>
#   Android SDK: <Android SDK>   (already written to local.properties)
```

```powershell
cd .
$env:JAVA_HOME = '<JDK>'
.\gradlew.bat :app:assembleDebug          # build the debug APK
.\gradlew.bat :app:testDebugUnitTest      # run the unit tests
```

Outputs:

```
app\build\outputs\apk\debug\app-debug.apk        # debug APK (signed with the debug key, ready to install)
app\build\outputs\apk\release\app-release.apk    # unsigned release APK (you must sign it yourself before installing)
```

Installing on a phone (after enabling USB debugging):

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

> Note: the sandbox of the environment this document was written in does not allow writing outside the
> working directory, so builds redirect the Gradle home to `.\.gradle-home` (`GRADLE_USER_HOME`) and the
> Android user home to `.\.android-home` (`ANDROID_USER_HOME`). On an ordinary machine none of this is
> needed and `gradlew` will use the default `~/.gradle`.

---

## 3. Project structure

```
app/src/main/java/com/drawlots/app/
├─ MainActivity.kt            # entry point, enables edge-to-edge
├─ DrawLotsApp.kt             # two bottom tabs: Draw / Pool, global Snackbar
├─ AppViewModel.kt            # single source of truth: pool + results + persistence + image import
├─ domain/
│  ├─ Lot.kt                  # Lot / LotKind / DrawMode / DrawRecord / PoolSnapshot
│  ├─ DrawSession.kt          # pure Kotlin draw algorithm (with/without replacement, quantity weights, undo, reset)
│  └─ PoolCodec.kt            # save-state serialisation with org.json
├─ data/PoolStore.kt          # SharedPreferences storage (key draw_lots_state)
├─ media/ImageStore.kt        # gallery/camera images: compression, EXIF rotation, writing to files/lots
└─ ui/
   ├─ theme/Theme.kt          # Material 3 light/dark colours (Chinese red + gold)
   ├─ components/             # local image loader, thumbnail, quantity picker, mode buttons
   ├─ PoolScreen.kt           # lot-creation UI
   ├─ DrawScreen.kt           # draw UI + result grid
   └─ Dialogs.kt              # add/edit/confirm/result-preview dialogs

app/src/test/java/com/drawlots/app/
├─ domain/DrawSessionTest.kt      # 19 draw-rule tests
├─ domain/PoolCodecTest.kt        # 4 save-state serialisation tests
├─ domain/TextLotEntriesTest.kt   # 4 bulk-input parsing tests
├─ DrawLotsUiJourneyTest.kt       # 1 end-to-end UI journey test (Robolectric)
└─ TestState.kt                   # helper that resets the save state before tests
```

---

## 4. Draw-rule implementation (the core of the task)

`DrawSession` does not depend on any Android API, which makes it easy to test. The core idea: **each
kind of lot in the pool has `quantity` copies, and the copies that have been drawn are recorded in a
`consumed` counter instead of changing the quantity directly**, so "with replacement / without
replacement" is just a two-line branch:

- The chance of each lot being drawn is **proportional to its number of copies**:
  with-replacement weight = `quantity`; without-replacement weight = `quantity - consumed` (that is, the
  remaining copies). The implementation is "weighted random by copies": treat each lot as `weight`
  tickets and pick one ticket at random.
- Without replacement: on a hit `consumed[id] += 1`, and lots whose remaining count is 0 leave the candidate pool;
- With replacement: `consumed` never changes, the pool is always full and you can draw forever;
- **Return the last lot**: delete the final result, and if that draw consumed a copy, decrement `consumed` back;
- **Restart**: clear the results + zero `consumed` (every lot goes back);
- When a quantity is reduced, `consumed` converges to the new quantity automatically (the surplus copies are effectively returned).

A result `DrawRecord` stores a **snapshot of the lot's content** (text/image path/kind/draw mode/time/ordinal),
so editing or deleting lots afterwards still shows past results correctly and they never get out of step when
the pool changes.

---

## 5. Tests

```powershell
$env:JAVA_HOME = '<JDK>'
.\gradlew.bat :app:testDebugUnitTest
```

**40 cases in total, all passing** (27 + 5 + 4 + 3 pure JVM logic tests plus 1 Robolectric UI journey test).

### Draw rules (DrawSessionTest, 27)

- Drawing the whole pool without replacement: every lot appears exactly "its quantity" times, and `canDraw()` is false once the pool is empty;
- When fewer than requested remain, the draw is trimmed to the remaining quantity; `plannedDrawCount` is correct;
- With replacement, 50 draws do not consume the pool; without replacement, lots that are used up never appear again;
- Probabilities match the quantity ratio: with `A=3, B=1`, over 8000 draws A is about 75% (one case each for with and without replacement);
- The result order and the ordinals (1,2,3…) correspond strictly to the draw order and equal `DrawSession.results`;
- Undo returns one copy / with replacement undo only removes the record / undo on an empty history has no side effects;
- **Returning one specified result**: only the copy it occupies is given back, the other results and their order are
  unaffected, and the returned lot can be drawn again; with replacement only the record is removed (the pool was full
  anyway); a record that cannot be found causes no change at all;
- **Notes**: written only to the specified result (leading and trailing whitespace is trimmed), can be changed to an
  empty note to clear it, writing to a non-existent record has no side effects, and notes are read back together with
  the save state;
- Restart restores every copy; decreasing a quantity returns the surplus drawn copies; increasing a quantity adds drawable copies;
- Deleting a lot keeps past results; clearing the pool clears both lots and results;
- Switching the replacement mode mid-way still allows drawing, and consumed copies are preserved;
- After save → load, "drawn/remaining" is exactly the same, and you can keep drawing until the pool is empty.

### Save state, exported text and bulk input (PoolCodecTest / ResultsTextTest / TextLotEntriesTest, 12)

- A JSON save state round-trips exactly (including Chinese text, newlines, empty image paths and notes);
- Notes are saved and read back; **save states from older versions (with no note field) read back with an empty note** and do not fail;
- Bad data / empty data falls back safely to an empty pool;
- Exported text: an empty result shows a hint, notes follow in brackets, and the numbering uses the current position (no gaps after returning a result in the middle);
- Bulk input: one lot per non-empty line, a whole block of text as one lot, blank input produces no lots, leading and trailing whitespace is trimmed.

### UI journey (DrawLotsUiJourneyTest, 1)

The app is really started on the JVM with Robolectric (no emulator needed) and verified end to end:
load the save state (including one already-drawn result and its note) → header statistics → with/without
replacement toggle → pool list and each lot's quantity → the row of four action buttons (undo/reset/share/copy) →
tap a result card, and the dialog shows "lot 1 / note: drawn by Xiao Ming / Edit note" →
"Return this lot" → the results are cleared and the remaining count goes back to 3/3 → draw once, then "Reset" →
clear the pool (confirmation dialog) → empty-pool state and hint text.

Three notes about the test environment:

- **Robolectric's `android-all` jar**: by default it is downloaded from the network into `~/.m2`. If the environment
  does not allow writing to the home directory, put `android-all-instrumented-<version>.jar` into `robolectric-deps/`
  at the project root; the build script detects that directory and turns offline mode on automatically
  (`robolectric.offline` + `robolectric.dependency.dir`).
- **Only one UI test method was written**: Robolectric reuses the Application / SharedPreferences inside the same
  sandbox, so several test methods pollute each other; the end-to-end journey is therefore deliberately run inside
  a single method.
- **The tests never touch an input field**: a Compose text field's cursor is an endless animation, which keeps
  Robolectric's idle check waiting — as soon as there is one text field on screen, every later query hangs. That is
  why the note field only appears after tapping "Add note" (the default dialog has no text field, so the UI test can
  keep running); typing and saving are covered by verification on a real device plus the DrawSession unit tests.

### On-device verification (test device A / Android 17 / HyperOS)

Actually tapped through on a real device and confirmed with screenshots:

| Check | Result |
| --- | --- |
| First launch with an empty pool | ✅ Header shows "0 lot kinds · 0 copies", the empty state and the three buttons work |
| Bulk text lots | ✅ Enter three lines → hint "3 lots will be added" → lots 1/2/3 appear in the list, Snackbar "Added 3 text lots" |
| Quantity change | ✅ After tapping "+" Alice becomes "quantity ×2" and the header total goes from 3 copies to 4 |
| Draw (without replacement) | ✅ Drawing 2 gives ①Alice ②Bob with 2/4 remaining; drawing 5 more gives 5 results with 4/9 remaining |
| Result order and highlight | ✅ The grid is numbered by position and the newest batch has a red border |
| Image lot rendering | ✅ Camera photos and gallery images render correctly in both pool thumbnails and result cards |
| Four action buttons in one row | ✅ Undo / Reset / Share / Copy all fit in one row and are fully visible; the copy button has an icon |
| Return a single lot | ✅ Tap the 2nd card → "lot 2" dialog → "Return this lot" → that card disappears, the remaining two are renumbered ①②, 1/3 remains, hint "Returned: b" |
| Note | ✅ Tap a card → "Add note" → type `memo-A` → after closing, the note strip appears at the bottom of the card; `memo-A` really is written into the save JSON; reopening the dialog shows "note: memo-A / Edit note" |
| Undo | ✅ Results drop to 1, remaining goes back to 3/4, hint "Returned: Bob" |
| Reset | ✅ Results are cleared and every lot goes back (each lot returns to "drawn 0, remaining N") |
| Camera lot | ✅ System camera → a 173 KB compressed copy is created in `files/lots/` → an "Image lot" appears in the list |
| Gallery multi-select lots | ✅ System Photo Picker (0/9 multi-select) → each of the 4 images becomes one "Image lot" |
| Continuing after an overwrite install | ✅ After reinstalling the APK the pool, remaining copies and draw results are all restored |
| Crashes | ✅ No FATAL / AndroidRuntime exceptions in logcat during the whole flow |

---

## 6. Known trade-offs

- Using images as lots is "one image, one lot": selecting 9 images adds 9 lots, and adjusting their quantities means editing them one by one (multi-select batch editing is not implemented yet).
- The pool order is the order lots were added; drag-to-reorder is not supported yet.
- The save state uses SharedPreferences + JSON: it is fine for up to tens of thousands of lot kinds/results; beyond that, switching to Room is recommended.
- Images in the private directory are kept after you leave the app; the corresponding files are only deleted by "delete that lot / clear the pool".

---

## 7. Multi-device rooms

One phone acts as the **host** (authoritative state) and other people join the same draw: everyone sees the same
pool and results in real time, and every drawn lot is **automatically signed with who drew it**.

### How to use

1. Tap "**Room**" in the top bar → fill in "My name" (defaults to the device model; this name signs your draws).
2. **Create a room**: enter a room name, pick a connection type (LAN / Bluetooth), switch on the two delegation toggles as needed (both off by default) → a QR code + a 6-digit room code + the member list appear.
3. Others **join**: scan the QR code / tap a room under "Nearby rooms" / type the room code by hand (if the broadcast is blocked by the router you can fill in the host address and port in that panel).
4. While in a room the top bar keeps a status strip (room name · host/member · number of people · connection state); tap it to see the QR code/room code/members again, and leave on the right.

### Permission model (least privilege by default)

| Action | Host | Member (default) | Member (after the host grants rights) |
| --- | --- | --- | --- |
| Draw / write notes | ✅ | ✅ | ✅ |
| Undo / return a specified lot | ✅ | ❌ | ✅ after "allow others to return/undo" is switched on |
| Reset (return everything) | ✅ | ❌ | always ❌ |
| Pool editing (add lot / change quantity / delete / clear) | ✅ | ❌ | ✅ after "allow others to edit the pool" is switched on |
| Toggle with/without replacement | ✅ | ❌ | always ❌ (the room shares one rule) |

Even with editing granted, members can only add **text lots**: image files live on the host's device, and v1 does
not upload them back.

### Permissions and networking

- Standalone use **needs no permissions at all**; permissions are only requested on demand once you enter "Room": LAN needs the network permission (a normal permission, granted at install), scanning needs the camera permission, and a Bluetooth room needs Bluetooth advertise/scan permissions (**provided on Android 12+ only**; on older versions please use LAN).
- Bluetooth room: the host advertises over BLE and members scan and connect using the service UUID in the QR code. **No more than 6 people recommended** (limited by the GATT concurrent connection count); image transfer is slower than over LAN.
- LAN room: the phone and the computer/other phones must be on the same Wi-Fi or hotspot; the host keeps the screen awake while the room is open so the system does not freeze it.
- Image lots are fetched on demand: a member asks the host for an image the first time it has to show it, and it is cached in `room-images/` (50MB cap, evicted automatically).

### Known limitations

- A room does not survive the process: the room ends when the host leaves the app; when a member leaves or the host ends it, the member **gets back the pool it had before joining** (results never leak into their own pool).
- A Bluetooth phone-to-phone connection has not been verified on real devices (the development environment has only one phone); only the fragmentation logic, GATT service registration and advertising start-up, and the invite payload were verified. Please report logs if you run into problems.
- Scanning a "live" QR code with the camera needs a second screen to test on your own; picking a QR code from the gallery and typing the room code by hand are alternatives.
- Host migration, incremental frame catch-up, internet (WAN) play and a foreground service are not implemented.

---

## SAEP support

The app ships a **SAEP (Screen Automation Execution Protocol)** static policy that tells the system
what screen automation is allowed to do with this app.

- Policy file: `app/src/main/res/raw/agent_saep_policy.json` (schema `AGRP-Policy/1.0`, at most 10 KiB)
- Declared via the `com.obric.agentrobots.POLICY_JSON` metadata entry in `AndroidManifest.xml`
- Current policy: **fully permissive** (all of `global_disable` / `screenshot_disable` / `input_disable`
  and the four agent intents are `false`), so assistants are free to operate the app
- Requirements: a system that supports SAEP (ObricUI 2.2+) and the `com.obric.agentrobots.provider`;
  on regular Android devices the policy is simply ignored and nothing else changes
- Reference implementation: ByteDance's open-source demo [bytedance/SAEP-demo](https://github.com/bytedance/SAEP-demo) (Apache-2.0)

---

## License

This project is released under the [MIT licence](LICENSE): you are free to use, modify and distribute it,
including commercially, as long as you keep the copyright and licence notice.

> The app contains no third-party assets; the dependencies follow their own licences (AndroidX / Kotlin / CameraX / zxing and so on).
