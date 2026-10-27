# Load testing and reproducible performance evidence

This document accompanies `FramePipelineLoadComparisonTest`, which executes as part of `mvn verify`.
It separates what can be tested deterministically in any Java build from what requires a real video,
JavaFX, and the **original supplied** video player.

## What the automated test actually compares

The test runs the same 96 synthetic frames (320 × 180, 24 FPS timestamps) through the same six
built-in colour processors, three times:

1. **Cooperative baseline:** One thread grabs a frame, applies every effect to that frame,
   then proceeds to the next frame. There is no decoder/worker overlap. This models the
   supplied player's single-threaded *processing schedule*, but **does not execute the
   original starter application**.
2. **One-worker decoder:** The modified `FrameDecoder` grabs frames and submits effects to a
   single worker. Decoding and processing can overlap, but frames remain in decode order.
3. **Four-worker decoder:** The same modified decoder submits different frames to four
   effects workers, preserving the command-line order of effects *within* each frame.

The test asserts that all three paths produce **byte-for-byte identical output** for every
frame and preserve the frame count and decode order. It also prints elapsed execution time
and a cooperative/four-worker time ratio, for example:

```text
LOAD COMPARISON (96 synthetic 320x180 frames, six ordered colour effects): cooperative = ...s, 1 worker = ...s, 4 workers = ...s, cooperative/4-worker ratio = ...x
```

These timings are diagnostic, **not grading thresholds**. OS scheduling, thermal state,
JIT warm-up, the number of physical cores and concurrent load can affect them. The test does
not assert that four workers always outperform one worker. This makes correctness tests
repeatable without causing spurious build failures on busy machines.

To run the whole suite from the project directory:

```powershell
mvn clean verify
```

For only the load comparison (still through Maven's test phase):

```powershell
mvn -Dtest=FramePipelineLoadComparisonTest test
```

## Limitations: not yet an original-player integration benchmark

The rubric also asks for an **automated load test against the actual original single-threaded
starter player**, runnable via `mvn verify`. This project's self-contained test is useful
concurrency evidence, but does **not** completely satisfy that stronger requirement.
A faithful original-vs-modified player comparison would require:

- The **unmodified** starter project's source/build (kept separately from the student's project).
- A fixed, locally accessible video with reproducible resolution, FPS and duration.
- A graphical environment with consistent window size, image capture and start/stop markers.
- A test harness that runs both implementations, counts visible numbered frames and compares
  matching frame pixels, not merely the timestamp of the final frame.
- Isolation between runs to avoid competing playback processes, plus several repeats to
  report variability (median/individual results and any capture failures).

Do not label this synthetic benchmark an original-player comparison. In particular, do not
silently generate reference outputs from the optimised implementation and call them
"starter outputs".

## Existing external performance evidence (TITAN)

The course's TITAN runner has separately compared this project with the original starter
player on an AWS `c7i.2xlarge` (8 vCPUs). One successful run after lowering the effects
workers' priority measured:

| Metric | Observed value |
|---|---:|
| Entry throughput with 60 effects | 18.471 FPS |
| Starter/reference throughput with 60 effects | 3.429 FPS |
| Reported performance comparison | 538.68% |
| Logical processors in use by entry | 7.24 / 8 |
| Missed screen-captured frames at 60 effects | 7 |
| Missed screen-captured frames at 40 effects | 0 |

These figures are **historical observations on one machine**, not deterministic guarantees.
Other TITAN runs showed varied rates and sometimes failed while the *reference player's*
Windows Graphics Capture service was starting. Treat those as incomplete comparisons, not
as proof of correct reference-frame measurement. The external observer can miss a frame even
when the player itself reports no gaps in numbered frames.

## Running local video diagnostics

```powershell
$env:COMP3011_TRACE_PLAYBACK = '1'
mvn javafx:run '-Djavafx.args=movie.mp4 -sfwyvdjmbppbmjdvywfssfwyvdjmbppbmjdvywfs --number-frames'
Remove-Item Env:COMP3011_TRACE_PLAYBACK -ErrorAction SilentlyContinue
```

This trace reports the decoder's queue, JavaFX pulses, frame lateness and internal gaps.
It does **not** prove a particular frame remained visible after the GPU/OS compositor
presented it. Keep diagnostics disabled for measured TITAN comparisons.
