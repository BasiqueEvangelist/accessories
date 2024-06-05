package io.wispforest.accessories;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import io.wispforest.accessories.api.events.AllowEntityModificationCallback;
import io.wispforest.accessories.client.AccessoriesMenu;
import io.wispforest.accessories.compat.AccessoriesConfig;
import io.wispforest.accessories.criteria.AccessoryChangedCriterion;
import io.wispforest.accessories.impl.AccessoriesTags;
import io.wispforest.accessories.mixin.CriteriaTriggersAccessor;
import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.ConfigHolder;
import me.shedaniel.autoconfig.serializer.JanksonConfigSerializer;
import net.fabricmc.fabric.api.util.TriState;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.EntityHitResult;
import org.jetbrains.annotations.Nullable;

public class Accessories {

    public static final String MODID = "accessories";

    public static ResourceLocation of(String path){
        return new ResourceLocation(MODID, path);
    }

    public static String translation(String path){
        return MODID + "." + path;
    }

    public static AccessoriesConfig getConfig(){
        if(CONFIG_HOLDER == null) return null;

        return CONFIG_HOLDER.getConfig();
    }

    public static boolean attemptOpenScreenPlayer(ServerPlayer player) {
        var result = ProjectileUtil.getHitResultOnViewVector(player, e -> e instanceof LivingEntity, (player.isCreative() ? 4.5 : 4));

        if(!(result instanceof EntityHitResult entityHitResult)) return false;

        Accessories.openAccessoriesMenu(player, (LivingEntity) entityHitResult.getEntity());

        return true;
    }

    public static void openAccessoriesMenu(Player player, @Nullable LivingEntity targetEntity) {
        openAccessoriesMenu(player, targetEntity, null);
    }

    public static void openAccessoriesMenu(Player player, @Nullable LivingEntity targetEntity, @Nullable ItemStack carriedStack) {
        if(targetEntity != null) {
            var result = AllowEntityModificationCallback.EVENT.invoker().allowModifications(targetEntity, player, null);

            if(!result.orElse(false)) return;
        }

        AccessoriesInternals.openAccessoriesMenu(player, targetEntity, carriedStack);
    }

    //--

    @Nullable
    private static ConfigHolder<AccessoriesConfig> CONFIG_HOLDER = null;

    public static MenuType<AccessoriesMenu> ACCESSORIES_MENU_TYPE;

    public static AccessoryChangedCriterion ACCESSORY_EQUIPPED;
    public static AccessoryChangedCriterion ACCESSORY_UNEQUIPPED;

    public static void init() {
        CONFIG_HOLDER = AutoConfig.register(AccessoriesConfig.class, JanksonConfigSerializer::new);

        AllowEntityModificationCallback.EVENT.register((target, player, reference) -> {
            var type = target.getType();

            if(type.is(AccessoriesTags.MODIFIABLE_ENTITY_BLACKLIST)) return TriState.FALSE;
            if(target instanceof OwnableEntity || type.is(AccessoriesTags.MODIFIABLE_ENTITY_WHITELIST)) return TriState.TRUE;

            return TriState.DEFAULT;
        });
    }

    public static void registerCriteria(){
        ACCESSORY_EQUIPPED = CriteriaTriggersAccessor.accessories$callRegister(new AccessoryChangedCriterion(Accessories.of("equip_accessory")));
        ACCESSORY_UNEQUIPPED = CriteriaTriggersAccessor.accessories$callRegister(new AccessoryChangedCriterion(Accessories.of("unequip_accessory")));
    }

    public static void registerMenuType() {
        ACCESSORIES_MENU_TYPE = AccessoriesInternals.registerMenuType(of("accessories_menu"), (i, inv, buf) -> AccessoriesMenu.of(i, inv, false, buf));
    }

    private static final SimpleCommandExceptionType NON_LIVING_ENTITY_TARGET = new SimpleCommandExceptionType(Component.translatable("argument.livingEntities.nonLiving"));

    //accessories edit {}
    public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("accessories")
                        .then(
                                Commands.literal("edit")
                                        .requires(commandSourceStack -> commandSourceStack.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                        .then(
                                                Commands.argument("entity", EntityArgument.entity())
                                                        .executes((context) -> {
                                                            var player = context.getSource().getPlayerOrException();

                                                            var entity = EntityArgument.getEntity(context, "entity");

                                                            if(!(entity instanceof LivingEntity livingEntity)) {
                                                                throw NON_LIVING_ENTITY_TARGET.create();
                                                            }

                                                            Accessories.openAccessoriesMenu(player, livingEntity);

                                                            return 1;
                                                        })
                                        )
                                        .executes(context -> {
                                            return attemptOpenScreenPlayer(context.getSource().getPlayerOrException())
                                                    ? 1
                                                    : 0;
                                        })
                        )
        );
    }
}