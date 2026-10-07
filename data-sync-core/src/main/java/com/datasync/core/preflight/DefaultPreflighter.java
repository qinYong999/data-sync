package com.datasync.core.preflight;

import com.datasync.core.model.PreflightIssue;
import com.datasync.core.model.SyncTaskConfig;
import java.util.List;
import javax.sql.DataSource;

/**
 * 默认预检实现：委托给 {@link SyncPlan#resolve}（与引擎共用同一份解析结果，杜绝"预检一套、执行另一套"），
 * 并可选地叠加 {@link MetadataGuard} 元数据库防护。
 *
 * <p>两个构造器都在用：
 * <ul>
 *   <li>{@code new DefaultPreflighter()} —— 契约要求的无参形态，不做元数据库检查。</li>
 *   <li>{@code new DefaultPreflighter(MetadataGuard.of(url))} —— 平台装配用，guard 为 null 等价于不检查。</li>
 * </ul>
 */
public final class DefaultPreflighter implements Preflighter {

    private final MetadataGuard guard;

    public DefaultPreflighter() {
        this(null);
    }

    public DefaultPreflighter(MetadataGuard guard) {
        this.guard = guard;
    }

    @Override
    public List<PreflightIssue> check(SyncTaskConfig cfg, DataSource src, DataSource dst) {
        SyncPlan plan = SyncPlan.resolve(cfg, src, dst);
        List<PreflightIssue> issues = plan.issues();
        applyGuard(cfg, dst, issues);
        return issues;
    }

    /**
     * 把元数据库防护的判定结果追加到已有 issues 上（引擎复用已解析计划时调用，避免重复读元数据）。
     *
     * <p>刻意不叫 {@code check}：与契约里的三参 {@code check(cfg, src, dst)} 放在一起会造成
     * "三个 null 字面量" 的编译歧义。
     */
    public void checkInto(SyncTaskConfig cfg, DataSource target, List<PreflightIssue> issues) {
        applyGuard(cfg, target, issues);
    }

    private void applyGuard(SyncTaskConfig cfg, DataSource target, List<PreflightIssue> issues) {
        if (guard == null) {
            return;
        }
        String table = cfg == null ? null : cfg.getTargetTable();
        guard.check(target, table, issues);
    }
}
