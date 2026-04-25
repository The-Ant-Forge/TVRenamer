package org.tvrenamer.controller.subtitle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link SubtitleSwap}.
 *
 * <p>The retry / backoff behaviour of {@link SubtitleSwap#swap(Path, Path)} is
 * exercised via a package-private indirection: tests replace the static
 * {@link SubtitleSwap.MoveOperation} with a fake that fails the first N times,
 * then succeeds (or always fails).  This avoids the brittleness of trying to
 * mock {@code java.nio.file.Files.move} directly.  A cross-volume / atomic-move
 * test is intentionally omitted — the same retry surface is what would be
 * tested there, and we exercise it more thoroughly via the indirection below.
 */
class SubtitleSwapTest {

    @BeforeEach
    void resetMoveOp() {
        SubtitleSwap.resetMoveOperation();
    }

    @AfterEach
    void clearMoveOp() {
        SubtitleSwap.resetMoveOperation();
    }

    // ---------- integrityGate ----------

    @Test
    void integrityGate_tmpLargerThanSrc_returnsTrue(@TempDir Path dir) throws IOException {
        Path src = writeBytes(dir.resolve("src.mkv"), 100);
        Path tmp = writeBytes(dir.resolve("src.mkv.merging.mkv"), 200);
        assertTrue(SubtitleSwap.integrityGate(tmp, src));
    }

    @Test
    void integrityGate_tmpAbove80Percent_returnsTrue(@TempDir Path dir) throws IOException {
        Path src = writeBytes(dir.resolve("src.mkv"), 100);
        Path tmp = writeBytes(dir.resolve("src.mkv.merging.mkv"), 90);
        assertTrue(SubtitleSwap.integrityGate(tmp, src));
    }

    @Test
    void integrityGate_tmpExactly80Percent_returnsTrue(@TempDir Path dir) throws IOException {
        Path src = writeBytes(dir.resolve("src.mkv"), 100);
        Path tmp = writeBytes(dir.resolve("src.mkv.merging.mkv"), 80);
        assertTrue(SubtitleSwap.integrityGate(tmp, src));
    }

    @Test
    void integrityGate_tmpBelow80Percent_returnsFalse(@TempDir Path dir) throws IOException {
        Path src = writeBytes(dir.resolve("src.mkv"), 100);
        Path tmp = writeBytes(dir.resolve("src.mkv.merging.mkv"), 79);
        assertFalse(SubtitleSwap.integrityGate(tmp, src));
    }

    @Test
    void integrityGate_tmpDoesNotExist_returnsFalse(@TempDir Path dir) throws IOException {
        Path src = writeBytes(dir.resolve("src.mkv"), 100);
        Path tmp = dir.resolve("missing.merging.mkv");
        assertFalse(SubtitleSwap.integrityGate(tmp, src));
    }

    @Test
    void integrityGate_bothEmpty_returnsTrue(@TempDir Path dir) throws IOException {
        // Both files are 0 bytes; tmp >= 0.8 * 0 holds trivially, so the gate passes.
        Path src = Files.createFile(dir.resolve("src.mkv"));
        Path tmp = Files.createFile(dir.resolve("src.mkv.merging.mkv"));
        assertTrue(SubtitleSwap.integrityGate(tmp, src));
    }

    @Test
    void integrityGate_srcDoesNotExist_throws(@TempDir Path dir) throws IOException {
        Path src = dir.resolve("missing-src.mkv");
        Path tmp = writeBytes(dir.resolve("src.mkv.merging.mkv"), 50);
        assertThrows(IOException.class, () -> SubtitleSwap.integrityGate(tmp, src));
    }

    // ---------- swap (real filesystem) ----------

    @Test
    void swap_happyPath_movesTmpOverSrc(@TempDir Path dir) throws IOException {
        Path src = dir.resolve("src.mkv");
        Path tmp = dir.resolve("src.mkv.merging.mkv");
        Files.writeString(src, "OLD", StandardCharsets.UTF_8);
        Files.writeString(tmp, "NEW", StandardCharsets.UTF_8);

        assertTrue(SubtitleSwap.swap(tmp, src));

        assertFalse(Files.exists(tmp), "temp file should have been moved away");
        assertTrue(Files.exists(src), "source path should now hold the new content");
        assertEquals("NEW", Files.readString(src, StandardCharsets.UTF_8));
    }

    // ---------- swap (injected indirection: retry behaviour) ----------

    @Test
    void swap_failureThenSuccessOnAttempt2_succeedsAndSleepsAtLeastOnce(@TempDir Path dir)
            throws IOException {
        Path src = dir.resolve("src.mkv");
        Path tmp = dir.resolve("tmp.mkv");
        Files.writeString(tmp, "NEW", StandardCharsets.UTF_8);

        CountingFailingThenRealMove move = new CountingFailingThenRealMove(1);
        SubtitleSwap.setMoveOperation(move);

        long start = System.nanoTime();
        boolean ok = SubtitleSwap.swap(tmp, src);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertTrue(ok, "swap should succeed on attempt 2");
        assertEquals(2, move.callCount());
        assertTrue(elapsedMs >= 100L,
            "should have slept the 100ms first backoff; elapsed=" + elapsedMs);
    }

    @Test
    void swap_failureThenSuccessOnAttempt3_succeedsAndSleepsBothBackoffs(@TempDir Path dir)
            throws IOException {
        Path src = dir.resolve("src.mkv");
        Path tmp = dir.resolve("tmp.mkv");
        Files.writeString(tmp, "NEW", StandardCharsets.UTF_8);

        CountingFailingThenRealMove move = new CountingFailingThenRealMove(2);
        SubtitleSwap.setMoveOperation(move);

        long start = System.nanoTime();
        boolean ok = SubtitleSwap.swap(tmp, src);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertTrue(ok, "swap should succeed on attempt 3");
        assertEquals(3, move.callCount());
        // 100 + 300 = 400 ms minimum (allow no upper bound; CI machines can be slow).
        assertTrue(elapsedMs >= 400L,
            "should have slept both 100ms and 300ms backoffs; elapsed=" + elapsedMs);
    }

    @Test
    void swap_allAttemptsFail_returnsFalseAndPreservesTemp(@TempDir Path dir) throws IOException {
        Path src = dir.resolve("src.mkv");
        Path tmp = dir.resolve("tmp.mkv");
        Files.writeString(tmp, "NEW", StandardCharsets.UTF_8);

        AtomicInteger calls = new AtomicInteger(0);
        SubtitleSwap.setMoveOperation((s, t) -> {
            calls.incrementAndGet();
            throw new IOException("simulated AV lock");
        });

        boolean ok = SubtitleSwap.swap(tmp, src);

        assertFalse(ok, "swap should report failure after exhausting retries");
        assertEquals(3, calls.get(), "fake move op should be invoked exactly 3 times");
        assertTrue(Files.exists(tmp),
            "temp file MUST be preserved on retry exhaustion for manual recovery");
        assertFalse(Files.exists(src),
            "source path should not have been created since every move attempt failed");
    }

    @Test
    void swap_interruptedDuringBackoff_returnsFalseAndRestoresInterruptFlag(@TempDir Path dir)
            throws IOException {
        Path src = dir.resolve("src.mkv");
        Path tmp = dir.resolve("tmp.mkv");
        Files.writeString(tmp, "NEW", StandardCharsets.UTF_8);

        // Always fail, so swap will hit the backoff sleep.  We pre-set the interrupt
        // flag, which makes Thread.sleep throw InterruptedException immediately when
        // swap reaches the first backoff after the first failed attempt.
        SubtitleSwap.setMoveOperation((s, t) -> { throw new IOException("boom"); });

        // Make sure we don't leak the interrupted state into other tests.
        boolean ok;
        boolean stillInterrupted;
        try {
            Thread.currentThread().interrupt();
            ok = SubtitleSwap.swap(tmp, src);
            stillInterrupted = Thread.currentThread().isInterrupted();
        } finally {
            // Clear any residual interrupt regardless of test outcome.
            Thread.interrupted();
        }

        assertFalse(ok, "swap should report failure on interruption");
        assertTrue(stillInterrupted,
            "interrupt flag must be restored after InterruptedException is caught");
    }

    // ---------- computeTimeoutSeconds ----------

    @Test
    void computeTimeoutSeconds_zeroBytes_returnsBase() {
        assertEquals(30, SubtitleSwap.computeTimeoutSeconds(0L));
    }

    @Test
    void computeTimeoutSeconds_negativeBytes_returnsBase() {
        assertEquals(30, SubtitleSwap.computeTimeoutSeconds(-1L));
    }

    @Test
    void computeTimeoutSeconds_oneByte_returnsBase() {
        assertEquals(30, SubtitleSwap.computeTimeoutSeconds(1L));
    }

    @Test
    void computeTimeoutSeconds_justUnderOneMb_returnsBase() {
        assertEquals(30, SubtitleSwap.computeTimeoutSeconds(999_999L));
    }

    @Test
    void computeTimeoutSeconds_oneMb_returnsBasePlusOne() {
        assertEquals(31, SubtitleSwap.computeTimeoutSeconds(1_000_000L));
    }

    @Test
    void computeTimeoutSeconds_fiveMb_returnsBasePlusFive() {
        assertEquals(35, SubtitleSwap.computeTimeoutSeconds(5_000_000L));
    }

    @Test
    void computeTimeoutSeconds_atCap_returnsCap() {
        assertEquals(600, SubtitleSwap.computeTimeoutSeconds(570_000_000L));
    }

    @Test
    void computeTimeoutSeconds_oneGb_returnsCap() {
        assertEquals(600, SubtitleSwap.computeTimeoutSeconds(1_000_000_000L));
    }

    @Test
    void computeTimeoutSeconds_longMaxValue_doesNotOverflow() {
        assertEquals(600, SubtitleSwap.computeTimeoutSeconds(Long.MAX_VALUE));
    }

    // ---------- helpers ----------

    private static Path writeBytes(Path path, int size) throws IOException {
        byte[] payload = new byte[size];
        // Content doesn't matter; the integrity gate only consults size.
        for (int i = 0; i < size; i++) {
            payload[i] = (byte) (i & 0xFF);
        }
        Files.write(path, payload);
        return path;
    }

    /**
     * Test fake: throws {@link IOException} the first {@code failuresBeforeSuccess}
     * times, then delegates to a real {@link Files#move} so we can assert the file
     * actually moved on the successful attempt.
     */
    private static final class CountingFailingThenRealMove implements SubtitleSwap.MoveOperation {
        private final int failuresBeforeSuccess;
        private int calls;

        CountingFailingThenRealMove(int failuresBeforeSuccess) {
            this.failuresBeforeSuccess = failuresBeforeSuccess;
        }

        int callCount() {
            return calls;
        }

        @Override
        public void move(Path source, Path target) throws IOException {
            calls++;
            if (calls <= failuresBeforeSuccess) {
                throw new IOException("simulated transient failure #" + calls);
            }
            // Real move on the successful attempt.
            if (!Files.exists(source)) {
                throw new NoSuchFileException(source.toString());
            }
            SubtitleSwap.REAL_MOVE.move(source, target);
        }
    }
}
