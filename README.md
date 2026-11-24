# LineageOS 23.2 — Nubia Red Magic 9 Pro (tiro)

Custom LineageOS 23.2 source state for Nubia Red Magic 9 Pro / NX769J (SM8650 / tiro).

Maintainer: GAME_OVER_op

This repository contains the complete custom source state used to build the current ROM. It is a
plain "snapshot" repository (not an AOSP `repo` checkout): all locally modified and added source
files are stored verbatim under `snapshot/`, binary patches (HEAD -> working tree) under `patches/`,
and restore metadata/scripts under `metadata/` and `scripts/`.

## Restore

```bash
git clone https://github.com/GAME-OVER-op/LineageOS-23.2-tiro.git
cd LineageOS-23.2-tiro
# after syncing a clean LineageOS 23.2 tree for tiro:
./scripts/apply-changes.sh /path/to/android-source
cd /path/to/android-source && ./build.sh
```

`apply-changes.sh` copies `snapshot/` on top of the clean AOSP checkout. `scripts/build.sh` runs
`lunch lineage_tiro-bp3a-userdebug && m bacon -j12` (adjust thread count as needed).

## What is included

- exact pinned Android manifest (`manifest-pinned.xml`);
- complete copies of every modified/added source file under `snapshot/`;
- per-project binary patches under `patches/` (from the base commits in `metadata/base-revisions.tsv`);
- modified device/common Nubia trees, including the restored **Nubia Settings** app
  (fan/cooler, RGB lighting, hardware triggers) and its native services under
  `snapshot/device/nubia/sm8650-common/parts/` and `.../hardware/`;
- **Freeform / EdgeLauncher** integration (virtual-display based floating windows,
  smooth scale-up fullscreen expansion and DPI restoration) under
  `snapshot/device/nubia/tiro/freeform/`. Minimize-to-bubble keeps the window's
  `TextureView`/virtual display mounted and never powers the display off, so
  reopening does not recreate the app Activity or lose playback/task state;
- custom SystemUI per-app volume implementation (AppVolumeController + dialog sliders/binder);
- Launcher3 overview freeform action;
- audio HAL / DTS:X Ultra changes, SELinux changes, ABL payload, LTE+ carrier config;
- compiled binaries that have **no source** are included verbatim (not stripped) under
  `snapshot/prebuilts/`:
  - GPP / FRC++ module binaries (`gppservice`, `libgpp*.so`, `libMotionEngine*.so`,
    `libgui.so`, VPP/HEXLP NDK libs, `gpp_app_list`, rc/seccomp policy) from
    `snapshot/prebuilts/gpp/`;
  - stock NX809J GPP libraries for reference under `snapshot/prebuilts/nubia-stock/lib64/`;
  - DTS:X Ultra prebuilt APK/JAR/databases under `snapshot/vendor/nubia/tiro/dtsx/`.

## Note on GPP / FRC++ frame generation

The ROM-side GPP extension sources are present, but frame generation is **disabled by default**
(`vendor.gpp.create_frc_extension=0` in `snapshot/device/nubia/tiro/device.mk`) because the Qualcomm
VPP usecase on tiro cannot actually start interpolation (`ConfigureDynamic() ... ret=7`,
`fastrpc_get_info_from_dsp ... -111`). Full investigation, reproduction steps and rationale are in
`snapshot/device/nubia/tiro/docs/gpp-frc-status.md`. Nothing was deleted — set the prop to 1 and
install a matching GPP module to re-enable for experiments.

## Firmware / OTA

The ROM ZIP is not stored in Git. The reference stock Nubia OTA used for GPP analysis is on the
build host under `/mnt/axion-build/nubia-fw/`.

## Build configuration: use the stable `bp3a` release

**Always build this device with the stable release:**

```bash
lunch lineage_tiro-bp3a-userdebug
m bacon -j16
```

Do **not** use `lunch lineage_tiro trunk_staging userdebug`. The `trunk_staging` release is a
developer-preview build and produces the following properties:

| | `bp3a` (stable, correct) | `trunk_staging` (preview, broken) |
| --- | --- | --- |
| `ro.build.version.codename` | `REL` | `Baklava` |
| `ro.build.version.preview_sdk` | `0` | `1` |
| `ro.build.version.sdk_full` | `36.0` | `36.1` |

LSPosed / ZygiskNext (LSPlant) breaks on the preview variant: during app start via zygote's
`forkSimpleApps` it fails with
`NoSuchMethodError: DexFile.<init>(ByteBuffer[], ClassLoader, Element[])`,
`Failed to init lsplant` and then SIGSEGVs in `zygisk_lsposed/zygisk/arm64-v8a.so`, killing the
app process. LSPlant selects its ART hook strategy by version properties and picks the wrong
branch on a preview/codename build. The `DexFile` constructor itself is present in both ROMs and
the ART files are byte-identical to the build output, so this is a version-detection issue,
not ROM corruption — rebuilding with the same `trunk_staging` target will not fix it.

The 2026-11-21 clean rebuild (`clean-bp3a-20261120-225523`) was made with `bp3a` and is the
first published build with this correction.

## Large camera resource (not stored in Git)

The stock camera resources under `snapshot/vendor/nubia/tiro/proprietary/system/camera_profiles/`
are included verbatim, except one file that exceeds GitHub's 100 MB per-file limit:

```
system/etc/portrait_segment/IDPhotoMattingCache.bin   (226 MB)
```

Download it from the release assets (`20261120`) and place it at:

```
vendor/nubia/tiro/proprietary/system/camera_profiles/system/etc/portrait_segment/IDPhotoMattingCache.bin
```

before building. Its SHA256 is recorded in the release notes; please verify it after download.
All other camera model files (motion predictor, classifiers, tflite models, image styles,
cartoon/cosmos filters) are present in the snapshot.
