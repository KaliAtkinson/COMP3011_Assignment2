/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 *
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. Kali Atkinson 2948529
 *
 * Copyright 2026 Simon Ratcliffe
 */
package comp3011;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Checks that every frame processor selectable from the command line can safely be run on many threads at once. The
 * player will process different frames on different worker threads, so the result for a given frame must not depend
 * on what other frames are being processed at the same moment.
 */
class FrameProcessorConcurrencyTest {
    private static final int REPEATS = 3;

    static Stream<Arguments> everyProcessor() {
        return FrameProcessorCatalogue.options().stream()
                .map(option -> Arguments.of(option.longName(), option));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyProcessor")
    @Timeout(120)
    void parallelResultsMatchSequentialResults(String name, FrameProcessorOption option) throws Exception {
        FrameProcessor processor = option.create();
        processor.initialise(FrameTestSupport.infoVideo());

        List<byte[]> reference = FrameTestSupport.processSequentially(processor);

        for (int run = 1; run <= REPEATS; run++) {
            List<byte[]> parallel = FrameTestSupport.processInParallel(processor);
            assertEquals(
                    0,
                    FrameTestSupport.countDifferentFrames(reference, parallel),
                    name + ": frames differed from the sequential result on parallel run " + run);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyProcessor")
    void processorActuallyChangesFrames(String name, FrameProcessorOption option) throws Exception {
        FrameProcessor processor = option.create();
        processor.initialise(FrameTestSupport.infoVideo());

        List<byte[]> processed = FrameTestSupport.processSequentially(processor);

        // Guards against the test above passing only because a processor does nothing at all.
        assertTrue(FrameTestSupport.countModifiedFrames(processed) > 0, name + " never modified any frame");
    }
}
