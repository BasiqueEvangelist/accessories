package io.wispforest.cclayer;

import io.wispforest.accessories.api.AccessoriesAPI;
import io.wispforest.accessories.api.AccessoriesCapability;
import io.wispforest.accessories.data.EntitySlotLoader;
import io.wispforest.accessories.impl.AccessoriesCapabilityImpl;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.capabilities.RegisterCapabilitiesEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.InterModProcessEvent;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.CuriosCapability;
import top.theillusivec4.curios.api.SlotTypeMessage;
import top.theillusivec4.curios.api.type.ISlotType;
import top.theillusivec4.curios.api.type.capability.ICurio;
import top.theillusivec4.curios.api.type.capability.ICurioItem;
import top.theillusivec4.curios.common.CuriosHelper;
import top.theillusivec4.curios.common.CuriosRegistry;
import top.theillusivec4.curios.common.capability.CurioItemHandler;
import top.theillusivec4.curios.common.capability.ItemizedCurioCapability;
import top.theillusivec4.curios.common.data.CuriosSlotManager;
import top.theillusivec4.curios.common.slottype.LegacySlotManager;
import top.theillusivec4.curios.compat.WrappedAccessory;
import top.theillusivec4.curios.compat.WrappedCurioItemHandler;
import top.theillusivec4.curios.mixin.CuriosImplMixinHooks;
import top.theillusivec4.curios.server.SlotHelper;
import top.theillusivec4.curios.server.command.CurioArgumentType;

import java.util.HashSet;
import java.util.Set;

@Mod(value = CCLayer.MODID)
public class CCLayer {

    public static final String MODID = "cclayer";

    public CCLayer(IEventBus eventBus){
        eventBus.addListener(this::registerCapabilities);
        eventBus.addListener(this::process);
        MinecraftForge.EVENT_BUS.addListener(this::serverAboutToStart);
        MinecraftForge.EVENT_BUS.addListener(this::serverStopped);

        CuriosApi.setCuriosHelper(new CuriosHelper());

        //ModList.get().isLoaded("curios");

        CuriosRegistry.init(eventBus);
    }

    private void registerCapabilities(RegisterCapabilitiesEvent event) {
        for (EntityType<?> entityType : BuiltInRegistries.ENTITY_TYPE) {
            event.registerEntity(CuriosCapability.ITEM_HANDLER, entityType,
                    (entity, ctx) -> {
                        if (entity instanceof LivingEntity livingEntity && EntitySlotLoader.getEntitySlots(livingEntity).isEmpty()) {
                            return new CurioItemHandler(livingEntity);
                        }

                        return null;
                    });

            event.registerEntity(CuriosCapability.INVENTORY, entityType,
                    (entity, ctx) -> {
                        if (entity instanceof LivingEntity livingEntity) {
                            var capability = AccessoriesCapability.get(livingEntity);

                            if(capability != null) return new WrappedCurioItemHandler((AccessoriesCapabilityImpl) capability);
                        }

                        return null;
                    });
        }

        for (Item item : BuiltInRegistries.ITEM) {
            // Force all items instanceof ICurioItem to register for Accessories systems
            if(CuriosImplMixinHooks.getCurioFromRegistry(item).isEmpty() && item instanceof ICurioItem iCurioItem){
                CuriosImplMixinHooks.registerCurio(item, iCurioItem);
            }

            event.registerItem(CuriosCapability.ITEM, BASE_PROVIDER, item);
        }
    }

    public static final ICapabilityProvider<ItemStack, Void, ICurio> BASE_PROVIDER = (stack, ctx) -> {
        Item it = stack.getItem();
        ICurioItem curioItem = CuriosImplMixinHooks.getCurioFromRegistry(it).orElse(null);

        if (curioItem == null && it instanceof ICurioItem itemCurio) {
            curioItem = itemCurio;
        }

        if(curioItem == null){
            var accessory = AccessoriesAPI.getOrDefaultAccessory(stack);

            curioItem = new WrappedAccessory(accessory);
        }

        if (curioItem != null && curioItem.hasCurioCapability(stack)) {
            return new ItemizedCurioCapability(curioItem, stack);
        }

        return null;
    };

    private void serverAboutToStart(ServerAboutToStartEvent evt) {
        CuriosApi.setSlotHelper(new SlotHelper());
        Set<String> slotIds = new HashSet<>();

        for (ISlotType value : CuriosSlotManager.INSTANCE.getSlots().values()) {
            CuriosApi.getSlotHelper().addSlotType(value);
            slotIds.add(value.getIdentifier());
        }
        CurioArgumentType.slotIds = slotIds;
    }

    private void serverStopped(ServerStoppedEvent evt) {
        CuriosApi.setSlotHelper(null);
    }

    private void process(InterModProcessEvent evt) {
        LegacySlotManager.buildImcSlotTypes(evt.getIMCStream(SlotTypeMessage.REGISTER_TYPE::equals),
                evt.getIMCStream(SlotTypeMessage.MODIFY_TYPE::equals));
    }
}
