package com.aero.bollyho;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * bollyho 模组 — 客户端专用初始化
 *
 * <p>这个类<b>只在物理客户端</b>上加载，服务端（专用服务器）不会加载它。
 * 所有客户端专属的代码（渲染、按键、HUD等）都应该放在这里或由这里触发。</p>
 *
 * <h3>两个注解的作用：</h3>
 * <ul>
 *   <li>{@code @Mod(value = BollyhoMod.MODID, dist = Dist.CLIENT)} —
 *       声明这是客户端专属的模组入口，服务端自动跳过</li>
 *   <li>{@code @EventBusSubscriber(modid = BollyhoMod.MODID, value = Dist.CLIENT)} —
 *       自动注册本类中所有 static + @SubscribeEvent 的方法到客户端事件总线</li>
 * </ul>
 *
 * @see BollyhoMod 主模组类
 */
@Mod(value = BollyhoMod.MODID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = BollyhoMod.MODID, value = Dist.CLIENT)
public class BollyhoModClient {

    /**
     * 客户端构造函数
     *
     * <p>在这里做：</p>
     * <ul>
     *   <li>注册配置界面（让玩家在"模组列表 → bollyho → 配置"中看到设置页面）</li>
     *   <li>注册按键绑定</li>
     *   <li>注册客户端事件监听器</li>
     * </ul>
     *
     * @param container 模组容器
     */
    public BollyhoModClient(ModContainer container) {
        // 注册配置界面扩展点：让 NeoForge 知道本模组有配置界面
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }

    /**
     * 客户端初始化
     *
     * <p>在 Minecraft 客户端启动时触发，适合：</p>
     * <ul>
     *   <li>注册实体渲染器（EntityRenderer）</li>
     *   <li>注册方块实体渲染器（BlockEntityRenderer）</li>
     *   <li>注册模型图层（ModelLayerLocation）</li>
     *   <li>注册颜色处理器（ColorHandler）</li>
     *   <li>注册按键绑定（KeyMapping）</li>
     * </ul>
     *
     * @param event 客户端初始化事件
     */
    @SubscribeEvent
    static void onClientSetup(final FMLClientSetupEvent event) {
        BollyhoMod.LOGGER.info("Bollyho 客户端初始化完成！");
        BollyhoMod.LOGGER.info("当前玩家：{}", Minecraft.getInstance().getUser().getName());
    }
}
