# GPP / FRC++ frame generation on tiro — status (2026-11-20)

## Outcome

The ROM-side GPP/FRC++ frame-generation integration is **present in the source tree but disabled** by default:

- `device/nubia/tiro/device.mk`: `vendor.gpp.create_frc_extension=0`, `vendor.gpp.allgame.enable=0`.
- No code was deleted. The extension sources and all hooks remain in place:
  - `frameworks/native/libs/gui/QtiExtension/QtiSurfaceExtensionGPP.{h,cpp}`
  - `frameworks/native/libs/gui/Android.bp` (source in `libgui-sources`)
  - `frameworks/native/libs/gui/Surface.cpp` / `Surface.h` (GPP member + hooks)
  - `device/nubia/tiro/device.mk` (disabled prop block with rationale)

Setting `vendor.gpp.create_frc_extension=1` and installing a compatible GPP
module re-activates the whole path.

## What works

A stock RedMagic 11 Pro (NX809J, REDMagicOS 11.0.19MR2) OTA was downloaded and
unpacked to obtain the reference GPP stack. Comparison with the OnePlus 11
(`Test-mora-game`) module showed:

- `gppservice` and `libgppextension.so` are byte-identical between OP11 and stock NX809J.
- The AOSPA-derived `GPP_SERV_QUERY_SUPPORTED_GAME` (transaction code 3 with a
  UID payload) used by the original `QtiSurfaceExtensionGPP.cpp` is **not** part
  of this `gppservice` — it always returned `-EBADR` and set `mIsSupported=false`,
  so `dlopen("libgppextension.so")` / `dlsym("Init"/"Deinit")` were never reached.
  The constructor was corrected to match stock behaviour (UID check, then
  `dlopen` + `dlsym`), after which the full chain works:
  - `SurfaceExtension-GPP: ctor ... ready`
  - `Connect api=1 supported=1`
  - `GPP-Extension: GPP Extension Init: uid <app>`
  - `GPP-Service: createSession: package <game> is supported 1/2`
  - `GPP-Service: createSession: connect session: ... SurfaceView[...]#N`
  The module's `gpp_app_list` was also replaced with the stock 240-game list
  (includes `com.mobile.legends,1,1`, `com.tencent.tmgp.cod,2,1`) and
  `vendor.gpp.frc.enable=0x22` was supplied via the module `system.prop`.
- A KernelSU module was assembled with the above (backup:
  `/mnt/axion-src/gpp_frc_fixed/module/`) and the diagnostic `libgui.so` was
  replaced with the clean one.

## Why frame generation still fails

With GPP attached to Mobile Legends (uid 10203, app set to 60 Hz, display 120 Hz)
the downstream Qualcomm VPP usecase refuses to start interpolation:

```
vpp-uc: u32VppUsecase_ObtainProcessingService (1931): configure dynamic failed, ret=7
vpp-uc: u32VppUsecase_UpdateFramerate (1837): ConfigureDynamic() failed, so can't process, ret=7
adsprpc: vppservice: fastrpc_get_info_from_dsp: could not obtain dsp information, err val -111
VppComponent: [com.mobile.legends-N] VPP processStart_l failure, cancel the output buffer
```

`frameRateOverride` stays at the app value (`frameRateOverride {uid=10203
frameRateHz=60.000004}`); no 60→120 interpolation occurs.

Things tried that did **not** fix it:

1. Swapping the OP11 `libgppvppgfrcplussession.so`, `libgpphexlpsession.so`,
   `libgpppreprocessing.so` for the stock NX809J versions (different MD5s).
2. Enabling VPP in `/vendor/etc/configstore/vpp.configstore.xml`
   (`enable=true`, `disable_dynamic_hal=false`) — stock tiro ships
   `enable=false / disable_dynamic_hal=true`. This removed `ConfigureDynamic`
   errors for one VPP instance but they reappeared on the next session and
   `processStart_l failure` remained.
3. Forcing static usecase props: `vendor.gpp.dynamic.settings.enable=0`,
   `vendor.gpp.usecase=1`, `vendor.gpp.gfrc.interp.rate=2`,
   `vendor.gpp.gfrc.upscale.ratio=1`.
4. Removing multi-session noise (`vendor.gpp.allgame.enable=0`, game whitelisted
   only) — `GPP-Service: createSession: There is still one session running`.
5. Adding the missing `capabilityconfigstore@1.0` HAL and the SELinux allow for
   `/sys/devices/soc0/subset_parts` (`vendor_vppservice` was denied read).

Root cause is therefore in the **vendor VPP / DSP stack** on tiro:
`ConfigureDynamic()` returns 7 (`VPP failure`) and the DSP info query returns
`-ECONNREFUSED`. The GPP client side (this ROM) is fine, but the Qualcomm VPP
usecase (HXLP/G-FRC+ via CDSP) is not functional with the tiro vendor blobs /
DSP firmware. Making it work would require a matching VPP+HAL+DSP firmware set
from a device already shipping FRC++ on this exact SoC, which is outside the
scope of these sources.

## Reproduction / verification

1. Build and flash the ROM from this tree (with the prop disabled by default).
2. For experiments: set `vendor.gpp.create_frc_extension=1` and install a GPP
   module (KernelSU) providing `vendor.gppservice` + matching `libgpp*.so`.
3. Launch Mobile Legends, set 60 FPS in-game, display 120 Hz.
4. Check:
   - `logcat -b all | grep -i "GPP Extension Init"` (client attaches)
   - `logcat -b all | grep -i "configure dynamic\\|processStart_l"` (VPP fails)
   - `dumpsys display | grep frameRateOverride` (stays 60)

## Related

- Stock NX809J OTA: `/mnt/axion-build/nubia-fw/` (payload + extracted images).
- OP11 reference module: `/mnt/axion-src/Test-mora-game/magisk/`.
- Prepared fixed module: `/mnt/axion-src/gpp_frc_fixed/module/`.
