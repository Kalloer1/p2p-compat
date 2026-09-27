package com.kalloer1.p2parsnouveau;

import com.kalloer1.p2p.P2P;
import com.kalloer1.p2p.medium.Medium;
import com.kalloer1.p2p.medium.Mediums;
import net.neoforged.bus.api.IEventBus;

/**
 * Ars Nouveau compat（魔源介质）：仅在 ars_nouveau 模组加载时由统一入口 p2p_compat 注册。
 */
public final class CompatArsNouveau {
    public static final String MODID = "p2parsnouveau";

    private CompatArsNouveau() {}

    public static void register(IEventBus modEventBus) {
        Mediums.register(new Medium("arsnouveau:source", 0xFF6a5acd, "p2p.arsnouveau.source", 5, ArsSourceTransfer::transfer));
        P2P.LOGGER.info("[p2parsnouveau] media registered (source)");
    }
}
