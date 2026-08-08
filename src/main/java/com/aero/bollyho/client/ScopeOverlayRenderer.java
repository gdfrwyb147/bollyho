package com.aero.bollyho.client;

import com.aero.bollyho.BollyhoMod;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * 炮镜 HUD 叠加渲染器
 *
 * <p>在炮镜视角中，在屏幕上渲染瞄准镜分划线（scope.png）。
 * 只渲染 misc 目录下的一张 scope 纹理，不含暗角等额外图层。</p>
 *
 * <p>渲染仅在 {@link ScopeViewHandler#isInScopeView()} 为 true 时触发。</p>
 *
 * @see ScopeViewHandler 炮镜视角管理器
 */
@EventBusSubscriber(modid = BollyhoMod.MODID, value = Dist.CLIENT)
public final class ScopeOverlayRenderer {

    // ==================== 纹理资源位置 ====================

    /**
     * 瞄准镜分划线/边框纹理
     * <p>资源位置：assets/bollyho/textures/misc/scope.png。
     * 设计为全屏纹理，中心为透明/半透明的镜片区域，
     * 四周为不透明的镜筒边框和瞄准分划线。</p>
     */
    private static final ResourceLocation SCOPE_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            BollyhoMod.MODID, "textures/misc/scope.png"
    );

    private ScopeOverlayRenderer() {}

    // ==================== 渲染事件 ====================

    /**
     * GUI 渲染后事件 — 在所有原版 GUI 元素之后绘制炮镜叠加层
     *
     * <p>使用 {@link RenderGuiEvent.Post} 确保：
     * <ul>
     *   <li>叠加层在所有原版 HUD 之上渲染</li>
     *   <li>手部渲染已经被取消（通过第4步的 RenderHandEvent）</li>
     *   <li>准星已经被取消（通过第4步的 RenderGuiLayerEvent）</li>
     * </ul>
     * </p>
     *
     * @param event GUI 渲染后事件
     */
    @SubscribeEvent
    public static void onRenderGuiPost(RenderGuiEvent.Post event) {
        // 只在炮镜视角中渲染
        if (!ScopeViewHandler.getInstance().isInScopeView()) {
            return;
        }

        GuiGraphics graphics = event.getGuiGraphics();
        Minecraft mc = Minecraft.getInstance();
        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();

        // 渲染 scope.png 叠加层
        renderScopeOverlay(graphics, screenWidth, screenHeight);
    }

    // ==================== 渲染核心 ====================

    /**
     * 渲染炮镜叠加层（仅 scope.png）
     *
     * <p>全屏渲染 misc/scope.png 纹理作为瞄准镜分划线叠加层。</p>
     *
     * @param graphics     GuiGraphics 绘图上下文
     * @param screenWidth  屏幕宽度（GUI 缩放后的像素）
     * @param screenHeight 屏幕高度（GUI 缩放后的像素）
     */
    private static void renderScopeOverlay(GuiGraphics graphics, int screenWidth, int screenHeight) {
        // 启用透明度混合，让 scope.png 的透明区域正确显示
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        // ---- 瞄准镜分划线/边框（misc/scope.png） ----
        // 全屏渲染 scope 纹理，仅此一层
        graphics.blit(
                SCOPE_TEXTURE,
                0, 0,                        // 屏幕位置：左上角
                0, 0,                        // 纹理采样起点
                screenWidth, screenHeight,   // 渲染尺寸（全屏）
                screenWidth, screenHeight    // 纹理采样区域（全纹理映射）
        );

        // 禁用混合（恢复默认状态，避免影响后续渲染）
        RenderSystem.disableBlend();
    }
}
