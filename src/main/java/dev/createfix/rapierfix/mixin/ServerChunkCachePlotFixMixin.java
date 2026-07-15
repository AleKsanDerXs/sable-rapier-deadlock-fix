package dev.createfix.rapierfix.mixin;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fixes a Sable 2.0.3 crash reported as:
 *
 *   java.lang.UnsupportedOperationException: Cannot change blocks in nonexistent plot holder
 *       at ServerChunkCache.handler$...$sable$blockChanged(ServerChunkCache.java:2714)
 *       at ServerChunkCache.blockChanged(ServerChunkCache.java)
 *       at ServerLevel.sendBlockUpdated(ServerLevel.java:1071)
 *       at TrialSpawnerBlockEntity.markUpdated(...)
 *
 * Sable represents each active sublevel (ship/contraption) as a "plot": a
 * region of chunks at very large, out-of-the-way coordinates in the normal
 * overworld, managed through a mixin on vanilla ServerChunkCache.blockChanged.
 * That handler looks up the SubLevelContainer for the level, and if the
 * changed block's position falls inside the plot coordinate space
 * (inBounds() == true) but no PlotChunkHolder is registered for that chunk,
 * it throws UnsupportedOperationException instead of silently ignoring it.
 * That happens when a block entity (e.g. a trial spawner) is still ticking
 * inside a plot whose holder has already been torn down - most likely
 * because the sublevel it belonged to was removed/disassembled while the
 * block entity itself hadn't been cleaned up yet (the same general kind of
 * "something was already removed but another system doesn't know it yet"
 * race as the other fixes in this mod).
 *
 * Fix: replicate Sable's own bounds/holder check ourselves in a HEAD
 * injection with a lower priority (900 vs. Mixin's default 1000) so ours
 * runs first, and cancel the method before Sable's own injected check can
 * throw. Silently skipping the block-update notification for a chunk with
 * no holder is safe: nothing is tracking that plot chunk anymore anyway, so
 * there is nothing meaningful to notify.
 */
@Mixin(value = ServerChunkCache.class, priority = 900, remap = false)
public abstract class ServerChunkCachePlotFixMixin {

    @Shadow
    public ServerLevel level;

    @Inject(method = "blockChanged", at = @At("HEAD"), cancellable = true)
    private void createfix$skipMissingPlotHolder(BlockPos pos, CallbackInfo ci) {
        SubLevelContainer container = SubLevelContainer.getContainer(this.level);
        if (container == null) {
            return;
        }
        ChunkPos chunkPos = new ChunkPos(pos);
        if (container.inBounds(chunkPos) && container.getChunkHolder(chunkPos) == null) {
            ci.cancel();
        }
    }
}
