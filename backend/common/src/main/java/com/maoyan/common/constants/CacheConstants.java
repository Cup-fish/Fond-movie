package com.maoyan.common.constants;

/**
 * 缓存常量（多级缓存 & Redis Key 规范）
 *
 * <p>Key 设计规范：{业务域}:{数据类型}:{唯一标识}</p>
 */
public final class CacheConstants {

    private CacheConstants() {}

    // =================== L1 / L2 缓存 Key ===================

    /** 热映电影列表缓存键 */
    public static final String HOT_MOVIES = "hot_movies";

    /** 即将上映列表缓存键 */
    public static final String COMING_MOVIES = "coming_movies";

    /** 最受期待列表缓存键 */
    public static final String MOST_EXPECTED = "most_expected";

    /** 城市列表缓存键 */
    public static final String CITIES = "cities";

    /** 影院筛选项缓存前缀 */
    public static final String CINEMA_FILTER_PREFIX = "cinema_filter:";

    // =================== Redis 专用 Key ===================

    /** 电影想看数 Hash: movie:wish → {movieId: count} */
    public static final String MOVIE_WISH_HASH = "movie:wish";

    /** 用户想看集合: user:wish:{userId} */
    public static final String USER_WISH_PREFIX = "user:wish:";

    /** 场次库存: schedule:stock:{scheduleId} */
    public static final String SCHEDULE_STOCK_PREFIX = "schedule:stock:";

    /** 场次详情缓存: schedule:detail:{scheduleId} */
    public static final String SCHEDULE_DETAIL_PREFIX = "schedule:detail:";

    /**
     * 脏数据队列（Redis 回滚失败时记录，用于修复不一致）
     * 结构：Hash，key=scheduleId，value=seatCount（应回滚但未回滚的数量）
     */
    public static final String DIRTY_ROLLBACK_KEY = "stock:dirty:rollback";

    /** 限流 Key 前缀: rate_limit:{resource}:{identifier} */
    public static final String RATE_LIMIT_PREFIX = "rate_limit:";

    /** 分布式锁前缀: lock:{resource} */
    public static final String LOCK_PREFIX = "lock:";

    /** 电影详情缓存: movie:detail:{movieId} */
    public static final String MOVIE_DETAIL_PREFIX = "movie:detail:";

    /** 座位锁定前缀: seat:lock:{scheduleId}:{row}:{col} */
    public static final String SEAT_LOCK_PREFIX = "seat:lock:";

    /** 抢座请求结果前缀: seat:request:result:{requestId} */
    public static final String SEAT_REQUEST_RESULT_PREFIX = "seat:request:result:";

    /** 抢座队列深度计数器 */
    public static final String SEAT_QUEUE_DEPTH_KEY = "seat:queue:depth";

    /** 座位锁定过期时间(分钟) */
    public static final int SEAT_LOCK_MINUTES = 15;

    /** 抢座请求最大排队数 */
    public static final int SEAT_QUEUE_MAX_DEPTH = 200;

    /** 抢座请求结果缓存过期时间(秒) */
    public static final int SEAT_REQUEST_RESULT_TTL = 30;

    /** 订单支付超时时间(分钟) */
    public static final int ORDER_PAY_TIMEOUT_MINUTES = 15;

    // =================== 过期时间 ===================

    /** L1 Caffeine 默认过期时间(秒) */
    public static final long L1_EXPIRE_SECONDS = 60;

    /** L2 Redis 默认过期时间(分钟) */
    public static final long L2_EXPIRE_MINUTES = 10;

    /** 默认缓存过期时间(分钟) */
    public static final long DEFAULT_EXPIRE_MINUTES = 10;

    /** 城市列表缓存过期时间(小时) */
    public static final long CITY_EXPIRE_HOURS = 24;

    /** 库存缓存过期时间(小时) */
    public static final long STOCK_EXPIRE_HOURS = 12;
}
