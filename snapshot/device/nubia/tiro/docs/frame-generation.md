# System frame generation on tiro

Settings → Display → Frame generation is disabled by default. Availability is
provided by the tiro resource overlay, independently of the disabled GPP/FRC++
stack. The per-user setting can enable only selected applications or default to
Auto. Per-application overrides are Default, Off, Video only and Auto.

System Server resolves the foreground user's package settings to UIDs and applies
the policy in SurfaceFlinger. Video-only mode uses the MediaCodec/BufferQueue
producer API marker forwarded by BLAST, not a package name or layer-name heuristic.
When several surfaces qualify, a video layer has priority. At most one layer is
interpolated per refresh. Other surfaces, including the player controls if they
are separate layers, stay on their regular composition path.

SurfaceFlinger composites an intermediate only after seeing a stable source
cadence and a ready future buffer queued for that same layer. Original timestamps
and the audio clock are unchanged. The active physical display determines available
slots; the FPS cap does not force a panel mode or change application FPS. If the
producer sends the next buffer too late, motion is irregular, or the buffer/slot
is not suitable, composition continues with the original frame. This also means
an app that submits no lookahead buffers may show no gain at all.

Protected, secure, HDR, sideband/tunneled and front-buffer content is bypassed,
as is generation on other or mirrored displays. If the graphics backend cannot
import the second image for sampling, it falls back to the currently displayed
buffer. For video, interpolation is a fallback-friendly adaptive temporal blend,
not an optical-flow accelerator.

## Device acceptance checks

Run on an authorized test firmware; no configuration needs to be changed with
ADB. Select the desired refresh mode and settings from the phone UI.

1. YouTube → Video only: compare a fixed SDR clip with generation off/on. Start
   with 30 FPS at 60 Hz, then 60 FPS at 120 Hz. Verify audio, subtitles, controls,
   PiP, pause/resume, seek, orientation changes and leaving the player.
2. At 120 Hz, verify even slot use for 24 FPS with the automatic cap and no
   uneven 24-to-48 scheduling when capped at 60. Test a 30 FPS clip with cap 60.
3. Compare a stable-rate OpenGL or Vulkan game and an ordinary scrolling app
   in Auto. Verify that static content and apps already at panel refresh do not
   trigger the shader. Test Off and both inherited defaults, switching users,
   battery saver and mirrored/recorded output.
4. Verify that secure, tunneled and HDR video continue to display normally.
   Compare GPU time, thermals, input latency, and original and synthetic
   presentation cadence once warmed up, not only the refresh-rate indicator.

`adb shell dumpsys SurfaceFlinger` exposes a Frame generation diagnostic line with
separate counts for actual blend submissions and signaled physical present fences.
Use a Perfetto graphics trace to correlate `FrameGeneration composition`,
`RE frame interpolation: blend selected` and `FrameGeneration presented`.
