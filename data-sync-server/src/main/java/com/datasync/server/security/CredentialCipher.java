package com.datasync.server.security;

import com.datasync.server.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 数据源口令加解密（D9）—— AES-256-GCM。
 *
 * <p>落库格式：{@code ENC(base64(IV || 密文 || GCM Tag))}，前缀 {@code ENC(} 用于区分历史明文。
 * 读取时兼容历史明文（原样返回），由 {@code ConnectionPoolRegistry} 在首次使用时自动升级为密文。</p>
 *
 * <p>安全约束：密钥只来自 {@code app.security.secret-key}（生产用环境变量注入）；
 * 日志与异常消息中绝不出现明文口令或密文本身。</p>
 */
@Component
public class CredentialCipher {

    private static final Logger log = LoggerFactory.getLogger(CredentialCipher.class);

    /** 密文前缀：只有以此开头的值才被当作密文 */
    public static final String ENC_PREFIX = "ENC(";

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final int MIN_KEY_LENGTH = 32;
    /** 仅用于非生产环境的兜底密钥；生产 profile 下密钥为空会直接拒绝启动 */
    private static final String DEV_FALLBACK_KEY = "datasync-dev-only-insecure-secret-key-change-me";

    private final SecretKeySpec keySpec;
    private final SecureRandom random = new SecureRandom();

    public CredentialCipher(AppProperties properties, Environment environment) {
        String secret = properties.getSecurity().getSecretKey();
        boolean prod = environment.acceptsProfiles(Profiles.of("prod", "production"));
        if (secret == null || secret.isBlank()) {
            if (prod) {
                throw new IllegalStateException(
                    "生产环境必须配置 app.security.secret-key（环境变量 DATASYNC_SECRET_KEY），"
                        + "它是数据源口令 AES-GCM 加密的密钥，长度至少 " + MIN_KEY_LENGTH + " 字符；"
                        + "缺失时拒绝启动，避免口令以明文落库。");
            }
            log.warn("未配置 app.security.secret-key，已启用内置开发密钥（仅供本地开发）；"
                + "生产环境请通过环境变量 DATASYNC_SECRET_KEY 注入至少 {} 字符的密钥", MIN_KEY_LENGTH);
            secret = DEV_FALLBACK_KEY;
        } else if (secret.trim().length() < MIN_KEY_LENGTH) {
            throw new IllegalArgumentException("app.security.secret-key 长度不足 " + MIN_KEY_LENGTH
                + " 字符（当前 " + secret.trim().length() + "），无法安全用于 AES-GCM 加密");
        }
        this.keySpec = new SecretKeySpec(sha256(secret.trim()), "AES");
        log.info("数据源口令加密已启用：AES-256-GCM（密钥来源 app.security.secret-key，长度 {}）", secret.trim().length());
    }

    /** 加密；已加密或空白值原样返回（幂等，避免二次加密） */
    public String encrypt(String plain) {
        if (plain == null || plain.isEmpty() || isEncrypted(plain)) {
            return plain;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));

            byte[] payload = new byte[iv.length + sealed.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(sealed, 0, payload, iv.length, sealed.length);
            return ENC_PREFIX + Base64.getEncoder().encodeToString(payload) + ")";
        } catch (Exception e) {
            // 不复述任何口令内容
            throw new IllegalStateException("数据源口令加密失败：" + e.getClass().getSimpleName());
        }
    }

    /**
     * 解密。历史明文（无 {@code ENC(} 前缀）原样返回，保证升级期可读。
     */
    public String decrypt(String stored) {
        if (stored == null || stored.isEmpty() || !isEncrypted(stored)) {
            return stored;
        }
        String body = stored.substring(ENC_PREFIX.length(), stored.length() - 1);
        try {
            byte[] payload = Base64.getDecoder().decode(body);
            if (payload.length <= IV_LENGTH) {
                throw new IllegalArgumentException("payload too short");
            }
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(payload, 0, iv, 0, IV_LENGTH);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] plain = cipher.doFinal(payload, IV_LENGTH, payload.length - IV_LENGTH);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // 消息里不带密文，也不带底层异常细节（可能含密钥信息）
            throw new IllegalStateException(
                "数据源口令解密失败：密文格式非法或加密密钥已变更，请重新保存该数据源口令");
        }
    }

    /** 是否为密文（ENC(...) 形态） */
    public boolean isEncrypted(String stored) {
        return stored != null && stored.startsWith(ENC_PREFIX) && stored.endsWith(")");
    }

    /** 是否为需要自动升级的历史明文（非空且不是密文） */
    public boolean isLegacyPlaintext(String stored) {
        return stored != null && !stored.isEmpty() && !isEncrypted(stored);
    }

    /**
     * 用于异常消息 / 日志的清洗：把可能出现的明文口令替换为掩码。
     * 连接测试失败时 JDBC 驱动会把用户名/URL 带出来，但绝不应把口令带出来。
     */
    public static String scrub(String message, String... secrets) {
        if (message == null) {
            return null;
        }
        String cleaned = message;
        if (secrets != null) {
            for (String secret : secrets) {
                if (secret != null && !secret.isEmpty()) {
                    cleaned = cleaned.replace(secret, "******");
                }
            }
        }
        // 兜底：password=xxx / PASSWORD = 'xxx' 形态一律掩码
        return cleaned.replaceAll("(?i)(password\\s*[=:]\\s*)('[^']*'|\"[^\"]*\"|\\S+)", "$1******");
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("初始化加密密钥失败", e);
        }
    }
}
