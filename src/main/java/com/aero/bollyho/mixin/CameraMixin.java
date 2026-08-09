package com.aero.bollyho.mixin;

import com.aero.bollyho.client.CbcIntegration;
import com.aero.bollyho.client.SableIntegration;
import com.aero.bollyho.client.ScopeViewHandler;
import net.minecraft.client.Camera;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 相机 Mixin
 *
 * <p>在炮镜视角中，覆盖相机位置。相机放置位置取决于火炮底座类型：</p>
 * <ul>
 *   <li><b>原版 CBC 火炮底座</b> — 相机放在火炮底座方块中心（旋转轴）</li>
 *   <li><b>紧凑式火炮底座 / 无火炮</b> — 相机放在目标方块中心</li>
 * </ul>
 *
 * <p>注入在 {@link Camera#setPosition(double, double, double)} 之后，
 * 确保覆盖原版设置的实体位置。</p>
 *
 * <h3>为什么需要这个 Mixin？</h3>
 * <p>{@link Camera#setup} 的执行顺序为：</p>
 * <ol>
 *   <li>触发 {@code ComputeCameraAngles} 事件（旋转在这里设置）</li>
 *   <li>调用 {@code setRotation()}（使用事件的 yaw/pitch）</li>
 *   <li>调用 {@code setPosition(entity)}（从实体位置设置）</li>
 * </ol>
 * <p>在事件处理器中用反射改位置无效，因为第 3 步会覆盖。
 * <b>解决办法：在 {@code setup()} 返回前再次覆盖位置。</b></p>
 */
@Mixin(Camera.class)
public abstract class CameraMixin {

    @Shadow
    protected abstract void setPosition(double x, double y, double z);

    @Shadow
    private boolean detached;

    /**
     * 在 {@code Camera.setup()} 返回前，覆盖相机位置
     *
     * <p>只覆盖非 detached（非第三人称）模式，因为炮镜视角始终是第一人称。</p>
     *
     * <p>位置选择逻辑：</p>
     * <ul>
     *   <li>原版 CBC 火炮底座 → 相机放在火炮底座方块中心（旋转轴所在位置）</li>
     *   <li>紧凑式火炮底座 / 无火炮 → 相机放在目标方块中心</li>
     * </ul>
     *
     * @param level             世界
     * @param entity            实体（未使用）
     * @param detached          是否第三人称
     * @param thirdPersonReverse 是否第三人称反转（未使用）
     * @param partialTick       部分 tick（未使用）
     * @param ci                回调信息
     */
    @Inject(method = "setup(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/world/entity/Entity;ZZF)V", at = @At("RETURN"))
    private void onSetupReturn(BlockGetter level, Entity entity, boolean detached, boolean thirdPersonReverse, float partialTick, CallbackInfo ci) {
        ScopeViewHandler handler = ScopeViewHandler.getInstance();
        if (!handler.isInScopeView()) {
            return;
        }

        SableIntegration sable = SableIntegration.getInstance();

        // SubLevel 跟踪模式：使用 Vec3 精度的相机位置（保留小数，避免截断抖动）
        if (sable.isTracking()) {
            Vec3 subLevelPos = handler.getSubLevelCameraPos();
            if (subLevelPos != null) {
                this.setPosition(subLevelPos.x, subLevelPos.y, subLevelPos.z);
                return;
            }
        }

        CbcIntegration cbc = CbcIntegration.getInstance();

        // 原版 CBC 火炮底座 → 相机放在火炮底座中心（旋转轴）
        if (cbc.getCannonType() == CbcIntegration.CannonType.ORIGINAL_CBC) {
            BlockPos cannonPos = cbc.getCachedCannonPos();
            if (cannonPos != null) {
                this.setPosition(
                        cannonPos.getX() + 0.5,
                        cannonPos.getY() + 0.5,
                        cannonPos.getZ() + 0.5
                );
                return;
            }
        }

        // 紧凑式火炮底座 / 无火炮 → 相机放在目标方块中心
        BlockPos targetPos = handler.getTargetPos();
        if (targetPos == null) {
            return;
        }

        this.setPosition(
                targetPos.getX() + 0.5,
                targetPos.getY() + 0.5,
                targetPos.getZ() + 0.5
        );
    }
}
