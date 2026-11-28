#
# SPDX-FileCopyrightText: The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
#

# Inherit from nubia sm8650-common
$(call inherit-product, device/nubia/sm8650-common/common.mk)

DEVICE_PATH := device/nubia/tiro

# Audio
PRODUCT_COPY_FILES += \
    $(DEVICE_PATH)/audio/mixer_paths_pineapple_mtp.xml:$(TARGET_COPY_OUT_VENDOR)/etc/audio/sku_pineapple/mixer_paths_pineapple_mtp.xml

# Soong
PRODUCT_SOONG_NAMESPACES += \
    $(DEVICE_PATH)

# NubiaParts
PRODUCT_PACKAGES += \
    NubiaParts

# Freeform overlay host (Stage 1)
PRODUCT_PACKAGES += \
    EdgeLauncher

# Basic Call Recorder
ifeq ($(strip $(TARGET_PREBUILT_BCR)),true)
PRODUCT_PACKAGES += \
    Bcr
endif

# DTS:X Ultra
$(call inherit-product, vendor/nubia/tiro/dtsx/dtsx.mk)

# Overlays
PRODUCT_PACKAGES += \
    SettingsProviderResTiro

# Frame generation is opt-in and is exposed only for this product.
PRODUCT_PACKAGE_OVERLAYS += $(DEVICE_PATH)/overlay-frame-generation
PRODUCT_ENFORCE_RRO_EXCLUDED_OVERLAYS += $(DEVICE_PATH)/overlay-frame-generation

# GPP frame generation (Qualcomm FRC++ via vendor.gppservice) -- DISABLED
#
# NOTE (2026-11-20): The ROM-side GPP/FRC++ integration is present in the tree
# (frameworks/native/libs/gui/QtiExtension/* and the hooks in Surface.cpp) but
# is intentionally switched off with vendor.gpp.create_frc_extension=0.
#
# Reason: the GPP binder/libgui part works (libgppextension.so Init/Deinit are
# reached and vendor.gppservice creates a session for the game SurfaceView),
# but the downstream Qualcomm VPP usecase on tiro (SM8650 / NX769J) cannot
# start frame interpolation. Observed with logcat on device:
#   vpp-uc: u32VppUsecase_ObtainProcessingService (1931): configure dynamic failed, ret=7
#   vpp-uc: u32VppUsecase_UpdateFramerate (1837): ConfigureDynamic() failed, so can't process, ret=7
#   adsprpc: vppservice: fastrpc_get_info_from_dsp: could not obtain dsp information, err val -111
#   VppComponent: [com.mobile.legends-N] VPP processStart_l failure, cancel the output buffer
# The frame rate therefore stays at the app value (60 Hz) instead of being
# interpolated to 120 Hz. The VPP configstore on tiro also ships with
# enable=false / disable_dynamic_hal=true; flipping those and swapping in the
# stock NX809J GPP/VPP libraries did not fix ConfigureDynamic() (ret=7).
# Keeping the extension enabled only adds log spam / extra binder sessions
# without producing interpolated frames, so it is disabled here.
#
# To re-enable for experiments set vendor.gpp.create_frc_extension=1 and
# install a matching GPP/FRC++ module (vendor.gppservice + libgpp* libs).
# See docs/gpp-frc-status.md for the full investigation.
PRODUCT_SYSTEM_DEFAULT_PROPERTIES += \
    vendor.gpp.create_frc_extension=0 \
    vendor.gpp.allgame.enable=0 \
    dalvik.vm.dex2oat64.enabled=true

# The vendor PAL/HAL on tiro misreports the wired headset jack whenever an app
# uses MMAP AAudio streams (seen with games in freeform): it emits a fake
# unplug/plug every ~5s, which flips the mixer between speaker and headset and
# floods the log/CPU. Force legacy AAudio so streams stay on one route.
# Verified on device: plug events 28-34/90s -> 0/75s, log 100k+ -> 15k lines.
PRODUCT_SYSTEM_DEFAULT_PROPERTIES += \
    aaudio.mmap_policy=0 \
    aaudio.mmap_exclusive_policy=0

# This is a 64-bit-only platform (no dex2oat32 in the ART APEX). core_64_bit.mk
# (used for the stable bp3a release) does not set the flag that core_64_bit_only.mk
# sets. Without it artd picks dex2oat32 and aborts with "No such file or directory".

# Inherit from proprietary targets
$(call inherit-product, vendor/nubia/tiro/tiro-vendor.mk)
