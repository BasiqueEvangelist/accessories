package io.wispforest.accessories.compat;

import io.wispforest.accessories.Accessories;
import io.wispforest.owo.config.annotation.*;
import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.ConfigEntry;

import java.util.ArrayList;
import java.util.List;

@Config(name = Accessories.MODID, wrapperName = "AccessoriesConfig")
public class AccessoriesConfigModel {

    @Nest
    @Expanded
    public ClientData clientData = new ClientData();

    public static class ClientData {
        public boolean showGroupTabs = true;

        @Hook
        public boolean showUniqueRendering = false;
        public boolean showLineRendering = true;

        public int inventoryButtonXOffset = 66;
        public int inventoryButtonYOffset = 9;

        public int creativeInventoryButtonXOffset = 96;
        public int creativeInventoryButtonYOffset = 7;

        public boolean forceNullRenderReplacement = false;

        public boolean disableEmptySlotScreenError = false;
    }

    public List<SlotAmountModifier> modifiers = new ArrayList<>();

    public static class SlotAmountModifier {
        public String slotType = "";
        public int amount = 0;
    }
}