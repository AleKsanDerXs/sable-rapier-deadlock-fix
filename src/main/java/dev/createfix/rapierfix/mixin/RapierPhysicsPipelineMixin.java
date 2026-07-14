package dev.createfix.rapierfix.mixin;

import dev.createfix.rapierfix.Config;
import dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fixes a dedicated-server deadlock/hang in Sable 2.0.3's Rapier physics
 * backend on NeoForge 21.1.228 / Minecraft 1.21.1, reported as:
 *
 *   java.lang.Error: ServerHangWatchdog detected that a single server tick
 *   took 60000004.00 seconds (should be max 0.05)
 *       at dev.ryanhcode.sable.physics.impl.rapier.Rapier3D.newVoxelCollider(Native Method)
 *       ...
 *       at dev.ryanhcode.sable.physics.impl.rapier.Rapier3D.step(Native Method)
 *       at dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline.physicsTick(...)
 *
 * Root cause: RapierPhysicsPipeline.physicsTick() calls the native
 * Rapier3D.step() function, which runs the physics simulation inside the
 * native (Rust/C++) engine. During that native step, Rapier can invoke a
 * Java-side collision callback (e.g. FragileBlockCallback.onHit(), which
 * destroys a "fragile" block hit by a moving physics object). Destroying a
 * block fires vanilla neighbor-update/block-change hooks, which Sable also
 * listens to (RapierPhysicsPipeline.handleBlockChange()) in order to keep
 * its own voxel collider cache in sync with the Minecraft world. If the
 * collider data for the changed block (or one of its neighbors) isn't
 * already cached, handleBlockChange() calls back into the native engine via
 * Rapier3D.newVoxelCollider() to bake it - from *inside* the very step()
 * call that triggered this whole chain. Rapier's native engine is not
 * reentrant for this call pair, so this second native call blocks forever,
 * hanging the server thread; the JVM cannot interrupt a native call, so the
 * only symptom is the watchdog eventually firing with a nonsensical elapsed
 * time once the (already-dead) server thread never completes its tick.
 *
 * Fix: track (per RapierPhysicsPipeline instance, i.e. per level) whether
 * we are currently inside physicsTick(), and skip handleBlockChange() while
 * that's true. The Minecraft-side block change has already happened by the
 * time this hook runs either way (it's called *after* Level.setBlock/
 * destroyBlock already applied the change) - skipping only means Sable's
 * physics-side voxel collider cache for that position stays one tick stale
 * instead of rebuilding immediately. It gets rebuilt on the very next block
 * change in that area, or simply reflects reality again once anything else
 * touches it - a low-severity, self-correcting cosmetic gap, and a vastly
 * better outcome than hanging (and eventually crash-reporting) the entire
 * server.
 *
 * Rapier3D.step(long, double) is a package-private native method, so it
 * can't be called/wrapped directly from this mixin's own package without
 * hitting the same problem as referencing a private/package-private member
 * across packages (see the create-6.0.10-obb-collision-npe-fix mod's notes).
 * Bracketing the whole (public) physicsTick() method instead - rather than
 * wrapping the step() call specifically - avoids needing to touch that
 * method at all.
 */
@Mixin(value = RapierPhysicsPipeline.class, remap = false)
public abstract class RapierPhysicsPipelineMixin {

    @Unique
    private static final Logger createfix$LOGGER = LoggerFactory.getLogger("sable_rapier_deadlock_fix");

    @Unique
    private boolean createfix$inPhysicsTick = false;

    @Inject(method = "physicsTick", at = @At("HEAD"))
    private void createfix$beginPhysicsTick(double dt, CallbackInfo ci) {
        this.createfix$inPhysicsTick = true;
    }

    @Inject(method = "physicsTick", at = @At("RETURN"))
    private void createfix$endPhysicsTick(double dt, CallbackInfo ci) {
        this.createfix$inPhysicsTick = false;
    }

    @Inject(method = "handleBlockChange", at = @At("HEAD"), cancellable = true)
    private void createfix$skipReentrantBlockChange(
            SectionPos sectionPos, LevelChunkSection section, int sectionX, int sectionY, int sectionZ,
            BlockState oldState, BlockState newState, CallbackInfo ci) {
        if (this.createfix$inPhysicsTick) {
            if (Config.LOG_PREVENTED_DEADLOCKS.get()) {
                int worldX = (sectionPos.x() << 4) + sectionX;
                int worldY = (sectionPos.y() << 4) + sectionY;
                int worldZ = (sectionPos.z() << 4) + sectionZ;
                createfix$LOGGER.info(
                    "[sable_rapier_deadlock_fix] Prevented a reentrant Rapier voxel-collider rebuild near ({}, {}, {}) "
                        + "(block change {} -> {} during physicsTick) - this would have deadlocked the server. "
                        + "The physics collider for this spot will refresh on the next block change nearby.",
                    worldX, worldY, worldZ, oldState.getBlock(), newState.getBlock());
            }
            ci.cancel();
        }
    }
}
