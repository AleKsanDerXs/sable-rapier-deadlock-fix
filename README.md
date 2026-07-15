# Sable 2.0.3 — Stability Fixes (Rapier deadlock, removed-body crash, plot-holder crash)

A small [Mixin](https://github.com/SpongePowered/Mixin)-based patch mod that fixes **three** related dedicated-server
crashes in [Sable](https://modrinth.com/mod/sable) **2.0.3**'s physics and sublevel ("plot") systems, on
**NeoForge 21.1.228 / Minecraft 1.21.1**. Sable is the physics engine behind
[Create Aeronautics](https://modrinth.com/mod/create-aeronautics), Create Offroad, and other physics-driven Create
addons.

All three share the same underlying pattern: some object (a block's physics collider, a physics body, a plot's
chunk holder) gets removed/torn down, and another part of Sable tries to use it a moment later without checking —
throwing an exception that takes the whole server down instead of just skipping that one stale reference.

## 1. Rapier physics deadlock (server freeze, not crash)

If your server froze silently (players stop moving, no chat, a crash report only appears minutes/hours later) while
a plane, vehicle, or other Sable-physics object was crashing through blocks:

```
java.lang.Error: ServerHangWatchdog detected that a single server tick took 60000004.00 seconds (should be max 0.05)
    at dev.ryanhcode.sable.physics.impl.rapier.Rapier3D.newVoxelCollider(Native Method)
    at dev.ryanhcode.sable.physics.impl.rapier.Rapier3D.createVoxelColliderEntry(Rapier3D.java:289)
    at dev.ryanhcode.sable.physics.impl.rapier.collider.RapierVoxelColliderBakery.buildPhysicsDataForBlock(RapierVoxelColliderBakery.java:60)
    ...
    at dev.ryanhcode.sable.physics.impl.rapier.Rapier3D.step(Native Method)
    at dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline.physicsTick(RapierPhysicsPipeline.java:...)
```

The watchdog's reported elapsed time (60000004.00 seconds) is meaningless — it's not how long the tick actually
ran, it's what you get once the server thread has been permanently stuck in a native call for a long time and the
watchdog thread's own bookkeeping overflows. The real problem happened much earlier, silently.

### Root cause

`RapierPhysicsPipeline.physicsTick()` calls the native `Rapier3D.step()` function, which runs the physics
simulation inside Rapier's native (Rust) engine. While that native step is running, Rapier can call back into Java
mid-simulation — for example `FragileBlockCallback.onHit()`, which destroys a "fragile" block (leaves, bamboo,
melon, pumpkin, cactus, ice, frosted ice, lily pad — see `data/sable/tags/block/fragile.json`) that a fast-moving
physics object just hit at speed > 4.0 (`FragileBlockCallback.getTriggerVelocity()`).

Destroying that block fires Minecraft's normal block-change/neighbor-update chain, which Sable also hooks
(`RapierPhysicsPipeline.handleBlockChange()`) to keep its own voxel collider cache in sync with the world. If the
collider data for the changed block — or any of its 6 neighbors — hasn't been cached yet, `handleBlockChange()`
calls back into the native engine via `Rapier3D.newVoxelCollider()` to bake it. That's a **second** native call into
the same Rapier engine instance, made from *inside* the `step()` call that started this whole chain. Rapier's
native engine isn't reentrant for this call pair, so the second call blocks forever. The JVM can't interrupt a
native call, so nothing shows up until `ServerHangWatchdog` eventually notices the server thread never returned
from its tick and force-crashes the process.

The voxel collider cache (`net.minecraft.Util.memoize`) lives on each `RapierPhysicsPipeline` instance and is
rebuilt fresh whenever that level's physics pipeline is (re)created. The deadlock only happens the *first* time a
given block state needs baking in that pipeline's lifetime — once normal gameplay has touched a block type once,
it's cached and safe. This is why the hang is intermittent, not guaranteed on every fragile-block break.

### Fix

[`RapierPhysicsPipelineMixin`](src/main/java/dev/createfix/rapierfix/mixin/RapierPhysicsPipelineMixin.java) tracks
(per `RapierPhysicsPipeline` instance) whether execution is currently inside `physicsTick()`, and skips
`handleBlockChange()` entirely while that's true. By the time `handleBlockChange()` runs, the Minecraft-side block
change has *already* happened either way — skipping only means Sable's collider cache for that spot stays one tick
stale instead of rebuilding immediately, a tiny self-correcting gap versus freezing the whole server.

`Rapier3D.step(long, double)` is itself a **package-private native method**, so this mixin can't call/wrap it
directly from its own package (same category of problem as referencing a private member across packages — see the
[create-6.0.10-obb-collision-npe-fix](https://github.com/AleKsanDerXs/create-6.0.10-obb-collision-npe-fix) mod's
notes). Bracketing the whole (public) `physicsTick()` method instead of wrapping `step()` specifically sidesteps
that entirely.

**Confirmed working on a live server** — log captured for an `oak_leaves -> air` fragile-block destruction.

## 2. "Body has been removed" crash during world autosave

```
java.lang.RuntimeException: Body has been removed
    at dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline.assertBodyValid(RapierPhysicsPipeline.java:578)
    at dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline.getLinearVelocity(RapierPhysicsPipeline.java:485)
    at dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelSerializer.serialize(SubLevelSerializer.java:62)
    at dev.ryanhcode.sable.sublevel.storage.holding.SubLevelHoldingChunkMap.saveAll(SubLevelHoldingChunkMap.java:240)
```

### Root cause

`getLinearVelocity()`/`getAngularVelocity()` both start by calling `assertBodyValid()`, which throws a plain
`RuntimeException("Body has been removed")` if the physics body was already removed (its sublevel/contraption was
disassembled or despawned). The periodic world autosave (`SubLevelHoldingChunkMap.saveAll()` →
`SubLevelSerializer.serialize()`) reads a sublevel's velocity via exactly these two methods without checking
`RigidBodyHandle.isValid()` first — a check Sable's own API already exposes for this. If a sublevel's body happens
to have been removed right before an autosave tick, the exception propagates all the way up through
`ServerLevel.save()` and crashes the *entire* tick — not just that one sublevel's save.

### Fix

The same mixin class adds two more injections: when `getLinearVelocity()`/`getAngularVelocity()` are called on an
already-removed body, they now return a zero vector instead of throwing. A removed body isn't moving anywhere, so
zero is the physically-correct answer, not just a crash-avoidance placeholder.

## 3. "Cannot change blocks in nonexistent plot holder" crash

```
java.lang.UnsupportedOperationException: Cannot change blocks in nonexistent plot holder
    at net.minecraft.server.level.ServerChunkCache.handler$...$sable$blockChanged(ServerChunkCache.java:2714)
    at net.minecraft.server.level.ServerChunkCache.blockChanged(ServerChunkCache.java)
    at net.minecraft.server.level.ServerLevel.sendBlockUpdated(ServerLevel.java:1071)
    at net.minecraft.world.level.block.entity.trialspawner.TrialSpawner.tickServer(TrialSpawner.java:298)
```

### Root cause

Sable represents each active sublevel (ship/contraption) as a "plot": a region of chunks at very large,
out-of-the-way coordinates in the normal overworld (this is why you'll see block positions like
`(20482989, -15, 20558967)` in the crash report — that's not corruption, it's Sable's plot allocation scheme
working as intended). A mixin on vanilla `ServerChunkCache.blockChanged` routes block-change notifications for
these coordinates to the right plot; if the position falls inside the plot coordinate space
(`SubLevelContainer.inBounds()`) but no `PlotChunkHolder` is registered for that specific chunk, Sable throws
`UnsupportedOperationException` instead of just ignoring it.

That happens when a block entity (a trial spawner, in the observed case) is still ticking inside a plot whose
holder has already been torn down — the same general "something was already removed, but another system doesn't
know it yet" race as the other two fixes above, just in Sable's plot/chunk-cache system instead of its physics
system.

### Fix

[`ServerChunkCachePlotFixMixin`](src/main/java/dev/createfix/rapierfix/mixin/ServerChunkCachePlotFixMixin.java)
replicates Sable's own bounds/holder check in a `HEAD` injection on vanilla `ServerChunkCache.blockChanged`, with a
lower Mixin priority (900 vs. the default 1000) so it runs *before* Sable's own injected check, and cancels the
method early instead of letting Sable's check throw. Silently skipping the notification for a chunk with no holder
is safe — nothing is tracking that plot chunk anymore, so there's nothing meaningful to notify.

## Optional logging

Disabled by default. Set `logPreventedDeadlocks = true` in `config/sable_rapier_deadlock_fix-common.toml` to log an
INFO line every time fix #1 (the Rapier reentrancy guard) actually fires — useful to confirm it's triggering on
your server:

```
[sable_rapier_deadlock_fix] Prevented a reentrant Rapier voxel-collider rebuild near (1169, 106, 1815)
(block change Block{minecraft:oak_leaves} -> Block{minecraft:air} during physicsTick) - this would have
deadlocked the server. The physics collider for this spot will refresh on the next block change nearby.
```

## Compatibility

| | |
|---|---|
| Minecraft | 1.21.1 |
| Loader | NeoForge 21.1.228+ |
| Sable | 2.0.3 only (see note below) |

The mod declares a dependency on `sable` version range `[2.0.3,2.0.4)` — it will refuse to load against other
Sable versions, since these mixins target exact method signatures specific to 2.0.3. Once a Sable release fixes
these upstream, remove this mod.

## Installation

Drop the jar into `mods/` on the **server**. All three bugs are server-authoritative (world autosave, server-side
physics tick, server-side chunk cache); installing on the client as well is harmless but shouldn't be necessary.

## Building from source

This mod compiles against Sable and its bundled `sable_rapier` native-physics module as `compileOnly` local jar
dependencies (neither is published on a Maven repository this project pins to).

1. Grab `sable-neoforge-1.21.1-2.0.3.jar` ([Modrinth](https://modrinth.com/mod/sable)).
2. Extract `dev.ryanhcode.sable.sable-sable_rapier-1.21.1-2.0.3.jar` from its `META-INF/jarjar/` folder.
3. Place both jars in `libs/` (create the folder if it doesn't exist).
4. Run:
   ```
   ./gradlew build
   ```

The output jar is written to `build/libs/`.

## License

[MIT](LICENSE)
