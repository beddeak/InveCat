package io.github.beddeak.invecat;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SharedSlotsTest {

    record Stack(String id, int amount) {
    }

    private static final SharedSlots.Stacks<Stack> STACKS = new SharedSlots.Stacks<>() {
        @Override
        public boolean similar(Stack a, Stack b) {
            return a.id().equals(b.id());
        }

        @Override
        public boolean sameKind(Stack a, Stack b) {
            return kind(a).equals(kind(b));
        }

        @Override
        public int amount(Stack stack) {
            return stack.amount();
        }

        @Override
        public int max(Stack stack) {
            return kind(stack).equals("sword") || kind(stack).equals("pickaxe") ? 1 : 64;
        }

        /** "pickaxe#5" is a pickaxe with 5 damage. */
        private static String kind(Stack stack) {
            int data = stack.id().indexOf('#');
            return data < 0 ? stack.id() : stack.id().substring(0, data);
        }

        @Override
        public Stack withAmount(Stack stack, int amount) {
            return new Stack(stack.id(), amount);
        }
    };

    private static SharedSlots<Stack> slots() {
        return new SharedSlots<>(STACKS);
    }

    private static Stack cobble(int amount) {
        return new Stack("cobble", amount);
    }

    private static List<Stack> players(Stack... stacks) {
        return Arrays.asList(stacks);
    }

    @Test
    void unchangedSlotStaysAsIs() {
        SharedSlots<Stack> slots = slots();
        slots.set(0, cobble(10));
        assertTrue(slots.merge(0, players(cobble(10), cobble(10))).isEmpty());
        assertEquals(cobble(10), slots.get(0));
    }

    @Test
    void amountChangesFromSeveralPlayersAddUp() {
        SharedSlots<Stack> slots = slots();
        slots.set(0, cobble(10));
        assertTrue(slots.merge(0, players(cobble(11), cobble(8), cobble(10))).isEmpty());
        assertEquals(cobble(9), slots.get(0));
    }

    @Test
    void usingUpTheStackEmptiesTheSlot() {
        SharedSlots<Stack> slots = slots();
        slots.set(3, cobble(2));
        slots.merge(3, players(null, cobble(1)));
        assertNull(slots.get(3));
    }

    @Test
    void twoPickupsIntoAnEmptySlotBothCount() {
        SharedSlots<Stack> slots = slots();
        assertTrue(slots.merge(5, players(cobble(1), cobble(2))).isEmpty());
        assertEquals(cobble(3), slots.get(5));
    }

    @Test
    void differentItemsInTheSameSlotComeBackAsLeftovers() {
        SharedSlots<Stack> slots = slots();
        Stack sand = new Stack("sand", 5);
        List<SharedSlots.Leftover<Stack>> leftovers = slots.merge(5, players(new Stack("dirt", 3), sand));
        assertEquals(new Stack("dirt", 3), slots.get(5));
        assertEquals(List.of(new SharedSlots.Leftover<>(1, sand)), leftovers);
    }

    @Test
    void amountOverTheStackLimitComesBackToWhoeverGainedIt() {
        SharedSlots<Stack> slots = slots();
        slots.set(0, cobble(63));
        List<SharedSlots.Leftover<Stack>> leftovers = slots.merge(0, players(cobble(64), cobble(64)));
        assertEquals(cobble(64), slots.get(0));
        assertEquals(List.of(new SharedSlots.Leftover<>(0, cobble(1))), leftovers);
    }

    @Test
    void replacingTheStackKeepsWhatOthersAddedToIt() {
        SharedSlots<Stack> slots = slots();
        slots.set(0, cobble(10));
        Stack diamond = new Stack("diamond", 3);
        List<SharedSlots.Leftover<Stack>> leftovers = slots.merge(0, players(diamond, cobble(12)));
        assertEquals(diamond, slots.get(0));
        assertEquals(List.of(new SharedSlots.Leftover<>(1, cobble(2))), leftovers);
    }

    @Test
    void twoPlayersUsingOneToolInTheSameTickDoNotDuplicateIt() {
        SharedSlots<Stack> slots = slots();
        slots.set(0, new Stack("pickaxe#5", 1));
        assertTrue(slots.merge(0, players(new Stack("pickaxe#6", 1), new Stack("pickaxe#6", 1))).isEmpty());
        assertEquals(new Stack("pickaxe#6", 1), slots.get(0));
    }

    @Test
    void differentEditsOfOneItemKeepTheFirstAndDropNothing() {
        SharedSlots<Stack> slots = slots();
        slots.set(38, new Stack("chestplate#5", 1));
        assertTrue(slots.merge(38, players(new Stack("chestplate#6", 1), new Stack("chestplate#8", 1))).isEmpty());
        assertEquals(new Stack("chestplate#6", 1), slots.get(38));
    }

    @Test
    void droppingAToolBeatsSomeoneElseUsingIt() {
        SharedSlots<Stack> slots = slots();
        slots.set(0, new Stack("pickaxe#5", 1));
        assertTrue(slots.merge(0, players(null, new Stack("pickaxe#6", 1))).isEmpty());
        assertNull(slots.get(0));
    }

    @Test
    void swappingAToolOutBeatsSomeoneElseUsingIt() {
        SharedSlots<Stack> slots = slots();
        slots.set(0, new Stack("pickaxe#5", 1));
        Stack dirt = new Stack("dirt", 4);
        assertTrue(slots.merge(0, players(new Stack("pickaxe#6", 1), dirt)).isEmpty());
        assertEquals(dirt, slots.get(0));
    }

    @Test
    void theSameNewItemOverAnOldOneIsCountedOnce() {
        SharedSlots<Stack> slots = slots();
        slots.set(0, cobble(10));
        Stack dirt = new Stack("dirt", 4);
        assertTrue(slots.merge(0, players(dirt, dirt)).isEmpty());
        assertEquals(dirt, slots.get(0));
    }

    @Test
    void insertTopsUpStacksBeforeUsingEmptySlots() {
        SharedSlots<Stack> slots = slots();
        slots.set(0, new Stack("dirt", 1));
        slots.set(4, cobble(60));
        slots.lock(1);
        assertNull(slots.insert(cobble(10)));
        assertEquals(cobble(64), slots.get(4));
        assertEquals(cobble(6), slots.get(2));
        assertNull(slots.get(1));
    }

    @Test
    void insertNeverUsesLockedSlotsOrArmorAndReturnsTheRest() {
        SharedSlots<Stack> slots = slots();
        for (int slot = 1; slot < SharedSlots.STORAGE; slot++) {
            slots.lock(slot);
        }
        Stack rest = slots.insert(new Stack("sword", 3));
        assertEquals(new Stack("sword", 1), slots.get(SharedSlots.KEPT));
        assertEquals(new Stack("sword", 2), rest);
        for (int slot = SharedSlots.STORAGE; slot < SharedSlots.SIZE; slot++) {
            assertNull(slots.get(slot));
        }
    }

    @Test
    void lockTakesTheItemOutAndUnlockGoesNewestFirst() {
        SharedSlots<Stack> slots = slots();
        slots.set(5, cobble(7));
        assertEquals(cobble(7), slots.lock(5));
        assertNull(slots.get(5));
        assertTrue(slots.isLocked(5));
        slots.set(5, cobble(1));
        assertNull(slots.get(5), "a locked slot stays empty");
        assertNull(slots.lock(5), "locking twice does nothing");
        slots.lock(7);
        assertEquals(List.of(5, 7), slots.lockedSlots());
        assertEquals(SharedSlots.SIZE - 2, slots.openCount());
        assertEquals(7, slots.unlockLast());
        assertEquals(5, slots.unlockLast());
        assertEquals(-1, slots.unlockLast());
        assertFalse(slots.isLocked(5));
    }

    @Test
    void armorAndOffHandCanBeLockedButHotbarOneCannot() {
        SharedSlots<Stack> slots = slots();
        slots.set(40, new Stack("shield", 1));
        slots.set(38, new Stack("chestplate", 1));
        slots.set(SharedSlots.KEPT, cobble(3));
        assertEquals(new Stack("shield", 1), slots.lock(40));
        assertEquals(new Stack("chestplate", 1), slots.lock(38));
        assertTrue(slots.isLocked(40));
        assertNull(slots.get(38));
        assertNull(slots.lock(SharedSlots.KEPT));
        assertFalse(slots.isLocked(SharedSlots.KEPT));
        assertEquals(cobble(3), slots.get(SharedSlots.KEPT));
    }

    @Test
    void pickNextPicksEverySlotButHotbarOneThenStops() {
        SharedSlots<Stack> slots = slots();
        Random random = new Random(1);
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < SharedSlots.LOCKABLE; i++) {
            int slot = slots.pickNext(random, false);
            assertTrue(slot > SharedSlots.KEPT && slot < SharedSlots.SIZE, "bad slot: " + slot);
            assertTrue(seen.add(slot), "picked a locked slot: " + slot);
            slots.lock(slot);
        }
        assertEquals(-1, slots.pickNext(random, false));
        assertEquals(0, slots.lockableCount());
        assertEquals(1, slots.openCount());
        assertFalse(slots.isLocked(SharedSlots.KEPT));
    }

    @Test
    void hotbarLastSavesTheHotbarForTheEnd() {
        SharedSlots<Stack> slots = slots();
        Random random = new Random(2);
        for (int i = 0; i < SharedSlots.SIZE - SharedSlots.HOTBAR; i++) {
            int slot = slots.pickNext(random, true);
            assertTrue(slot >= SharedSlots.HOTBAR, "hotbar slot picked too early: " + slot);
            slots.lock(slot);
        }
        for (int i = 1; i < SharedSlots.HOTBAR; i++) {
            int slot = slots.pickNext(random, true);
            assertTrue(slot > SharedSlots.KEPT && slot < SharedSlots.HOTBAR, "bad slot: " + slot);
            slots.lock(slot);
        }
        assertEquals(-1, slots.pickNext(random, true));
    }

    @Test
    void restoreBringsBackItemsAndLocks() {
        SharedSlots<Stack> slots = slots();
        List<Stack> saved = new ArrayList<>(Collections.nCopies(SharedSlots.SIZE, null));
        saved.set(2, cobble(5));
        saved.set(3, cobble(9));
        saved.set(39, new Stack("helmet", 1));
        slots.restore(saved, List.of(3, 4));
        assertEquals(cobble(5), slots.get(2));
        assertNull(slots.get(3));
        assertEquals(new Stack("helmet", 1), slots.get(39));
        assertEquals(List.of(3, 4), slots.lockedSlots());
    }

    @Test
    void clearItemsKeepsLocks() {
        SharedSlots<Stack> slots = slots();
        slots.set(1, cobble(1));
        slots.set(40, new Stack("shield", 1));
        slots.lock(2);
        slots.clearItems();
        assertNull(slots.get(1));
        assertNull(slots.get(40));
        assertTrue(slots.isLocked(2));
        slots.reset();
        assertFalse(slots.isLocked(2));
    }
}
