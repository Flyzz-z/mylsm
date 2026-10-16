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

    private static ManifestState initial() {
        return new ManifestState(1, 1, 0, 0, List.of());
    }
}
