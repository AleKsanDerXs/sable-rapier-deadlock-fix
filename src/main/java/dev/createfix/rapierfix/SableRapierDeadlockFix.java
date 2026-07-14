package dev.createfix.rapierfix;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;

@Mod(SableRapierDeadlockFix.MOD_ID)
public class SableRapierDeadlockFix {
    public static final String MOD_ID = "sable_rapier_deadlock_fix";

    public SableRapierDeadlockFix(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }
}
