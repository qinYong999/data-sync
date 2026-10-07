package com.datasync.server.security;

import com.datasync.server.config.AppProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D9：数据源口令 AES-GCM 加解密 + 历史明文兼容。
 */
class CredentialCipherTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    private static CredentialCipher cipher(String secret) {
        AppProperties properties = new AppProperties();
        properties.getSecurity().setSecretKey(secret);
        return new CredentialCipher(properties, new MockEnvironment());
    }

    @Test
    @DisplayName("加密后再解密得到原文，且密文带 ENC( 前缀")
    void encryptThenDecryptRoundTrip() {
        CredentialCipher cipher = cipher(SECRET);
        String plain = "p@ssw0rd-中文-!@#";

        String encrypted = cipher.encrypt(plain);

        assertThat(encrypted).startsWith("ENC(").endsWith(")");
        assertThat(encrypted).doesNotContain(plain);
        assertThat(cipher.isEncrypted(encrypted)).isTrue();
        assertThat(cipher.decrypt(encrypted)).isEqualTo(plain);
    }

    @Test
    @DisplayName("相同明文两次加密得到不同密文（随机 IV），都能解回原文")
    void encryptionIsRandomized() {
        CredentialCipher cipher = cipher(SECRET);

        String first = cipher.encrypt("same-secret");
        String second = cipher.encrypt("same-secret");

        assertThat(first).isNotEqualTo(second);
        assertThat(cipher.decrypt(first)).isEqualTo("same-secret");
        assertThat(cipher.decrypt(second)).isEqualTo("same-secret");
    }

    @Test
    @DisplayName("历史明文兼容：直接返回原文，并标记为可升级")
    void legacyPlaintextIsReturnedAsIs() {
        CredentialCipher cipher = cipher(SECRET);

        assertThat(cipher.decrypt("legacy-plain-password")).isEqualTo("legacy-plain-password");
        assertThat(cipher.isEncrypted("legacy-plain-password")).isFalse();
        assertThat(cipher.isLegacyPlaintext("legacy-plain-password")).isTrue();
        assertThat(cipher.isLegacyPlaintext("ENC(whatever)")).isFalse();
    }

    @Test
    @DisplayName("加密是幂等的：已是密文再加密不会二次加密")
    void encryptIsIdempotent() {
        CredentialCipher cipher = cipher(SECRET);
        String once = cipher.encrypt("secret");

        assertThat(cipher.encrypt(once)).isEqualTo(once);
    }

    @Test
    @DisplayName("空值安全")
    void nullAndEmptyAreSafe() {
        CredentialCipher cipher = cipher(SECRET);
        assertThat(cipher.encrypt(null)).isNull();
        assertThat(cipher.encrypt("")).isEmpty();
        assertThat(cipher.decrypt(null)).isNull();
        assertThat(cipher.decrypt("")).isEmpty();
    }

    @Test
    @DisplayName("密文被篡改或密钥变更时抛中文异常，且不泄漏密文内容")
    void tamperedCiphertextFailsWithChineseMessage() {
        CredentialCipher cipher = cipher(SECRET);
        String encrypted = cipher.encrypt("secret");
        String tampered = encrypted.substring(0, encrypted.length() - 3) + "AAA)";

        assertThatThrownBy(() -> cipher.decrypt(tampered))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("解密失败")
            .hasMessageNotContaining(tampered);
    }

    @Test
    @DisplayName("换密钥后旧密文解不开（证明密钥真的参与运算）")
    void differentKeyCannotDecrypt() {
        String encrypted = cipher(SECRET).encrypt("secret");
        CredentialCipher other = cipher("ffffffffffffffffffffffffffffffff");

        assertThatThrownBy(() -> other.decrypt(encrypted)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("生产 profile 未配置密钥 → 拒绝启动")
    void prodRequiresSecretKey() {
        AppProperties properties = new AppProperties();
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");

        assertThatThrownBy(() -> new CredentialCipher(properties, environment))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("DATASYNC_SECRET_KEY");
    }

    @Test
    @DisplayName("密钥长度不足 32 字符 → 拒绝启动")
    void shortKeyIsRejected() {
        assertThatThrownBy(() -> cipher("too-short"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("32");
    }

    @Test
    @DisplayName("非生产环境未配置密钥 → 使用内置开发密钥并可正常加解密")
    void devFallsBackToBuiltInKey() {
        CredentialCipher cipher = cipher("");
        String encrypted = cipher.encrypt("secret");
        assertThat(cipher.decrypt(encrypted)).isEqualTo("secret");
    }

    @Test
    @DisplayName("口令清洗：明文与 password=xxx 形态都被掩码")
    void scrubRemovesSecrets() {
        String scrubbed = CredentialCipher.scrub(
            "Access denied for user 'root'@'localhost' password=hunter2 using password: YES", "hunter2");
        assertThat(scrubbed).doesNotContain("hunter2").contains("******");
    }
}
