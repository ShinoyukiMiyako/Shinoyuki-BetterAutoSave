package com.shinoyuki.betterautosave.core.scheduler;

public final class AdaptiveThrottle {

    public static final double TICK_HALVE_THRESHOLD_MS = 51.3;
    public static final double TICK_SKIP_THRESHOLD_MS = 52.6;
    public static final int CONFIRMATION_TICKS = 3;

    private final int baseBudget;
    private final int deadlineGuardSeconds;

    private int committedBudget;
    private int desiredBudget;
    private int confirmationCounter;

    public AdaptiveThrottle(int baseBudget, int deadlineGuardSeconds) {
        if (baseBudget < 1) {
            throw new IllegalArgumentException("baseBudget must be >= 1");
        }
        this.baseBudget = baseBudget;
        this.deadlineGuardSeconds = deadlineGuardSeconds;
        this.committedBudget = baseBudget;
        this.desiredBudget = baseBudget;
    }

    public int adjust(double avgTickMs, int remainingSecondsInCycle, boolean adaptiveEnabled, boolean shutdownMode) {
        if (shutdownMode) {
            committedBudget = Integer.MAX_VALUE;
            return committedBudget;
        }
        if (remainingSecondsInCycle >= 0 && remainingSecondsInCycle < deadlineGuardSeconds) {
            committedBudget = baseBudget * 4;
            confirmationCounter = 0;
            return committedBudget;
        }
        if (!adaptiveEnabled) {
            committedBudget = baseBudget;
            confirmationCounter = 0;
            return committedBudget;
        }

        int targetDesired;
        if (avgTickMs <= TICK_HALVE_THRESHOLD_MS) {
            targetDesired = baseBudget;
        } else if (avgTickMs <= TICK_SKIP_THRESHOLD_MS) {
            targetDesired = Math.max(1, baseBudget / 2);
        } else {
            targetDesired = 0;
        }

        if (targetDesired == committedBudget) {
            confirmationCounter = 0;
            desiredBudget = committedBudget;
            return committedBudget;
        }

        if (targetDesired != desiredBudget) {
            desiredBudget = targetDesired;
            confirmationCounter = 1;
            return committedBudget;
        }

        if (++confirmationCounter >= CONFIRMATION_TICKS) {
            committedBudget = desiredBudget;
            confirmationCounter = 0;
        }
        return committedBudget;
    }

    public int committedBudget() {
        return committedBudget;
    }

    public int baseBudget() {
        return baseBudget;
    }
}
