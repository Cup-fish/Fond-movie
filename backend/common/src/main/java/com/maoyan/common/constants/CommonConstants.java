package com.maoyan.common.constants;

/**
 * 通用常量
 */
public final class CommonConstants {

    private CommonConstants() {}

    /** 每页默认大小 */
    public static final int DEFAULT_PAGE_SIZE = 20;

    /** 电影列表每批加载数量 */
    public static final int MOVIE_BATCH_SIZE = 10;

    /** 默认头像 */
    public static final String DEFAULT_HEAD_IMG = "/static/images/default-avatar.png";

    /** JWT header name */
    public static final String AUTH_HEADER = "Authorization";

    /** JWT token前缀 */
    public static final String TOKEN_PREFIX = "Bearer ";

    /** 模拟支付网关签名密钥（仅演示用，真实环境由支付平台下发，不暴露给前端） */
    public static final String MOCK_PAYMENT_SECRET = "maoyan-mock-pay-secret";
}
