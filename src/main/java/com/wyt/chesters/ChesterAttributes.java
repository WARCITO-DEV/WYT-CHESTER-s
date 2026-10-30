package com.wyt.chesters;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;

@EventBusSubscriber(modid = "wyt_chesters")
public class ChesterAttributes {

    @SubscribeEvent
    public static void atributos(EntityAttributeCreationEvent event) {
        event.put(ChestersMod.CHESTER.get(), ChesterEntity.crearAtributos().build());
    }
}
