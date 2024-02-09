package io.wispforest.accessories.client;

import com.mojang.datafixers.util.Pair;
import io.wispforest.accessories.Accessories;
import io.wispforest.accessories.AccessoriesAccess;
import io.wispforest.accessories.api.*;
import io.wispforest.accessories.client.gui.AccessoriesSlot;
import io.wispforest.accessories.data.SlotGroupLoader;
import io.wispforest.accessories.mixin.AbstractContainerMenuAccessor;
import io.wispforest.accessories.mixin.InventoryMenuAccessor;
import io.wispforest.accessories.mixin.SlotAccessor;
import io.wispforest.accessories.networking.client.SyncCosmeticsMenuToggle;
import io.wispforest.accessories.networking.client.SyncLinesMenuToggle;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.*;

public class AccessoriesMenu extends InventoryMenu {

    public int totalSlots = 0;
    public boolean overMaxVisibleSlots = false;

    public int scrolledIndex = 0;

    public float smoothScroll = 0;

    public int maxScrollableIndex = 0;

    public int accessoriesSlotStartIndex = 0;
    public int cosmeticSlotStartIndex = 0;

    private final Map<Integer, Boolean> slotToView = new HashMap<>();

    public Runnable onScrollToEvent = () -> {};

    public AccessoriesMenu(int containerId, Inventory inventory) {
        super(inventory, inventory.player.level().isClientSide, inventory.player);
//        this.slots.remove(slots.size()-1);
//        this.slots.removeIf(slot -> slot.index < 5);
//        this.slots.add(new Slot(inventory, 40, 77+51, 62) {
//            public void setByPlayer(ItemStack newStack, ItemStack oldStack) {
//                InventoryMenu.onEquipItem(inventory.player, EquipmentSlot.OFFHAND, newStack, oldStack);
//                super.setByPlayer(newStack, oldStack);
//            }
//
//            public Pair<ResourceLocation, ResourceLocation> getNoItemIcon() {
//                return Pair.of(InventoryMenu.BLOCK_ATLAS, InventoryMenu.EMPTY_ARMOR_SLOT_SHIELD);
//            }
//        });


        var player = inventory.player;

        var accessor = (AbstractContainerMenuAccessor) this;

        accessor.accessories$setMenuType(Accessories.ACCESSORIES_MENU_TYPE);
        accessor.accessories$setContainerId(containerId);

        var capability = AccessoriesAPI.getCapability(player);

        var entitySlotTypes = AccessoriesAPI.getEntitySlots(player);

        int slotScale = 18;

        int minX = -46;
        int maxX = 60;
        int minY = 8;
        int maxY = 152;

        int cosmeticPadding = 2;

        int yOffset = 8;

        Map<SlotType, Set<Accessory>> sortAccessories = new HashMap<>();

        for (var item : BuiltInRegistries.ITEM) {
            var accessory = AccessoriesAPI.getAccessory(item);

            if (accessory.isEmpty()) continue;

//            if(item == Items.APPLE){
//                System.out.println("APPLE");
//            }

            for (var value : entitySlotTypes.values()) {
                if (AccessoriesAPI.canInsertIntoSlot(player, new SlotReference(value.name(), player, 0), item.getDefaultInstance())) {
                    sortAccessories.computeIfAbsent(value, s -> new HashSet<>()).add(accessory.get());
                }
            }
        }

        if (capability.isEmpty()) return;

        var containers = capability.get().getContainers();

        int yIndex = 0;

        this.accessoriesSlotStartIndex = this.slots.size();

        var slotVisablity = new HashMap<Slot, Boolean>();

        var accessoriesSlots = new ArrayList<AccessoriesSlot>();
        var cosmeticSlots = new ArrayList<AccessoriesSlot>();

        var groups = SlotGroupLoader.INSTANCE.getGroups(inventory.player.level().isClientSide);

        for (var group : groups.values().stream().sorted(Comparator.comparingInt(SlotGroup::order).reversed()).toList()) {
            var slotNames = group.slots();

            var slotTypes = slotNames.stream()
                    .flatMap(s -> AccessoriesAPI.getSlotType(player.level(), s).stream())
                    .sorted(Comparator.comparingInt(SlotType::order).reversed())
                    .toList();

            for (SlotType slot : slotTypes) {
                var accessoryContainer = containers.get(slot.name());

                if (accessoryContainer == null) continue;

                var slotType = accessoryContainer.slotType();

                if (slotType.isEmpty()) continue;

                var size = accessoryContainer.getSize();

                for (int i = 0; i < size; i++) {
                    int currentY = (yIndex * Math.max(18, slotScale)) + minY + yOffset;

                    int currentX = minX;

                    var cosmeticSlot =
                            new AccessoriesSlot(yIndex, player, accessoryContainer, true, i, currentX, currentY)
                                    .isActive((slot1) -> this.isCosmeticsOpen() && slotToView.getOrDefault(slot1.index, true))
                                    .isAccessible(slot1 -> slot1.isCosmetic && isCosmeticsOpen());


                    cosmeticSlots.add(cosmeticSlot);

                    slotVisablity.put(cosmeticSlot, !overMaxVisibleSlots);

                    currentX += slotScale + cosmeticPadding;

                    var baseSlot =
                            new AccessoriesSlot(yIndex, player, accessoryContainer, false, i, currentX, currentY)
                                    .isActive(slot1 -> {
                                        return slotToView.getOrDefault(slot1.index, true);
                                    });

                    accessoriesSlots.add(baseSlot);

                    slotVisablity.put(baseSlot, !overMaxVisibleSlots);

                    yIndex++;

                    if (!overMaxVisibleSlots && currentY + Math.max(18, slotScale) > maxY) {
                        overMaxVisibleSlots = true;
                    }
                }
            }
        }

        for (var accessoriesSlot : accessoriesSlots) {
            this.addSlot(accessoriesSlot);
            slotToView.put(accessoriesSlot.index, slotVisablity.getOrDefault(accessoriesSlot, false));
        }

        cosmeticSlotStartIndex = this.slots.size();

        for (var cosmeticSlot : cosmeticSlots) {
            this.addSlot(cosmeticSlot);
            slotToView.put(cosmeticSlot.index, slotVisablity.getOrDefault(cosmeticSlot, false));
        }

        totalSlots = yIndex;

        maxScrollableIndex = totalSlots - 8;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        final var slots = this.slots;
        final var clickedSlot = slots.get(index);
        if (!clickedSlot.hasItem()) return ItemStack.EMPTY;

        final var clickedStack = clickedSlot.getItem();

        ItemStack itemStack2 = clickedSlot.getItem();
        var itemStack = itemStack2.copy();
        EquipmentSlot equipmentSlot = Mob.getEquipmentSlotForItem(itemStack);
        if (index >= 5 && index < 9) {
            if (!this.moveItemStackTo(itemStack2, 9, 45, false)) {
                return ItemStack.EMPTY;
            }
        } else if (equipmentSlot.getType() == EquipmentSlot.Type.ARMOR && !this.slots.get(8 - equipmentSlot.getIndex()).hasItem()) {
            int i = 8 - equipmentSlot.getIndex();
            if (!this.moveItemStackTo(itemStack2, i, i + 1, false)) {
                return ItemStack.EMPTY;
            }
        } else if (equipmentSlot == EquipmentSlot.OFFHAND && !this.slots.get(45).hasItem()) {
            if (!this.moveItemStackTo(itemStack2, 45, 46, false)) {
                return ItemStack.EMPTY;
            }
        } else if ((index < this.accessoriesSlotStartIndex && index < 45) && !moveItemStackTo(clickedStack, this.accessoriesSlotStartIndex, this.slots.size(), false)) {
            if (index >= 9 && index < 36) {
                if (!this.moveItemStackTo(itemStack2, 36, 45, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (index >= 36) {
                if (!this.moveItemStackTo(itemStack2, 9, 36, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (!this.moveItemStackTo(itemStack2, 9, 45, false)) {
                return ItemStack.EMPTY;
            }
        }


        return super.quickMoveStack(player, index);
    }

    public boolean scrollTo(int i, boolean smooth) {
        var index = Math.min(Math.max(i, 0), this.maxScrollableIndex);

        if (index == this.scrolledIndex) return false;

        var diff = this.scrolledIndex - index;

        if (!smooth) this.smoothScroll = Mth.clamp(index / (float) this.maxScrollableIndex, 0.0f, 1.0f);

        for (Slot slot : this.slots) {
            if (!(slot instanceof AccessoriesSlot accessoriesSlot)) continue;

            ((SlotAccessor) accessoriesSlot).accessories$setY(accessoriesSlot.y + (diff * 18));

            var menuIndex = accessoriesSlot.menuIndex;

            this.slotToView.put(accessoriesSlot.index, (menuIndex >= index && menuIndex < index + 8));
        }

        this.scrolledIndex = index;

        onScrollToEvent.run();

        return true;
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (player.level().isClientSide) return true;

        if (id == 0) {
            AccessoriesAccess.modifyHolder(player, holder -> holder.cosmeticsShown(!isCosmeticsOpen()));

            AccessoriesAccess.getNetworkHandler().sendToPlayer((ServerPlayer) player, new SyncCosmeticsMenuToggle(isCosmeticsOpen()));

            return true;
        }

        if (id == 1) {
            AccessoriesAccess.modifyHolder(player, holder -> holder.linesShown(!areLinesShown()));

            AccessoriesAccess.getNetworkHandler().sendToPlayer((ServerPlayer) player, new SyncLinesMenuToggle(areLinesShown()));

            return true;
        }

        if (this.slots.get(id) instanceof AccessoriesSlot slot) {
            var renderOptions = slot.container.renderOptions();
            renderOptions.set(slot.getContainerSlot(), !slot.container.shouldRender(slot.getContainerSlot()));
            slot.container.markChanged();
        }

        return super.clickMenuButton(player, id);
    }

    public boolean isCosmeticsOpen() {
        return AccessoriesAccess.getHolder(((InventoryMenuAccessor) this).getOwner()).cosmeticsShown();
    }

    public boolean areLinesShown() {
        return AccessoriesAccess.getHolder(((InventoryMenuAccessor) this).getOwner()).linesShown();
    }
}