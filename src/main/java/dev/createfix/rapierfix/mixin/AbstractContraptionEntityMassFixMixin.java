package dev.createfix.rapierfix.mixin;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import dev.ryanhcode.sable.api.physics.mass.MassTracker;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = AbstractContraptionEntity.class, priority = 2000, remap = false)
public abstract class AbstractContraptionEntityMassFixMixin {

    @Redirect(method = "sable$buildProperties()V", at = @At(value = "INVOKE",
            target = "Ldev/ryanhcode/sable/api/physics/mass/MassTracker;getCenterOfMass()Lorg/joml/Vector3dc;"))
    private Vector3dc createfix$safeGetCenterOfMass(MassTracker massTracker) {
        Vector3dc centerOfMass = massTracker.getCenterOfMass();
        return centerOfMass != null ? centerOfMass : new Vector3d(0, 0, 0);
    }
}
