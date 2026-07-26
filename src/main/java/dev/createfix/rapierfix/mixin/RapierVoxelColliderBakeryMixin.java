package dev.createfix.rapierfix.mixin;

import dev.ryanhcode.sable.physics.impl.rapier.collider.RapierVoxelColliderBakery;
import dev.ryanhcode.sable.physics.impl.rapier.collider.RapierVoxelColliderData;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Replaces Sable 2.0.3's Util.memoize-backed collider lookup with a
 * recursion-safe cache.
 *
 * Sable's original RapierVoxelColliderBakery stores
 * buildPhysicsDataForBlock() behind Minecraft Util.memoize(). That memoizer
 * uses a compute-if-absent style critical section. Some modded collision-shape
 * implementations can cause Sable to request collider data recursively while
 * the first collider for the same BlockState is still being built. The
 * original memoizer then waits/re-enters its own in-progress entry, leaving
 * the server thread stuck in Util$8.apply until ServerHangWatchdog kills it.
 *
 * This mixin cancels getPhysicsDataForBlock() at HEAD and performs the same
 * cache operation without computeIfAbsent:
 *  - completed entries are cached per RapierVoxelColliderBakery instance;
 *  - the expensive build happens outside ConcurrentHashMap internals;
 *  - a per-thread identity set detects A -> ... -> A recursive collider bakes;
 *  - only the nested recursive request is treated as empty; the outer build
 *    continues and its completed collider is cached normally.
 *
 * The patch is intentionally isolated from RapierPhysicsPipelineMixin and the
 * other existing fixes in this mod.
 */
@Mixin(value = RapierVoxelColliderBakery.class, priority = 2000, remap = false)
public abstract class RapierVoxelColliderBakeryMixin {

    @Unique
    private static final Logger createfix$COLLIDER_LOGGER =
        LoggerFactory.getLogger("sable_rapier_deadlock_fix");

    /** Published last by createfix$ensureColliderGuardState(). */
    @Unique
    private volatile ConcurrentMap<BlockState, RapierVoxelColliderData> createfix$safeColliderCache;

    @Unique
    private ThreadLocal<Set<BlockState>> createfix$activeColliderBuilds;

    @Unique
    private Set<BlockState> createfix$reportedRecursiveStates;

    @Invoker("buildPhysicsDataForBlock")
    protected abstract RapierVoxelColliderData createfix$invokeBuildPhysicsDataForBlock(BlockState state);

    @Unique
    private void createfix$ensureColliderGuardState() {
        if (this.createfix$safeColliderCache != null) {
            return;
        }

        synchronized (this) {
            if (this.createfix$safeColliderCache != null) {
                return;
            }

            this.createfix$activeColliderBuilds = ThreadLocal.withInitial(
                () -> Collections.newSetFromMap(new IdentityHashMap<>())
            );
            this.createfix$reportedRecursiveStates = ConcurrentHashMap.newKeySet();

            // Volatile write publishes all fields initialized above.
            this.createfix$safeColliderCache = new ConcurrentHashMap<>();
        }
    }

    @Inject(method = "getPhysicsDataForBlock", at = @At("HEAD"), cancellable = true)
    private void createfix$useRecursionSafeColliderCache(
            BlockState state,
            CallbackInfoReturnable<RapierVoxelColliderData> cir) {
        this.createfix$ensureColliderGuardState();

        RapierVoxelColliderData cached = this.createfix$safeColliderCache.get(state);
        if (cached != null) {
            cir.setReturnValue(cached == RapierVoxelColliderData.EMPTY ? null : cached);
            return;
        }

        Set<BlockState> activeBuilds = this.createfix$activeColliderBuilds.get();
        if (!activeBuilds.add(state)) {
            if (this.createfix$reportedRecursiveStates.add(state)) {
                createfix$COLLIDER_LOGGER.warn(
                    "[sable_rapier_deadlock_fix] Prevented a recursive Sable collider bake for {}. "
                        + "The nested request is temporarily treated as non-colliding; the outer collider bake "
                        + "continues and will be cached. This prevents the Util.memoize/Util$8.apply server hang.",
                    state
                );
            }
            cir.setReturnValue(null);
            return;
        }

        try {
            // Important: compute outside ConcurrentHashMap.computeIfAbsent.
            RapierVoxelColliderData built = this.createfix$invokeBuildPhysicsDataForBlock(state);
            if (built == null) {
                built = RapierVoxelColliderData.EMPTY;
            }

            RapierVoxelColliderData alreadyCached =
                this.createfix$safeColliderCache.putIfAbsent(state, built);
            RapierVoxelColliderData result = alreadyCached != null ? alreadyCached : built;

            cir.setReturnValue(result == RapierVoxelColliderData.EMPTY ? null : result);
        } finally {
            activeBuilds.remove(state);
            if (activeBuilds.isEmpty()) {
                this.createfix$activeColliderBuilds.remove();
            }
        }
    }
}
