package com.lostfound.config;

import com.lostfound.interceptor.JwtInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 配置 — CORS + 拦截器注册。
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final JwtInterceptor jwtInterceptor;
    private final RateLimitInterceptor rateLimitInterceptor;

    /** CORS — 开发阶段允许所有来源 */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }

    /**
     * 静态资源映射 — 让浏览器能访问本地磁盘上的上传文件。
     * /uploads/xxx.jpg → 磁盘上的 uploads/xxx.jpg
     * "file:" 前缀表示这是磁盘路径，不是 classpath。
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:uploads/");
    }

    /** 注册 JWT 拦截器 */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(jwtInterceptor)
                .addPathPatterns("/api/**")
                // 放行不需要登录的接口
                .excludePathPatterns(
                        "/api/user/register",
                        "/api/user/login"
                );

        // 限流拦截器 — 仅拦截 AI 接口（在 JWT 之后执行）
        //
        // 为什么把会话的读/建排除掉：限流保护的是**花模型额度**的调用。
        // 多轮找物助手每轮至少一次请求，页面加载还各有一次读会话，
        // 全都计入的话，默认 5 次/分钟的额度十几秒就用完了 ——
        // 用户会在一段正常的对话里突然被 429 挡住。
        //
        // ⚠️ 只排除**不调模型**的路径：
        //    /api/ai/session            GET 读当前会话 / POST 新建会话
        //    /api/ai/session/{id}       GET 读指定会话
        //    「*」只匹配一段，所以 /api/ai/session/{id}/messages 仍然受限 ——
        //    那才是真正调用大模型的接口，它必须留着。
        //    同理 /api/ai/chat、/api/ai/query 未做任何改动，仍然受限。
        //
        // ⚠️ 放行限流 ≠ 放行鉴权：这些接口仍然要求登录，
        //    会话所有权仍然逐个在 Service 层校验。
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/api/ai/**")
                .excludePathPatterns(
                        "/api/ai/session",
                        "/api/ai/session/*"
                );
    }
}
