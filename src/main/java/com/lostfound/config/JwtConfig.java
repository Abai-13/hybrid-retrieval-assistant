package com.lostfound.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * JWT 配置 — 从 application.yml 读取。
 *
 * <p>密钥<b>没有默认值</b>，必须由外部提供（见 {@link #validateSecret()}）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "jwt")
public class JwtConfig {

    /** HS256 要求密钥至少 256 位 */
    private static final int MIN_SECRET_BYTES = 32;

    /** 签名密钥（至少 256 位）—— 通过环境变量 JWT_SECRET 或本地配置文件提供 */
    private String secret;

    /** 过期天数 */
    private int expireDays = 7;

    /**
     * 启动时校验密钥 —— <b>不合法就直接起不来</b>。
     *
     * <p>为什么必须失败而不是兜底：以前这里配了一个硬编码的默认密钥，它随仓库公开了。
     * 只要有人部署时忘了设环境变量，就会用那个<b>人人可见</b>的密钥签 token，
     * 任何人都能伪造出合法身份 —— 而且系统看起来一切正常，不会有任何报错。
     * 这种"静默降级到不安全状态"正是最不该有的默认行为。
     *
     * <p>为什么在启动时校验而不是等到第一次签发 token：那时候报出来的是一个
     * 藏在调用栈深处的 NPE 或 WeakKeyException，没人能一眼看出是密钥没配。
     */
    @PostConstruct
    void validateSecret() {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("""
                    JWT 密钥未配置 —— 仓库里刻意不提供默认值（公开的默认密钥等于没有认证）。
                    请通过下面任一方式提供（至少 32 字节）：
                      · 环境变量：JWT_SECRET=<你的密钥>
                      · 本地开发：在 application-local.yml 里加一行 jwt.secret: <你的密钥>（该文件已 gitignore）
                    """);
        }
        int length = secret.getBytes(StandardCharsets.UTF_8).length;
        if (length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("JWT 密钥太短：当前 " + length + " 字节，"
                    + "HS256 要求至少 " + MIN_SECRET_BYTES + " 字节（256 位），否则签名强度不足。");
        }
    }
}
