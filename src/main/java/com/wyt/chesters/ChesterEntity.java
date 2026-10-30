package com.wyt.chesters;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.geckolib.animatable.GeoEntity;
import com.geckolib.animatable.instance.AnimatableInstanceCache;
import com.geckolib.animatable.manager.AnimatableManager;
import com.geckolib.animation.AnimationController;
import com.geckolib.animation.RawAnimation;
import com.geckolib.animation.object.PlayState;
import com.geckolib.animation.state.AnimationTest;
import com.geckolib.util.GeckoLibUtil;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.NonNullList;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.AvoidEntityGoal;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

/**
 * CHESTER: cofre aliado con patas (animado con GeckoLib).
 *
 *  - 20 de vida, resistencia al empuje, inmune a la caída; los jugadores solo pueden herirlo con hacha.
 *  - Inventario de 27 espacios. CLICK derecho lo abre, Shift + CLICK derecho lo sienta.
 *  - SIGUE al jugador CAMINANDO a su lado (se acelera si se queda atrás). Solo se teletransporta
 *    en casos extremos: el jugador va en montura / volando / muy rápido, o está muy lejos o Chester se atasca.
 *  - Busca objetos del suelo por su cuenta (hasta 19 bloques), prefiere los cercanos al jugador, corre
 *    de inmediato a los que el jugador tira, los guarda con la animación de comer y se cura.
 *  - Si el jugador está AFK más de 5 s, vaga por la zona (hasta 19 bloques) sin dejar de ir a por objetos.
 *  - Si se llena, busca la tolva más cercana, se sienta encima y se vacía. Si la tolva está bloqueada,
 *    huele (OLFATO) y prueba con otra. Al terminar de llenarse o vaciarse: eructo agudo + humo blanco.
 *  - Con varios Chester se reparten objetos y tolvas y se colocan en puestos distintos alrededor del jugador.
 *  - Huye de los monstruos y del fuego (busca agua si se quema). Atado con cuerda no se teletransporta ni sigue.
 *  - Al morir suelta su llave (con su contenido), que se recarga durante 2 minutos.
 */
public class ChesterEntity extends PathfinderMob implements GeoEntity {

