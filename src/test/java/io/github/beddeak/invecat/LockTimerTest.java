package io.github.beddeak.invecat;

import org.junit.jupiter.api.Test;

import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LockTimerTest {

    @Test
    void firesOncePerInterval() {
        LockTimer timer = new LockTimer(60);
        timer.start();
        for (int second = 1; second < 60; second++) {
            assertFalse(timer.tick(), "second " + second);
        }
        assertTrue(timer.tick());
        assertEquals(60, timer.remaining());
    }

    @Test
    void stoppedOrPausedTimerDoesNotCount() {
        LockTimer timer = new LockTimer(10);
        assertFalse(timer.tick());
        timer.start();
        assertTrue(timer.pause());
        assertFalse(timer.pause());
        for (int i = 0; i < 20; i++) {
            assertFalse(timer.tick());
        }
        assertEquals(10, timer.remaining());
        assertTrue(timer.resume());
        timer.tick();
        assertEquals(9, timer.remaining());
        timer.stop();
        assertFalse(timer.resume());
    }

    @Test
    void addAndSetKeepAtLeastOneSecond() {
        LockTimer timer = new LockTimer(60);
        timer.start();
        timer.add(30);
        assertEquals(90, timer.remaining());
        timer.add(-500);
        assertEquals(1, timer.remaining());
        assertTrue(timer.tick());
    }

    @Test
    void shorterIntervalClampsCurrentCountdown() {
        LockTimer timer = new LockTimer(600);
        timer.start();
        timer.setInterval(30);
        assertEquals(30, timer.remaining());
        timer.setInterval(1);
        assertEquals(LockTimer.MIN_INTERVAL, timer.interval());
    }

    @Test
    void bareNumbersUseTheGivenUnit() {
        assertEquals(OptionalInt.of(600), LockTimer.parseSeconds("10", 60));
        assertEquals(OptionalInt.of(10), LockTimer.parseSeconds("10", 1));
        assertEquals(OptionalInt.of(-30), LockTimer.parseSeconds("-30", 1));
        assertEquals(OptionalInt.of(LockTimer.MAX_SECONDS), LockTimer.parseSeconds("99999", 60));
    }

    @Test
    void unitsWorkInEnglishAndKorean() {
        assertEquals(OptionalInt.of(30), LockTimer.parseSeconds("30s", 60));
        assertEquals(OptionalInt.of(90), LockTimer.parseSeconds("1m30s", 60));
        assertEquals(OptionalInt.of(90), LockTimer.parseSeconds("1분30초", 60));
        assertEquals(OptionalInt.of(5400), LockTimer.parseSeconds("1h30m", 1));
        assertEquals(OptionalInt.of(600), LockTimer.parseSeconds("10분", 1));
    }

    @Test
    void rejectsGarbage() {
        assertTrue(LockTimer.parseSeconds("", 60).isEmpty());
        assertTrue(LockTimer.parseSeconds("abc", 60).isEmpty());
        assertTrue(LockTimer.parseSeconds("10x", 60).isEmpty());
        assertTrue(LockTimer.parseSeconds("-", 60).isEmpty());
    }

    @Test
    void formatsDurations() {
        assertEquals("10분", LockTimer.words(600));
        assertEquals("1시간 2분 3초", LockTimer.words(3723));
        assertEquals("1시간", LockTimer.words(3600));
        assertEquals("0초", LockTimer.words(0));
        assertEquals("1:02:03", LockTimer.clock(3723));
        assertEquals("9:05", LockTimer.clock(545));
    }
}
