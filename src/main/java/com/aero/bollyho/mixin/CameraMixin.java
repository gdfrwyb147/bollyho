package com.aero.bollyho.mixin;

import com.aero.bollyho.client.CbcIntegration;
import com.aero.bollyho.client.SableIntegration;
import com.aero.bollyho.client.ScopeViewHandler;
import net.minecraft.client.Camera;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 相机 Mixin
 *
 * <p>在炮镜视角中，覆盖相机<b>位置</b>和<b>朝向</b>。</p>
 *
 * <h3>为什么需要四元数旋转？</h3>
 * <p>{@code ViewportEvent.ComputeCameraAngles} 只能设置 yaw/pitch，
 * <b>无法表示任意三维旋转</b>。当炮镜安装在 SubLevel 上时，
 * 船的朝向包含完整的 3D 旋转（含 roll 分量），
 * 但 yaw/pitch 系统会丢失 roll 信息。因此直接在
 * {@code Camera.setup()} 返回前，用世界空间前向/上方向量
 * 构建四元数并写入 Camera 内部字段（参考 createrenxin 的做法）。</p>
 */
@Mixin(Camera.class)
public abstract class CameraMixin {

    @Shadow
    protected abstract void setPosition(double x, double y, double z);

    @Shadow
    private boolean detached;

