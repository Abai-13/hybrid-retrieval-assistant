package com.lostfound.common;

/**
 * 分页参数的统一夹取。
 *
 * <p>参数是从 URL 直接绑定的，可以传任意值。不夹的话最少有两个坑：
 * ① {@code page} 传 9999 → offset 极大 → 深分页打穿数据库（实测过，EXPLAIN 会退化成全表扫描）；
 * ② {@code size} 传 0 或不传负值时，LIMIT 的语义会变得不可控。
 * <p>
 * 上限 {@value #MAX_PAGE} 页 / {@value #MAX_SIZE} 条是<b>产品判断</b>，不是性能优化的成果 ——
 * 失物招领不可能有人翻到第 2000 页，与其让极端参数打到数据库再做优化，不如从需求上砍掉这个场景。
 */
public final class PageLimits {

    private PageLimits() {}

    /** 页码上限 */
    public static final int MAX_PAGE = 200;

    /** 每页条数上限 */
    public static final int MAX_SIZE = 50;

    /** 页码夹到 [1, MAX_PAGE] */
    public static int page(int page) {
        if (page < 1) {
            return 1;
        }
        return Math.min(page, MAX_PAGE);
    }

    /** 每页条数夹到 [1, MAX_SIZE] */
    public static int size(int size) {
        if (size < 1) {
            return 1;
        }
        return Math.min(size, MAX_SIZE);
    }
}