    private static final EntityDataAccessor<Boolean> DATA_SENTADO =
            SynchedEntityData.defineId(ChesterEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> DATA_ABIERTO =
            SynchedEntityData.defineId(ChesterEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> DATA_HUYENDO =
            SynchedEntityData.defineId(ChesterEntity.class, EntityDataSerializers.BOOLEAN);

    // ---------------- Ajustes ----------------
    static final byte EVENTO_EXPRESIONES = 61;
    static final byte EVENTO_OLFATO = 62;

    static final double RADIO_OBJETOS = 19.0;          // ve objetos hasta a 19 bloques
    static final double RADIO_OBJETOS_ATADO = 5.0;
    static final double RADIO_DEAMBULAR = 19.0;
    static final int DUENO_AFK_TICKS = 100;            // 5 s sin moverse -> vaga
    static final double DISTANCIA_SEGUIR = 3.5;        // empieza a caminar hacia el jugador a partir de aquí
    static final double DISTANCIA_PARAR = 2.0;
    static final double DISTANCIA_TP_RAPIDO = 20.0;    // teletransporte si el jugador va muy rápido
    static final double DISTANCIA_TP_LEJOS = 48.0;     // teletransporte si está lejísimos
    static final float CURA_POR_OBJETO = 2.0f;         // 1 corazón por objeto

    // ---------------- Animaciones de tu archivo chester.animation.json (sin cambios) ----------------
    private static final RawAnimation ANIM_IDLE = RawAnimation.begin().thenLoop("estatico_cerrado");
    private static final RawAnimation ANIM_CAMINAR = RawAnimation.begin().thenLoop("walk");
    private static final RawAnimation ANIM_CORRER = RawAnimation.begin().thenLoop("CORRER");
    private static final RawAnimation ANIM_SENTADO = RawAnimation.begin().thenLoop("SENTADO");
    private static final RawAnimation ANIM_ABRIR = RawAnimation.begin().thenPlay("abriendose").thenLoop("estatico_abierto");
    private static final RawAnimation ANIM_ABRIR_SENTADO = RawAnimation.begin().thenPlay("abriendose_sentado");
    // Animación de comer: en tu archivo se llama "EXPRESIONES" (no existe ninguna llamada "expresion_comer")
    private static final RawAnimation ANIM_EXPRESION = RawAnimation.begin().thenPlay("EXPRESIONES");
    private static final RawAnimation ANIM_OLFATO = RawAnimation.begin().thenPlay("OLFATO");
    private static final RawAnimation ANIM_MUERTE = RawAnimation.begin().thenPlayAndHold("MUERTE");

    /** Reservas compartidas entre varios Chester (para que no vayan todos al mismo objeto o tolva). */
    private record Reclamo(UUID chester, long expira) { }
    private static final Map<UUID, Reclamo> OBJETOS_RECLAMADOS = new HashMap<>();
    private static final Map<BlockPos, Reclamo> TOLVAS_RECLAMADAS = new HashMap<>();

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    /** Inventario de Chester (27 espacios). */
    public final SimpleContainer container = new SimpleContainer(27);

    private UUID duenoId;

    // Estado del "cerebro" (servidor)
    int ticksDuenoQuieto;
    private Vec3 ultimaPosDueno;
    private boolean huyendoDeMobs;
    private boolean abiertoPrevio;
    private boolean inicializado;
    private boolean llenoPrevio;
    private int enfriamientoOlfato;
    private int pausa;                      // pausa corta tras comer
    private ItemEntity itemObjetivo;
    private ItemEntity itemPrioritario;     // objeto que acaba de tirar el jugador
    private long prioritarioDesde;
    private final Map<UUID, Long> objetosIgnorados = new HashMap<>();
    private BlockPos tolvaObjetivo;
    private final Map<BlockPos, Long> tolvasBloqueadas = new HashMap<>();
    private boolean sentadoPorTolva;
    private boolean descargoAlgo;
    private int intervalosSinDescargar;
    private int avisosBloqueo;
    private int ticksVagar;
    private double ultimaDistDueno;
    private int ticksAtascado;

    // Solo cliente
    private int ticksExpresiones;
    private int ticksOlfato;
    private RawAnimation animActual = ANIM_IDLE;

    public ChesterEntity(EntityType<? extends ChesterEntity> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder crearAtributos() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.ARMOR, 20.0)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.2)
                .add(Attributes.MOVEMENT_SPEED, 0.35)
                .add(Attributes.FOLLOW_RANGE, 48.0);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_SENTADO, false);
        builder.define(DATA_ABIERTO, false);
        builder.define(DATA_HUYENDO, false);
    }

    // ---------------- GeckoLib ----------------

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<ChesterEntity>("chester", 3, this::elegirAnimacion));
    }

    private PlayState elegirAnimacion(AnimationTest<ChesterEntity> test) {
        return test.setAndContinue(this.animActual);
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.geoCache;
    }

    // ---------------- Estado ----------------

    public boolean isSentado() { return this.entityData.get(DATA_SENTADO); }
    public void setSentado(boolean v) { this.entityData.set(DATA_SENTADO, v); }
    public boolean isAbierto() { return this.entityData.get(DATA_ABIERTO); }
    public boolean isHuyendo() { return this.entityData.get(DATA_HUYENDO); }
    public void setHuyendo(boolean v) { this.entityData.set(DATA_HUYENDO, v); }

    public void setDueno(Player dueno) { this.duenoId = dueno.getUUID(); }
    public boolean perteneceA(Player jugador) { return this.duenoId != null && this.duenoId.equals(jugador.getUUID()); }

    public Player getDueno() {
        return duenoId == null ? null : this.level().getPlayerByUUID(duenoId);
    }

    /** El jugador acaba de tirar este objeto: Chester corre a por él de inmediato. */
    public void setItemPrioritario(ItemEntity item) {
        if (this.isSentado() || this.isAbierto() || this.isLeashed()) return;
        if (!this.container.canAddItem(item.getItem())) return;
        this.itemPrioritario = item;
        this.itemObjetivo = item;
        this.prioritarioDesde = this.level().getGameTime();
        this.sentadoPorTolva = false;
        this.tolvaObjetivo = null;
    }

    @Override
    public boolean removeWhenFarAway(double distancia) {
        return false;
    }

    private boolean puedeMoverse() {
        return !this.isSentado() && !this.isAbierto();
    }

    /** "Lleno": no le queda ningún hueco libre. */
    private boolean estaLleno() {
        for (int i = 0; i < this.container.getContainerSize(); i++) {
            if (this.container.getItem(i).isEmpty()) return false;
        }
        return true;
    }

    private boolean estaVacio() {
        for (int i = 0; i < this.container.getContainerSize(); i++) {
            if (!this.container.getItem(i).isEmpty()) return false;
        }
        return true;
    }

    // ---------------- Sonidos (acepta tanto SoundEvent como Holder<SoundEvent>) ----------------

    private static SoundEvent sonido(Object o) {
        if (o instanceof SoundEvent s) return s;
        if (o instanceof Holder<?> h && h.value() instanceof SoundEvent s2) return s2;
        return SoundEvents.ENDERMAN_TELEPORT;
    }

    // Al caminar suena a madera, al ser golpeado a madera y al morir como un shulker (sin @Override a propósito)
    protected void playStepSound(BlockPos pos, BlockState state) {
        this.playSound(sonido(SoundEvents.WOOD_STEP), 0.15f, 1.0f);
    }

    protected SoundEvent getHurtSound(DamageSource source) {
        return sonido(SoundEvents.WOOD_HIT);
    }

    protected SoundEvent getDeathSound() {
        return sonido(SoundEvents.SHULKER_DEATH);
    }

    // ---------------- Guardado en el mundo ----------------

    @Override
    public void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        if (duenoId != null) output.putString("Dueno", duenoId.toString());
        output.putBoolean("Sentado", isSentado());
        NonNullList<ItemStack> lista = NonNullList.withSize(27, ItemStack.EMPTY);
        for (int i = 0; i < 27; i++) lista.set(i, this.container.getItem(i));
        ContainerHelper.saveAllItems(output, lista);
    }

    @Override
    public void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        String id = input.getStringOr("Dueno", "");
        if (!id.isEmpty()) {
            try {
                this.duenoId = UUID.fromString(id);
            } catch (IllegalArgumentException ignored) {
            }
        }
        setSentado(input.getBooleanOr("Sentado", false));
        NonNullList<ItemStack> lista = NonNullList.withSize(27, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(input, lista);
        for (int i = 0; i < 27; i++) this.container.setItem(i, lista.get(i));
    }

    // ---------------- IA (objetivos) ----------------

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new HuirGoal(this));
        this.goalSelector.addGoal(1, new BuscarAguaGoal(this));
        this.goalSelector.addGoal(2, new ComportamientoGoal(this));
    }

    /** Huye de los monstruos hostiles (animación CORRER mientras dura). */
    private static class HuirGoal extends AvoidEntityGoal<Monster> {
        private final ChesterEntity chester;

        HuirGoal(ChesterEntity chester) {
            super(chester, Monster.class, 10.0F, 1.1, 1.6);
            this.chester = chester;
        }

        @Override
        public boolean canUse() {
            return chester.puedeMoverse() && super.canUse();
        }

        @Override
        public void start() {
            super.start();
            chester.huyendoDeMobs = true;
        }

        @Override
        public void stop() {
            super.stop();
            chester.huyendoDeMobs = false;
        }
    }

    /** Si se está quemando, busca una fuente de agua cercana para apagarse. */
    private static class BuscarAguaGoal extends Goal {
        private final ChesterEntity chester;
        private BlockPos agua;

        BuscarAguaGoal(ChesterEntity chester) {
            this.chester = chester;
            this.setFlags(EnumSet.of(Goal.Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            if (!chester.isOnFire() || chester.isInWater()) return false;
            this.agua = buscarAgua();
            return this.agua != null;
        }

        @Override
        public boolean canContinueToUse() {
            return chester.isOnFire() && !chester.getNavigation().isDone();
        }

        @Override
        public void start() {
            chester.getNavigation().moveTo(agua.getX() + 0.5, agua.getY(), agua.getZ() + 0.5, 1.6);
        }

        private BlockPos buscarAgua() {
            BlockPos base = chester.blockPosition();
            BlockPos mejor = null;
            double mejorDist = Double.MAX_VALUE;
            for (int dx = -12; dx <= 12; dx++) {
                for (int dz = -12; dz <= 12; dz++) {
                    for (int dy = -3; dy <= 3; dy++) {
                        BlockPos p = base.offset(dx, dy, dz);
                        if (chester.level().getFluidState(p).is(FluidTags.WATER)) {
                            double d = p.distSqr(base);
                            if (d < mejorDist) {
                                mejorDist = d;
                                mejor = p;
                            }
                        }
                    }
                }
            }
            return mejor;
        }
    }

    /** El "cerebro": objetos, tolvas, seguir al jugador y vagar. Se ejecuta cada tick mientras puede moverse. */
    private static class ComportamientoGoal extends Goal {
        private final ChesterEntity chester;

        ComportamientoGoal(ChesterEntity chester) {
            this.chester = chester;
            this.setFlags(EnumSet.of(Goal.Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            return chester.puedeMoverse() && !chester.isHuyendo() && chester.pausa <= 0;
        }

        @Override
        public boolean canContinueToUse() {
            return canUse();
        }

        @Override
        public void tick() {
            if (chester.level() instanceof ServerLevel sl) chester.cerebro(sl);
        }

        @Override
        public void stop() {
            chester.getNavigation().stop();
        }
    }

    // ---------------- Cerebro ----------------

    private void cerebro(ServerLevel sl) {
        long ahora = sl.getGameTime();
        Player dueno = this.getDueno();
        boolean atado = this.isLeashed();

        // 1) Lleno -> ir a una tolva a vaciarse
        if (this.estaLleno() && this.irATolva(sl, ahora)) return;

        // 2) Objetos del suelo (los que tira el jugador tienen prioridad)
        if (this.irAObjeto(sl, dueno, atado, ahora)) return;

        // 3) Atado: no sigue ni vaga
        if (atado || dueno == null) return;

        // 4) Seguir al jugador o vagar
        this.seguirOVagar(sl, dueno, ahora);
    }

    // ---- Tolvas ----

    private boolean irATolva(ServerLevel sl, long ahora) {
        if (this.tolvaObjetivo != null) {
            Long bloq = this.tolvasBloqueadas.get(this.tolvaObjetivo);
            if (!sl.getBlockState(this.tolvaObjetivo).is(Blocks.HOPPER) || (bloq != null && bloq > ahora)) {
                this.tolvaObjetivo = null;
            }
        }
        if (this.tolvaObjetivo == null && this.tickCount % 20 == 0) {
            this.tolvaObjetivo = this.buscarTolva(sl, ahora);
        }
        if (this.tolvaObjetivo == null) {
            // Lleno y sin tolva cerca: huele de vez en cuando para avisar
            if (this.enfriamientoOlfato <= 0 && this.tickCount % 100 == 0) this.oler(sl);
            return false;
        }

        TOLVAS_RECLAMADAS.put(this.tolvaObjetivo, new Reclamo(this.getUUID(), ahora + 60));
        double dx = this.getX() - (this.tolvaObjetivo.getX() + 0.5);
        double dz = this.getZ() - (this.tolvaObjetivo.getZ() + 0.5);
        boolean encima = dx * dx + dz * dz < 0.8 * 0.8
                && this.getY() >= this.tolvaObjetivo.getY() + 0.4 && this.getY() <= this.tolvaObjetivo.getY() + 1.8;
        if (encima) {
            // Llegó: se sienta sobre la tolva y empieza a vaciarse
            this.setSentado(true);
            this.sentadoPorTolva = true;
            this.descargoAlgo = false;
            this.intervalosSinDescargar = 0;
            this.avisosBloqueo = 0;
            this.getNavigation().stop();
            return true;
        }
        if (this.tickCount % 5 == 0 || this.getNavigation().isDone()) {
            this.getNavigation().moveTo(this.tolvaObjetivo.getX() + 0.5, this.tolvaObjetivo.getY() + 1.0,
                    this.tolvaObjetivo.getZ() + 0.5, 1.3);
        }
        return true;
    }

    private BlockPos buscarTolva(ServerLevel sl, long ahora) {
        BlockPos base = this.blockPosition();
        BlockPos mejor = null;
        double mejorDist = Double.MAX_VALUE;
        for (int dx = -16; dx <= 16; dx++) {
            for (int dz = -16; dz <= 16; dz++) {
                for (int dy = -5; dy <= 5; dy++) {
                    BlockPos p = base.offset(dx, dy, dz);
                    if (!sl.getBlockState(p).is(Blocks.HOPPER)) continue;
                    Long bloq = this.tolvasBloqueadas.get(p);
                    if (bloq != null && bloq > ahora) continue;
                    if (reclamadoPorOtro(TOLVAS_RECLAMADAS, p, ahora)) continue;
                    if (!sl.getBlockState(p.above()).isAir()) continue;
                    double d = p.distSqr(base);
                    if (d < mejorDist) {
                        mejorDist = d;
                        mejor = p.immutable();
                    }
                }
            }
        }
        return mejor;
    }

    private <K> boolean reclamadoPorOtro(Map<K, Reclamo> mapa, K clave, long ahora) {
        Reclamo r = mapa.get(clave);
        if (r == null) return false;
        if (r.expira() < ahora) {
            mapa.remove(clave);
            return false;
        }
        return !r.chester().equals(this.getUUID());
    }

    // ---- Objetos ----

    private boolean irAObjeto(ServerLevel sl, Player dueno, boolean atado, long ahora) {
        if (this.itemPrioritario != null
                && (!this.itemPrioritario.isAlive() || ahora - this.prioritarioDesde > 600)) {
            this.itemPrioritario = null;
        }
        if (this.itemObjetivo != null && (!this.itemObjetivo.isAlive()
                || !this.container.canAddItem(this.itemObjetivo.getItem())
                || reclamadoPorOtro(OBJETOS_RECLAMADOS, this.itemObjetivo.getUUID(), ahora))) {
            this.itemObjetivo = null;
        }
        if (this.itemObjetivo == null && this.tickCount % 4 == 0) {
            this.itemObjetivo = this.elegirObjeto(sl, dueno, atado, ahora);
        }
        if (this.itemObjetivo == null) return false;

        OBJETOS_RECLAMADOS.put(this.itemObjetivo.getUUID(), new Reclamo(this.getUUID(), ahora + 30));
        if (this.tickCount % 4 == 0 || this.getNavigation().isDone()) {
            boolean hayCamino = this.getNavigation().moveTo(this.itemObjetivo, 1.4);
            if (!hayCamino && this.distanceToSqr(this.itemObjetivo) > 2.5) {
                // Inalcanzable: se olvida de él un rato
                this.objetosIgnorados.put(this.itemObjetivo.getUUID(), ahora + 300);
                this.itemObjetivo = null;
                return false;
            }
        }
        return true;
    }

    /** Elige el mejor objeto: primero el que acaba de tirar el jugador; después los cercanos al jugador. */
    private ItemEntity elegirObjeto(ServerLevel sl, Player dueno, boolean atado, long ahora) {
        if (this.itemPrioritario != null && this.itemPrioritario.isAlive()
                && this.container.canAddItem(this.itemPrioritario.getItem())) {
            return this.itemPrioritario;
        }

        double radio = atado ? RADIO_OBJETOS_ATADO : RADIO_OBJETOS;
        boolean duenoAfk = this.ticksDuenoQuieto >= DUENO_AFK_TICKS;
        List<ItemEntity> objetos = sl.getEntitiesOfClass(ItemEntity.class,
                this.getBoundingBox().inflate(radio, 8.0, radio),
                i -> i.isAlive() && this.container.canAddItem(i.getItem()));

        ItemEntity mejor = null;
        double mejorPuntaje = Double.MAX_VALUE;
        for (ItemEntity it : objetos) {
            Long ign = this.objetosIgnorados.get(it.getUUID());
            if (ign != null && ign > ahora) continue;
            if (reclamadoPorOtro(OBJETOS_RECLAMADOS, it.getUUID(), ahora)) continue;

            double dChester = this.distanceToSqr(it);
            double puntaje = dChester;
            if (dueno != null && !atado) {
                double dDueno = dueno.distanceToSqr(it);
                if (dDueno < 10.0 * 10.0) {
                    puntaje = dChester * 0.25;          // prefiere los objetos cercanos al jugador
                } else if (!duenoAfk && dDueno > 16.0 * 16.0) {
                    continue;                           // si el jugador va andando no se desvía demasiado
                }
            }
            if (puntaje < mejorPuntaje) {
                mejorPuntaje = puntaje;
                mejor = it;
            }
        }
        return mejor;
    }

    // ---- Seguir / vagar ----

    private void seguirOVagar(ServerLevel sl, Player dueno, long ahora) {
        double dist = Math.sqrt(this.distanceToSqr(dueno));
        boolean afk = this.ticksDuenoQuieto >= DUENO_AFK_TICKS;

        this.comprobarTeletransporte(dueno, dist, afk);

        if (!afk || dist > RADIO_DEAMBULAR) {
            // CAMINA junto al jugador; si se queda atrás, acelera
            if (dist > DISTANCIA_SEGUIR) {
                double vel = Mth.clamp(1.0 + (dist - 4.0) * 0.12, 1.0, 2.3);
                if (this.tickCount % 4 == 0 || this.getNavigation().isDone()) {
                    Vec3 punto = this.puestoJuntoAlJugador(dueno, dist);
                    this.getNavigation().moveTo(punto.x, punto.y, punto.z, vel);
                }
            } else if (dist < DISTANCIA_PARAR && !this.getNavigation().isDone()) {
                this.getNavigation().stop();
            }
        } else {
            // Jugador AFK: vaga por la zona
            if (this.getNavigation().isDone()) {
                if (this.ticksVagar > 0) {
                    this.ticksVagar--;
                } else {
                    double ang = this.random.nextDouble() * Math.PI * 2.0;
                    double r = 3.0 + this.random.nextDouble() * (RADIO_DEAMBULAR - 3.0);
                    this.getNavigation().moveTo(dueno.getX() + Math.cos(ang) * r, dueno.getY(),
                            dueno.getZ() + Math.sin(ang) * r, 0.8);
                    this.ticksVagar = 30 + this.random.nextInt(70);
                }
            }
        }
    }

    /** Cada Chester tiene su propio puesto alrededor del jugador, para que no se amontonen. */
    private Vec3 puestoJuntoAlJugador(Player dueno, double dist) {
        if (dist > 10.0) return dueno.position();
        double ang = ((this.getUUID().hashCode() & 0xFFFF) / 65535.0) * Math.PI * 2.0;
        return new Vec3(dueno.getX() + Math.cos(ang) * 2.2, dueno.getY(), dueno.getZ() + Math.sin(ang) * 2.2);
    }

    /**
     * El teletransporte es el último recurso: solo si el jugador va demasiado rápido (montura, volando,
     * elytra), está lejísimos, o Chester lleva varios segundos atascado sin acercarse.
     */
    private void comprobarTeletransporte(Player dueno, double dist, boolean afk) {
        if (this.isSentado() || this.isAbierto() || this.isLeashed()) return;

        boolean rapido = dueno.isPassenger() || dueno.getAbilities().flying || dueno.isFallFlying()
                || dueno.getDeltaMovement().horizontalDistance() > 0.6;
        boolean teletransportar = (dist > DISTANCIA_TP_RAPIDO && rapido) || dist > DISTANCIA_TP_LEJOS;

        if (this.tickCount % 20 == 0) {
            if (!afk && dist > 14.0 && dist >= this.ultimaDistDueno - 0.5) {
                this.ticksAtascado++;
            } else {
                this.ticksAtascado = 0;
            }
            this.ultimaDistDueno = dist;
            if (this.ticksAtascado >= 8 && dist > DISTANCIA_TP_RAPIDO) teletransportar = true;
        }

        if (teletransportar) {
            this.setPos(dueno.getX() + (this.random.nextDouble() - 0.5) * 3.0, dueno.getY(),
                    dueno.getZ() + (this.random.nextDouble() - 0.5) * 3.0);
            this.getNavigation().stop();
            this.ticksAtascado = 0;
            this.itemObjetivo = null;
        }
    }

    // ---------------- Daño: solo con hacha ----------------

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        Entity atacante = source.getEntity();
        if (atacante instanceof Player jugador && !jugador.getMainHandItem().is(ItemTags.AXES)) {
            return false; // los jugadores solo pueden herirlo con un hacha (de cualquier tipo)
        }
        return super.hurtServer(level, source, amount);
    }

    // ---------------- Interacción ----------------

    private void avisar(Player player, String texto) {
        if (player instanceof ServerPlayer sp) {
            sp.connection.send(new ClientboundSetActionBarTextPacket(Component.literal(texto)));
            this.level().playSound(null, player.blockPosition(), sonido(SoundEvents.EXPERIENCE_ORB_PICKUP),
                    SoundSource.PLAYERS, 0.6f, 1.0f);
        }
    }

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (this.isDeadOrDying()) return InteractionResult.PASS;
        if (this.level().isClientSide()) return InteractionResult.SUCCESS;

        if (this.duenoId == null) this.duenoId = player.getUUID();
        if (!player.getUUID().equals(this.duenoId)) return InteractionResult.PASS; // solo su dueño

        ItemStack mano = player.getItemInHand(hand);
        if (player.isShiftKeyDown() && mano.is(Items.BUCKET)) {
            // Con una cubeta se guarda de nuevo en su llave: con todo su contenido y sin tiempo de espera
            ItemStack llave = ChesterKeyItem.crearLlaveLista(this.container);
            if (!player.getInventory().add(llave)) {
                this.level().addFreshEntity(new ItemEntity(this.level(), player.getX(), player.getY(), player.getZ(), llave));
            }
            this.container.clearContent();
            this.discard();
            return InteractionResult.SUCCESS;
        }

        if (player.isShiftKeyDown()) {
            this.sentadoPorTolva = false;
            this.tolvaObjetivo = null;
            setSentado(!isSentado());
            this.getNavigation().stop();
            avisar(player, isSentado() ? "Chester: sentado" : "Chester: te sigue");
        } else {
            player.openMenu(new SimpleMenuProvider(
                    (id, inv, p) -> ChestMenu.threeRows(id, inv, this.container),
                    Component.literal("Chester")));
        }
        return InteractionResult.SUCCESS;
    }

    // ---------------- Muerte: suelta la llave con todo su contenido ----------------

    @Override
    public void die(DamageSource source) {
        if (this.level() instanceof ServerLevel sl && !this.isRemoved()) {
            ItemStack llave = ChesterKeyItem.crearLlaveRecargando(sl, this.container);
            sl.addFreshEntity(new ItemEntity(sl, this.getX(), this.getY(0.5), this.getZ(), llave));
            this.container.clearContent();
        }
        super.die(source);
    }

    // ---------------- Tick ----------------

    @Override
    public void tick() {
        super.tick();

        if (!(this.level() instanceof ServerLevel sl)) {
            this.tickCliente();
            return;
        }
        if (this.isDeadOrDying()) return;

        this.fallDistance = 0; // inmune a la caída
        if (this.enfriamientoOlfato > 0) this.enfriamientoOlfato--;
        if (this.pausa > 0) this.pausa--;

        // ¿Hay algún jugador con su inventario abierto?
        boolean abierto = false;
        for (ServerPlayer p : sl.players()) {
            if (p.containerMenu instanceof ChestMenu menu && menu.getContainer() == this.container) {
                abierto = true;
                break;
            }
        }
        if (abierto != this.isAbierto()) this.entityData.set(DATA_ABIERTO, abierto);
        if (abierto != this.abiertoPrevio) {
            this.abiertoPrevio = abierto;
            sl.playSound(null, this.blockPosition(), sonido(abierto ? SoundEvents.SHULKER_OPEN : SoundEvents.SHULKER_CLOSE),
                    SoundSource.NEUTRAL, 1.0f, 1.6f); // como un shulker, pero más agudo
        }
        if (abierto || this.isSentado()) this.getNavigation().stop();

        // Huir: de monstruos o del fuego
        boolean huyendo = this.huyendoDeMobs || this.isOnFire();
        if (huyendo != this.isHuyendo()) this.setHuyendo(huyendo);

        // ¿El dueño lleva quieto un rato (AFK)?
        Player dueno = this.getDueno();
        if (dueno != null) {
            Vec3 pos = dueno.position();
            if (this.ultimaPosDueno == null || pos.distanceToSqr(this.ultimaPosDueno) > 0.0004) {
                this.ticksDuenoQuieto = 0;
                this.ultimaPosDueno = pos;
            } else {
                this.ticksDuenoQuieto++;
            }
        }

        // Detecta constantemente si le queda espacio: al terminar de llenarse -> eructo + humo
        if (this.tickCount % 10 == 0) {
            boolean lleno = this.estaLleno();
            if (this.inicializado && lleno && !this.llenoPrevio) this.efectoTerminar(sl);
            this.llenoPrevio = lleno;
            this.inicializado = true;
        }

        if (!abierto && this.tickCount % 3 == 0) this.recogerObjetos(sl);
        if (this.isSentado() && this.tickCount % 8 == 0) this.gestionarTolva(sl);
    }

    /** Si tiene un objeto al alcance lo guarda (animación de comer); si no le cabe, huele. */
    private void recogerObjetos(ServerLevel sl) {
        List<ItemEntity> cercanos = sl.getEntitiesOfClass(ItemEntity.class,
                this.getBoundingBox().inflate(1.1, 0.7, 1.1), i -> i.isAlive() && !i.hasPickUpDelay());
        for (ItemEntity it : cercanos) {
            if (!this.container.canAddItem(it.getItem())) {
                if (this.enfriamientoOlfato <= 0) this.oler(sl);
                continue;
            }
            ItemStack resto = this.container.addItem(it.getItem().copy());
            if (resto.isEmpty()) {
                it.discard();
            } else {
                it.setItem(resto);
            }
            if (it == this.itemObjetivo) this.itemObjetivo = null;
            if (it == this.itemPrioritario) this.itemPrioritario = null;

            sl.broadcastEntityEvent(this, EVENTO_EXPRESIONES); // animación de comer
            sl.sendParticles(ParticleTypes.HAPPY_VILLAGER, this.getX(), this.getY(0.8), this.getZ(), 6, 0.3, 0.2, 0.3, 0.0);
            sl.playSound(null, this.blockPosition(), sonido(SoundEvents.GENERIC_EAT), SoundSource.NEUTRAL, 0.6f, 1.0f);
            this.heal(CURA_POR_OBJETO); // se cura al comer
            this.pausa = 8;
            this.getNavigation().stop();
            return;
        }
    }

    private void oler(ServerLevel sl) {
        sl.broadcastEntityEvent(this, EVENTO_OLFATO);
        sl.sendParticles(ParticleTypes.ANGRY_VILLAGER, this.getX(), this.getY(1.0), this.getZ(), 3, 0.2, 0.1, 0.2, 0.0);
        this.enfriamientoOlfato = 60;
    }

    /** Eructo agudo + humo blanco: al terminar de llenarse o de vaciarse. */
    private void efectoTerminar(ServerLevel sl) {
        sl.playSound(null, this.blockPosition(), sonido(SoundEvents.PLAYER_BURP), SoundSource.NEUTRAL, 0.9f, 1.9f);
        sl.sendParticles(ParticleTypes.CLOUD, this.getX(), this.getY(0.9), this.getZ(), 14, 0.3, 0.3, 0.3, 0.02);
    }

    // ---------------- Tolvas: vaciarse sentado encima ----------------

    private static final int SIN_TOLVA = -1;
    private static final int NADA_QUE_PASAR = 0;
    private static final int TRANSFIRIO = 1;
    private static final int BLOQUEADA = 2;

    private int vaciarEnTolva(ServerLevel sl) {
        BlockPos[] posiciones = { this.blockPosition(), this.blockPosition().below() };
        for (BlockPos pos : posiciones) {
            if (sl.getBlockEntity(pos) instanceof HopperBlockEntity tolva) {
                boolean habiaObjetos = false;
                for (int i = 0; i < this.container.getContainerSize(); i++) {
                    ItemStack st = this.container.getItem(i);
                    if (st.isEmpty()) continue;
                    habiaObjetos = true;
                    ItemStack resto = HopperBlockEntity.addItem(null, tolva, st.copy(), Direction.UP);
                    if (resto.getCount() != st.getCount()) {
                        this.container.setItem(i, resto);
                        sl.sendParticles(ParticleTypes.NOTE, this.getX(), this.getY(1.1), this.getZ(), 1, 0.2, 0.1, 0.2, 1.0);
                        return TRANSFIRIO; // un objeto por intervalo
                    }
                }
                return habiaObjetos ? BLOQUEADA : NADA_QUE_PASAR;
            }
        }
        return SIN_TOLVA;
    }

    private void gestionarTolva(ServerLevel sl) {
        int r = this.vaciarEnTolva(sl);
        if (r == SIN_TOLVA) {
            if (this.sentadoPorTolva) this.levantarse(); // la tolva desapareció
            return;
        }

        if (r == TRANSFIRIO) {
            this.descargoAlgo = true;
            this.intervalosSinDescargar = 0;
        } else if (r == BLOQUEADA) {
            // La tolva está bloqueada o no está descargando: usa la misma animación que cuando no puede comer más
            this.intervalosSinDescargar++;
            if (this.sentadoPorTolva && this.intervalosSinDescargar >= 6 && this.enfriamientoOlfato <= 0) {
                this.oler(sl);
                this.avisosBloqueo++;
                this.intervalosSinDescargar = 0;
                if (this.avisosBloqueo >= 3 && this.tolvaObjetivo != null) {
                    // Se rinde con esta tolva y prueba con otra
                    this.tolvasBloqueadas.put(this.tolvaObjetivo, sl.getGameTime() + 1200);
                    this.levantarse();
                }
            }
        }

        if (this.estaVacio() && this.descargoAlgo) {
            this.efectoTerminar(sl);   // terminó de vaciarse
            this.descargoAlgo = false;
            if (this.sentadoPorTolva) this.levantarse();
        }
    }

    private void levantarse() {
        this.setSentado(false);
        this.sentadoPorTolva = false;
        this.tolvaObjetivo = null;
        this.pausa = 0;
    }

    // ---------------- Cliente: elegir la animación ----------------

    @Override
    public void handleEntityEvent(byte id) {
        if (id == EVENTO_EXPRESIONES) {
            this.ticksExpresiones = 21;
        } else if (id == EVENTO_OLFATO) {
            this.ticksOlfato = 32;
        } else {
            super.handleEntityEvent(id);
        }
    }

    private void tickCliente() {
        double dx = this.getX() - this.xo;
        double dy = this.getY() - this.yo;
        double dz = this.getZ() - this.zo;
        boolean moviendose = dx * dx + dy * dy + dz * dz > 0.0004;

        if (this.ticksExpresiones > 0) this.ticksExpresiones--;
        if (this.ticksOlfato > 0) this.ticksOlfato--;

        boolean abierto = this.isAbierto();
        boolean sentado = this.isSentado();

        if (this.isDeadOrDying()) {
            this.animActual = ANIM_MUERTE;
        } else if (abierto) {
            this.animActual = sentado ? ANIM_ABRIR_SENTADO : ANIM_ABRIR;
        } else if (this.ticksOlfato > 0) {
            this.animActual = ANIM_OLFATO;
        } else if (this.ticksExpresiones > 0) {
            this.animActual = ANIM_EXPRESION;
        } else if (this.isHuyendo()) {
            this.animActual = ANIM_CORRER;
        } else if (sentado) {
            this.animActual = ANIM_SENTADO;
        } else if (moviendose) {
            this.animActual = ANIM_CAMINAR;
        } else {
            this.animActual = ANIM_IDLE;
        }
    }
}
