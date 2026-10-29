#
# SPDX-FileCopyrightText: The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
#

# Inherit from those products. Most specific first.
$(call inherit-product, $(SRC_TARGET_DIR)/product/core_64_bit.mk)
$(call inherit-product, $(SRC_TARGET_DIR)/product/full_base_telephony.mk)

# Inherit from device.
# Basic Call Recorder
TARGET_PREBUILT_BCR := true

$(call inherit-product, device/nubia/tiro/device.mk)

# DTS:X Ultra replaces Lineage AudioFX
TARGET_EXCLUDES_AUDIOFX := true

# Inherit some common Lineage stuff.
$(call inherit-product, vendor/lineage/config/common_full_phone.mk)

# Boot animation
TARGET_SCREEN_HEIGHT := 2480
TARGET_SCREEN_WIDTH := 1116

PRODUCT_BRAND := nubia
PRODUCT_DEVICE := tiro
PRODUCT_MANUFACTURER := nubia
PRODUCT_MODEL := ZTE A2020G Pro
PRODUCT_NAME := lineage_tiro
PRODUCT_SYSTEM_DEVICE := NX769J
PRODUCT_SYSTEM_NAME := NX769J-UN

# === DTSX identity compatibility ===
PRODUCT_SYSTEM_PROPERTIES += \
    ro.build.product=P855A01
# === End DTSX identity compatibility ===

PRODUCT_GMS_CLIENTID_BASE := android-zte

PRODUCT_BUILD_PROP_OVERRIDES += \
    BuildDesc="qssi_64-user 15 AQ3A.240812.002 20251224.151310 release-keys" \
    BuildFingerprint=nubia/NX769J-UN/NX769J:15/AQ3A.240812.002/20251224.151310:user/release-keys \
    DeviceName=$(PRODUCT_SYSTEM_DEVICE) \
    DeviceProduct=$(PRODUCT_SYSTEM_NAME)
