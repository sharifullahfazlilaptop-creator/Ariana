package com.ariana.learnenglish

import org.junit.Assert.assertEquals
import org.junit.Test

class ProgressMathTest {
    @Test fun masteryMovesWithinBounds() {
        assertEquals(60, nextMastery(50, true))
        assertEquals(38, nextMastery(50, false))
        assertEquals(100, nextMastery(96, true))
        assertEquals(0, nextMastery(5, false))
    }
}
