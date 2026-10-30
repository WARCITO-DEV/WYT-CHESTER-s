package com.wyt.chesters;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/** Registro del renderer de Chester (solo en el cliente). */
@EventBusSubscriber(modid = "wyt_chesters", value = Dist.CLIENT)
public class ChesterClient {

    @SubscribeEvent
    public static void registrarRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ChestersMod.CHESTER.get(), ChesterRenderer::new);
    }
}
