# Sable 2.0.3 — Rapier Physics Deadlock Fix

A small [Mixin](https://github.com/SpongePowered/Mixin)-based patch mod that fixes a dedicated-server **hang**
(not a crash-on-its-own — it silently freezes until `ServerHangWatchdog` eventually fires) in
[Sable](https://modrinth.com/mod/sable) **2.0.3**'s Rapier physics backend, on **NeoForge 21.1.228 / Minecraft 1.21.1**.
Sable is the physics engine behind [Create Aeronautics](https://modrinth.com/mod/create-aeronautics),
Create Offroad, and other physics-driven Create addons.

If your server froze (players stop moving, no more chat, eventually a crash report appears minutes/hours later)
while a plane, vehicle, or other Sable-physics object was crashing through blocks, this is the fix:

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

## Root cause

`RapierPhysicsPipeline.physicsTick()` calls the native `Rapier3D.step()` function, which runs the physics
simulation inside Rapier's native (Rust) engine. While that native step is running, Rapier can call back into Java
mid-simulation — for example `FragileBlockCallback.onHit()`, which destroys a "fragile" block (see below) that a
fast-moving physics object just hit.

Destroying that block fires Minecraft's normal block-change/neighbor-update chain, which Sable also hooks
(`RapierPhysicsPipeline.handleBlockChange()`) to keep its own voxel collider cache in sync with the world. If the
collider data for the changed block — or any of its 6 neighbors — hasn't been cached yet, `handleBlockChange()`
calls back into the native engine via `Rapier3D.newVoxelCollider()` to bake it. That's a **second** native call into
the same Rapier engine instance, made from *inside* the `step()` call that started this whole chain. Rapier's
native engine isn't reentrant for this call pair, so the second call blocks forever. The JVM can't interrupt a
native call, so nothing shows up until `ServerHangWatchdog` eventually notices the server thread never returned
from its tick and force-crashes the process.

### Which blocks trigger it

Sable tags these blocks `#sable:fragile` (`data/sable/tags/block/fragile.json`):

- Leaves (only naturally-grown ones — player-placed leaves have `persistent=true` and are exempt)
- Bamboo, melon, pumpkin, cactus, ice, frosted ice, lily pad

A physics object has to hit one at a speed above the engine's trigger threshold (`FragileBlockCallback.getTriggerVelocity() == 4.0`)
to break it — a real high-speed crash, not a gentle bump.

### Why it's intermittent

The voxel collider cache (`net.minecraft.Util.memoize`) lives on each `RapierPhysicsPipeline` instance — one per
level, rebuilt fresh whenever that level's physics pipeline is (re)created (e.g. on a level/dimension load). The
deadlock only happens the *first* time a given block state (the destroyed block's new state, or one of its
neighbors') needs baking in that pipeline's lifetime. Once normal gameplay (players building, breaking, redstone,
etc. — all of which also go through `handleBlockChange()`, just never while `physicsTick()` is on the stack) has
touched a block type once, it's cached and safe from then on. This is why the hang doesn't happen every time a
fragile block breaks — only when the unlucky first bake of some block state's collider happens to land inside a
physics step.

## What the fix does

[`RapierPhysicsPipelineMixin`](src/main/java/dev/createfix/rapierfix/mixin/RapierPhysicsPipelineMixin.java) tracks
(as a field on each `RapierPhysicsPipeline` instance) whether execution is currently inside `physicsTick()`, and
skips `handleBlockChange()` entirely while that's true.

By the time `handleBlockChange()` runs, the Minecraft-side block change has *already* happened — the hook fires
after `Level.setBlock`/`destroyBlock` already applied it. Skipping only means Sable's physics-side collider cache
for that spot stays one tick stale instead of rebuilding immediately; it rebuilds on the very next block change
nearby, or the moment anything else touches it. A tiny, self-correcting, purely cosmetic gap — a vastly better
outcome than freezing (and eventually crashing) the whole server.

`Rapier3D.step(long, double)` is itself a **package-private native method**, so this mixin can't call/wrap it
directly from its own package (same category of problem as referencing a private member across packages — see the
[create-6.0.10-obb-collision-npe-fix](https://github.com/AleKsanDerXs/create-6.0.10-obb-collision-npe-fix) mod's
notes). Bracketing the whole (public) `physicsTick()` method instead of wrapping the `step()` call specifically
sidesteps that entirely.

### Optional logging

Disabled by default. Set `logPreventedDeadlocks = true` in `config/sable_rapier_deadlock_fix-common.toml` to log an
INFO line every time the guard actually fires — useful to confirm the fix is triggering on your server:

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
Sable versions, since this mixin targets exact method signatures specific to 2.0.3. Once a Sable release fixes
this upstream, remove this mod.

## Installation

Drop the jar into `mods/` on the **server**. The whole call chain for this bug (`ServerLevel` → `ServerSubLevelContainer`
→ `SubLevelPhysicsSystem` → `RapierPhysicsPipeline`) is server-authoritative; installing on the client as well is
harmless but shouldn't be necessary.

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
