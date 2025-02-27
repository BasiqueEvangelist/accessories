package io.wispforest.accessories.impl;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.mojang.datafixers.util.Pair;
import io.wispforest.accessories.api.AccessoriesAPI;
import io.wispforest.accessories.api.AccessoriesCapability;
import io.wispforest.accessories.api.AccessoriesContainer;
import io.wispforest.accessories.api.slot.SlotReference;
import io.wispforest.accessories.api.slot.SlotType;
import io.wispforest.accessories.api.slot.UniqueSlotHandling;
import io.wispforest.accessories.data.SlotTypeLoader;
import io.wispforest.accessories.endec.RegistriesAttribute;
import io.wispforest.accessories.endec.format.nbt.NbtEndec;
import io.wispforest.accessories.utils.AttributeUtils;
import io.wispforest.endec.Endec;
import io.wispforest.endec.SerializationContext;
import io.wispforest.endec.impl.KeyedEndec;
import io.wispforest.endec.util.MapCarrier;
import net.minecraft.Util;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.*;

@ApiStatus.Internal
public class AccessoriesContainerImpl implements AccessoriesContainer, InstanceEndec {

    protected AccessoriesCapability capability;
    private String slotName;

    protected final Map<ResourceLocation, AttributeModifier> modifiers = new HashMap<>();
    protected final Set<AttributeModifier> persistentModifiers = new HashSet<>();
    protected final Set<AttributeModifier> cachedModifiers = new HashSet<>();

    private final Multimap<AttributeModifier.Operation, AttributeModifier> modifiersByOperation = HashMultimap.create();

    @Nullable
    private Integer baseSize;

    private List<Boolean> renderOptions;

    private ExpandedSimpleContainer accessories;
    private ExpandedSimpleContainer cosmeticAccessories;

    private boolean update = false;
    private boolean resizingUpdate = false;

    public AccessoriesContainerImpl(AccessoriesCapability capability, SlotType slotType){
        this.capability = capability;

        this.slotName = slotType.name();
        this.baseSize = slotType.amount();

        this.accessories = new ExpandedSimpleContainer(this.baseSize, "Accessories", false);
        this.cosmeticAccessories = new ExpandedSimpleContainer(this.baseSize, "Cosmetic Accessories", false);

        this.renderOptions = Util.make(new ArrayList<>(baseSize), booleans -> {
            for (int i = 0; i < baseSize; i++) booleans.add(i, true);
        });
    }

    @Nullable
    public Integer getBaseSize(){
        return this.baseSize;
    }

    @Override
    public void markChanged(boolean resizingUpdate){
        this.update = true;
        this.resizingUpdate = resizingUpdate;

        if(this.capability.entity().level().isClientSide) return;

        var inv = ((AccessoriesCapabilityImpl) this.capability).getUpdatingInventories();

        inv.remove(this);
        inv.put(this, resizingUpdate);
    }

    @Override
    public boolean hasChanged() {
        return this.update;
    }

