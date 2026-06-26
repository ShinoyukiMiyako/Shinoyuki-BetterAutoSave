package com.shinoyuki.betterautosave.gametest;

import com.shinoyuki.betterautosave.BetterAutoSaveMod;
import com.shinoyuki.betterautosave.api.ChunkRestoreOutcome;
import com.shinoyuki.betterautosave.api.ChunkRestoreResult;
import com.shinoyuki.betterautosave.api.SaveCoordination;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.concurrent.CompletableFuture;

/**
 * §3.8 restore/rollback 安全网的在位 (in-game) GameTest, 被测入口为 BAS
 * {@link SaveCoordination#restoreChunkLive} (在线单 chunk 内存原地回退)。
 *
 * <p><b>运行前置 (重要)</b>: 在线回退复用 BAS 异步 load worker, 故需 {@code load.enabled=true}
 * 且 load 池活跃 —— 否则 restoreChunkLive 返回 {@code REJECT_DISABLED}, 用例会以明确文案失败。
 * 跑法: {@code ./gradlew :forge:runGameTestServer} (进程退出码反映 PASS/FAIL); 需在 run 目录
 * config 里把 load.enabled 置 true, 并在 run/gameteststructures/ 放 restore_platform.snbt。
 *
 * <p><b>单一用例 (重要)</b>: restoreChunkLive 是<b>整 chunk 粒度</b>。GameTest 默认把多个小结构
 * 紧凑排布, 易落进同一 16x16 chunk; 若拆成两个用例并发跑, 一个用例的整块回退会连带覆盖另一个
 * 用例的区域 (互相污染)。故合并为单用例: 一个 chunk、一次回退, 先验活服回退、再 save 验落盘存活。
 *
 * <p><b>异步语义</b>: restoreChunkLive 反序列化在 load worker、光照在光引擎线程; 其 future 完成
 * 即"安装完成"(方块/容器/BE 已就位), 但 isLightCorrect 由异步光照稍后收敛。故用
 * {@code GameTestSequence}: 先等 future 完成断言同步态, 再 {@code thenWaitUntil} 轮询光照收敛。
 */
