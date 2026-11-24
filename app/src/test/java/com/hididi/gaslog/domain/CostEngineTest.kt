package com.hididi.gaslog.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CostEngineTest {

    private val tiers = listOf(
        TierDef(1, 300.0, 278),
        TierDef(2, 540.0, 306),
        TierDef(3, null, 361),
    )

    @Test
    fun `no volume costs nothing`() {
        val q = CostEngine.quote(CostEngine.initialState(tiers), tiers, 0.0)
        assertEquals(0L, q.totalCents)
        assertTrue(q.lines.isEmpty())
    }

    @Test
    fun `within first tier uses first price`() {
        val q = CostEngine.quote(CostEngine.initialState(tiers), tiers, 100.0)
        assertEquals(1, q.lines.size)
        assertEquals(27800L, q.totalCents)
        assertEquals(200.0, q.endState!!.remainingM3, 1e-9)
        assertEquals(1, q.endState!!.tierNo)
    }

    @Test
    fun `crossing tier boundary charges each segment`() {
        val q = CostEngine.quote(CostEngine.initialState(tiers), tiers, 350.0)
        // 300 × 2.78 = 834.00 元，50 × 3.06 = 153.00 元
        assertEquals(98700L, q.totalCents)
        assertEquals(2, q.lines.size)
        assertEquals(300.0, q.lines[0].volumeM3, 1e-9)
        assertEquals(50.0, q.lines[1].volumeM3, 1e-9)
        assertEquals(190.0, q.endState!!.remainingM3, 1e-9)
        assertEquals(2, q.endState!!.tierNo)
    }

    @Test
    fun `manual state is respected`() {
        val state = CostEngine.stateFromManual(1, 10.0, tiers)
        assertNotNull(state)
        val q = CostEngine.quote(state, tiers, 500.0)
        // 10 × 2.78 + 240 × 3.06 + 250 × 3.61
        assertEquals(2780L + 73440L + 90250L, q.totalCents)
        assertEquals(3, q.lines.size)
        assertTrue(q.endState!!.unlimited)
    }

    @Test
    fun `state from annual usage matches tier position`() {
        val s1 = CostEngine.stateFromAnnualUsage(120.0, tiers)!!
        assertEquals(1, s1.tierNo)
        assertEquals(180.0, s1.remainingM3, 1e-9)

        val s2 = CostEngine.stateFromAnnualUsage(310.0, tiers)!!
        assertEquals(2, s2.tierNo)
        assertEquals(230.0, s2.remainingM3, 1e-9)

        val s3 = CostEngine.stateFromAnnualUsage(700.0, tiers)!!
        assertEquals(3, s3.tierNo)
        assertTrue(s3.unlimited)
    }

    @Test
    fun `annual usage derived from state`() {
        assertEquals(120.0, CostEngine.annualUsageFromState(TierState(1, 180.0), tiers)!!, 1e-9)
        assertEquals(310.0, CostEngine.annualUsageFromState(TierState(2, 230.0), tiers)!!, 1e-9)
        assertNull(CostEngine.annualUsageFromState(TierState(3, Double.MAX_VALUE, true), tiers))
    }

    @Test
    fun `days to next tier from current state`() {
        assertEquals(10, CostEngine.daysToNextTier(TierState(1, 20.0), tiers, 2.0))
        assertNull(CostEngine.daysToNextTier(TierState(3, Double.MAX_VALUE, true), tiers, 2.0))
    }

    @Test
    fun `empty tiers produce warning instead of crash`() {
        val q = CostEngine.quote(null, emptyList(), 10.0)
        assertEquals(0L, q.totalCents)
        assertTrue(q.warnings.isNotEmpty())
    }
}