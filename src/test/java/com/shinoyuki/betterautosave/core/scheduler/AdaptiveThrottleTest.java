package com.shinoyuki.betterautosave.core.scheduler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdaptiveThrottleTest {

    private static final int LARGE_REMAINING_SECONDS = 9999;

    @Test
    void shutdown_mode_returns_unbounded_budget() {
        AdaptiveThrottle t = new AdaptiveThrottle(4, 30);
        int budget = t.adjust(120.0, 0, true, true);
        assertEquals(Integer.MAX_VALUE, budget,
                "Shutdown drains everything regardless of TPS or remaining time");
    }

    @Test
    void deadline_guard_quadruples_base_when_under_threshold() {
        AdaptiveThrottle t = new AdaptiveThrottle(4, 30);
        int budget = t.adjust(45.0, 25, true, false);
        assertEquals(16, budget,
                "Within deadline guard window we accelerate to base*4");
    }

    @Test
    void normal_tps_yields_base_budget() {
        AdaptiveThrottle t = new AdaptiveThrottle(4, 30);
        int budget = t.adjust(45.0, LARGE_REMAINING_SECONDS, true, false);
        assertEquals(4, budget);
    }

    @Test
    void slightly_low_tps_halves_after_three_confirmations() {
        AdaptiveThrottle t = new AdaptiveThrottle(8, 30);
        assertEquals(8, t.adjust(45.0, LARGE_REMAINING_SECONDS, true, false));

        assertEquals(8, t.adjust(52.0, LARGE_REMAINING_SECONDS, true, false));
        assertEquals(8, t.adjust(52.0, LARGE_REMAINING_SECONDS, true, false));
        int afterConfirm = t.adjust(52.0, LARGE_REMAINING_SECONDS, true, false);
        assertEquals(4, afterConfirm,
                "Three consecutive ticks at TPS<19.5 must halve the budget");
    }

    @Test
    void severely_low_tps_drops_to_zero_after_three_confirmations() {
        AdaptiveThrottle t = new AdaptiveThrottle(4, 30);
        assertEquals(4, t.adjust(45.0, LARGE_REMAINING_SECONDS, true, false));

        assertEquals(4, t.adjust(80.0, LARGE_REMAINING_SECONDS, true, false));
        assertEquals(4, t.adjust(80.0, LARGE_REMAINING_SECONDS, true, false));
        int afterConfirm = t.adjust(80.0, LARGE_REMAINING_SECONDS, true, false);
        assertEquals(0, afterConfirm,
                "Severe TPS drop should zero the budget after confirmation window");
    }

    @Test
    void single_tick_spike_does_not_change_committed_budget() {
        AdaptiveThrottle t = new AdaptiveThrottle(4, 30);
        assertEquals(4, t.adjust(45.0, LARGE_REMAINING_SECONDS, true, false));
        int duringSpike = t.adjust(80.0, LARGE_REMAINING_SECONDS, true, false);
        int afterRecovery = t.adjust(45.0, LARGE_REMAINING_SECONDS, true, false);

        assertEquals(4, duringSpike,
                "A single GC pause spike must not immediately collapse the committed budget");
        assertEquals(4, afterRecovery);
    }

    @Test
    void adaptive_disabled_keeps_base_budget_regardless_of_tps() {
        AdaptiveThrottle t = new AdaptiveThrottle(4, 30);
        int budget = t.adjust(120.0, LARGE_REMAINING_SECONDS, false, false);
        assertEquals(4, budget);
    }

    @Test
    void boundary_at_51_3_ms_keeps_full_budget() {
        AdaptiveThrottle t = new AdaptiveThrottle(4, 30);
        int budget = t.adjust(51.3, LARGE_REMAINING_SECONDS, true, false);
        assertEquals(4, budget);
    }

    @Test
    void boundary_just_above_skip_threshold_eventually_skips() {
        AdaptiveThrottle t = new AdaptiveThrottle(4, 30);
        t.adjust(45.0, LARGE_REMAINING_SECONDS, true, false);
        t.adjust(53.0, LARGE_REMAINING_SECONDS, true, false);
        t.adjust(53.0, LARGE_REMAINING_SECONDS, true, false);
        int afterConfirm = t.adjust(53.0, LARGE_REMAINING_SECONDS, true, false);
        assertTrue(afterConfirm == 0,
                "avgTickMs above the skip threshold for three consecutive ticks must zero the budget");
    }
}
