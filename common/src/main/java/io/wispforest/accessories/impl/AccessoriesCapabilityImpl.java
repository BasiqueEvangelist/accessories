package io.wispforest.accessories.impl;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import io.wispforest.accessories.AccessoriesInternals;
import io.wispforest.accessories.api.*;
import io.wispforest.accessories.api.slot.SlotEntryReference;
import io.wispforest.accessories.api.slot.SlotReference;
import io.wispforest.accessories.data.EntitySlotLoader;
import io.wispforest.accessories.endec.EdmUtils;
import io.wispforest.accessories.endec.RegistriesAttribute;
import io.wispforest.accessories.networking.client.SyncEntireContainer;
import io.wispforest.endec.SerializationContext;
import io.wispforest.endec.util.MapCarrier;
import it.unimi.dsi.fastutil.Pair;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.ItemStack;
import org.apache.commons.lang3.function.TriFunction;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.*;

@ApiStatus.Internal
public class AccessoriesCapabilityImpl implements AccessoriesCapability, InstanceEndec {

    private final LivingEntity entity;

    public AccessoriesCapabilityImpl(LivingEntity entity) {
        this.entity = entity;

        if (holder().loadedFromTag) this.reset(true);
    }

    @Override
    public LivingEntity entity() {
        return entity;
    }

    @Override
    public AccessoriesHolder getHolder() {
        var holder = AccessoriesInternals.getHolder(entity);

        if (((AccessoriesHolderImpl) holder).loadedFromTag) this.reset(true);

        return holder;
    }

    private AccessoriesHolderImpl holder() {
        return (AccessoriesHolderImpl) this.getHolder();
    }

    @Override
    public Map<String, AccessoriesContainer> getContainers() {
        var containers = this.holder().getSlotContainers();

        // Dirty patch to handle capability mismatch on containers when transferring such
        // TODO: Wonder if such is the best solution to the problem of desynced when data is copied
        for (var container : containers.values()) {
            if(this.entity == container.capability().entity()) break;

            ((AccessoriesContainerImpl) container).capability = this;
        }

        return containers;
    }

    @Override
    public void reset(boolean loadedFromTag) {
        if (this.entity.level().isClientSide()) return;

        var holder = ((AccessoriesHolderImpl) AccessoriesInternals.getHolder(entity));

        if (!loadedFromTag) {
            var oldContainers = Map.copyOf(holder.getSlotContainers());

            holder.init(this);

            var currentContainers = holder.getSlotContainers();

            oldContainers.forEach((s, oldContainer) -> {
                var currentContainer = currentContainers.get(s);

                currentContainer.getAccessories().setFromPrev(oldContainer.getAccessories());

                currentContainer.markChanged(false);
            });
        } else {
            holder.init(this);

            if (!(this.entity instanceof ServerPlayer serverPlayer) || serverPlayer.connection == null) return;

            var carrier = EdmUtils.newMap();

            holder.write(carrier, SerializationContext.attributes(RegistriesAttribute.of(this.entity.level().registryAccess())));

            AccessoriesInternals.getNetworkHandler().sendToTrackingAndSelf(this.entity(), new SyncEntireContainer(this.entity.getId(), carrier));
        }
    }

    private boolean updateContainersLock = false;

    @Override
    public void updateContainers() {
        if (updateContainersLock) return;

        boolean hasUpdateOccurred;

        var containers = this.getContainers().values();

        this.updateContainersLock = true;

        do {
            hasUpdateOccurred = false;

            for (var container : containers) {
                if (!container.hasChanged()) {
                    continue;
                }

                container.update();

                hasUpdateOccurred = true;
            }
        } while (hasUpdateOccurred);

        this.updateContainersLock = false;
    }

    @Override
    public void addTransientSlotModifiers(Multimap<String, AttributeModifier> modifiers) {
        var containers = this.getContainers();

        for (var entry : modifiers.asMap().entrySet()) {
            if (!containers.containsKey(entry.getKey())) continue;

            var container = containers.get(entry.getKey());

            entry.getValue().forEach(container::addTransientModifier);
        }
    }

    @Override
    public void addPersistentSlotModifiers(Multimap<String, AttributeModifier> modifiers) {
        var containers = this.getContainers();

        for (var entry : modifiers.asMap().entrySet()) {
            if (!containers.containsKey(entry.getKey())) continue;

            var container = containers.get(entry.getKey());

            entry.getValue().forEach(container::addPersistentModifier);
        }
    }

    @Override
    public void removeSlotModifiers(Multimap<String, AttributeModifier> modifiers) {
        var containers = this.getContainers();

        for (var entry : modifiers.asMap().entrySet()) {
            if (!containers.containsKey(entry.getKey())) continue;

            var container = containers.get(entry.getKey());

            entry.getValue().forEach(modifier -> container.removeModifier(modifier.id()));
        }
    }

    @Override
    public Multimap<String, AttributeModifier> getSlotModifiers() {
        Multimap<String, AttributeModifier> modifiers = HashMultimap.create();

        this.getContainers().forEach((s, container) -> modifiers.putAll(s, container.getModifiers().values()));

        return modifiers;
    }

    @Override
    public void clearSlotModifiers() {
        this.getContainers().forEach((s, container) -> container.clearModifiers());
    }

