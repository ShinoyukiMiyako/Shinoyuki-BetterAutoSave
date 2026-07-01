package com.shinoyuki.betterautosave.gametest;

import com.shinoyuki.betterautosave.BetterAutoSaveMod;
import com.shinoyuki.betterautosave.api.ChunkRestoreOutcome;
import com.shinoyuki.betterautosave.api.ChunkRestoreResult;
import com.shinoyuki.betterautosave.api.SaveCoordination;
import com.shinoyuki.betterautosave.core.restore.OnlineChunkRestorer;
import com.shinoyuki.betterautosave.mixin.accessor.ChunkAccessAccessor;
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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
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
    @GameTest(template = "restore_platform", timeoutTicks = 400, batch = "liveRestore")
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

    /**
     * §3.8 GT-3 (回滚安全网): install 在半改态中途抛错时, 事务回滚须把 live chunk 精确还原到安装前基线,
     * outcome 为 INSTALL_FAILED —— 令 "失败已回滚, 不留半个 chunk" 的文案名副其实。
     *
     * <p>做法: 布好态并拍安装前基线 (方块态/容器/BE 总数/<b>pendingBlockEntities</b>/各 heightmap raw/
     * isLightCorrect), 经 {@link OnlineChunkRestorer#swapFaultInjector} 注入抛点令 install 在 arraycopy
     * 之后、setUnsaved 之前抛, 再断言全部维度逐一等于基线。
     *
     * <p><b>pendingBlockEntities 是验收核心</b>: clearAllBlockEntities 不清 pending, 若回滚漏还原 pending,
     * 安装期的 {@code livePending.clear()} 会留下空表 —— 故先注入一个合成 pending 项 (与真 BE 不同坐标),
     * 使 pending 非空且可判等。删掉 install 里的 pending 回滚逻辑, 本用例的 pending 断言必挂。
     */
    @GameTest(template = "restore_platform", timeoutTicks = 400, batch = "installFailRollback")
    public void gt_installFailRollback(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos chestAbs = helper.absolutePos(CHEST_POS);
        BlockPos markerAbs = helper.absolutePos(MARKER_POS);
        ChunkPos chunkPos = new ChunkPos(chestAbs);

        // 好态: 箱子 + 槽0 钻石, 旁证格金块。这是要被回退到的快照世界状态。
        level.setBlockAndUpdate(chestAbs, Blocks.CHEST.defaultBlockState());
        level.setBlockAndUpdate(markerAbs, Blocks.GOLD_BLOCK.defaultBlockState());
        Container chest = containerAt(helper, chestAbs);
        chest.setItem(0, new ItemStack(Items.DIAMOND, 1));
        ((BlockEntity) chest).setChanged();

        LevelChunk liveChunk = level.getChunkAt(chestAbs);

        // 快照: vanilla ChunkSerializer.write 序列化当前(好)态整 chunk (restoreChunkLive 内部喂给 read 的字节)。
        CompoundTag snapshot = ChunkSerializer.write(level, liveChunk);

        // 合成 pending 项 (keepPacked 形态未物化 BE): 令 live 的 pendingBlockEntities 非空, 使 "回滚还原
        // pending" 成为可判等的载重断言。经 accessor 直接写 pending map (而非 setBlockEntityNbt) 并放在快照
        // 序列化之后: 活 chunk 上任何 getBlockEntity(pos) 会促升并移除该 pos 的 pending 项 (LevelChunk:304),
        // ChunkSerializer.write 也会促升; 故须在这些促升点之后、且此后不再读该 pos 的 BE, 该项才能留到 restore。
        // 该 pos 不在快照里 —— 因此成功安装会 clear 后不重灌它, 唯有回滚还原 base pending 才会把它放回。
        BlockPos pendingPos = new BlockPos(chestAbs.getX(), chestAbs.getY() + 2, chestAbs.getZ());
        CompoundTag pendingTag = new CompoundTag();
        pendingTag.putString("id", "minecraft:sign");
        pendingTag.putInt("x", pendingPos.getX());
        pendingTag.putInt("y", pendingPos.getY());
        pendingTag.putInt("z", pendingPos.getZ());
        pendingTag.putBoolean("keepPacked", true);
        ((ChunkAccessAccessor) liveChunk).betterautosave$getPendingBlockEntities().put(pendingPos, pendingTag);

        // 安装前基线 (逐维度): 破坏世界前抓, 抛错回滚后须逐一等于这些值。
        BlockState baseMarker = level.getBlockState(markerAbs);
        int baseDiamond = chest.countItem(Items.DIAMOND);
        int baseBeCount = liveChunk.getBlockEntities().size();
        Map<BlockPos, CompoundTag> basePending =
                new LinkedHashMap<>(((ChunkAccessAccessor) liveChunk).betterautosave$getPendingBlockEntities());
        Map<Heightmap.Types, long[]> baseHeightmaps = new EnumMap<>(Heightmap.Types.class);
        for (Map.Entry<Heightmap.Types, Heightmap> entry : liveChunk.getHeightmaps()) {
            baseHeightmaps.put(entry.getKey(), entry.getValue().getRawData().clone());
        }
        boolean baseLightCorrect = liveChunk.isLightCorrect();

        // 断言前置: 合成 pending 项确已进表 (否则 pending 断言退化为空对空的假绿)。
        helper.assertTrue(basePending.containsKey(pendingPos),
                "前置: 合成 pending 项须在安装前基线里, 实得 keys=" + basePending.keySet());

        // 注入抛点: install 在 arraycopy 之后、setUnsaved 之前抛 IllegalStateException, 触发回滚路径。
        // 时序关键: install 在 load worker 反序列化(异步)回调后于主线程执行, future 完成是在后续 tick。故注入器
        // 必须一直保持到 install 读取 faultInjector 之后才还原 —— 若在提交 restoreChunkLive 后立即还原, install 届时
        // 读到的是 no-op, 抛点不触发。因此在 sequence 内 future 完成后 (install 已跑完) 才 swap 回原值。
        java.util.function.Consumer<ChunkPos> prevInjector = OnlineChunkRestorer.swapFaultInjector(injectedPos -> {
            // 按 pos 自限定: 只在本用例那个 chunk 的 install 里抛, 不误伤同批次其它用例。
            if (injectedPos.equals(chunkPos)) {
                throw new IllegalStateException("gt_installFailRollback 注入的 install 半改态故障点");
            }
        });

        CompletableFuture<ChunkRestoreResult> future =
                SaveCoordination.restoreChunkLive(level, chunkPos, snapshot);

        helper.startSequence()
                // 1) 等安装尝试完成 (install 抛错 -> future 以 INSTALL_FAILED 完成, 跨 tick)
                .thenWaitUntil(() -> helper.assertTrue(future.isDone(), "restoreChunkLive future 未完成"))
                // 2) install 已跑完, 还原注入器 (避免污染同批次其它用例); 再断言 outcome=INSTALL_FAILED
                .thenExecute(() -> {
                    OnlineChunkRestorer.swapFaultInjector(prevInjector);
                    ChunkRestoreResult result = future.join();
                    helper.assertTrue(result.outcome() == ChunkRestoreOutcome.INSTALL_FAILED,
                            "install 抛错须归 INSTALL_FAILED, 实得 " + result.outcome());
                })
                // 3) 逐维度断言回滚到安装前基线
                .thenExecute(() -> {
                    LevelChunk chunk = level.getChunkAt(chestAbs);
                    // 方块态: 旁证格仍是金块 (未被半改态或部分回滚破坏)
                    helper.assertBlock(MARKER_POS, b -> b == Blocks.GOLD_BLOCK,
                            () -> "回滚后旁证须为 GOLD_BLOCK, 实得 " + level.getBlockState(markerAbs));
                    helper.assertTrue(level.getBlockState(markerAbs) == baseMarker,
                            "回滚后旁证方块态须与基线全等");
                    // 容器内容: 槽0 仍恰 1 个钻石
                    Container restored = containerAt(helper, chestAbs);
                    helper.assertTrue(restored.countItem(Items.DIAMOND) == baseDiamond && baseDiamond == 1,
                            "回滚后箱子须恰 " + baseDiamond + " 个钻石, 实得 " + restored.countItem(Items.DIAMOND));
                    helper.assertTrue(restored.getItem(0).getCount() == 1 && restored.getItem(0).is(Items.DIAMOND),
                            "回滚后箱子槽0 须恰 1 个 DIAMOND");
                    // BE 总数: 等于基线 (无泄漏/无丢失/无重复)
                    helper.assertTrue(chunk.getBlockEntities().size() == baseBeCount,
                            "回滚后 chunk BE 总数须等于基线 " + baseBeCount
                                    + ", 实得 " + chunk.getBlockEntities().size());
                    // pendingBlockEntities (验收核心): 键集与内容须与基线全等
                    Map<BlockPos, CompoundTag> nowPending =
                            ((ChunkAccessAccessor) chunk).betterautosave$getPendingBlockEntities();
                    helper.assertTrue(nowPending.equals(basePending),
                            "回滚后 pendingBlockEntities 须与基线全等 (删 pending 回滚逻辑此处必挂); 基线="
                                    + basePending.keySet() + " 实得=" + nowPending.keySet());
                    // 各 heightmap raw: 逐 type long[] 逐字节相等
                    for (Map.Entry<Heightmap.Types, long[]> entry : baseHeightmaps.entrySet()) {
                        long[] now = chunk.getOrCreateHeightmapUnprimed(entry.getKey()).getRawData();
                        helper.assertTrue(Arrays.equals(now, entry.getValue()),
                                "回滚后 heightmap " + entry.getKey() + " raw 须与基线全等");
                    }
                    // isLightCorrect: install 头置 false, 回滚须还原到基线值 (且不触发异步光照拉回)
                    helper.assertTrue(chunk.isLightCorrect() == baseLightCorrect,
                            "回滚后 isLightCorrect 须还原为基线 " + baseLightCorrect
                                    + ", 实得 " + chunk.isLightCorrect());
                })
                .thenSucceed();
    }

    /** 取指定绝对坐标处的容器 BE; 不是容器则让 ClassCastException 自然冒泡 (断言前置条件被破坏)。 */
    private static Container containerAt(GameTestHelper helper, BlockPos absPos) {
        BlockEntity be = helper.getLevel().getBlockEntity(absPos);
        return (Container) be;
    }
}
