# Fold 5 · AGC 50 MP fork for LineageOS

A working 50 MP mode for the Galaxy Z Fold 5 (SM-F946B, q5q) on **LineageOS 23.2 /
Android 16**, inside the AGC (Google Camera port) that
[HyperRamzey's fork](https://github.com/HyperRamzey/fold5-camera2-research) already fixes
for stock One UI.

Press the 50MP button and you get a real 8160x6120 JPEG (49.9 MP, ~14 MB) out of the
Samsung vendor HAL's HighResolution remosaic pipeline:

```
LEraw50 : 50MP: configuring RAW10+JPEG 8160x6120 at 0x20000, preview at 0x10000
LEraw50 : 50MP: session configured
LEraw50 : 50MP: JPEG_ORIENTATION=90
LEraw50 : 50MP: firing single still after 8 previews
LEraw50 : 50MP: JPEG 14278111 bytes, SOF 8160x6120
LEraw50 : 50MP: saved=true AGC_50MP_1791892110076.jpg (14278111 bytes)
```

This repository is the app half; the ROM half (the patched `cameraserver` that makes the
vendor HAL accept a third-party 50 MP session) is
[tomppi/fold5-lineageos-50mp](https://github.com/tomppi/fold5-lineageos-50mp), and the
KernelSU module that installs it is in `module/` here as well.

## Why the fork needs a port at all

The fork's 50 MP feature was written against One UI, where two framework APIs exist and
LineageOS does not have them:

| One UI API | LineageOS | This port |
|---|---|---|
| `OutputConfiguration.semCreateOutputConfiguration(int, Surface, int, int)` | absent | `com.agc.ReaOutputConfig` — same signature, builds a plain `OutputConfiguration` and folds Samsung's option into `streamUseCase` (`option << 16`) |
| `CustomInterfaceHelper.setSamsungParameter(CameraDevice, String)` (from `/system/framework/scamera_sdk_util.jar`) | absent | `com.agc.ReaPdkHelper` — a no-op; the patched cameraserver sends the PDK parameter string itself |

Both are reached through the fork's own reflection bridges, so the port only redirects
which class those bridges resolve — the feature code itself is untouched.

## What else this port changes

- **Restores the feature classes.** v6.0.0 ships `Eraw50Bridge`/`Eraw50Burst` as dead
  code with no callers and no settings entry; the 21 `Eraw50*`/`LEraw50*` classes come
  from the v4.1 build of the same fork (`build.sh` decompiles both — they are the fork's
  code, not redistributed here).
- **Re-applies the four hooks** v4.1 used, at v6's identical anchors: `kzs.<init>` →
  `Eraw50UI.install` (the viewfinder button), `sz.i` and `mxc` → `LEraw50.wrapOpenCb`
  (stores the camera device GCam already opened), `Application$1.onActivityCreated` →
  `LEraw50.setContext`.
- **Restores the settings toggle** that v6 dropped: *Settings → Lens Setting → Main →
  "50MP Expert RAW Mode"*. v6 has no `pref_50mp_eraw_key` anywhere in `res/`.
- **Replaces the six-frame burst with a single shot.** This is the interesting one — see
  below.

## Why not the burst

The fork's `Eraw50Burst` configures its own session, waits six previews, then captures
six RAW10 frames and merges them on the CPU. On this ROM those six frames are **black**:
the still requests land in the window where the HAL is swapping sessions

```
[CHI_HR] [HR] 2. Destroying Binning Preview Session.
[CHI_HR] [HR] 3. Destroyed Binning Preview Session. Creating Quadra Preview Session...
[CHI_HR] [HR] 4. Created Quadra Preview Session. Waiting for Rdi...
```

so they are captured before the quadra readout is live. Measured: the merged output has
mean 0.056 / max 0.286 (auto-levelled, pure noise) where the HAL's own JPEG has mean 0.406
across the full range.

The HAL does not need the help. Given a session with Samsung's high-resolution option on
a RAW10/JPEG 8160x6120 pair, it runs the remosaic itself, waits for RDI internally and
encodes the JPEG — one still request is enough. `com.agc.Rea50Shot` does exactly that,
and it is what the 50MP button now calls. `Eraw50Burst` is left in the APK, so restoring
the six-frame merge is a one-line change in `patch_agc.py` (`patch_button`).

### Why a RAW or YUV burst cannot work on this HAL

Measured on the device:

| Attempt | Result |
|---|---|
| six stills fired 40 ms apart (the original burst) | six RAW10 frames, **all black** — captured during the swap |
| RAW10 8160x6120 option 2, primed, 1.6 s wait, eight stills | **zero frames** — an app RAW stream is not part of the HighResolution graph |
| YUV_420_888 8160x6120 option 2 + preview | session **rejected**, `-EINVAL` |
| JPEG 8160x6120 option 2, one still | works — the HAL's own 50 MP JPEG, ~14 MB |

The vendor HAL says the same thing. Its stream dump shows the YUV attempt arriving
correctly and being turned down:

```
stream[0]: scenarioType(YUV_CAPTURE), format(0x23), size(8160x6120), option(0x2), stream_use_case(131072)
stream[1]: scenarioType(PREVIEWCB),   format(0x23), size(1280x960), option(0x1)
ScenarioManager: isValidStreamConfiguration=true, shootingMode=UNI_SHOOTING_MODE_GALAXY_RAW_HIRES
ScenarioFactorySec: CreateScenarioUnit: JPEG
AIDL_CameraDeviceSession: ConfigureStreams: configure stream failed: Invalid argument(-22)
```

and the CHI pipeline table in `/vendor/lib64/libchifeature2.so` names the graph that does
the work:

```
HighResolutionAiRealtimeSensor2RDI         sensor -> RDI (the HAL's internal raw dump)
HighResolutionHighResBayerBpsIpeGpu2Yuv    Bayer -> BPS/IPE/GPU -> YUV (internal)
HighResolutionYuv2Jpeg                     YUV -> JPEG
```

RAW enters that chain as the HAL's own RDI and the YUV exists only inside it; neither is an
app-facing stream. The only HighResolution output an app can receive is the finished JPEG,
which is what this port uses. A multi-frame merge would have to happen in the JPEG domain
(two or three HAL shots averaged), which the GCam session window makes marginal, so it is
not implemented here.

## Requirements

- Galaxy Z Fold 5 on LineageOS 23.2 (or any ROM whose Samsung vendor HAL is intact)
- the patched `cameraserver` from
  [fold5-lineageos-50mp](https://github.com/tomppi/fold5-lineageos-50mp) — patches 0002
  (PDK parameters over the Samsung session extension) and 0003 (accept vendor stream
  options) are what make this work. `module/q5q_cameraserver_hr_v8.zip` installs it.
- KernelSU (or a root solution that can mount a module)
- AGC 9.2.14 from HyperRamzey's releases as the build input

## Building

```sh
git clone https://github.com/tomppi/fold5-agc-50mp && cd fold5-agc-50mp
curl -LO https://github.com/HyperRamzey/fold5-camera2-research/releases/download/v6.0.0/AGC9.2.14_V6.0.0_fold5.apk
curl -LO https://github.com/HyperRamzey/fold5-camera2-research/releases/download/v4.1.0/AGC9.2.14_V14.0_ruler_fold5fix_v4.1.apk
ANDROID_HOME=/path/to/sdk ./build.sh AGC9.2.14_V6.0.0_fold5.apk AGC9.2.14_V14.0_ruler_fold5fix_v4.1.apk
```

`build.sh` decompiles both upstream APKs, applies `agc-port/patch_agc.py`, compiles
`agc-port/java/com/agc/Rea50Shot.java` into a fourth dex (`classes4.dex`), rebuilds the
APK and signs it with a debug key. Nothing in the port is hand-written smali except the
two small helper classes; the capture logic is real Java.

Install over an existing AGC only if the signature matches, otherwise uninstall first
(your `.agc` configs survive as files in `Download/AGC.9.2/configs/`).

## Using it

1. Install the cameraserver module, reboot, confirm `init.svc.cameraserver = running`.
2. Install the APK, open it, allow camera + storage.
3. *Settings → Lens Setting → Main → 50MP Expert RAW Mode* → **On** — a 50MP button
   appears in the viewfinder.
4. Press it: ~2 s later the shot lands in `Pictures/AGC_50MP/`.

## Files

```
build.sh                     end-to-end build from the upstream APKs
agc-port/patch_agc.py        the port: class copy, bridge redirect, hooks, resources
agc-port/java/               Rea50Shot.java + compile stubs (real Java, compiled in)
agc-port/smali/              ReaOutputConfig.smali, ReaPdkHelper.smali (tiny helpers)
module/                      KernelSU module (patched cameraserver, v8)
evidence/                    logs from the verified runs
```

## Credits and licensing

Reverse engineering and the 50 MP feature this fork carries are
[HyperRamzey](https://github.com/HyperRamzey/fold5-camera2-research)'s work; the base app
is BigKaka's AGC port of Google Camera. This repository contains only the LineageOS
adaptation (helpers, hooks, resources, single-shot path) — the upstream APKs are build
inputs you fetch yourself.

MIT for the code here (see `LICENSE`); upstream components keep their own terms. Modding
and sideloading are your own call on your own hardware.