    public void update(){
        var hasChangeOccurred = !this.resizingUpdate;

        if(!update) return;

        this.update = false;

        if(this.capability.entity().level().isClientSide) return;

        var slotType = this.slotType();

        if(this.baseSize == null) this.baseSize = 0;

        if (slotType != null && this.baseSize != slotType.amount()) {
            this.baseSize = slotType.amount();

            hasChangeOccurred = true;
        }

        double baseSize = this.baseSize;

        double size;

        if(UniqueSlotHandling.allowResizing(this.slotName)) {
            for (AttributeModifier modifier : this.getModifiersForOperation(AttributeModifier.Operation.ADD_VALUE)) {
                baseSize += modifier.amount();
            }

            size = baseSize;

            for (AttributeModifier modifier : this.getModifiersForOperation(AttributeModifier.Operation.ADD_MULTIPLIED_BASE)) {
                size += (this.baseSize * modifier.amount());
            }

            for (AttributeModifier modifier : this.getModifiersForOperation(AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL)) {
                size *= modifier.amount();
            }
        } else {
            size = baseSize;
        }

        //--

        var currentSize = (int) Math.round(size);

        if(currentSize != this.accessories.getContainerSize()) {
            hasChangeOccurred = true;

            var invalidAccessories = new ArrayList<Pair<Integer, ItemStack>>();

            var invalidStacks = new ArrayList<ItemStack>();

            var newAccessories = new ExpandedSimpleContainer(currentSize, "Accessories");
            var newCosmetics = new ExpandedSimpleContainer(currentSize, "Cosmetic Accessories");

            for (int i = 0; i < this.accessories.getContainerSize(); i++) {
                if (i < newAccessories.getContainerSize()) {
                    newAccessories.setItem(i, this.accessories.getItem(i));
                    newCosmetics.setItem(i, this.cosmeticAccessories.getItem(i));
                } else {
                    invalidAccessories.add(Pair.of(i, this.accessories.getItem(i)));
                    invalidStacks.add(this.cosmeticAccessories.getItem(i));
                }
            }

            newAccessories.copyPrev(this.accessories);
            newCosmetics.copyPrev(this.cosmeticAccessories);

            this.accessories = newAccessories;
            this.cosmeticAccessories = newCosmetics;

            var newRenderOptions = new ArrayList<Boolean>(currentSize);

            for (int i = 0; i < currentSize; i++) {
                newRenderOptions.add(i < this.renderOptions.size() ? this.renderOptions.get(i) : true);
            }

            this.renderOptions = newRenderOptions;

            var livingEntity = this.capability.entity();

            //TODO: Confirm if such is needed
            for (var invalidAccessory : invalidAccessories) {
                var index = invalidAccessory.getFirst();

                var invalidStack = invalidAccessory.getSecond();

                if (invalidStack.isEmpty()) continue;

                var slotReference = SlotReference.of(livingEntity, this.slotName, index);

                AttributeUtils.removeTransientAttributeModifiers(livingEntity, AccessoriesAPI.getAttributeModifiers(invalidStack, slotReference));

                var accessory = AccessoriesAPI.getAccessory(invalidStack);

                if (accessory != null) accessory.onUnequip(invalidStack, slotReference);

                invalidStacks.add(invalidStack);
            }

            ((AccessoriesHolderImpl) this.capability.getHolder()).invalidStacks.addAll(invalidStacks);

            if (this.update) this.capability.updateContainers();
        }

        if(!hasChangeOccurred) {
            var inv = ((AccessoriesCapabilityImpl) this.capability).getUpdatingInventories();

            inv.remove(this);
        }
    }

    @Override
    public int getSize() {
        this.update();
        return this.accessories.getContainerSize();
    }

    @Override
    public String getSlotName(){
        return this.slotName;
    }

    @Override
    public AccessoriesCapability capability() {
        return this.capability;
    }

    @Override
    public List<Boolean> renderOptions() {
        this.update();
        return this.renderOptions;
    }

    @Override
    public ExpandedSimpleContainer getAccessories() {
        this.update();
        return accessories;
    }

    @Override
    public ExpandedSimpleContainer getCosmeticAccessories() {
        this.update();
        return cosmeticAccessories;
    }

    @Override
    public Map<ResourceLocation, AttributeModifier> getModifiers() {
        return this.modifiers;
    }

    public Set<AttributeModifier> getCachedModifiers(){
        return this.cachedModifiers;
    }

    @Override
    public Collection<AttributeModifier> getModifiersForOperation(AttributeModifier.Operation operation) {
        return this.modifiersByOperation.get(operation);
    }

    @Override
    public void addTransientModifier(AttributeModifier modifier) {
        this.modifiers.put(modifier.id(), modifier);
        this.getModifiersForOperation(modifier.operation()).add(modifier);
        this.markChanged();
    }

    @Override
    public void addPersistentModifier(AttributeModifier modifier) {
        this.addTransientModifier(modifier);
        this.persistentModifiers.add(modifier);
    }

    @Override
    public boolean hasModifier(ResourceLocation location) {
        return this.modifiers.containsKey(location);
    }

    @Override
    public void removeModifier(ResourceLocation location) {
        var modifier = this.modifiers.get(location);

        if(modifier == null) return;

        this.persistentModifiers.remove(modifier);
        this.getModifiersForOperation(modifier.operation()).remove(modifier);
        this.markChanged();
    }

