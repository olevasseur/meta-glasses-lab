# Photo-to-Mac evidence

Date: **2026-10-10**. Read with [M4](m4-vision-notes.md) and
[read-to-me](read-to-me-notes.md). Runtime AI remains Gemini.

Re-inspected the installed `mwdat-camera:1.0.0` AAR with JDK 17 `javap`:
`VideoQuality` exposes HIGH, MEDIUM, LOW; `Stream.capturePhoto(Continuation)`
returns `DatResult<PhotoData, CaptureError>`. There is no sensor-resolution
argument on that method or `StreamConfiguration`. Use HIGH plus the still API
and preserve returned pixels. This is not a claim of native sensor resolution.
The installed Meta CameraAccess source also distinguishes still capture from
preview/video streaming. No SDK update or new dependency was needed.

Sources (artifact/source inspected locally on this date):

- [DAT 1.0.0 camera artifact](https://repo.maven.apache.org/maven2/com/meta/wearable/mwdat-camera/1.0.0/).
  The web reader could not open this directory; verification used the cached AAR.
- [Meta CameraAccess source](https://github.com/facebook/meta-wearables-dat-android/blob/d3159f738676221181577dd745d83d10b6ab46ba/samples/CameraAccess/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/camera/CameraViewModel.kt).
- [Pillow ImageOps.exif_transpose](https://pillow.readthedocs.io/en/stable/reference/ImageOps.html#PIL.ImageOps.exif_transpose)
  (official docs checked 2026-10-10): applies the EXIF orientation before removing
  the orientation tag. Shared JPEG validation now applies this operation before
  clearing metadata and re-encoding. Tests inspect actual saved output, including
  a GPS-bearing synthetic JPEG rotated from 1800×1200 to 1200×1800.

The 12 MB / 50 MP policy allows larger stills than visual memory's 4 MP input.
These are resource limits, not measured hardware maxima. Raw HTTP avoids base64
inflation. Authentication and a body limit precede decode; one active photo
request bounds full-resolution memory use. No Gemini caption/image path is used.
Android's ordinary tool response remains image-free and uses the existing
blocking-tool follow-up policy. Actual installed SDK compilation and JVM protocol
checks establish integration, not hardware performance.

## Unverified

- Native/returned camera dimensions, real HEIC/bitmap orientation, HFP shutter
  audibility and simultaneous camera/Inputs/Motion lifecycle.
- Spoken Gemini tool choice and exact confirmations. Dedicated voice-eval cases
  are simulator coverage, not a real API or recorded-speech result.
- Real Tailscale transfer latency, sleeping-Mac recovery, macOS Downloads
  permissions and long-lived service scheduling/battery behavior.
- No new real Gemini calls, private photos or writes to the user's Downloads were
  used in validation. Photo storage tests use temporary directories only.
