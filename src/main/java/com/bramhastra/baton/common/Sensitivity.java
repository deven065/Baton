package com.bramhastra.baton.common;

/**
 * Ordered sensitivity levels. The ordinal is what is stored (SMALLINT), so
 * "sensitivity <= clearance" works the same in Java and in SQL.
 */
public enum Sensitivity {
    PUBLIC, INTERNAL, CONFIDENTIAL, RESTRICTED;

    public short level() {
        return (short) ordinal();
    }

    public static Sensitivity ofLevel(int level) {
        return values()[level];
    }

    public boolean atMost(Sensitivity clearance) {
        return this.ordinal() <= clearance.ordinal();
    }

    /** A claim inherits the highest sensitivity of its evidence. */
    public static Sensitivity max(Iterable<Sensitivity> levels) {
        Sensitivity result = PUBLIC;
        for (Sensitivity s : levels) {
            if (s.ordinal() > result.ordinal()) {
                result = s;
            }
        }
        return result;
    }
}
