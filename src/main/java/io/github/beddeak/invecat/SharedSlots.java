package io.github.beddeak.invecat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * The one inventory every participant shares, indexed like {@code PlayerInventory#setItem(int, ItemStack)}:
 * 0-8 hotbar, 9-35 the rows above it, 36-39 armor, 40 off hand. Every slot except {@link #KEPT} can be locked.
 */
final class SharedSlots<T> {

    static final int HOTBAR = 9;
    static final int STORAGE = 36;
    static final int SIZE = 41;
    /** Hotbar slot 1 never disappears, so there is always one slot left. */
    static final int KEPT = 0;
    static final int LOCKABLE = SIZE - 1;

    interface Stacks<T> {
        /** Same item and data, ignoring the amount. */
        boolean similar(T a, T b);

        /** Same item type; the data (durability, bundle contents, ...) may differ. */
        boolean sameKind(T a, T b);

        int amount(T stack);

        int max(T stack);

        T withAmount(T stack, int amount);
    }

    /** Items that no longer fit where a player put them. {@code owner} is that player's index, or -1. */
    record Leftover<T>(int owner, T stack) {
    }

    private final Stacks<T> stacks;
    private final List<T> items = new ArrayList<>(Collections.nCopies(SIZE, null));
    private final boolean[] locked = new boolean[SIZE];
    private final List<Integer> lockOrder = new ArrayList<>();

    SharedSlots(Stacks<T> stacks) {
        this.stacks = stacks;
    }

    T get(int slot) {
        return items.get(slot);
    }

    void set(int slot, T stack) {
        items.set(slot, locked[slot] ? null : stack);
    }

    boolean isLocked(int slot) {
        return locked[slot];
    }

    List<Integer> lockedSlots() {
        return List.copyOf(lockOrder);
    }

    int openCount() {
        return SIZE - lockOrder.size();
    }

    /** How many slots can still be locked. */
    int lockableCount() {
        return LOCKABLE - lockOrder.size();
    }

    boolean same(T a, T b) {
        if (a == null || b == null) {
            return a == b;
        }
        return stacks.amount(a) == stacks.amount(b) && stacks.similar(a, b);
    }

    /** Empties every slot and unlocks them all. */
    void reset() {
        Collections.fill(items, null);
        Arrays.fill(locked, false);
        lockOrder.clear();
    }

    /** Empties every slot that is not locked. */
    void clearItems() {
        Collections.fill(items, null);
    }

    /** Locks {@code slot} and returns what was in it. */
    T lock(int slot) {
        if (slot < 0 || slot >= SIZE || slot == KEPT || locked[slot]) {
            return null;
        }
        T removed = items.get(slot);
        items.set(slot, null);
        locked[slot] = true;
        lockOrder.add(slot);
        return removed;
    }

    /** Unlocks the most recently locked slot and returns it, or -1 when nothing is locked. */
    int unlockLast() {
        if (lockOrder.isEmpty()) {
            return -1;
        }
        int slot = lockOrder.removeLast();
        locked[slot] = false;
        return slot;
    }

    /** Picks a random lockable open slot, or -1 when none is left. {@code hotbarLast} saves the hotbar for the end. */
    int pickNext(Random random, boolean hotbarLast) {
        List<Integer> open = new ArrayList<>();
        for (int slot = hotbarLast ? HOTBAR : 0; slot < SIZE; slot++) {
            if (slot != KEPT && !locked[slot]) {
                open.add(slot);
            }
        }
        if (open.isEmpty() && hotbarLast) {
            for (int slot = 0; slot < HOTBAR; slot++) {
                if (slot != KEPT && !locked[slot]) {
                    open.add(slot);
                }
            }
        }
        return open.isEmpty() ? -1 : open.get(random.nextInt(open.size()));
    }

    /**
     * Decides {@code slot} from what each player now holds there.
     * <ul>
     * <li>Amount changes to the same item add up, so two players using or picking up the same stack in one tick
     * both count.</li>
     * <li>The same item type with new data (a tool that took damage, a filled bundle) is an edit of the one shared
     * item: the first edit wins and edits never add up, or two players mining with one pickaxe would make two.</li>
     * <li>A different item replaces the slot. Items several players dropped into an empty slot add up; another
     * player's different item and anything over the stack limit come back as leftovers.</li>
     * </ul>
     */
    List<Leftover<T>> merge(int slot, List<T> current) {
        T base = items.get(slot);
        T replacement = null;
        int replacementOwner = -1;
        int replacementAmount = 0;
        T edited = null;
        int delta = 0;
        List<Leftover<T>> gains = new ArrayList<>();
        List<Leftover<T>> leftovers = new ArrayList<>();
        for (int owner = 0; owner < current.size(); owner++) {
            T now = current.get(owner);
            if (same(now, base)) {
                continue;
            }
            if (base != null && (now == null || stacks.similar(now, base))) {
                int change = (now == null ? 0 : stacks.amount(now)) - stacks.amount(base);
                delta += change;
                if (change > 0) {
                    gains.add(new Leftover<>(owner, stacks.withAmount(base, change)));
                }
            } else if (base != null && stacks.sameKind(now, base)) {
                if (edited == null) {
                    edited = now;
                }
            } else if (replacement == null) {
                replacement = now;
                replacementOwner = owner;
                replacementAmount = stacks.amount(now);
            } else if (stacks.similar(now, replacement)) {
                if (base == null) {
                    replacementAmount += stacks.amount(now);
                }
            } else {
                leftovers.add(new Leftover<>(owner, now));
            }
        }
        int gainer = gains.isEmpty() ? -1 : gains.getFirst().owner();
        if (replacement != null) {
            leftovers.addAll(gains);
            items.set(slot, fit(replacement, replacementAmount, replacementOwner, leftovers));
        } else if (edited != null) {
            int total = stacks.amount(edited) + delta;
            items.set(slot, total <= 0 ? null : fit(edited, total, gainer, leftovers));
        } else if (base != null) {
            int total = stacks.amount(base) + delta;
            items.set(slot, total <= 0 ? null : fit(base, total, gainer, leftovers));
        }
        return leftovers;
    }

    /** Stacks {@code stack} into open storage slots and returns what did not fit, or null. */
    T insert(T stack) {
        int left = stacks.amount(stack);
        for (int slot = 0; slot < STORAGE && left > 0; slot++) {
            T there = items.get(slot);
            if (locked[slot] || there == null || !stacks.similar(there, stack)) {
                continue;
            }
            int moved = Math.min(stacks.max(there) - stacks.amount(there), left);
            if (moved > 0) {
                items.set(slot, stacks.withAmount(there, stacks.amount(there) + moved));
                left -= moved;
            }
        }
        for (int slot = 0; slot < STORAGE && left > 0; slot++) {
            if (locked[slot] || items.get(slot) != null) {
                continue;
            }
            int moved = Math.min(stacks.max(stack), left);
            items.set(slot, stacks.withAmount(stack, moved));
            left -= moved;
        }
        return left <= 0 ? null : stacks.withAmount(stack, left);
    }

    /** Restores saved state. {@code saved} may be shorter than {@link #SIZE}; null entries are empty. */
    void restore(List<T> saved, List<Integer> lockedSlots) {
        reset();
        for (int slot = 0; slot < Math.min(saved.size(), SIZE); slot++) {
            items.set(slot, saved.get(slot));
        }
        for (int slot : lockedSlots) {
            lock(slot);
        }
    }

    private T fit(T stack, int amount, int owner, List<Leftover<T>> leftovers) {
        int cap = Math.max(stacks.max(stack), stacks.amount(stack));
        if (amount > cap) {
            leftovers.add(new Leftover<>(owner, stacks.withAmount(stack, amount - cap)));
            amount = cap;
        }
        return amount == stacks.amount(stack) ? stack : stacks.withAmount(stack, amount);
    }
}