    /**
     * 在 {@code Camera.setup()} 返回前，覆盖相机位置与朝向
     *
     * @param level             世界
     * @param entity            实体
     * @param detached          是否第三人称
     * @param thirdPersonReverse 是否第三人称反转
     * @param partialTick       部分 tick
     * @param ci                回调信息
     */
    @Inject(method = "setup(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/world/entity/Entity;ZZF)V", at = @At("RETURN"))
    private void onSetupReturn(BlockGetter level, Entity entity, boolean detached, boolean thirdPersonReverse, float partialTick, CallbackInfo ci) {
        ScopeViewHandler handler = ScopeViewHandler.getInstance();
        if (!handler.isInScopeView()) {
            return;
        }

        SableIntegration sable = SableIntegration.getInstance();

        // ============================================================
        // SubLevel 跟踪模式：四元数旋转 + Vec3 位置
        // ============================================================
        if (sable.isTracking()) {
            Vec3 subLevelPos = handler.getSubLevelCameraPos();
            Vec3 worldForward = sable.getWorldForward(partialTick);
            Vec3 worldUp = sable.getWorldUp(partialTick);

            if (subLevelPos != null) {
                this.setPosition(subLevelPos.x, subLevelPos.y, subLevelPos.z);
            }

            // 设置完整的四元数旋转（绕过 yaw/pitch 限制）
            if (worldForward != null && worldUp != null && worldForward.lengthSqr() > 0.0001) {
                // Layer 3: 应用火炮的俯仰/偏航角（从 CbcIntegration 读取主世界炮管）
                CbcIntegration cbc = CbcIntegration.getInstance();
                Float cannonPitch = null;
                Float cannonYaw = null;
                if (level instanceof Level worldLevel) {
                    cannonPitch = cbc.readCannonPitch(worldLevel);
                    cannonYaw = cbc.readCannonYaw(worldLevel);
                }

                Vec3 adjustedForward = worldForward;
                Vec3 adjustedUp = worldUp;

                if (cannonYaw != null && Math.abs(cannonYaw) > 0.01f) {
                    // 偏航：绕 worldUp（SubLevel 局部 Y 轴的世界方向）旋转
                    Vec3 yawAxis = adjustedUp.normalize();
                    double yawRad = Math.toRadians(cannonYaw);
                    double cosY = Math.cos(yawRad);
                    double sinY = Math.sin(yawRad);
                    double kDotV = yawAxis.dot(adjustedForward);
                    Vec3 kCrossV = yawAxis.cross(adjustedForward);
                    adjustedForward = new Vec3(
                            adjustedForward.x * cosY + kCrossV.x * sinY + yawAxis.x * kDotV * (1.0 - cosY),
                            adjustedForward.y * cosY + kCrossV.y * sinY + yawAxis.y * kDotV * (1.0 - cosY),
                            adjustedForward.z * cosY + kCrossV.z * sinY + yawAxis.z * kDotV * (1.0 - cosY)
                    );
                }

                if (cannonPitch != null && Math.abs(cannonPitch) > 0.01f) {
                    // 俯仰：绕 local right 轴旋转（俯仰角正值=火炮仰起=视角朝上）
                    // 注意：rightAxis = forward×up 已指向观察者右手方向，
                    // Rodrigues 正角度旋转使 forward 的 y 分量增大（朝上），
                    // 因此这里直接取正弧度，无需负号。
                    Vec3 rightAxis = adjustedForward.cross(adjustedUp).normalize();
                    double pitchRad = Math.toRadians(cannonPitch);
                    double cosP = Math.cos(pitchRad);
                    double sinP = Math.sin(pitchRad);
                    Vec3 kCrossV = rightAxis.cross(adjustedForward);
                    adjustedForward = new Vec3(
                            adjustedForward.x * cosP + kCrossV.x * sinP,
                            adjustedForward.y * cosP + kCrossV.y * sinP,
                            adjustedForward.z * cosP + kCrossV.z * sinP
                    );
                    Vec3 kCrossUp = rightAxis.cross(adjustedUp);
                    adjustedUp = new Vec3(
                            adjustedUp.x * cosP + kCrossUp.x * sinP,
                            adjustedUp.y * cosP + kCrossUp.y * sinP,
                            adjustedUp.z * cosP + kCrossUp.z * sinP
                    );
                }

                setQuaternionRotation(adjustedForward, adjustedUp);
            }
            return;
        }

        // ============================================================
        // 主世界 / 运动学模式：沿用 yaw/pitch 事件（Camera.setup 已处理）
        // 这里只覆盖位置
        // ============================================================
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

    /**
     * 从世界空间的前向/上方向量构建正交基，设置 Camera 的四元数旋转
     *
     * <p>算法（参考 createrenxin 的 CameraMixin）：</p>
     * <ol>
     *   <li>前向 = normalize(forward)</li>
     *   <li>右向 = normalize(correctedUp × forward)</li>
     *   <li>修正上方 = normalize(forward × right)</li>
     *   <li>构建 3×3 旋转矩阵：col0=-right, col1=up, col2=-forward</li>
     *   <li>从矩阵转四元数，归一化</li>
     *   <li>写入 Camera 的 rotation / forwards / up / left</li>
     * </ol>
     *
     * @param worldForward 世界空间前向向量
     * @param worldUp      世界空间上方向量（未正交化）
     */
    private void setQuaternionRotation(Vec3 worldForward, Vec3 worldUp) {
        Vec3 forward = worldForward.normalize();

        // 将 up 投影到垂直于 forward 的平面，得到正交的 right 向量
        Vec3 projectedUp = worldUp.subtract(forward.scale(worldUp.dot(forward)));
        double upLenSq = projectedUp.lengthSqr();
        Vec3 correctedUp;
        if (upLenSq < 0.000001) {
            // forward 几乎平行于 worldUp，构造任意正交基
            correctedUp = new Vec3(0, 1, 0);
            if (Math.abs(forward.dot(correctedUp)) > 0.99) {
                correctedUp = new Vec3(1, 0, 0);
            }
            correctedUp = correctedUp.subtract(forward.scale(correctedUp.dot(forward))).normalize();
        } else {
            correctedUp = projectedUp.normalize();
        }

        // right = cross(correctedUp, forward)，在右手系中
        Vec3 right = correctedUp.cross(forward).normalize();

        // 重新正交化 up：up = cross(forward, right)
        correctedUp = forward.cross(right).normalize();

        // 构建旋转矩阵（列主序）
        Matrix3f matrix = new Matrix3f();
        matrix.setColumn(0, new Vector3f(-(float) right.x, -(float) right.y, -(float) right.z));
        matrix.setColumn(1, new Vector3f((float) correctedUp.x, (float) correctedUp.y, (float) correctedUp.z));
        matrix.setColumn(2, new Vector3f(-(float) forward.x, -(float) forward.y, -(float) forward.z));

        // 从正交矩阵转换为四元数
        Quaternionf quat = new Quaternionf();
        quat.setFromNormalized(matrix).normalize();

        // 写入 Camera 的内部字段
        CameraAccessor accessor = (CameraAccessor) this;
        accessor.bollho$getRotation().set(quat);
        // 更新基向量（Camera 渲染时直接使用这些向量）
        accessor.bollho$getForwards().set(0, 0, -1).rotate(quat);
        accessor.bollho$getUp().set(0, 1, 0).rotate(quat);
        accessor.bollho$getLeft().set(-1, 0, 0).rotate(quat);
    }
}
