package io.wispforest.tclayer.mixin;

import dev.emi.trinkets.data.SlotLoader;
import io.wispforest.accessories.Accessories;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.minecraft.server.packs.PackType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "io/wispforest/accessories/DataLoaderImpl", remap = false)
public abstract class DataLoaderImplMixin {

    @Inject(method = "registerListeners", at = @At("TAIL"))
    private void adjustDependenciesAndAddListeners(CallbackInfo ci){
        var manager = ResourceManagerHelper.get(PackType.SERVER_DATA);

        manager.registerReloadListener(SlotLoader.INSTANCE);
        manager.registerReloadListener(dev.emi.trinkets.data.EntitySlotLoader.SERVER);

        var listeners = ((ResourceManagerHelperImplAccessor) manager).getAddedListeners();

        for (var listener : listeners) {
            if(listener.getFabricId().equals(Accessories.of("slot_loader"))) {
                listener.getFabricDependencies().add(SlotLoader.INSTANCE.getFabricId());
            }

            if(listener.getFabricId().equals(Accessories.of("entity_slot_loader"))) {
                listener.getFabricDependencies().add(dev.emi.trinkets.data.EntitySlotLoader.SERVER.getFabricId());
            }
        }
    }
}