    @Override
    public void clearModifiers() {
        this.getModifiers().keySet().iterator().forEachRemaining(this::removeModifier);
    }

    @Override
    public void removeCachedModifiers(AttributeModifier modifier) {
        this.cachedModifiers.remove(modifier);
    }

    @Override
    public void clearCachedModifiers() {
        this.cachedModifiers.forEach(cachedModifier -> this.removeModifier(cachedModifier.id()));
        this.cachedModifiers.clear();
    }

    //--

    public void copyFrom(AccessoriesContainerImpl other){
        this.modifiers.clear();
        this.modifiersByOperation.clear();
        this.persistentModifiers.clear();
        other.modifiers.values().forEach(this::addTransientModifier);
        other.persistentModifiers.forEach(this::addPersistentModifier);
        this.update();
    }

    //TODO: Confirm Cross Dimension stuff works!
//    public static void copyFrom(LivingEntity oldEntity, LivingEntity newEntity){
//        var api = AccessoriesAccess.getAPI();
//
//        var oldCapability = api.getCapability(oldEntity);
//        var newCapability = api.getCapability(newEntity);
//
//        if(oldCapability.isEmpty() || newCapability.isEmpty()) return;
//
//        var newContainers = newCapability.get().getContainers();
//        for (var containerEntries : oldCapability.get().getContainers().entrySet()) {
//            if(!newContainers.containsKey(containerEntries.getKey())) continue;
//        }
//    }

    //--

    public static final KeyedEndec<String> SLOT_NAME_KEY = Endec.STRING.keyed("SlotName", "UNKNOWN");

    public static final KeyedEndec<Integer> BASE_SIZE_KEY = Endec.INT.keyed("BaseSize", () -> null);

    public static final KeyedEndec<Integer> CURRENT_SIZE_KEY = Endec.INT.keyed("CurrentSize", 0);

    public static final KeyedEndec<List<Boolean>> RENDER_OPTIONS_KEY = Endec.BOOLEAN.listOf().keyed("RenderOptions", ArrayList::new);

    public static final KeyedEndec<List<CompoundTag>> MODIFIERS_KEY = NbtEndec.COMPOUND.listOf().keyed("Modifiers", ArrayList::new);
    public static final KeyedEndec<List<CompoundTag>> PERSISTENT_MODIFIERS_KEY = NbtEndec.COMPOUND.listOf().keyed("PersistentModifiers", ArrayList::new);
    public static final KeyedEndec<List<CompoundTag>> CACHED_MODIFIERS_KEY = NbtEndec.COMPOUND.listOf().keyed("CachedModifiers", ArrayList::new);

    public static final KeyedEndec<ListTag> ITEMS_KEY = NbtEndec.LIST.keyed("Items", ListTag::new);
    public static final KeyedEndec<ListTag> COSMETICS_KEY = NbtEndec.LIST.keyed("Cosmetics", ListTag::new);

    @Override
    public void write(MapCarrier carrier, SerializationContext ctx) {
        write(carrier, ctx, false);
    }

    public void write(MapCarrier carrier, SerializationContext ctx, boolean sync){
        var registryAccess = ctx.requireAttributeValue(RegistriesAttribute.REGISTRIES).registryManager();

        carrier.put(SLOT_NAME_KEY, this.slotName);

        carrier.putIfNotNull(ctx, BASE_SIZE_KEY, this.baseSize);

        carrier.put(RENDER_OPTIONS_KEY, this.renderOptions);

        if(!sync || this.accessories.wasNewlyConstructed()) {
            carrier.put(CURRENT_SIZE_KEY, accessories.getContainerSize());

            carrier.put(ITEMS_KEY, accessories.createTag(registryAccess));
            carrier.put(COSMETICS_KEY, cosmeticAccessories.createTag(registryAccess));
        }

        if(sync){
            if(!this.modifiers.isEmpty()){
                var modifiersTag = new ArrayList<CompoundTag>();

                this.modifiers.values().forEach(modifier -> modifiersTag.add(modifier.save()));

                carrier.put(MODIFIERS_KEY, modifiersTag);
            }
        } else {
            if(!this.persistentModifiers.isEmpty()){
                var persistentTag = new ArrayList<CompoundTag>();

                this.persistentModifiers.forEach(modifier -> persistentTag.add(modifier.save()));

                carrier.put(PERSISTENT_MODIFIERS_KEY, persistentTag);
            }

            if(!this.modifiers.isEmpty()){
                var cachedTag = new ArrayList<CompoundTag>();

                this.modifiers.values().forEach(modifier -> {
                    if(this.persistentModifiers.contains(modifier)) return;

                    cachedTag.add(modifier.save());
                });

                carrier.put(CACHED_MODIFIERS_KEY, cachedTag);
            }
        }
    }

