package com.lostfound.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JWT 密钥的启动校验。
 *
 * <p>钉住的是这条安全契约：<b>密钥没配或太短，就在启动时直接失败</b>，
 * 而不是拿一个默认值（尤其是公开的默认值）继续跑。
 *
 * <p>为什么这条特别值得测：以前 application.yml 里写了一个硬编码的默认密钥，
 * 它随公开仓库一起发布了。只要有人忘了设环境变量，系统就会用那个人人可见的密钥
 * 签 token —— <b>任何人可以伪造身份，而系统一切正常、不报任何错</b>。
 * 这类"静默降级到不安全状态"是最不该有的默认行为。
 */
class JwtConfigTest {

    private static JwtConfig configWith(String secret) {
        JwtConfig config = new JwtConfig();
        config.setSecret(secret);
        return config;
    }

    /** 32 字节正好是 HS256 的下限 */
    private static final String EXACTLY_32_BYTES = "0123456789abcdef0123456789abcdef";

    @Test
    @DisplayName("未配置密钥 → 启动失败，且错误信息告诉你怎么配")
    void blankSecretFailsFastWithInstructions() {
        for (String blank : new String[]{null, "", "   "}) {
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> configWith(blank).validateSecret());
            assertTrue(ex.getMessage().contains("JWT 密钥未配置"), ex.getMessage());
            assertTrue(ex.getMessage().contains("JWT_SECRET"), "要给出环境变量名: " + ex.getMessage());
            assertTrue(ex.getMessage().contains("application-local.yml"), "要给出本地开发怎么配: " + ex.getMessage());
        }
    }

    @Test
    @DisplayName("★ 不存在任何默认密钥：空配置一定起不来（而不是回退到某个公开值）")
    void thereIsNoDefaultSecret() {
        // 这条是给"删掉硬编码默认值"这个改动立的桩：
        // 新对象不设 secret，也必须是失败的 —— 说明默认值真的不存在了
        assertThrows(IllegalStateException.class, () -> new JwtConfig().validateSecret());
    }

    @Test
    @DisplayName("密钥过短 → 启动失败，并说明要求多少字节")
    void shortSecretFailsFast() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> configWith("too-short-secret").validateSecret());

        assertTrue(ex.getMessage().contains("太短"), ex.getMessage());
        assertTrue(ex.getMessage().contains("32"), "要说明下限: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("256"), "要说明是 256 位: " + ex.getMessage());
    }

    @Test
    @DisplayName("边界：正好 32 字节放行，31 字节拦下")
    void boundaryIsExactly32Bytes() {
        assertDoesNotThrow(() -> configWith(EXACTLY_32_BYTES).validateSecret());
        assertThrows(IllegalStateException.class,
                () -> configWith(EXACTLY_32_BYTES.substring(0, 31)).validateSecret());
    }

    @Test
    @DisplayName("长度按 UTF-8 字节算，不是按字符数")
    void lengthIsCountedInUtf8Bytes() {
        // 12 个汉字 = 36 字节 ≥ 32 → 合法（按字符数只有 12，会误判为太短）
        String chinese = "这是一段足够长的中文密钥用于测试边界情况";
        assertTrue(chinese.getBytes(java.nio.charset.StandardCharsets.UTF_8).length >= 32);
        assertDoesNotThrow(() -> configWith(chinese).validateSecret());
    }

    @Test
    @DisplayName("过期天数有默认值 7 天")
    void expireDaysHasDefault() {
        assertEquals(7, new JwtConfig().getExpireDays());
    }
}
