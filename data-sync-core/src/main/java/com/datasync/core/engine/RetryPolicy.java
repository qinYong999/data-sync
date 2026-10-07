package com.datasync.core.engine;

import com.datasync.core.model.ErrorPolicy;
import java.sql.SQLException;
import java.sql.SQLNonTransientException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientException;

/**
 * 重试判定与退避。
 *
 * <p>原则：<b>只重试"重试有意义"的错误</b>。数据本身的问题（约束冲突、类型错误、语法错误）
 * 重试一万次也一样失败，只会把任务卡住；连接断掉、死锁、锁等待超时才是值得重试的。
 */
final class RetryPolicy {

    private RetryPolicy() {
    }

    /** 是否可重试。 */
    static boolean isRetryable(Throwable t) {
        if (t == null) {
            return false;
        }
        if (t instanceof SQLTransientException || t instanceof SQLRecoverableException
                || t instanceof SQLTimeoutException) {
            return true;
        }
        if (t instanceof SQLNonTransientException) {
            return false;
        }
        Throwable cause = t.getCause();
        if (cause != null && cause != t && !(t instanceof SQLException)) {
            return isRetryable(cause);
        }
        if (t instanceof SQLException e) {
            String state = e.getSQLState();
            if (state != null) {
                // 08xxx 连接异常；40xxx 事务回滚（死锁）；53xxx 资源不足；HYT00/HYT01 超时
                if (state.startsWith("08") || state.startsWith("40") || state.startsWith("53")
                        || state.equals("HYT00") || state.equals("HYT01")
                        || state.startsWith("S1") || state.equals("70100")) {
                    return true;
                }
                // 23xxx 完整性约束：数据问题，不重试
                if (state.startsWith("23")) {
                    return false;
                }
            }
            int code = e.getErrorCode();
            // MySQL: 1205 锁等待超时、1213 死锁、2006/2013 连接断开、1040 连接数满
            return code == 1205 || code == 1213 || code == 2006 || code == 2013 || code == 1040;
        }
        return false;
    }

    /** 指数退避毫秒数：base * 2^(attempt-1)，上限 60s。 */
    static long backoffMillis(ErrorPolicy policy, int attempt) {
        long base = policy == null ? 2000L : Math.max(0L, policy.getRetryBackoffMs());
        if (base == 0L) {
            return 0L;
        }
        long shift = Math.min(Math.max(0, attempt - 1), 10);
        long ms = base << shift;
        return Math.min(ms, 60_000L);
    }

    /** 可中断的等待。 */
    static void sleep(long millis) throws InterruptedException {
        if (millis > 0) {
            Thread.sleep(millis);
        }
    }
}