    @Override
    public void read(MapCarrier carrier, SerializationContext ctx) {
        read(carrier, ctx, false);
    }

    public void read(MapCarrier carrier, SerializationContext ctx, boolean sync){
        var registryAccess = ctx.requireAttributeValue(RegistriesAttribute.REGISTRIES).registryManager();

        this.slotName = carrier.get(SLOT_NAME_KEY);

        this.baseSize = carrier.get(BASE_SIZE_KEY);

        this.renderOptions = Util.make(new ArrayList<>(baseSize), booleans -> {
            for (int i = 0; i < baseSize; i++) booleans.add(i, true);
        });

        var readOptions = carrier.get(RENDER_OPTIONS_KEY);

        for (int i = 0; i < readOptions.size(); i++) this.renderOptions.set(i, readOptions.get(i));

        if(carrier.has(CURRENT_SIZE_KEY)) {
            var size = carrier.get(CURRENT_SIZE_KEY);

            if(this.accessories.getContainerSize() != size) {
                this.accessories = new ExpandedSimpleContainer(size, "Accessories");
                this.cosmeticAccessories = new ExpandedSimpleContainer(size, "Cosmetic Accessories");
            }

            this.accessories.fromTag(carrier.get(ITEMS_KEY), registryAccess);
            this.cosmeticAccessories.fromTag(carrier.get(COSMETICS_KEY), registryAccess);
        }

        if(sync) {
            this.modifiers.clear();
            this.persistentModifiers.clear();
            this.modifiersByOperation.clear();

            if (carrier.has(MODIFIERS_KEY)) {
                var persistentTag = carrier.get(MODIFIERS_KEY);

                for (var compoundTag : persistentTag) {
                    var modifier = AttributeModifier.load(compoundTag);

                    if (modifier != null) this.addTransientModifier(modifier);
                }
            }
        } else {
            if (carrier.has(PERSISTENT_MODIFIERS_KEY)) {
                var persistentTag = carrier.get(PERSISTENT_MODIFIERS_KEY);

                for (var compoundTag : persistentTag) {
                    var modifier = AttributeModifier.load(compoundTag);

                    if (modifier != null) this.addPersistentModifier(modifier);
                }
            }

            if (carrier.has(CACHED_MODIFIERS_KEY)) {
                var cachedTag = carrier.get(PERSISTENT_MODIFIERS_KEY);

                for (CompoundTag compoundTag : cachedTag) {
                    var modifier = AttributeModifier.load(compoundTag);

                    if (modifier != null) {
                        this.cachedModifiers.add(modifier);
                        this.addTransientModifier(modifier);
                    }

                    this.update();
                }
            }
        }
    }

    public static SimpleContainer readContainer(MapCarrier carrier, SerializationContext ctx, KeyedEndec<ListTag> key){
        return readContainers(carrier, ctx, key).get(0);
    }

    @SafeVarargs
    public static List<SimpleContainer> readContainers(MapCarrier carrier, SerializationContext ctx, KeyedEndec<ListTag> ...keys){
        var containers = new ArrayList<SimpleContainer>();

        var registryAccess = ctx.requireAttributeValue(RegistriesAttribute.REGISTRIES).registryManager();

        for (var key : keys) {
            var stacks = new SimpleContainer();

            if(carrier.has(key)) stacks.fromTag(carrier.get(key), registryAccess);

            containers.add(stacks);
        }

        return containers;
    }

    public static SimpleContainer copyContainerList(SimpleContainer container){
        return new SimpleContainer(container.getItems().toArray(ItemStack[]::new));
    }
}
