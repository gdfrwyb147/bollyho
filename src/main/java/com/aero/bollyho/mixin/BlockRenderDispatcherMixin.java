package com.aero.bollyho.mixin;

import com.aero.bollyho.client.ScopeViewHandler;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 方块渲染分发器 Mixin
 *
 * <p>在炮镜视角中，跳过目标方块的渲染。
 * 无论目标方块来自原版还是任何其他模组，都不会被渲染到区块顶点缓冲区中。</p>
 *
 * <h3>工作原理</h3>
 * <ol>
 *   <li>{@link BlockRenderDispatcher#renderBatched} 在区块烘焙时被调用，
 *       负责把方块的几何体写入顶点缓冲区</li>
 *   <li>Mixin 在方法头部注入，检查当前方块位置是否等于炮镜视角的目标位置</li>
 *   <li>如果匹配且玩家正在炮镜视角中 → 取消方法执行，该方块不会被烘焙</li>
 *   <li>进入/退出炮镜视角时，会标记相关区块为"脏"，触发重新烘焙</li>
 * </ol>
 *
 * <h3>线程安全</h3>
 * <p>{@link ScopeViewHandler#isInScopeView()} 和 {@link ScopeViewHandler#getTargetPos()}
 * 都是只读操作，在区块烘焙线程中访问是安全的。</p>
 */
@Mixin(BlockRenderDispatcher.class)
public abstract class BlockRenderDispatcherMixin {

    /**
     * 在 renderBatched 执行前检查是否需要跳过该方块
     *
     * <p>此方法在区块烘焙线程和主线程都可能被调用。
     * 无论哪种情况，炮镜视角中的目标方块都应被跳过。</p>
     *
     * @param blockState     方块状态（未使用）
     * @param blockPos       方块世界坐标 — 与 targetPos 比较
     * @param level          世界（未使用）
     * @param poseStack      姿态栈（未使用）
     * @param vertexConsumer 顶点消费者（未使用）
     * @param checkSides     是否检查面（未使用）
     * @param random         随机数（未使用）
     * @param ci             回调信息，用于取消方法执行
     */
    @Inject(
            method = "renderBatched(" +
                    "Lnet/minecraft/world/level/block/state/BlockState;" +
                    "Lnet/minecraft/core/BlockPos;" +
                    "Lnet/minecraft/world/level/BlockAndTintGetter;" +
                    "Lcom/mojang/blaze3d/vertex/PoseStack;" +
                    "Lcom/mojang/blaze3d/vertex/VertexConsumer;" +
                    "Z" +
                    "Lnet/minecraft/util/RandomSource;" +
                    ")V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void onRenderBatched(
            BlockState blockState,
            BlockPos blockPos,
            BlockAndTintGetter level,
            // PoseStack — 使用完全限定名是因为本类没有 import
            com.mojang.blaze3d.vertex.PoseStack poseStack,
            // VertexConsumer — 同上
            com.mojang.blaze3d.vertex.VertexConsumer vertexConsumer,
            boolean checkSides,
            RandomSource random,
            CallbackInfo ci
    ) {
        // 获取炮镜视角管理器
        ScopeViewHandler handler = ScopeViewHandler.getInstance();

        // 如果在炮镜视角中，且当前渲染的方块就是目标方块
        // → 取消渲染（无论原版方块还是模组方块，一律跳过）
        if (handler.isInScopeView()) {
            BlockPos targetPos = handler.getTargetPos();
            if (targetPos != null && targetPos.equals(blockPos)) {
                ci.cancel();
            }
        }
    }
}
