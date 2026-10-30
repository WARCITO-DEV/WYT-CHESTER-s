package com.wyt.chesters;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** WYT Chester's: Chester, el cofre aliado, y su Llave Dorada. */
@Mod(ChestersMod.MODID)
public class ChestersMod {
    public static final String MODID = "wyt_chesters";

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    public static final DeferredRegister.Entities ENTITIES = DeferredRegister.createEntities(MODID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    // ---- Llave Dorada de Chester ----
    public static final DeferredItem<Item> CHESTER_KEY = ITEMS.registerItem(
            "chester_key", ChesterKeyItem::new,
            props -> ChesterKeyItem.configurar(props));

    // ---- Chester ----
    public static final DeferredHolder<EntityType<?>, EntityType<ChesterEntity>> CHESTER =
            ENTITIES.registerEntityType("chester", ChesterEntity::new, MobCategory.MISC,
                    builder -> builder.sized(0.75f, 0.85f).clientTrackingRange(10));

    // ---- Pestaña propia del modo creativo ----
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB_PRINCIPAL = CREATIVE_TABS.register(
            "principal", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.wyt_chesters"))
                    .icon(() -> new ItemStack(CHESTER_KEY.get()))
                    .displayItems((parametros, salida) -> salida.accept(CHESTER_KEY.get()))
                    .build());

    public ChestersMod(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
        ENTITIES.register(modEventBus);
        CREATIVE_TABS.register(modEventBus);
    }
}
