package com.lostfound.common;

/**
 * 统一返回结果状态码
 */
public final class ResultCode {

    private ResultCode() {}

    public static final int SUCCESS = 200;
    public static final int BAD_REQUEST = 400;
    public static final int UNAUTHORIZED = 401;
    public static final int FORBIDDEN = 403;
    public static final int NOT_FOUND = 404;
    /**
     * 状态冲突 —— 请求合法，但对象当前状态不允许这次操作（如「这条申请已经被别人处理了」）。
     * <p>和 400 的区别：400 是参数本身就不对，不需要用户重试；
     * 409 是<b>时机不对</b>，用户刷新看到最新状态后可能是可以继续的。
     */
    public static final int CONFLICT = 409;
    /**
     * 资源已经不存在了，而且不会再回来（例如：会话超过保留期被清理）。
     * <p>和 409 的区别很重要，且**必须机器可辨**：
     * 409 是"时机不对"，刷新或重试就好了；410 是"这段状态没了"，
     * 重试一百次也一样，只能重新开始。两者混用一个码，前端就只能去匹配错误文案里的字，
     * 而任何一次文案微调都会悄悄打断它 —— 用户会在"该重试"的时候被引导去开新对话，
     * 把刚说过的条件丢掉。
     */
    public static final int GONE = 410;
    public static final int INTERNAL_ERROR = 500;

    public static final String SUCCESS_MSG = "操作成功";
    public static final String BAD_REQUEST_MSG = "请求参数错误";
    public static final String UNAUTHORIZED_MSG = "未登录或 token 已过期";
    public static final String FORBIDDEN_MSG = "无权限访问";
    public static final String NOT_FOUND_MSG = "资源不存在";
    public static final String CONFLICT_MSG = "操作冲突，请刷新后重试";
    public static final String GONE_MSG = "内容已过期或被清理，请重新开始";
    public static final String INTERNAL_ERROR_MSG = "服务器内部错误";
}
