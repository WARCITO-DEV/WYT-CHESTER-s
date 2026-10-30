package com.wyt.chesters;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.ChatFormatting;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * LLAVE DORADA DE CHESTER
 *  - CLICK derecho: invoca a Chester; la llave se consume. Es a prueba de fuego y lava.
 *  - Cuando Chester muere suelta una llave nueva con todo lo que guardaba. Esa llave se recarga
 *    durante 2 minutos (el contador aparece en la descripción) y hasta entonces no sirve.
 *  - Se encuentra en cofres de varias estructuras (ver data/wyt_chesters/loot_modifiers).
 */
public class ChesterKeyItem extends Item {

    public static final long RECARGA_TICKS = 2L * 60L * 20L; // 2 minutos
    static final String TAG_LISTA_EN = "listaEn";             // tick del mundo en que vuelve a estar lista (0 = lista)

    private static final Style DORADO = Style.EMPTY.withColor(0xFFD700);
    private static final Style NARANJA = Style.EMPTY.withColor(0xFF6A00);

    public ChesterKeyItem(Properties props) {
        super(props);
    }

    public static Item.Properties configurar(Item.Properties p) {
        return p.stacksTo(1).fireResistant().component(DataComponents.LORE, new ItemLore(lineas(true, 0)));
    }

    /** Descripción: contador de usos/recarga + instrucciones en dorado + aviso en naranja. */
    static List<Component> lineas(boolean lista, long restanteTicks) {
        List<Component> l = new ArrayList<>();
        if (lista) {
            l.add(Component.literal("Usos: 1/1").withStyle(ChatFormatting.AQUA));
        } else {
            long seg = (restanteTicks + 19) / 20;
            l.add(Component.literal(String.format("Recargando: %d:%02d", seg / 60, seg % 60))
                    .withStyle(ChatFormatting.RED));
        }
        l.add(Component.literal("CLICK derecho invoca a Chester (la llave se consume)").withStyle(DORADO));
        l.add(Component.literal("CLICK derecho abre el inventario de chester").withStyle(DORADO));
        l.add(Component.literal("Shift + CLICK derecho Sienta a chester").withStyle(DORADO));
        l.add(Component.literal("Shift + CLICK derecho con una cubeta lo guarda de nuevo en la llave").withStyle(DORADO));
        l.add(Component.literal("Un jugador puede dañarlo con un hacha (de cualquier tipo)").withStyle(DORADO));
        l.add(Component.literal("No incluye gafas de sol").withStyle(NARANJA));
        l.add(Component.literal("Un amigo de otro mundo, quisa por eso se me hace familiar!!!!")
                .withStyle(ChatFormatting.YELLOW));
        return l;
    }

    static long listaEn(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
                .copyTag().getLongOr(TAG_LISTA_EN, 0L);
    }

    // ---------- Contenido guardado en la llave ----------

    static void guardarContenido(ItemStack llave, SimpleContainer c) {
        List<ItemStack> lista = new ArrayList<>();
        for (int i = 0; i < 27; i++) lista.add(c.getItem(i).copy());
        llave.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(lista));
    }

    static void cargarContenido(ItemStack llave, SimpleContainer c) {
        ItemContainerContents contenido = llave.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
        NonNullList<ItemStack> lista = NonNullList.withSize(27, ItemStack.EMPTY);
        contenido.copyInto(lista);
        for (int i = 0; i < 27; i++) c.setItem(i, lista.get(i));
    }

    /** Llave que suelta Chester al morir: lleva su inventario y empieza a recargarse. */
    public static ItemStack crearLlaveRecargando(ServerLevel sl, SimpleContainer inventario) {
        ItemStack llave = new ItemStack(ChestersMod.CHESTER_KEY.get());
        guardarContenido(llave, inventario);
        long lista = sl.getGameTime() + RECARGA_TICKS;
        CustomData.update(DataComponents.CUSTOM_DATA, llave, tag -> tag.putLong(TAG_LISTA_EN, lista));
        llave.set(DataComponents.LORE, new ItemLore(lineas(false, RECARGA_TICKS)));
        return llave;
    }

    /** Llave lista para usar (al recoger a Chester con una cubeta): con su contenido y sin tiempo de espera. */
    public static ItemStack crearLlaveLista(SimpleContainer inventario) {
        ItemStack llave = new ItemStack(ChestersMod.CHESTER_KEY.get());
        guardarContenido(llave, inventario);
        return llave;
    }

    // ---------- Uso ----------

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        if (level instanceof ServerLevel sl) {
            long lista = listaEn(stack);
            if (lista != 0 && sl.getGameTime() < lista) {
                return InteractionResult.PASS; // todavía se está recargando
            }

            ChesterEntity chester = new ChesterEntity(ChestersMod.CHESTER.get(), sl);
            Vec3 mirada = player.getLookAngle();
            chester.setPos(player.getX() + mirada.x * 1.5, player.getY(), player.getZ() + mirada.z * 1.5);
            chester.setDueno(player);
            cargarContenido(stack, chester.container); // recupera lo que tenía guardado
            sl.addFreshEntity(chester);

            sl.sendParticles(ParticleTypes.POOF, chester.getX(), chester.getY(0.5), chester.getZ(), 10, 0.3, 0.3, 0.3, 0.02);
            sl.playSound(null, player.blockPosition(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1f, 1.2f);

            stack.shrink(1); // la llave desaparece al invocar
        }
        return InteractionResult.SUCCESS;
    }

    // Contador de recarga en la descripción (se actualiza cada segundo mientras está en un inventario)
    @Override
    public void inventoryTick(ItemStack stack, ServerLevel level, Entity entity, EquipmentSlot slot) {
        if (level.getGameTime() % 20 != 0) return;

        long lista = listaEn(stack);
        long ahora = level.getGameTime();
        boolean lista_ok = lista == 0 || ahora >= lista;
        if (lista != 0 && lista_ok) {
            CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putLong(TAG_LISTA_EN, 0L));
        }

        ItemLore nueva = new ItemLore(lineas(lista_ok, lista - ahora));
        if (!nueva.equals(stack.get(DataComponents.LORE))) {
            stack.set(DataComponents.LORE, nueva);
        }
    }
}
