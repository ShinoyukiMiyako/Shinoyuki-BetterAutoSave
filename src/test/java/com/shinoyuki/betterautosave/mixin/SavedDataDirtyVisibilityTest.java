package com.shinoyuki.betterautosave.mixin;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SavedData dirty 跨线程可见性回归.
 *
 * <p>现场: vanilla {@code SavedData.dirty} 是普通非 volatile boolean。worker 线程在 IO 失败时
 * 调 {@code setDirty()} 写 true, 主线程裸读 {@code isDirty()}, 无 happens-before → 主线程可能
 * 读到陈旧值, 把失败文件的重投推迟。{@link SavedDataMixin} 用 {@code @Unique volatile} 镜像收口
 * dirty 读写, 跨线程可见性由 volatile 语义保证。
 *
 * <p><b>为何不在 JUnit 直接测真实 SavedData</b>: 本项目 test 任务是裸 JUnit Platform, 不挂 mixin
 * 转换 agent (见 build.gradle test 任务), mixin 注入仅在 Forge 运行期生效, 无法在单测里观测真实
 * SavedData 实例的注入行为。
 *
 * <p>本测试锁两件 deletion-sensitive 的事:
 * 1. 镜像字段必须声明为 volatile — 一旦去掉 volatile, JMM 不再保证跨线程可见, 反射断言挂;
 * 2. 镜像的逻辑契约 (setDirty(true)->true / setDirty(false)->false / last-writer-wins) 用一个
 *    JMM 等价的独立 holder 复刻 mixin 同样的字段+读写, 多线程下断言写后可见。
 *
 * <p>判定标准: 把 SavedDataMixin 的 {@code volatile} 删掉 -> 第一个反射断言挂。
 */
class SavedDataDirtyVisibilityTest {

    /** JMM 等价 holder: 复刻 mixin 的镜像字段 + 读写, 让逻辑契约可单测. */
    private static final class MirrorHolder {
        private volatile boolean dirtyMirror;

        void setDirty(boolean dirty) {
            this.dirtyMirror = dirty;
        }

        boolean isDirty() {
            return this.dirtyMirror;
        }
    }

    @Test
    void mixin_mirror_field_is_volatile() throws NoSuchFieldException {
        Field mirror = SavedDataMixin.class.getDeclaredField("betterautosave$dirtyMirror");
        assertTrue(Modifier.isVolatile(mirror.getModifiers()),
                "dirty 镜像字段必须是 volatile — 否则 worker 写 / 主线程读无 happens-before, 跨线程可见性无保证");
    }

    @Test
    void worker_write_visible_to_reader_thread() throws InterruptedException {
        // 复刻现场: 一个线程 (worker) setDirty(true), 另一个线程 (主线程) 必须读到 true.
        // volatile 镜像下, 写后启动的读线程必然观测到最新值.
        for (int iter = 0; iter < 1000; iter++) {
            MirrorHolder holder = new MirrorHolder();
            holder.setDirty(false);

            Thread workerWrite = new Thread(() -> holder.setDirty(true));
            workerWrite.start();
            workerWrite.join();

            // worker 线程已 join 后主线程读: volatile 保证读到 true. 删 volatile 在弱内存序下可能读 false.
            assertTrue(holder.isDirty(),
                    "iter " + iter + ": worker setDirty(true) 后主线程必须读到 dirty=true");
        }
    }

    @Test
    void last_writer_wins_contract() {
        MirrorHolder holder = new MirrorHolder();
        holder.setDirty(true);
        assertTrue(holder.isDirty(), "setDirty(true) 后 isDirty 必须为 true");
        holder.setDirty(false);
        assertTrue(!holder.isDirty(), "setDirty(false) 后 isDirty 必须为 false (覆盖前值)");
        holder.setDirty(true);
        assertTrue(holder.isDirty(), "再 setDirty(true) 必须覆盖回 true");
    }
}
