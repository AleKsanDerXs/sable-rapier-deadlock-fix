package dev.createfix.rapierfix;

import net.neoforged.neoforge.common.ModConfigSpec;

public class Config {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue LOG_PREVENTED_DEADLOCKS = BUILDER
        .comment(
            "If true, logs an INFO line every time this mod prevents a reentrant Rapier",
            "voxel-collider rebuild that would otherwise have deadlocked the server.",
            "Useful for confirming the fix is actually triggering; off by default to avoid",
            "spamming the log during normal play.")
        .define("logPreventedDeadlocks", false);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private Config() {
    }
}
