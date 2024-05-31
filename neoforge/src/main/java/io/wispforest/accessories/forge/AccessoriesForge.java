package io.wispforest.accessories.forge;

import com.mojang.logging.LogUtils;
import io.wispforest.accessories.Accessories;
import io.wispforest.accessories.api.events.extra.ImplementedEvents;
import io.wispforest.accessories.data.EntitySlotLoader;
import io.wispforest.accessories.data.SlotGroupLoader;
import io.wispforest.accessories.data.SlotTypeLoader;
import io.wispforest.accessories.impl.AccessoriesEventHandler;
import io.wispforest.accessories.impl.AccessoriesHolderImpl;
import io.wispforest.accessories.impl.InstanceCodecable;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.living.LootingLevelEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.RegisterEvent;
import org.slf4j.Logger;

import java.util.function.Consumer;

@Mod(Accessories.MODID)
public class AccessoriesForge {

    public static final Logger LOGGER = LogUtils.getLogger();

    public static final AttachmentType<AccessoriesHolderImpl> HOLDER_ATTACHMENT_TYPE;

    static {
        HOLDER_ATTACHMENT_TYPE = AttachmentRegistry.<AccessoriesHolderImpl>builder()
                .initializer(AccessoriesHolderImpl::of)
                .persistent(InstanceCodecable.constructed(AccessoriesHolderImpl::new))
                .copyOnDeath()
                .buildAndRegister(Accessories.of("inventory_holder"));
    }

    public static IEventBus BUS;

    public AccessoriesForge() {
        IEventBus eventBus = FMLJavaModLoadingContext.get().getModEventBus();

        AccessoriesForge.BUS = eventBus;

        Accessories.init();

        MinecraftForge.EVENT_BUS.addListener(this::onEntityDeath);
        MinecraftForge.EVENT_BUS.addListener(this::onLivingEntityTick);
        MinecraftForge.EVENT_BUS.addListener(this::onDataSync);
        MinecraftForge.EVENT_BUS.addListener(this::onEntityLoad);
        MinecraftForge.EVENT_BUS.addListener(this::onStartTracking);
        MinecraftForge.EVENT_BUS.addListener(this::registerReloadListeners);

        eventBus.addListener(this::registerStuff);

        MinecraftForge.EVENT_BUS.addListener(this::adjustLooting);
        MinecraftForge.EVENT_BUS.addListener(this::onWorldTick);

        eventBus.register(AccessoriesForgeNetworkHandler.INSTANCE);

        MinecraftForge.EVENT_BUS.addListener(this::registerCommands);

        AccessoriesForgeNetworkHandler.INSTANCE.initializeNetworking();

        Accessories.registerCriteria();
    }

    public void attemptEquipFromUse(PlayerInteractEvent.RightClickItem event){
        var resultHolder = AccessoriesEventHandler.attemptEquipFromUse(event.getEntity(), event.getHand());

        if(resultHolder.getResult().consumesAction()) {
            event.getEntity().setItemInHand(event.getHand(), resultHolder.getObject());

            event.setCancellationResult(resultHolder.getResult());
        }
    }

    public void attemptEquipOnEntity(PlayerInteractEvent.EntityInteract event) {
        AccessoriesEventHandler.attemptEquipOnEntity(event.getEntity(), event.getHand(), event.getTarget());
    }

    public void registerCommands(RegisterCommandsEvent event) {
        Accessories.registerCommands(event.getDispatcher());
    }

    public void registerStuff(RegisterEvent event){
        event.register(Registries.MENU, (helper) -> Accessories.registerMenuType());
    }

    public void onEntityDeath(LivingDeathEvent event){
        AccessoriesEventHandler.onDeath(event.getEntity(), event.getSource());
    }

    public void onLivingEntityTick(LivingEvent.LivingTickEvent event){
        AccessoriesEventHandler.onLivingEntityTick(event.getEntity());
    }

    public void onDataSync(OnDatapackSyncEvent event){
        AccessoriesEventHandler.dataSync(event.getPlayerList(), event.getPlayer());
    }

    public void onEntityLoad(EntityJoinLevelEvent event){
        if(!(event.getEntity() instanceof LivingEntity livingEntity)) return;

        AccessoriesEventHandler.entityLoad(livingEntity, event.getLevel());
    }

    public void onStartTracking(PlayerEvent.StartTracking event){
        if(!(event.getTarget() instanceof LivingEntity livingEntity)) return;

        AccessoriesEventHandler.onTracking(livingEntity, (ServerPlayer) event.getEntity());
    }

    public void registerReloadListeners(AddReloadListenerEvent event){
        intermediateRegisterListeners(event::addListener);

        AccessoriesInternalsImpl.setContext(event.getConditionContext());
    }

    // This exists as a way to register things within the TCLayer without depending on NeoForge to do such within a mixin
    public void intermediateRegisterListeners(Consumer<PreparableReloadListener> registrationMethod){
//        for (ModFileScanData data : ModList.get().getAllScanData()) {
//            data.getAnnotations().forEach(annotationData -> {
//                if (annotationData.annotationType().equals(Type.getType(DataLoadingModifications.DataLoadingModificationsCapable.class))) {
//                    try {
//                        Class<?> clazz = Class.forName(annotationData.memberName());
//
//                        if (DataLoadingModifications.class.isAssignableFrom(clazz)) {
//                            try {
//                                var instance = (DataLoadingModifications) clazz.getDeclaredConstructor().newInstance();
//
//                                instance.beforeRegistration(registrationMethod);
//                            } catch (Throwable e) {
//                                LOGGER.error("Failed to load DataLoadingModificationsCapable: " + annotationData.memberName(), e);
//                            }
//                        }
//                    } catch (Throwable e) {
//                        LOGGER.error("No class from such annotation: " + annotationData.memberName(), e);
//                    }
//                }
//            });
//        }

        registrationMethod.accept(SlotTypeLoader.INSTANCE);
        registrationMethod.accept(EntitySlotLoader.INSTANCE);
        registrationMethod.accept(SlotGroupLoader.INSTANCE);

        registrationMethod.accept(new SimplePreparableReloadListener<Void>() {
            @Override protected Void prepare(ResourceManager resourceManager, ProfilerFiller profiler) { return null; }
            @Override protected void apply(Void object, ResourceManager resourceManager, ProfilerFiller profiler) {
                AccessoriesEventHandler.dataReloadOccured = true;

                AccessoriesInternalsImpl.setContext(null);
            }
        });
    }

    //--

    public void adjustLooting(LootingLevelEvent event){
        event.setLootingLevel(ImplementedEvents.lootingAdjustments(event.getEntity(), event.getDamageSource(), event.getLootingLevel()));
    }

    public void onWorldTick(TickEvent.LevelTickEvent event){
        if(event.phase == TickEvent.Phase.END) {
            //ImplementedEvents.clearEndermanAngryCache();
        } else {
            AccessoriesEventHandler.onWorldTick(event.level);
        }
    }
}
