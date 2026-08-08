package com.aero.bollyho;

import com.aero.bollyho.block.ScopeBlock;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;

/**
 * bollyho 模组主类
 *
 * <p>这是模组的入口点，负责：</p>
 * <ul>
 *   <li>注册所有方块（Blocks）</li>
 *   <li>注册所有物品（Items）</li>
 *   <li>注册创造模式标签页（CreativeModeTabs）</li>
 *   <li>公共初始化逻辑（commonSetup）</li>
 *   <li>加载配置文件</li>
 * </ul>
 *
 * @see BollyhoModClient 客户端专用初始化
 */
// @Mod 注解告诉 NeoForge/FML 这是一个模组主类
// value 必须与 neoforge.mods.toml 中的 modId 一致
@Mod(BollyhoMod.MODID)
public class BollyhoMod {

    // ==================== 模组元数据 ====================

    /**
     * 模组 ID，是整个模组的唯一标识符
     * <p>必须与以下位置保持一致：</p>
     * <ul>
     *   <li>gradle.properties 中的 mod_id</li>
     *   <li>META-INF/neoforge.mods.toml 中的 modId</li>
     *   <li>资源路径 assets/<b>bollyho</b>/...</li>
     * </ul>
     */
    public static final String MODID = "bollyho";

    /** SLF4J 日志记录器，用于在控制台输出调试信息 */
    public static final Logger LOGGER = LogUtils.getLogger();

    // ==================== 注册器（DeferredRegister） ====================

    /**
     * 方块注册器
     * <p>使用 NeoForge 的延迟注册系统，在正确的时机自动注册所有方块。
     * 使用方法：{@code BLOCKS.register("方块id", () -> new 方块类(...))}</p>
     */
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);

    /**
     * 物品注册器
     * <p>注册所有物品，包括方块物品（BlockItem）和独立物品。</p>
     */
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);

    /**
     * 创造模式标签页注册器
     * <p>用于在创造模式物品栏中创建本模组专属的标签页。</p>
     */
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    // ==================== 方块注册 ====================

    /**
     * 炮镜方块
     * <p>注册为 {@code bollyho:scope_block}。
     * 方块属性：强度 3（和原版熔炉一样），需要镐子挖掘。</p>
     */
    public static final DeferredBlock<ScopeBlock> SCOPE_BLOCK = BLOCKS.register(
            "scope_block",
            () -> new ScopeBlock(BlockBehaviour.Properties.of()
                    .strength(3.0F, 6.0F)  // 硬度 3，爆炸抗性 6（和熔炉相当）
                    .requiresCorrectToolForDrops()  // 需要正确工具才掉落
            )
    );

    // ==================== 物品注册 ====================

    /**
     * 炮镜方块物品
     * <p>对应 {@link #SCOPE_BLOCK} 的 BlockItem，玩家手持或物品栏显示的就是这个。</p>
     */
    public static final DeferredItem<BlockItem> SCOPE_BLOCK_ITEM = ITEMS.register(
            "scope_block",
            () -> new BlockItem(SCOPE_BLOCK.get(), new Item.Properties())
    );

    // ==================== 创造模式标签页 ====================

    /**
     * bollyho 模组的创造模式标签页
     * <p>注册为 {@code bollyho:bollyho_tab}。</p>
     * <p>在创造物品栏中排在"战斗"标签页之后。
     * 图标使用炮镜方块物品。
     * 内容由 {@link #addCreative(BuildCreativeModeTabContentsEvent)} 填充。</p>
     */
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> BOLLYHO_TAB =
            CREATIVE_MODE_TABS.register("bollyho_tab", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.bollyho"))  // 标签页标题（翻译键）
                    .withTabsBefore(CreativeModeTabs.COMBAT)             // 排在"战斗"标签页之前
                    .icon(() -> SCOPE_BLOCK_ITEM.get().getDefaultInstance()) // 图标
                    .displayItems((parameters, output) -> {
                        // 在此添加本标签页要显示的物品
                        output.accept(SCOPE_BLOCK_ITEM.get());
                    })
                    .build()
            );

    // ==================== 构造函数 ====================

    /**
     * 模组构造函数
     *
     * <p>FML 会自动注入以下参数：</p>
     * <ul>
     *   <li>{@code IEventBus modEventBus} — 模组事件总线，用于注册监听器和 DeferredRegister</li>
     *   <li>{@code ModContainer modContainer} — 模组容器，提供模组元数据和配置注册</li>
     * </ul>
     *
     * @param modEventBus  模组事件总线
     * @param modContainer 模组容器
     */
    public BollyhoMod(IEventBus modEventBus, ModContainer modContainer) {
        // ---- 注册生命周期事件监听 ----
        // commonSetup 在公共（双端）初始化阶段执行
        modEventBus.addListener(this::commonSetup);

        // ---- 注册 DeferredRegister 到事件总线 ----
        // 这一步是必须的，否则方块/物品/标签页不会被注册
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);

        // ---- 注册"添加到已有标签页"事件 ----
        modEventBus.addListener(this::addCreative);

        // ---- 注册配置文件 ----
        // COMMON 类型：配置文件在客户端和服务端都生效，且会同步到客户端
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    // ==================== 生命周期事件处理 ====================

    /**
     * 公共初始化（双端都会执行）
     *
     * <p>适合放：</p>
     * <ul>
     *   <li>网络包注册</li>
     *   <li>实体属性注册</li>
     *   <li>其他双端通用的初始化逻辑</li>
     * </ul>
     *
     * <p><b>注意：</b>这里不适合放客户端/服务端独有的代码，
     * 客户端专属逻辑请放在 {@link BollyhoModClient} 中</p>
     *
     * @param event 公共初始化事件
     */
    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("Bollyho 模组公共初始化完成！");
    }

    /**
     * 向原版创造模式标签页添加物品
     *
     * <p>如果你想把物品添加到原版的"建筑方块""红石"等标签页，在这里处理。
     * 本模组的专属标签页内容在 {@link #BOLLYHO_TAB} 的 {@code displayItems} 中定义。</p>
     *
     * @param event 创造标签页内容构建事件
     */
    private void addCreative(final BuildCreativeModeTabContentsEvent event) {
        // 例如：把炮镜也加入红石标签页
        // if (event.getTabKey() == CreativeModeTabs.REDSTONE_BLOCKS) {
        //     event.accept(SCOPE_BLOCK_ITEM.get());
        // }
    }
}