@GameTestHolder(BetterAutoSaveMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ChunkRestoreGameTest {

    /** 用例工作面: 模板 (3x3x3) 内的箱子坐标 (结构相对)。地面铺在 y=0, 箱子置于 y=1。 */
    private static final BlockPos CHEST_POS = new BlockPos(1, 1, 1);

    /** 旁证方块坐标: 与箱子同 chunk 的另一格, 验证非容器方块态也参与回退。 */
    private static final BlockPos MARKER_POS = new BlockPos(0, 1, 0);

    /**
     * 布置旧态 -> ChunkSerializer.write 捕获整 chunk 快照 -> 破坏方块/容器/光照标志
     * -> restoreChunkLive(快照) -> 断言:
     * (a) 方块态 + 容器内容回滚、BE 无泄漏、isLightCorrect 异步收敛为 true (活服回退正确);
     * (b) save(true) 落盘屏障后再读仍是 restored 值 (回退结果穿过硬盘真相边界, §3.8 GT-2 语义)。
     */
    @GameTest(template = "restore_platform", timeoutTicks = 400)
    public void gt_liveRestoreThenDiskFlush(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos chestAbs = helper.absolutePos(CHEST_POS);
        BlockPos markerAbs = helper.absolutePos(MARKER_POS);
        ChunkPos chunkPos = new ChunkPos(chestAbs);

        // 旧态: 箱子 + 槽0 钻石; 旁证格 = 金块。这是要被回退到的"快照"世界状态。
        level.setBlockAndUpdate(chestAbs, Blocks.CHEST.defaultBlockState());
        level.setBlockAndUpdate(markerAbs, Blocks.GOLD_BLOCK.defaultBlockState());
        Container oldChest = containerAt(helper, chestAbs);
        oldChest.setItem(0, new ItemStack(Items.DIAMOND, 1));
        ((BlockEntity) oldChest).setChanged();

        // 捕获快照: vanilla ChunkSerializer.write 把当前(好)态整 chunk 序列化成 NBT —
        // 正是 restoreChunkLive 内部喂给 ChunkSerializer.read 回灌的字节, 与 BB 生产路径同源。
        CompoundTag snapshot = ChunkSerializer.write(level, level.getChunkAt(chestAbs));
        // BE 基线 (好态总数): GameTest 竞技场 chunk 本就含框架自身 BE (structure block 等), 不止测试箱子。
        // 故"无泄漏"不能断言总数==1, 而是断言回退后总数==此基线 (restore 精确复现 BE 群体)。
        final int baselineBeCount = level.getChunkAt(chestAbs).getBlockEntities().size();

        // 破坏: 箱子槽0 换绿宝石, 旁证格改石头, 光照标志打 false (验证回退后被异步拉回 true 而非蒙混)。
        oldChest.setItem(0, new ItemStack(Items.EMERALD, 1));
        ((BlockEntity) oldChest).setChanged();
        level.setBlockAndUpdate(markerAbs, Blocks.STONE.defaultBlockState());
        level.getChunkAt(chestAbs).setLightCorrect(false);

        // 被测入口: 真实在线回退 (异步)。
        CompletableFuture<ChunkRestoreResult> future = SaveCoordination.restoreChunkLive(level, chunkPos, snapshot);

        helper.startSequence()
                // 1) 等安装完成 (future 在主线程任务队列跨 tick 完成)
                .thenWaitUntil(() -> helper.assertTrue(future.isDone(), "restoreChunkLive future 未完成"))
                // 2) outcome 必须 OK; 非 OK 明示原因 (REJECT_DISABLED 提示开 load.enabled)
                .thenExecute(() -> {
                    ChunkRestoreResult result = future.join();
                    helper.assertTrue(result.outcome() == ChunkRestoreOutcome.OK,
                            "restoreChunkLive 期望 OK (需 load.enabled=true), 实得 " + result.outcome()
                                    + (result.cause() != null ? " cause=" + result.cause() : ""));
                })
                // 3) 活服回退: 方块态 + 容器内容 + BE 集合在安装时已就位 (同步部分)
                .thenExecute(() -> {
                    helper.assertBlock(MARKER_POS, b -> b == Blocks.GOLD_BLOCK,
                            () -> "旁证方块须回滚为 GOLD_BLOCK, 实得 " + level.getBlockState(markerAbs));
                    helper.assertContainerContains(CHEST_POS, Items.DIAMOND);
                    Container restored = containerAt(helper, chestAbs);
                    helper.assertTrue(restored.countItem(Items.EMERALD) == 0,
                            "回退后容器不应残留破坏期写入的 EMERALD");
                    helper.assertTrue(restored.getItem(0).getCount() == 1 && restored.getItem(0).is(Items.DIAMOND),
                            "回退后箱子槽0 须恰为 1 个 DIAMOND");
                    // BE 无泄漏/无重复 (R2/R5): chestAbs 处恰一只箱子 (抓 clear+re-add 的重复),
                    // 且 chunk BE 总数等于快照基线 (抓泄漏/丢失; 基线含框架自身 BE)。
                    LevelChunk chunk = level.getChunkAt(chestAbs);
                    long chestBeCount = chunk.getBlockEntities().values().stream()
                            .filter(be -> be.getBlockPos().equals(chestAbs))
                            .count();
                    helper.assertTrue(chestBeCount == 1,
                            "回退后 chestAbs 处须恰一只箱子 BE (无重复/无泄漏), 实得 " + chestBeCount);
                    helper.assertTrue(chunk.getBlockEntities().size() == baselineBeCount,
                            "回退后 chunk BE 总数须等于快照基线 " + baselineBeCount
                                    + " (无泄漏/无丢失), 实得 " + chunk.getBlockEntities().size());
                })
                // 4) 光照异步收敛: install 后 light 在光引擎线程跑, 轮询至 isLightCorrect 回 true
                .thenWaitUntil(() -> helper.assertTrue(level.getChunkAt(chestAbs).isLightCorrect(),
                        "chunk isLightCorrect 须在异步光照收敛后为 true"))
                // 5) 落盘屏障: 把 restored chunk flush 进 region 文件
                .thenExecute(() -> level.getChunkSource().save(true))
                // 6) 落盘后再读仍须是 restored 值 (回退穿过硬盘真相边界)
                .thenExecute(() -> {
                    helper.assertBlock(MARKER_POS, b -> b == Blocks.GOLD_BLOCK,
                            () -> "落盘后旁证须仍为 GOLD_BLOCK, 实得 " + level.getBlockState(markerAbs));
                    helper.assertContainerContains(CHEST_POS, Items.DIAMOND);
                    Container afterFlush = containerAt(helper, chestAbs);
                    helper.assertTrue(afterFlush.countItem(Items.EMERALD) == 0,
                            "落盘后 chunk 不应含回退前的 EMERALD");
                    helper.assertTrue(level.getChunkAt(chestAbs).isLightCorrect(),
                            "chunk isLightCorrect 须跨落盘保持 true");
                })
                .thenSucceed();
    }

    /** 取指定绝对坐标处的容器 BE; 不是容器则让 ClassCastException 自然冒泡 (断言前置条件被破坏)。 */
    private static Container containerAt(GameTestHelper helper, BlockPos absPos) {
        BlockEntity be = helper.getLevel().getBlockEntity(absPos);
        return (Container) be;
    }
}
