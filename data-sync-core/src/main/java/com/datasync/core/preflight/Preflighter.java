package com.datasync.core.preflight;

import com.datasync.core.model.PreflightIssue;
import com.datasync.core.model.SyncTaskConfig;
import java.util.List;
import javax.sql.DataSource;

/**
 * 预检：在写入任何数据之前把配置错误全部暴露出来。
 *
 * <p>实现约定：<b>不抛异常</b>。连不上库、元数据读不到都要转成 ERROR issue，
 * 否则接口层只能给用户一个 500 白屏。
 */
public interface Preflighter {

    /**
     * @return 全部问题（含 WARN）；无 ERROR 表示可以开始同步
     */
    List<PreflightIssue> check(SyncTaskConfig cfg, DataSource src, DataSource dst);
}
