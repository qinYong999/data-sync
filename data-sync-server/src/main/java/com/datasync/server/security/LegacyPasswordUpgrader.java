package com.datasync.server.security;

import com.datasync.server.entity.DataSourceEntity;
import com.datasync.server.repository.DataSourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 历史明文口令的自动升级（D9，契约 §4.2「首次读取时自动升级」）。
 *
 * <p>关键设计：升级**不依赖连通性**。旧库里最常见的情况恰恰是"躺着一条连不上的旧数据源"
 * （口令错、库下线、网络不通），而它正是明文长期留存的场景。因此升级挂在三条互不依赖的路径上：</p>
 * <ol>
 *   <li><b>启动扫描</b>（{@link #upgradeAll()}）：一次性把库里所有残留明文加密回写，
 *       连不上也照样升级；</li>
 *   <li><b>读取路径</b>（{@code DataSourceService} 的查询/列表）：读到明文就地升级；</li>
 *   <li><b>取连接池路径</b>（{@code ConnectionPoolRegistry.get}）：兜底。</li>
 * </ol>
 *
 * <p>日志只记录数据源 ID 与条数，绝不输出明文或密文本身。</p>
 */
@Service
public class LegacyPasswordUpgrader {

    private static final Logger log = LoggerFactory.getLogger(LegacyPasswordUpgrader.class);

    private final DataSourceRepository repository;
    private final CredentialCipher cipher;

    public LegacyPasswordUpgrader(DataSourceRepository repository, CredentialCipher cipher) {
        this.repository = repository;
        this.cipher = cipher;
    }

    /**
     * 单条升级：明文 → AES-GCM 密文并回写。
     *
     * <p>刻意不加 {@code @Transactional}：常态是"读到密文直接返回"，不该为它开事务；
     * 真正需要写库时由 {@code repository.save(...)} 自己那一层事务保证原子性。</p>
     *
     * @return true 表示本次确实发生了升级
     */
    public boolean upgradeIfLegacy(DataSourceEntity entity) {
        if (entity == null) {
            return false;
        }
        String stored = entity.passwordCipher();
        if (!cipher.isLegacyPlaintext(stored)) {
            return false;
        }
        entity.passwordCipher(cipher.encrypt(stored));
        repository.save(entity);
        log.info("数据源 {} 的历史明文口令已升级为 AES-GCM 密文", entity.getId());
        return true;
    }

    /**
     * 启动时一次性全量扫描。走 {@code repository} 直查"非 ENC( 开头"的行，
     * 不建立任何业务库连接，因此"连不上的旧数据源"也会被升级。
     *
     * @return 升级条数
     */
    @Transactional
    public int upgradeAll() {
        List<DataSourceEntity> legacy = repository.findLegacyPlaintextPasswords();
        int upgraded = 0;
        for (DataSourceEntity entity : legacy) {
            String stored = entity.passwordCipher();
            if (stored == null || stored.isEmpty() || cipher.isEncrypted(stored)) {
                continue;
            }
            entity.passwordCipher(cipher.encrypt(stored));
            repository.save(entity);
            upgraded++;
        }
        if (upgraded > 0) {
            log.warn("启动扫描：{} 条数据源的历史明文口令已升级为 AES-GCM 密文（无需数据库连通性）", upgraded);
        } else {
            log.info("启动扫描：未发现历史明文口令");
        }
        return upgraded;
    }
}
