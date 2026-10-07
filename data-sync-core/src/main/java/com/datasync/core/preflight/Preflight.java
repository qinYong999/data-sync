package com.datasync.core.preflight;

import com.datasync.core.model.PreflightIssue;
import com.datasync.core.model.SyncTaskConfig;
import java.util.List;
import javax.sql.DataSource;

/**
 * 预检便捷入口（契约 §3.2）。引擎内部同样调用它，保证预检口径唯一。
 */
public final class Preflight {

    private static final Preflighter DEFAULT = new DefaultPreflighter();

    private Preflight() {
    }

    /** 执行预检；永不抛异常。 */
    public static List<PreflightIssue> check(SyncTaskConfig cfg, DataSource src, DataSource dst) {
        return DEFAULT.check(cfg, src, dst);
    }

    /**
     * 执行预检并叠加元数据库防护（扩展入口）。
     *
     * @param guard 元数据库防护；null 等价于 {@link #check(SyncTaskConfig, DataSource, DataSource)}
     */
    public static List<PreflightIssue> check(SyncTaskConfig cfg, DataSource src, DataSource dst,
                                             MetadataGuard guard) {
        return new DefaultPreflighter(guard).check(cfg, src, dst);
    }

    /** 是否包含 ERROR 级问题（有则禁止写入任何数据）。 */
    public static boolean hasError(List<PreflightIssue> issues) {
        if (issues == null) {
            return false;
        }
        for (PreflightIssue i : issues) {
            if (i != null && i.isError()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解析完整执行计划（引擎用，扩展入口）。
     *
     * <p>{@code plan.hasError() == false} 时即可安全执行；否则必须先修配置。
     */
    public static SyncPlan resolve(SyncTaskConfig cfg, DataSource src, DataSource dst) {
        return SyncPlan.resolve(cfg, src, dst);
    }
}