    @Override
    public void clearCachedSlotModifiers() {
        var slotModifiers = HashMultimap.<String, AttributeModifier>create();

        var containers = this.getContainers();

        containers.forEach((name, container) -> {
            var modifiers = container.getCachedModifiers();

            if (modifiers.isEmpty()) return;

            var accessories = container.getAccessories();

            for (int i = 0; i < accessories.getContainerSize(); i++) {
                var stack = accessories.getItem(i);

                if (stack.isEmpty()) continue;

                var slotReference = container.createReference(i);

                slotModifiers.putAll(AccessoriesAPI.getAttributeModifiers(stack, slotReference).getSlotModifiers());
            }
        });

        slotModifiers.asMap().forEach((name, modifiers) -> {
            if (!containers.containsKey(name)) return;

            var container = containers.get(name);

            modifiers.forEach(container.getCachedModifiers()::remove);
            container.clearCachedModifiers();
        });
    }

    public Map<AccessoriesContainer, Boolean> getUpdatingInventories() {
        return this.holder().containersRequiringUpdates;
    }

    @Override
    @Nullable
    public Pair<SlotReference, List<ItemStack>> equipAccessory(ItemStack stack, boolean allowSwapping, TriFunction<Accessory, ItemStack, SlotReference, Boolean> additionalCheck) {
        var accessory = AccessoriesAPI.getOrDefaultAccessory(stack);

        if (accessory == null) return null;

        var validContainers = new HashMap<String, AccessoriesContainer>();

        if (stack.isEmpty() && allowSwapping) {
            var allContainers = this.getContainers();

            EntitySlotLoader.getEntitySlots(this.entity())
                    .forEach((s, slotType) -> validContainers.put(s, allContainers.get(slotType.name())));
        } else {
            // First attempt to equip an accessory within empty slot
            for (var container : this.getContainers().values()) {
                if (container.getSize() <= 0) continue;

                var accessories = container.getAccessories();

                boolean isValid = AccessoriesAPI.canInsertIntoSlot(stack, container.createReference(0));

                if (!isValid) continue;

                if (allowSwapping) validContainers.put(container.getSlotName(), container);

                for (int i = 0; i < container.getSize(); i++) {
                    var slotStack = accessories.getItem(i);
                    var slotReference = container.createReference(i);

                    if (!slotStack.isEmpty()) continue;

                    if (!AccessoriesAPI.canUnequip(slotStack, slotReference)) continue;

                    if (additionalCheck.apply(accessory, stack, slotReference) && AccessoriesAPI.canInsertIntoSlot(stack, slotReference)) {
                        var stackCopy = stack.copy();

                        if (!entity.level().isClientSide) {
                            var splitStack = stackCopy.split(accessory.maxStackSize(stackCopy));

                            accessories.setItem(i, splitStack);

                            container.markChanged();
                        }

                        return Pair.of(container.createReference(i), List.of(stackCopy.isEmpty() ? ItemStack.EMPTY : stackCopy));
                    }
                }
            }
        }

        // Second attempt to equip an accessory within the first slot by swapping if allowed
        for (var validContainer : validContainers.values()) {
            var accessories = validContainer.getAccessories();

            for (int i = 0; i < accessories.getContainerSize(); i++) {
                var slotStack = accessories.getItem(i).copy();
                var slotReference = validContainer.createReference(i);

                if (!AccessoriesAPI.canUnequip(slotStack, slotReference) || slotStack.isEmpty()) continue;

                if (stack.isEmpty() || (additionalCheck.apply(accessory, stack, slotReference) && AccessoriesAPI.canInsertIntoSlot(stack, slotReference))) {
                    var stackCopy = stack.copy();

                    var splitStack = stackCopy.isEmpty() ? ItemStack.EMPTY : stackCopy.split(accessory.maxStackSize(stackCopy));

                    if (!entity.level().isClientSide) {
                        accessories.setItem(i, splitStack);

                        validContainer.markChanged();
                    }

                    return Pair.of(slotReference, List.of(stackCopy, slotStack));
                }
            }
        }

        return null;
    }

    public SlotEntryReference getFirstEquipped(Predicate<ItemStack> predicate, EquipmentChecking check) {
        for (var container : this.getContainers().values()) {
            for (var stackEntry : container.getAccessories()) {
                var stack = stackEntry.getSecond();
                var reference = container.createReference(stackEntry.getFirst());

                if(check == EquipmentChecking.COSMETICALLY_OVERRIDABLE) {
                    var cosmetic = container.getCosmeticAccessories().getItem(reference.slot());

                    if(!cosmetic.isEmpty()) stack = cosmetic;
                }

                var entryReference = AccessoryNestUtils.recursiveStackHandling(stack, reference, (innerStack, ref) -> {
                    return (!innerStack.isEmpty() && predicate.test(innerStack))
                            ? new SlotEntryReference(reference, innerStack)
                            : null;
                });

                if (entryReference != null) return entryReference;
            }
        }

        return null;
    }

    @Override
    public List<SlotEntryReference> getEquipped(Predicate<ItemStack> predicate) {
        return getAllEquipped().stream().filter(reference -> predicate.test(reference.stack())).toList();
    }

    @Override
    public List<SlotEntryReference> getAllEquipped(boolean recursiveStackLookup) {
        var references = new ArrayList<SlotEntryReference>();

        for (var container : this.getContainers().values()) {
            for (var stackEntry : container.getAccessories()) {
                var stack = stackEntry.getSecond();

                if (stack.isEmpty()) continue;

                var reference = container.createReference(stackEntry.getFirst());

                if(recursiveStackLookup) {
                    AccessoryNestUtils.recursiveStackConsumption(stack, reference, (innerStack, ref) -> references.add(new SlotEntryReference(ref, innerStack)));
                } else {
                    references.add(new SlotEntryReference(reference, stack));
                }
            }
        }

        return references;
    }

    @Override
    public void write(MapCarrier carrier, SerializationContext ctx) {
        this.holder().write(carrier, ctx);
    }

    @Override
    public void read(MapCarrier carrier, SerializationContext ctx) {
        this.holder().read(carrier, ctx);
    }
}
