package io.wispforest.accessories.networking.server;

import com.mojang.logging.LogUtils;
import io.wispforest.accessories.networking.base.HandledPacketPayload;
import io.wispforest.endec.Endec;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;

public class NukeAccessories implements HandledPacketPayload {

    public static final Endec<NukeAccessories> ENDEC = Endec.of((ctx, ser, v) -> {}, (ctx, deser) -> new NukeAccessories());

    private static final Logger LOGGER = LogUtils.getLogger();

    @Override
    public void handle(Player player) {
        // Only players in creative should be able to nuke their accessories
        if (!player.getAbilities().instabuild) {
            LOGGER.info("A given player sent a NukeAccessories packet not as a Creative Player: [Player: {}]", player.getName());

            return;
        }

        var cap = player.accessoriesCapability();

        if (cap != null) {
            cap.reset(false);

            player.containerMenu.broadcastChanges();
        }
    }
}