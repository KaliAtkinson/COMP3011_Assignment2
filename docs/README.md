# COMP3011 Assignment 2 — Multithreaded Video Player

This project extends the supplied JavaFX video player with an ordered command-line frame-effect stack and a concurrent frame-processing pipeline. The original starter code was supplied by Simon Ratcliffe (with the attribution recorded in the source-file headers); changes and new classes are identified in the source and Git history. See the assignment instructions for the required submission format.

## Build and run

Requirements: **JDK 25**, Maven, a working JavaFX graphical environment, and the JavaCV/FFmpeg native dependencies resolved by Maven. Run commands from the directory containing `pom.xml`.

```powershell
mvn validate
mvn clean verify
mvn package
mvn javafx:run '-Djavafx.args=--help'
mvn javafx:run '-Djavafx.args=movie.mp4 -sfwyvdjmbp --number-frames'
```

`movie.mp4` is an example; provide the path to an actual playable video on your machine. Audio, maximisation and monitor selection are controlled through the existing command-line options. Frame-effect options may be mixed, stacked or repeated, and are applied **in the order supplied**. `CommandLineController` parses the command line; `FrameProcessorCatalogue` is the single place to register another selectable effect and its help text.

## Thread architecture and ownership

```text
JavaFX application thread           Decoder thread                 Effect-worker pool
-------------------------           --------------                 ------------------
controls / playback clock     ->    seek-request queue
                                     grab next media item
ready frame / audio queues    <-    bounded ordered future queue <- process *private* frame copies
JavaFX image conversion
render notification
```

- **JavaFX thread — `VideoPlayerModel`**: owns the playback clock, GUI callbacks, pending audio, ready-frame deque and currently displayed frame state. `AnimationTimer` pumps completed items without waiting on unfinished futures. The JavaFX image conversion and view notification are also performed here, so the converter is not concurrently accessed.
- **Decoder thread — `FrameDecoder`**: is the exclusive owner of `MediaSource`/FFmpeg grabbing after startup. It grabs one frame at a time (inter-frame compressed-video decoding cannot simply be farmed out), clones video frames before handing them to workers, and publishes ordered futures to an `ArrayBlockingQueue`. A full queue applies backpressure rather than allowing the decoder to run indefinitely ahead of playback.
- **Effect workers**: a fixed thread pool processes **different frames concurrently**. The effects within one frame are executed **in command-line order**. The pool is sized to `max(1, availableProcessors() - 1)` to leave CPU scheduling capacity for rendering; its threads use `Thread.MIN_PRIORITY` as a scheduling *hint*. Neither the JVM nor the operating system guarantees that hint will reserve a physical core or prevent all rendering delays.
- **Audio**: decoded audio items share the ordered handoff to the JavaFX side. The cross-thread `audioOutputEnabled` switch is `volatile`; all other mutable playback state is confined to the JavaFX thread.

### Concurrency invariants and critical regions

| Shared boundary | How it is protected | Why it matters |
|---|---|---|
| Media grabber | Single decoder-thread ownership; decoder closes source on exit | FFmpeg's source is not treated as thread-safe |
| Worker → consumer handoff | `ArrayBlockingQueue` holding futures in decode order | Queue provides safe publication; head-of-line ordering prevents later frames overtaking earlier frames |
| Seeking | `AtomicInteger` generation plus `BlockingQueue` of seek requests; stale items removed/cancelled and ignored on consumption | Frames from before a seek must not appear at the new position |
| One-time effect initialisation | `EffectChain.initialiseLock` plus a `volatile initialised` flag | All workers see the fully initialised effect state before processing |
| Per-frame pixel memory | Frame cloned on decoder thread; exactly one effects worker owns a copy; consumer receives it after its future completes | Effects cannot overwrite another worker's frame; avoids tearing from decoder buffer reuse |
| Fast-path temporary rows | `ThreadLocal<byte[]>` in `FusedColourRun` | Parallel workers do not write into the same scratch array |
| Audio on/off | `volatile` flag; JavaFX-owned output queue | Toggle is visible to decoder without locking the GUI thread |
| Performance telemetry | Opt-in `LongAdder` counters / diagnostic snapshots | Telemetry is not used to make playback scheduling decisions |

The queue handoff and `Future` completion establish the necessary cross-thread visibility for processed pixels. `Reference.reachabilityFence` is used during JavaFX conversion to keep the native-backed frame reachable until copying is complete. `recentlyShown` retains a small number of converted frames temporarily; it is **not** a cross-thread recycling pool.

### Seeking, stopping and limitations

