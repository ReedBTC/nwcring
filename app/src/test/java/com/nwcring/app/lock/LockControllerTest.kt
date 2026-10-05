package com.nwcring.app.lock

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockControllerTest {
    private var now = 1_000_000L
    private val lock = LockController(nowMs = { now }, idleLimitMs = 60_000)

    @Test fun startsLocked() {
        assertTrue(lock.locked.value)
    }

    @Test fun staysLockedHoweverMuchTimePassesOrIsTouched() {
        lock.touched()
        now += 5_000
        lock.tick()
        assertTrue(lock.locked.value)
    }

    @Test fun unlocksOnlyWhenTold() {
        lock.unlocked()
        assertFalse(lock.locked.value)
    }

    @Test fun locksAfterSixtySecondsWithoutATouch() {
        lock.unlocked()
        now += 59_999
        lock.tick()
        assertFalse(lock.locked.value)
        now += 1
        lock.tick()
        assertTrue(lock.locked.value)
    }

    @Test fun aTouchRestartsTheCountdown() {
        lock.unlocked()
        now += 50_000
        lock.touched()
        now += 50_000
        lock.tick()
        assertFalse(lock.locked.value)
        now += 10_000
        lock.tick()
        assertTrue(lock.locked.value)
    }

    @Test fun locksImmediatelyWhenTold() {
        lock.unlocked()
        lock.lock()
        assertTrue(lock.locked.value)
    }

    @Test fun aTouchWhileLockedDoesNotUnlock() {
        lock.unlocked()
        lock.lock()
        lock.touched()
        lock.tick()
        assertTrue(lock.locked.value)
    }
}
