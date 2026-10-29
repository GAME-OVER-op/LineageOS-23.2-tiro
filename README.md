# LineageOS 23.2 — Nubia Red Magic 9 Pro (tiro)

Custom LineageOS 23.2 source state for Nubia Red Magic 9 Pro / NX769J.

Maintainer: GAME_OVER_op

This repository contains the custom source changes used to build:

lineage-23.2-20261028-UNOFFICIAL-tiro.zip

The repository contains:

- exact pinned Android manifest;
- complete copies of all locally modified and added files;
- binary Git patches for tracked changes;
- complete DTS:X Ultra payload;
- compiled DTS libraries and databases;
- Freeform/EdgeLauncher integration;
- custom SystemUI volume implementation;
- Launcher overview changes;
- device and common Nubia changes;
- audio HAL changes;
- SELinux changes;
- ABL payload;
- LTE+ carrier configuration;
- restore and build scripts.

The DTS directory is intentionally stored completely and is not stripped of
compiled libraries or binary tuning databases.

Firmware SHA256:

b274c5ac8762bee3444c3a1f1030241234ab62b138ab5ef47f799465fd6f1242

The firmware archive is slightly larger than GitHub's 2 GiB per-release-asset
limit. The Release therefore contains two byte-for-byte parts.

To restore the original ZIP, concatenate part 00 and part 01 in numerical
order and verify the SHA256 above before flashing.
