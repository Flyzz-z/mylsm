package io.github.flyzz.mylsm.lsm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManifestStateTest {
    @Test
    void copiesTheListWithoutSortingItsVersionOrder() {
        List<Long> input = new ArrayList<>(List.of(9L, 4L));
        ManifestState state = new ManifestState(7, 20, 15, 800, input);
        assertEquals(List.of(9L, 4L), input);
        input.clear();
        assertEquals(List.of(9L, 4L), state.sstIds());
        assertThrows(UnsupportedOperationException.class, () -> state.sstIds().add(30L));
        assertEquals(20, state.maxFileId());
        assertEquals(30, new ManifestState(8, 20, 0, 0, List.of(30L, 4L)).maxFileId());
    }

    @Test
    void rejectsNullListsAndEntries() {
        assertThrows(NullPointerException.class, () -> new ManifestState(1, 1, 0, 0, null));
        assertThrows(NullPointerException.class,
                () -> new ManifestState(1, 1, 0, 0, Arrays.asList(2L, null)));
        assertThrows(NullPointerException.class, () -> initial().requireSuccessor(null));
    }

    @ParameterizedTest
    @ValueSource(longs = {Long.MIN_VALUE, -1, 0})
    void rejectsNonPositiveRevisionOrActiveId(long number) {
        assertThrows(IllegalArgumentException.class, () -> new ManifestState(number, 1, 0, 0, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ManifestState(1, number, 0, 0, List.of()));
    }

    @Test
    void validatesFrozenIdentityAndSealedLengthTogether() {
        for (long id : new long[]{Long.MIN_VALUE, -1, 2}) {
            assertThrows(IllegalArgumentException.class, () -> new ManifestState(2, 2, id, 8, List.of()));
        }
        for (long length : new long[]{Long.MIN_VALUE, -1, 1, 8, Long.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> new ManifestState(1, 2, 0, length, List.of()));
        }
        for (long length : new long[]{Long.MIN_VALUE, -1, 0, 7}) {
            assertThrows(IllegalArgumentException.class, () -> new ManifestState(2, 2, 1, length, List.of()));
        }
        assertEquals(8, new ManifestState(2, 2, 1, 8, List.of()).frozenWalLength());
        assertEquals(Long.MAX_VALUE,
                new ManifestState(2, 2, 1, Long.MAX_VALUE, List.of()).frozenWalLength());
    }

    @Test
    void rejectsNonPositiveDuplicateAndOverlappingTableIds() {
        for (List<Long> ids : List.of(List.of(0L), List.of(-1L), List.of(Long.MIN_VALUE),
                List.of(10L), List.of(8L), List.of(3L, 2L, 3L))) {
            assertThrows(IllegalArgumentException.class, () -> new ManifestState(4, 10, 8, 100, ids));
        }
    }

    @Test
    void rejectsTooManyIdsBeforeCopyingOrExaminingThem() {
        // 虚拟列表不分配巨量条目；若未先检查容量，null 将引起不同的异常。
        List<Long> tooMany = Collections.nCopies(ManifestCodec.MAX_SSTABLE_COUNT + 1, null);
        assertThrows(IllegalArgumentException.class, () -> new ManifestState(1, 1, 0, 0, tooMany));
    }

    @Test
    void acceptsOnlyAnEmptyRevisionOneAsInitialState() {
        initial().requireInitial();
        for (ManifestState invalid : List.of(
                new ManifestState(2, 1, 0, 0, List.of()),
                new ManifestState(1, 2, 1, 8, List.of()),
                new ManifestState(1, 1, 0, 0, List.of(3L)))) {
            assertThrows(IllegalArgumentException.class, invalid::requireInitial);
        }
    }

    @Test
    void followsSeveralRotationsAndPublicationsWithGapsInFileIds() {
        List<ManifestState> history = List.of(initial(),
                new ManifestState(2, 5, 1, 12000, List.of()),
                new ManifestState(3, 5, 0, 0, List.of(9L)),
                new ManifestState(4, 12, 5, 9000, List.of(9L)),
                new ManifestState(5, 12, 0, 0, List.of(9L, 20L)));
        history.getFirst().requireInitial();
        for (int i = 1; i < history.size(); i++) {
            history.get(i - 1).requireSuccessor(history.get(i));
        }
        assertEquals(List.of(9L, 20L), history.getLast().sstIds());
    }

    @Test
    void rotationPreservesTablesAndFreezesExactlyThePreviousActiveLog() {
        ManifestState previous = new ManifestState(3, 4, 0, 0, List.of(2L, 3L));
        previous.requireSuccessor(new ManifestState(4, 10, 4, 200, List.of(2L, 3L)));
        for (ManifestState invalid : List.of(
                new ManifestState(4, 10, 0, 0, List.of(2L, 3L)),
                new ManifestState(4, 10, 1, 200, List.of(2L, 3L)),
                new ManifestState(4, 1, 4, 200, List.of(2L, 3L)),
                new ManifestState(4, 10, 4, 200, List.of(2L)),
                new ManifestState(4, 10, 4, 200, List.of(3L, 2L)),
                new ManifestState(4, 10, 4, 200, List.of(2L, 3L, 5L)))) {
            assertThrows(IllegalArgumentException.class, () -> previous.requireSuccessor(invalid));
        }
    }

    @Test
    void publicationPreservesActiveAndTablePrefixWhileReplacingFrozenLog() {
        ManifestState previous = new ManifestState(4, 10, 4, 200, List.of(2L, 3L));
        previous.requireSuccessor(new ManifestState(5, 10, 0, 0, List.of(2L, 3L, 11L)));
        for (ManifestState invalid : List.of(
                new ManifestState(5, 12, 0, 0, List.of(2L, 3L, 11L)),
                new ManifestState(5, 10, 4, 200, List.of(2L, 3L, 11L)),
                new ManifestState(5, 10, 9, 300, List.of(2L, 3L)),
                new ManifestState(5, 10, 0, 0, List.of(2L, 3L)),
                new ManifestState(5, 10, 0, 0, List.of(2L, 3L, 11L, 12L)),
                new ManifestState(5, 10, 0, 0, List.of(3L, 2L, 11L)),
                new ManifestState(5, 10, 0, 0, List.of(2L, 11L)),
                new ManifestState(5, 10, 0, 0, List.of(2L, 3L, 4L)),
                new ManifestState(5, 10, 0, 0, List.of(2L, 3L, 5L)))) {
            assertThrows(IllegalArgumentException.class, () -> previous.requireSuccessor(invalid));
        }
    }

    @Test
    void rejectsReuseOfIdsThatDisappearedFromTheCurrentState() {
        ManifestState rotated = new ManifestState(2, 5, 1, 100, List.of());
        ManifestState published = new ManifestState(3, 5, 0, 0, List.of(9L));
        rotated.requireSuccessor(published);
        assertThrows(IllegalArgumentException.class,
                () -> published.requireSuccessor(new ManifestState(4, 1, 5, 100, List.of(9L))));
        // 大于 active、却小于最新 SSTable 的编号也不能用作新 WAL。
        assertThrows(IllegalArgumentException.class,
                () -> published.requireSuccessor(new ManifestState(4, 8, 5, 100, List.of(9L))));
    }

    @Test
    void rejectsRevisionGapsReplaysAndOverflow() {
        for (long revision : new long[]{1, 3, Long.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class,
                    () -> initial().requireSuccessor(new ManifestState(revision, 2, 1, 8, List.of())));
        }
        ManifestState penultimate = new ManifestState(Long.MAX_VALUE - 1, 1, 0, 0, List.of());
        ManifestState last = new ManifestState(Long.MAX_VALUE, 2, 1, 8, List.of());
        penultimate.requireSuccessor(last);
        assertThrows(IllegalArgumentException.class,
                () -> last.requireSuccessor(new ManifestState(1, 2, 0, 0, List.of(3L))));
    }

    @Test
    void handlesLastPositiveFileIdWithoutWrapping() {
        initial().requireSuccessor(new ManifestState(2, Long.MAX_VALUE, 1, 8, List.of()));
        ManifestState exhausted = new ManifestState(1, Long.MAX_VALUE, 0, 0, List.of());
        assertThrows(IllegalArgumentException.class,
                () -> exhausted.requireSuccessor(new ManifestState(2, 1, Long.MAX_VALUE, 8, List.of())));
        ManifestState frozen = new ManifestState(2, Long.MAX_VALUE, 1, 8, List.of());
        assertThrows(IllegalArgumentException.class,
                () -> frozen.requireSuccessor(new ManifestState(3, Long.MAX_VALUE, 0, 0, List.of(2L))));
    }

    @Test
    void compactionReplacesAllTablesWithoutChangingAnActiveLog() {
        ManifestState before = new ManifestState(5, 4, 0, 0, List.of(3L, 5L));
        ManifestState after = new ManifestState(6, 4, 0, 0, List.of(6L));
        before.requireSuccessor(after);
        assertEquals(List.of(3L, 5L), before.sstIds());
        assertEquals(List.of(6L), after.sstIds());
        assertEquals(6, after.maxFileId());
        // 后续轮换与发布仍可正常发生，较新的 flush 输出追加到压缩输出之后。
        ManifestState rotated = new ManifestState(7, 7, 4, 300, List.of(6L));
        ManifestState published = new ManifestState(8, 7, 0, 0, List.of(6L, 8L));
        after.requireSuccessor(rotated);
        rotated.requireSuccessor(published);
    }

    @Test
    void compactionCanPublishAReservedOutputBelowANewerWalAndThenFlushFrozen() {
        List<ManifestState> history = List.of(initial(),
                new ManifestState(2, 2, 1, 100, List.of()),
                new ManifestState(3, 2, 0, 0, List.of(3L)),
                new ManifestState(4, 4, 2, 200, List.of(3L)),
                new ManifestState(5, 4, 0, 0, List.of(3L, 5L)),
                // 编号 6 在压缩开始时预留；期间前台轮换到新 WAL 7。
                new ManifestState(6, 7, 4, 300, List.of(3L, 5L)),
                new ManifestState(7, 7, 4, 300, List.of(6L)),
                new ManifestState(8, 7, 0, 0, List.of(6L, 8L)),
                new ManifestState(9, 9, 7, 400, List.of(6L, 8L)),
                new ManifestState(10, 9, 7, 400, List.of(10L)),
                new ManifestState(11, 9, 0, 0, List.of(10L, 11L)),
                new ManifestState(12, 9, 0, 0, List.of(12L)));
        history.getFirst().requireInitial();
        for (int i = 1; i < history.size(); i++) {
            ManifestState before = history.get(i - 1);
            ManifestState after = history.get(i);
            before.requireSuccessor(after);
            assertTrue(after.maxFileId() >= before.maxFileId());
        }
        assertEquals(7, history.get(6).maxFileId()); // 此次提交上界不变，无须人为再增加编号。
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void compactionRejectsPartialReplacementAndChangesToAnyWalField(boolean frozen) {
        long frozenId = frozen ? 10 : 0;
        long length = frozen ? 800 : 0;
        ManifestState before = new ManifestState(7, 20, frozenId, length, List.of(3L, 5L));
        List<ManifestState> invalid = new ArrayList<>(List.of(
                new ManifestState(8, 20, frozenId, length, List.of()),
                new ManifestState(8, 20, frozenId, length, List.of(3L)),
                new ManifestState(8, 20, frozenId, length, List.of(4L)),
                new ManifestState(8, 20, frozenId, length, List.of(5L)),
                new ManifestState(8, 20, frozenId, length, List.of(3L, 6L)),
                new ManifestState(8, 20, frozenId, length, List.of(6L, 3L)),
                new ManifestState(8, 20, frozenId, length, List.of(6L, 7L)),
                new ManifestState(8, 21, frozenId, length, List.of(6L))));
        if (frozen) {
            invalid.add(new ManifestState(8, 20, 0, 0, List.of(6L)));
            invalid.add(new ManifestState(8, 20, 11, 800, List.of(6L)));
            invalid.add(new ManifestState(8, 20, 10, 801, List.of(6L)));
        } else {
            invalid.add(new ManifestState(8, 20, 10, 800, List.of(6L)));
        }
        for (ManifestState next : invalid) {
            assertThrows(IllegalArgumentException.class, () -> before.requireSuccessor(next));
        }
        for (long walId : new long[]{20, 10}) {
            // 当前引用的查重先在状态构造器完成，压缩分支不能绕过它。
            if (walId == 10 && !frozen) {
                continue;
            }
            assertThrows(IllegalArgumentException.class,
                    () -> before.requireSuccessor(new ManifestState(8, 20, frozenId, length, List.of(walId))));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void compactionDoesNotAllowAnEmptyOrSingleInputRewrite(int count) {
        List<Long> ids = count == 0 ? List.of() : List.of(3L);
        ManifestState before = new ManifestState(5, 4, 0, 0, ids);
        assertThrows(IllegalArgumentException.class,
                () -> before.requireSuccessor(new ManifestState(6, 4, 0, 0, List.of(6L))));
    }

    @Test
    void compactionUsesTheMaximumInputIdRatherThanTheLastListPosition() {
        ManifestState before = new ManifestState(7, 20, 15, 800, List.of(9L, 4L));
        assertThrows(IllegalArgumentException.class,
                () -> before.requireSuccessor(new ManifestState(8, 20, 15, 800, List.of(8L))));
        before.requireSuccessor(new ManifestState(8, 20, 15, 800, List.of(10L)));
    }

    @Test
    void removedSstAndWalReferencesCannotBeReusedAfterRepeatedCompaction() {
        List<ManifestState> history = List.of(initial(),
                new ManifestState(2, 2, 1, 100, List.of()),
                new ManifestState(3, 2, 0, 0, List.of(3L)),
                new ManifestState(4, 4, 2, 200, List.of(3L)),
                new ManifestState(5, 4, 0, 0, List.of(3L, 5L)),
                new ManifestState(6, 4, 0, 0, List.of(6L)),
                new ManifestState(7, 7, 4, 300, List.of(6L)),
                new ManifestState(8, 7, 0, 0, List.of(6L, 8L)),
                new ManifestState(9, 7, 0, 0, List.of(9L)),
                new ManifestState(10, 10, 7, 400, List.of(9L)),
                new ManifestState(11, 10, 0, 0, List.of(9L, 11L)));
        for (int i = 1; i < history.size(); i++) {
            history.get(i - 1).requireSuccessor(history.get(i));
        }
        ManifestState current = history.getLast();
        for (long id : new long[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11}) {
            assertThrows(IllegalArgumentException.class,
                    () -> current.requireSuccessor(new ManifestState(12, 10, 0, 0, List.of(id))));
        }
        current.requireSuccessor(new ManifestState(12, 10, 0, 0, List.of(12L)));
    }

    @Test
    void compactionKeepsRevisionAndFileIdOverflowChecks() {
        ManifestState before = new ManifestState(Long.MAX_VALUE - 1, 4, 0, 0, List.of(3L, 5L));
        before.requireSuccessor(new ManifestState(Long.MAX_VALUE, 4, 0, 0, List.of(6L)));
        ManifestState last = new ManifestState(Long.MAX_VALUE, 4, 0, 0, List.of(3L, 5L));
        assertThrows(IllegalArgumentException.class,
                () -> last.requireSuccessor(new ManifestState(1, 4, 0, 0, List.of(6L))));
        assertThrows(IllegalArgumentException.class,
                () -> before.requireSuccessor(new ManifestState(Long.MAX_VALUE - 1, 4, 0, 0, List.of(6L))));
        ManifestState ordinary = new ManifestState(5, 4, 0, 0, List.of(3L, 5L));
        ordinary.requireSuccessor(new ManifestState(6, 4, 0, 0, List.of(Long.MAX_VALUE)));
        ManifestState exhausted = new ManifestState(5, 4, 0, 0, List.of(3L, Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class,
                () -> exhausted.requireSuccessor(new ManifestState(6, 4, 0, 0, List.of(Long.MAX_VALUE - 1))));
    }

    @Test
    void anAlreadyReservedOutputCanPublishAfterTheLastWalIdWasAllocated() {
        ManifestState before = new ManifestState(5, 4, 0, 0, List.of(3L, 5L));
        ManifestState rotated = new ManifestState(6, Long.MAX_VALUE, 4, 300, List.of(3L, 5L));
        before.requireSuccessor(rotated);
        ManifestState compacted = new ManifestState(7, Long.MAX_VALUE, 4, 300, List.of(6L));
        rotated.requireSuccessor(compacted);
        assertEquals(Long.MAX_VALUE, compacted.maxFileId());
    }

    private static ManifestState initial() {
        return new ManifestState(1, 1, 0, 0, List.of());
    }
}
