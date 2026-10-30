package com.wyt.chesters;

import com.geckolib.renderer.GeoEntityRenderer;
import com.geckolib.renderer.base.GeoRenderState;

import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;

/**
 * Renderer de Chester con GeckoLib. Usa los archivos por defecto de GeckoLib para la entidad "chester":
 *   assets/wyt_chesters/geckolib/models/entity/chester.geo.json
 *   assets/wyt_chesters/geckolib/animations/entity/chester.animation.json
 *   assets/wyt_chesters/textures/entity/chester.png
 */
public class ChesterRenderer extends GeoEntityRenderer<ChesterEntity, LivingEntityRenderState> {

    public ChesterRenderer(EntityRendererProvider.Context context) {
        super(context, ChestersMod.CHESTER.get());
    }

    // Chester no se tumba de lado al morir: lo anima su propia animación MUERTE
    @Override
    public float getDeathMaxRotation(GeoRenderState renderState) {
        return 0.0F;
    }
}