A seek increments the active *generation*. `FrameDecoder.seek()` serialises generation allocation and request publication, preventing simultaneous callers from publishing a lower generation after a higher one. The decoder moves its media source on its own thread; any results from an older generation are ignored by the consumer. The output queue is bounded and `poll()` is non-blocking, so slow workers cannot directly block a JavaFX pulse. A frame finishing after another frame still waits for its decode-order turn; this is intentional correctness over out-of-order playback.

Shutdown requests decoder termination and worker interruption, and the decoder tries to stop workers before closing the media source. **There is a bounded termination wait**, so if a native operation or effect ignores interruption, a warning can be emitted: this is a limit of the current shutdown design and should not be described as guaranteed prompt termination under every failure. Stalls in the GUI or OS scheduler can still cause frames to be *missed by an external screen capture* even if the internal ordered sequence has no frame gaps.

## Performance-sensitive decisions

1. Offload CPU-heavy effects to a bounded worker pool so mouse, window, and JavaFX rendering work is not performed on the effects threads.
2. Reserve scheduling headroom (`availableProcessors() - 1`) and lower worker priority. This improved external frame-capture consistency on the course test machine; it is a hint rather than an isolated-core guarantee.
3. Retain decoder order even when workers finish out of order. Swapping frame order to raise headline throughput would violate the video-timeline requirement.
4. Apply an exact colour fusion only for adjacent `W → Y → V` or `V → Y → W` built-in processors. `FusedColourRun` combines their pixel traversals while preserving the byte-level operations and rounding in that order. Unsupported combinations and processor subclasses use the original calls. `COMP3011_DISABLE_COLOUR_FUSION=1` disables this optimisation for comparison without changing the CLI specification.
5. Keep performance instrumentation **off by default** so it does not affect TITAN measurements or print extra console output.

### Evidence and test scope

Previous course TITAN runs on an AWS `c7i.2xlarge` (8 vCPUs) demonstrated correct real-time 40-effect playback with zero missed frames in three repeated runs after lowering effect worker priority. In the 60-effect workload, the same runs reported 16.216–18.471 FPS, with 3–8 missed screen-captured frames; one completed reference comparison reported **538.68% performance** relative to the starter player. These are **historical measured runs**, not guaranteed results on every machine. Two runs failed when the reference player's Windows Graphics Capture service timed out; that failure should not be counted as a clean performance measurement. Keep new TITAN reports alongside Git commits when tuning performance.

`mvn verify` runs focused tests of parsing, sequential-versus-parallel effect output, decoder queues, out-of-order worker completion, seeks, cancellation, worker priority, clock transitions and exact fused-pixel behaviour. The optional instrumentation is useful locally:

```powershell
$env:COMP3011_PROFILE_EFFECTS = '1'  # elapsed time by effect, bypasses colour fusion
$env:COMP3011_TRACE_PLAYBACK = '1'    # decoder / render timing
mvn javafx:run '-Djavafx.args=movie.mp4 -sfwyvdjmbppbmjdvywfssfwyvdjmbppbmjdvywfs --number-frames'
Remove-Item Env:COMP3011_PROFILE_EFFECTS -ErrorAction SilentlyContinue
Remove-Item Env:COMP3011_TRACE_PLAYBACK -ErrorAction SilentlyContinue
```

**Benchmark caveat:** The project's self-contained tests can compare a one-worker decoder to a multi-worker decoder using the same synthetic effects workload. That is *not*, by itself, a runnable integration test against the original supplied single-threaded application. A direct original-vs-modified **GUI** benchmark additionally requires the original project's source/build, a compatible media file, a graphical session and a frame-capture harness; it should be run and reported separately. Do not infer a universal speedup from small deterministic or synthetic tests.

## Code navigation

- `CommandLineController`, `FrameProcessorCatalogue`: CLI, options and extensible effect creation.
- `VideoPlayerModel`, `PlaybackClock`: playback lifecycle, JavaFX-thread scheduling, seeking and audio handoff.
- `FrameDecoder`, `MediaSource`, `DecodedItem`: decoder ownership, worker-pool orchestration, safe queues and generation-labelled results.
- `EffectChain`, `FrameEffectsStage`, `FusedColourRun`: effect ordering, one-time initialisation and optional fused path.
- `FrameProcessor` implementations: individually documented effects; any new processor must remain safe when different frames execute concurrently.
- `src/test/java/comp3011`: deterministic regression tests and concurrent-output tests.

When changing the thread model, verify **frame order, visual accuracy, pause/seek behaviour, shutdown and responsiveness while moving/resizing the window**, not only average FPS. A high relative speedup does not excuse torn, out-of-order or unseen frames.
