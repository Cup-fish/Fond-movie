# 猫眼项目完整流程详解

> 基于代码全量分析，覆盖：选座 → 锁座 → 下单 → 支付 → 想看 五个核心流程。
> 包含每一步读/写了哪些 Redis Key、走了哪些 DB、发了哪些 MQ 消息。

---

## 一、项目技术栈一览

```
┌─────────────────────────────────────────────────────────────┐
│                      前端 Next.js (3000)                     │
│                  移动端 HTML / React                         │
└────────────────────────┬────────────────────────────────────┘
                         │ HTTP REST
┌────────────────────────▼────────────────────────────────────┐
│                 后端 Spring Boot                             │
│                                                          │
│  provider层  ←  Controller（接收请求，限流注解）              │
│              ←  AOP限流切面（RateLimitAspect）               │
│                                                          │
│  service层  ←  SeatService（锁座）                          │
│              ←  OrderService（下单）                         │
│              ←  PaymentService（支付）                       │
│              ←  WishService（想看）                          │
│              ←  DistributedLockService（Redisson锁）         │
│              ←  StockService（Redis库存）                     │
│              ←  SeatRequestBufferService（MQ缓冲+Future桥接） │
│              ←  RateLimiterService（滑动窗口+令牌桶）         │
│              ←  MultiLevelCacheService（L1 Caffeine + L2 Redis）│
│                                                          │
│  MQ消费者  ←  SeatLockConsumer（处理锁座）                   │
│              ←  OrderEventConsumer（处理订单事件）             │
│              ←  WishWriteBackConsumer（想看写回）             │
└────────────────────────┬────────────────────────────────────┘
                         │
          ┌──────────────┼──────────────┐
          ▼              ▼              ▼
      ┌────────┐    ┌────────┐    ┌────────┐
      │  Redis  │    │ MySQL  │    │RocketMQ│
      │(6类Key) │    │(6张表) │    │(4个Topic)│
      └────────┘    └────────┘    └────────┘
```

---

## 二、Redis Key 全局总览

项目中共使用了 11 类 Redis Key，理解每类的用途是搞懂全流程的基础。

```
┌────────────────────────────────────────────────────────────────────────────┐
│  1. 分布式锁                                                                   │
│     lock:seat:{scheduleId}          锁座分布式锁（Redisson，场次级）            │
│     lock:{resource}                 通用锁前缀                               │
├────────────────────────────────────────────────────────────────────────────┤
│  2. 场次库存（防超卖核心）                                                      │
│     schedule:stock:{scheduleId}      场次剩余座位数（String，Lua原子扣减）        │
│     schedule:detail:{scheduleId}     场次详情Hash（movieId/cinemaId/price/    │
│                                     hallName/status/version等）               │
├────────────────────────────────────────────────────────────────────────────┤
│  3. 座位锁定（锁座过程）                                                         │
│     seat:lock:{scheduleId}:{row}:{col}  座位是否被锁（当前项目未实际使用）       │
│     seat:request:result:{requestId}    锁座结果缓存（Future超时时降级轮询）     │
│     seat:queue:depth                   当前MQ队列积压深度                       │
├────────────────────────────────────────────────────────────────────────────┤
│  4. 想看数（实时计数）                                                          │
│     movie:wish                        Hash：{movieId → count}                 │
│     user:wish:{userId}                Set：用户已想看的电影ID列表（去重）        │
├────────────────────────────────────────────────────────────────────────────┤
│  5. 限流器                                                                     │
│     rate_limit:{resource}:{identifier}      滑动窗口ZSet（时间戳做score）       │
│     rate_limit:tb:{resource}:{identifier}:tokens   令牌桶当前令牌数              │
│     rate_limit:tb:{resource}:{identifier}:ts       令牌桶上次补充时间            │
├────────────────────────────────────────────────────────────────────────────┤
│  6. 脏数据队列                                                                 │
│     stock:dirty:rollback               Hash：回滚失败的 scheduleId → seatCount  │
└────────────────────────────────────────────────────────────────────────────┘
```

**TTL 规则：**

| Key | TTL | 说明 |
|---|---|---|
| `schedule:stock:*` | 12小时 | 库存缓存，频繁读 |
| `schedule:detail:*` | 12小时 | 场次详情缓存 |
| `seat:request:result:*` | 30秒 | 锁座结果短暂缓存 |
| `seat:queue:depth` | 5分钟 | 队列计数器 |
| `movie:wish` | 永不过期 | Hash，需手动维护 |
| `user:wish:{userId}` | 永不过期 | Set |
| `rate_limit:*` | 窗口秒数 + 缓冲 | 自动过期 |
| `rate_limit:tb:*:tokens` | 桶满时间×2 | 自动过期 |
| `stock:dirty:rollback` | 永不过期 | 定时任务清空 |

---

## 三、数据库表结构

```
┌─────────────────────────────────────────────────────────────────────────┐
│  movie_schedule（场次表）                                                │
│  ├── id, movie_id, cinema_id, hall_name                                 │
│  ├── show_date, show_time, end_time, lang                               │
│  ├── total_seats, available_seats（剩余座位数，防超卖关键字段）             │
│  ├── price, status, deleted                                              │
│  └── version（乐观锁版本号，MyBatis-Plus @Version）                      │
│                                                                         │
│  seat_lock（座位锁定表）                                                  │
│  ├── id, schedule_id, row_num, col_num                                  │
│  ├── user_id, lock_until（锁定到期时间）                                 │
│  ├── status（0=已释放 1=锁定中 2=已购买）                                │
│  └── create_time, update_time                                           │
│  唯一索引: (schedule_id, row_num, col_num, status)                       │
│                                                                         │
│  ticket_order（订单表）                                                  │
│  ├── id, order_no（唯一）, user_id, schedule_id                         │
│  ├── movie_name, cinema_name, hall_name, show_time（快照）               │
│  ├── seat_count, seats_info, unit_price, total_price                    │
│  ├── status（0=待支付 1=已支付 2=已取消 3=已退款）                      │
│  └── expire_time, pay_time, cancel_time                                  │
│                                                                         │
│  order_seat（座位购买表）                                                │
│  ├── id, order_id, schedule_id, row_num, col_num                       │
│                                                                         │
│  user_wish（用户想看表）                                                 │
│  ├── id, user_id, movie_id                                              │
│  唯一索引: (user_id, movie_id)                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 四、完整流程一：选座 → 锁座

### 4.1 流量入口：用户点击座位

用户在前端选择座位，点击"确认选座"。

```
前端 POST /api/seat/lock
Content-Type: application/json
{
  "scheduleId": 123,
  "seats": [{"row": 5, "col": 8}, {"row": 5, "col": 9}]
}
```

### 4.2 第一层：限流（@RateLimit 注解）

在 `SeatController.lockSeats()` 上有一个注解：

```java
@PostMapping("/lock")
@RateLimit(key = "seat:lock", algorithm = RateLimitAlgorithm.TOKEN_BUCKET, capacity = 5, refillRate = 2)
public Result<Map<String, Object>> lockSeats(...) { ... }
```

**原理：** `RateLimitAspect` 拦截，在方法执行前调用 `RateLimiterService.isAllowedTokenBucket()`。

**Redis 操作：**

```
Key: rate_limit:tb:seat:lock:user:1001:tokens
Key: rate_limit:tb:seat:lock:user:1001:ts

Lua 脚本执行：
1. 读取当前令牌数和上次补充时间
2. elapsed = 当前时间 - 上次补充时间
3. 补充令牌：current_tokens = min(capacity, last_tokens + elapsed * refill_rate)
4. current_tokens >= 1 → 消耗一个令牌，返回 1（允许）
   current_tokens < 1  → 返回 0（限流）
```

**意图：** 单用户维度，最多允许 5 个请求突发进来（桶容量 5），之后每秒补充 2 个令牌（平均速率 2/s）。拦截恶意刷票用户。

**限流后发生什么：**

```
桶满(5个) → 用户前5个请求全部通过
桶空 → 第6个请求开始被拒绝，返回 429 错误

被限流后：throw BizException(408, "请求过于频繁")
```

### 4.3 第二层：MQ 削峰（异步缓冲）

**MQ 不可用时（降级路径）：**

```java
if (bufferService.isMQAvailable()) {
    return lockSeatsAsync(userId, dto);  // 走 MQ
}
return Result.ok(seatService.lockSeats(userId, dto));  // 降级：直接同步调用
```

**MQ 可用时（正常路径）→ `lockSeatsAsync()`：**

#### Step 1：检查队列深度

```java
public String submitLockRequest(Long userId, LockSeatsDTO dto) {
    // 检查 Redis 计数器
    if (depth >= 200) throw BizException(QUEUE_FULL);
```

**Redis 操作：**

```
GET seat:queue:depth
→ 如果 >= 200，拒绝请求（防堆积）
```

#### Step 2：生成 RequestId

```java
String requestId = UUID.randomUUID().toString().replace("-", "");
// 例：a1b2c3d4e5f6789012345678
```

#### Step 3：注册 CompletableFuture

```java
CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
pendingRequests.put(requestId, future);
// pendingRequests 是 ConcurrentHashMap<String, CompletableFuture>
// key=requestId, value=等待结果的 Future
```

**注意：** `pendingRequests` 在内存中（单 JVM 有效），所以 Controller 和 Consumer 必须在同一个服务实例里。

#### Step 4：发 MQ 消息

```java
SeatLockRequestEvent event = new SeatLockRequestEvent();
event.setRequestId(requestId);          // 用于回填结果
event.setUserId(userId);
event.setScheduleId(dto.getScheduleId());
event.setSeats([{row:5, col:8}, {row:5, col:9}]);
event.setTimestamp(System.currentTimeMillis());

rocketMQTemplate.syncSend("maoyan_seat_lock_request:SEAT_LOCK", event);
```

**MQ Topic：** `maoyan_seat_lock_request`
**MQ Tag：** `SEAT_LOCK`

#### Step 5：计数器 + 立即返回

```java
// Redis 操作：
INCR seat:queue:depth
EXPIRE seat:queue:depth 300  // 5分钟

// Controller 立即返回：
return Result.ok({requestId: "a1b2c3d4..."})
// 前端拿到 requestId，继续轮询或等待
```

### 4.4 第四层：Consumer 消费（同步等待结果）

Controller 端调用 `waitForResult(requestId)` 同步等待：

```java
Map<String, Object> result = bufferService.waitForResult(requestId);
// future.get(5, TimeUnit.SECONDS)
// 如果5秒内 Consumer 回填了结果 → 返回结果给前端
// 如果5秒超时 → 返回 null → 前端提示"系统繁忙"
```

**等待的底层机制：**

```
Controller 线程调用 future.get() → 被阻塞（不占线程池，只占线程）
                ↑
                │
        CompletableFuture 引用存在 pendingRequests 里
                ↑
                │
    Consumer 处理完 → completeRequest(requestId, result)
                → future.complete(result)
                → Controller 线程被唤醒 → 继续执行 → 返回给前端
```

### 4.5 第五层：Consumer 真正处理锁座逻辑

`SeatLockConsumer.onMessage(event)` 开始执行：

#### 第一道检查：超龄丢弃

```java
long age = System.currentTimeMillis() - event.getTimestamp();
if (age > 10_000) {  // >10秒
    bufferService.failRequest(requestId, BizException(408, "请求已超时"));
    return;
}
```

**意义：** MQ 可能有积压，过老的请求直接丢弃，避免处理无效数据。

#### 第二道检查：消费端令牌桶限速

```java
// 保护 DB：不计用户维度，只计全局
if (!rateLimiterService.isAllowedTokenBucket(
        "seat:consumer",   // 资源key
        "global",           // 标识（全局维度）
        15,                 // 桶容量
        10                  // 每秒补充10个
)) {
    throw RuntimeException("消费端令牌桶限速，等待重试");
}
```

**Redis 操作：**

```
Key: rate_limit:tb:seat:consumer:global:tokens
Key: rate_limit:tb:seat:consumer:global:ts

→ 每秒最多允许 10 个消息被处理
→ 超过 10 个/秒，新消息抛异常 → RocketMQ 自动重试（指数退避）
```

#### 核心：调用 SeatService.lockSeats

```java
LockSeatsDTO dto = convertToDTO(event);
Map<String, Object> result = seatService.lockSeats(event.getUserId(), dto);
bufferService.completeRequest(requestId, result);
```

---

### 4.6 锁座核心：SeatService.lockSeats 逐行分析

```java
@Transactional
public Map<String, Object> lockSeats(Long userId, LockSeatsDTO dto) {
    Long scheduleId = dto.getScheduleId();
    String lockKey = "seat:" + scheduleId;   // 例：seat:123

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  第一层保护：Redisson 分布式锁（场次级）
    //  Redis SETNX lock:seat:123 <threadId> NX PX 10000
    //  等3秒拿锁，持有10秒自动释放
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    Map<String, Object> result = lockService.executeWithLock(lockKey, 3, 10, () -> {

        // DB 查询①：验证场次是否存在且可售
        SchedulePO schedule = scheduleMapper.selectById(scheduleId);
        // SQL: SELECT * FROM movie_schedule WHERE id = 123 AND deleted = 0
        if (schedule == null || schedule.getStatus() != 1) {
            throw BizException("场次不存在或已停售");
        }

        LocalDateTime now = LocalDateTime.now();

        // DB 操作②：释放该用户在此场次的旧锁定（重新选座场景）
        seatLockMapper.releaseUserLocks(scheduleId, userId, now);
        // SQL: UPDATE seat_lock SET status=0 WHERE schedule_id=123 AND user_id=1001 AND status=1

        // 循环检查每个座位：
        for (LockSeatsDTO.SeatPos seat : dto.getSeats()) {

            // DB 查询③：查座位是否被他人锁定
            SeatLockPO existing = seatLockMapper.selectActiveLock(scheduleId, seat.getRow(), seat.getCol(), now);
            // SQL: SELECT * FROM seat_lock WHERE schedule_id=123 AND row=5 AND col=8
            //                    AND status IN(1,2) AND lock_until > now LIMIT 1
            if (existing != null && !existing.getUserId().equals(userId)) {
                throw BizException(SEAT_LOCKED);  // 座位已被他人锁定
            }

            // DB 查询④：查座位是否已售出
            List<OrderSeatPO> purchased = orderSeatMapper.selectPurchasedSeats(scheduleId);
            // SQL: SELECT * FROM order_seat WHERE schedule_id = 123
            boolean alreadyPurchased = purchased.stream()
                .anyMatch(os -> os.getRowNum().equals(seat.getRow()) && os.getColNum().equals(seat.getCol()));
            if (alreadyPurchased) {
                throw BizException(SEAT_LOCKED.getCode(), seat.getRow() + "排" + seat.getCol() + "座已售出");
            }
        }

        // DB 写入⑤：插入锁定记录（MySQL 唯一索引兜底）
        LocalDateTime lockUntil = now.plusMinutes(15);  // 锁定15分钟
        for (LockSeatsDTO.SeatPos seat : dto.getSeats()) {
            SeatLockPO lock = new SeatLockPO();
            lock.setScheduleId(scheduleId);
            lock.setRowNum(seat.getRow());
            lock.setColNum(seat.getCol());
            lock.setUserId(userId);
            lock.setLockUntil(lockUntil);
            lock.setStatus(1);  // 1=锁定中
            lock.setCreateTime(now);
            lock.setUpdateTime(now);
            seatLockMapper.insert(lock);
            // SQL: INSERT INTO seat_lock (schedule_id, row_num, col_num,
            //          user_id, lock_until, status, create_time, update_time)
            //          VALUES (123, 5, 8, 1001, '...', 1, now, now)
            //
            // 唯一索引兜底：(schedule_id, row_num, col_num, status)
            // 如果两个请求同时 insert 同一座位+同一状态，MySQL 报错 DuplicateKey
            // → 抛异常 → RocketMQ 重试 → 下一次可能拿到 Redis 锁继续处理
        }

        Map<String, Object> res = new HashMap<>();
        res.put("lockUntil", lockUntil.toString());   // 锁定截止时间
        res.put("seatCount", dto.getSeats().size()); // 座位数量
        res.put("price", schedule.getPrice());        // 单价
        return res;
    });

    // 如果 Redis 锁没拿到（等3秒超时）
    if (result == null) {
        throw BizException("系统繁忙，请重试");
    }
    return result;
}
```

### 4.7 锁座完成：结果回填

```java
// Consumer 端
bufferService.completeRequest(requestId, result);
```

```
bufferService.completeRequest 内部：
1. 从 pendingRequests 取出 future
2. future.complete(result) → Controller 的 future.get() 被唤醒
3. 收到结果的 Controller 线程继续执行
4. future.get() 返回 result
5. Controller 返回 Result.ok(result) 给前端

Decr seat:queue:depth（Redis）
```

### 4.8 座位布局实时查询

用户打开选座页面时，前端调用 `GET /api/seat/layout?scheduleId=123`：

```
SeatService.getSeatLayout(scheduleId, userId)

DB查询①：查影厅布局
CinemaHallPO hall = cinemaHallMapper.selectByCinemaAndHall(cinemaId, hallName);

DB查询②：查所有锁定中/已购买的座位
List<SeatLockPO> activeLocks = seatLockMapper.selectActiveLocks(scheduleId, now);
// SQL: SELECT * FROM seat_lock WHERE schedule_id=123 AND status IN(1,2) AND lock_until > now

DB查询③：查所有已购买座位
List<OrderSeatPO> purchasedSeats = orderSeatMapper.selectPurchasedSeats(scheduleId);

内存组装：根据 row/col 匹配，返回前端：
  status=-1: 不可用座位
  status=0:  可选座位
  status=1:  他人已锁定
  status=2:  他人已购买
  status=3:  当前用户已锁定（前端高亮显示）
```

---

## 五、完整流程二：下单 → 支付

### 5.1 用户确认下单

```
POST /api/order/create
Content-Type: application/json
{
  "scheduleId": 123,
  "seatCount": 2,
  "seatsInfo": "5排8座,5排9座"
}
```

### 5.2 第一层：限流

```java
@PostMapping("/create")
@RateLimit(key = "order:create", algorithm = TOKEN_BUCKET, capacity = 10, refillRate = 3)
```

**意图：** 单用户维度，最多突发 10 个下单请求，之后每秒最多下 3 单。

### 5.3 第二层：OrderBiz.createOrder（业务编排）

`OrderController` 调用 `OrderBiz.createOrder()`（业务编排层），后者依次调用各 Service。

#### Step 1：查询场次信息（优先 Redis）

```java
// 尝试从 Redis Hash 读取场次详情
SchedulePO schedule = stockService.getScheduleFromCache(scheduleId);
// Redis 操作：
// HGETALL schedule:detail:123
// → {movieId, cinemaId, hallName, showDate, showTime, endTime,
//     lang, totalSeats, price, status, version}
//
// 命中 → 直接返回 SchedulePO（包含 version）
// 未命中 → 查 DB → 回填 Redis

if (schedule == null) {
    // Redis 没有 → 查 DB
    schedule = scheduleMapper.selectById(scheduleId);
    // SQL: SELECT * FROM movie_schedule WHERE id=123 AND deleted=0
    // 回填 Redis
    stockService.initScheduleDetail(schedule);
    // Redis 操作：
    // HSET schedule:detail:123 movieId 1 cinemaId 2 ...
    // EXPIRE schedule:detail:123 43200  (12小时)
}
```

#### Step 2：Redis Lua 预扣库存（第一道防线）

```java
long remaining = stockService.preDeduct(scheduleId, seatCount);
// Redis 操作：Lua 脚本原子执行
//
// schedule:stock:123 = 50（假设）
//
// Lua 脚本：
// local stock = GET schedule:stock:123
// if stock >= 2 then
//     SET schedule:stock:123 stock - 2
//     return new_stock  -- 返回剩余库存
// else
//     return -1  -- 库存不足
// end
//
// remaining >= 0 → 预扣成功，继续
// remaining < 0  → 抛异常 STOCK_NOT_ENOUGH
```

**为什么要预扣？** 10 万人同时下单，如果直接查 DB，DB 会被打爆。Redis 预扣在内存层就拦掉了 99% 的无效请求。

#### Step 3：DB 乐观锁扣减（第二道防线）

```java
int affected = scheduleMapper.deductStock(scheduleId, seatCount, schedule.getVersion());
// SQL: UPDATE movie_schedule
//      SET available_seats = available_seats - 2,
//          version = version + 1,
//          update_time = NOW()
//      WHERE id = 123
//        AND version = 5          ← 乐观锁：只有版本匹配才扣
//        AND available_seats >= 2 ← 防超卖
//        AND deleted = 0
//
// affected = 1 → 扣减成功
// affected = 0 → 版本冲突或库存不足
```

**乐观锁原理：** `@Version` 注解自动递增。如果两个请求同时读到 `version=5`，只有第一个请求的 UPDATE 能成功（version 变成 6），第二个请求的 UPDATE 因为 `WHERE version=5` 匹配不到行而返回 0，触发回滚。

**affected=0 时回滚 Redis：**

```java
if (affected == 0) {
    stockService.rollback(scheduleId, seatCount);
    // Redis 操作：
    // INCRBY schedule:stock:123 2
    throw BizException(ORDER_CREATE_FAILED);
}
```

#### Step 4：创建订单

```java
String orderNo = generateOrderNo(userId);
// 格式：MO + yyyyMMddHHmmss + userId后4位 + 随机6位
// 例：MO202603231430451001A1B2C3

OrderPO order = new OrderPO();
order.setOrderNo(orderNo);
order.setUserId(userId);
order.setScheduleId(scheduleId);
order.setSeatCount(seatCount);
order.setSeatsInfo("5排8座,5排9座");
order.setUnitPrice(schedule.getPrice());
order.setTotalPrice(schedule.getPrice().multiply(BigDecimal.valueOf(seatCount)));
order.setStatus(0);              // 0=待支付
order.setExpireTime(now.plusMinutes(15));  // 15分钟支付超时
orderMapper.insert(order);
// SQL: INSERT INTO ticket_order (...)

// 注意：此时座位还没有从 seat_lock 标记为已购买
// 这是设计上的一点疏漏：seat_lock 的 status 还是 1（锁定中）
// 应该在创建订单时同步更新为 2（已购买）
```

#### Step 5：发 MQ 消息（异步通知）

```java
OrderEvent event = new OrderEvent();
event.setType(ORDER_CREATED);
event.setOrderNo(orderNo);
event.setUserId(userId);
event.setScheduleId(scheduleId);
event.setMovieId(schedule.getMovieId());
event.setSeatCount(seatCount);
event.setTotalPrice(order.getTotalPrice());
event.setTimestamp(System.currentTimeMillis());

rocketMQTemplate.syncSend("maoyan_order_event:ORDER_CREATED", event);
// Topic: maoyan_order_event
// Tag:   ORDER_CREATED
```

**OrderEventConsumer 收到 ORDER_CREATED 消息后：**

```java
case CREATED -> handleOrderCreated(event);
case PAID    -> handleOrderPaid(event);
case CANCELLED -> handleOrderCancelled(event);
case REFUNDED -> handleOrderRefunded(event);

private void handleOrderCreated(OrderEvent event) {
    cacheService.evict("hot_movies");
    // 清除多级缓存中的热映电影缓存
    // L1 Caffeine: cache.invalidate("hot_movies")
    // L2 Redis:   redisTemplate.delete("hot_movies")
    // 下次查询热映电影时，重新从 DB 加载（包含最新订单数）
}
```

---

### 5.4 定时任务：超 时订单自动取消

系统中有定时任务（`@Scheduled(fixedRate = 60000)` 每分钟），扫描 `expire_time < now AND status = 0` 的订单，自动取消并回滚库存。

### 5.5 用户支付

```
POST /api/payment/pay
{
  "orderNo": "MO202603231430451001A1B2C3"
}
```

#### Step 1：查询订单

```java
OrderPO order = orderMapper.selectOne(
    eq(orderNo, orderNo).eq(userId, userId));
// SQL: SELECT * FROM ticket_order WHERE order_no='MO...' AND user_id=1001
```

#### Step 2：检查超时

```java
if (now.isAfter(order.getExpireTime())) {
    // 超时 → 自动取消
    order.setStatus(CANCELLED);
    orderMapper.updateById(order);
    // 回滚库存：DB + Redis
    scheduleMapper.rollbackStock(scheduleId, seatCount);
    stockService.rollback(scheduleId, seatCount);
    // 释放座位锁定
    seatLockMapper.releaseUserLocks(scheduleId, userId, now);
    throw BizException(SEAT_LOCK_EXPIRED);
}
```

#### Step 3：积分扣减（模拟支付）

```java
int pointsCost = order.getTotalPrice().setScale(0, RoundingMode.UP).intValue();
// 例：总价 60.00 元 → 需要 60 积分

UserPO user = userMapper.selectById(userId);
// SQL: SELECT * FROM user WHERE id = 1001

if (user.getPoints() < pointsCost) {
    throw BizException("积分不足，需要60积分，当前30积分");
}

userMapper.deductPoints(userId, pointsCost);
// SQL: UPDATE user SET points = points - 60 WHERE id = 1001 AND points >= 60
// 乐观锁：WHERE 条件包含 points >= 60，防止负数
```

#### Step 4：更新订单状态

```java
order.setStatus(PAID);      // 1=已支付
order.setPayTime(now);
orderMapper.updateById(order);
// SQL: UPDATE ticket_order SET status=1, pay_time=NOW() WHERE id=?
```

#### Step 5：座位从"锁定"变为"已购买"

```java
seatLockMapper.markAsPurchased(scheduleId, userId, now);
// SQL: UPDATE seat_lock SET status=2 WHERE schedule_id=123 AND user_id=1001 AND status=1
// status: 1=锁定中 → 2=已购买
```

#### Step 6：发 MQ 通知

```java
rocketMQTemplate.syncSend("maoyan_order_event:ORDER_PAID", event);
// OrderEventConsumer 收到后：可扩展积分奖励、短信通知等
```

---

## 六、完整流程三：想看（实时计数）

### 6.1 用户点击"想看"

```
POST /api/wish/add?movieId=1
```

### 6.2 WishService.addWish 逐行分析

```java
public long addWish(Long userId, Long movieId) {
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // 第一步：Redis Set 去重（判断用户是否已想看）
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    String userWishKey = "user:wish:" + userId;
    Boolean added = redisTemplate.opsForSet().add(userWishKey, movieId) == 1;
    // Redis 操作：
    // SADD user:wish:1001 "1"
    // 返回 1 → 新增成功（用户之前没想看过这部电影）
    // 返回 0 → 已存在（重复点击，不计数）

    if (added == false) {
        // 用户已想看 → 直接返回当前计数，不重复累加
        return getWishCount(movieId);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // 第二步：Redis Hash 原子计数（实时展示用）
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    Long count = redisTemplate.opsForHash().increment(
        "movie:wish",        // Hash Key
        "1",                 // field = movieId
        1                    // 增量
    );
    // Redis 操作：
    // HINCRBY movie:wish "1" 1
    // 返回：这部电影当前的想看总数

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // 第三步：发 MQ 异步写 DB（保证持久化）
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    WishEvent event = new WishEvent(userId, movieId, 1, timestamp);
    rocketMQTemplate.syncSend("maoyan_wish_writeback:WISH_WRITEBACK", event);
    // Topic: maoyan_wish_writeback
    // Tag:   WISH_WRITEBACK

    return count;  // 返回实时计数给前端展示
}
```

### 6.3 Consumer 异步写 DB

`WishWriteBackConsumer.onMessage(event)`：

```java
// 第一步：写 user_wish 表（用户-电影关联）
try {
    userWishMapper.insert(wish);  // 唯一索引兜底
} catch (DuplicateKeyException e) {
    // 已存在就忽略（幂等）
}

// 第二步：更新 movie 表的 wish 字段
MoviePO movie = movieMapper.selectById(movieId);
movie.setWish(movie.getWish() + 1);
movieMapper.updateById(movie);
// SQL: UPDATE movie SET wish = wish + 1 WHERE id = ?
```

### 6.4 想看数查询

```java
public long getWishCount(Long movieId) {
    // 优先读 Redis（实时）
    Object val = redisTemplate.opsForHash().get("movie:wish", movieId.toString());
    if (val != null) return Long.parseLong(val.toString());

    // 降级：读 DB
    MoviePO movie = movieMapper.selectById(movieId);
    return movie != null ? movie.getWish() : 0;
}
```

### 6.5 想看数架构总结

```
用户点击"想看"
    ↓
SADD user:wish:1001 "1"      → 去重（Redis Set）
    ↓ 成功
HINCRBY movie:wish "1" 1     → 实时计数（Redis Hash，毫秒级）
    ↓ 同时
发 MQ 消息                  → 异步写 DB（保证不丢）
    ↓
Consumer 写 user_wish 表    → 持久化用户-电影关联
Consumer 写 movie.wish      → 持久化想看总数

前端展示 → 读 Redis Hash → 毫秒级响应
DB 数据  → Consumer 异步写入 → 最终一致性
```

**为什么这样做？**
- 直接写 DB：用户量一大，DB 被打爆，想看数响应慢
- 直接读 DB：每次查询都是一次 DB 访问
- Redis 计数 + MQ 异步写 DB：兼顾实时性和可靠性

---

## 七、完整流程四：座位超时释放（定时清理）

### 7.1 定时任务

`@Scheduled(fixedRate = 60000)` 每分钟执行一次：

```java
seatLockMapper.cleanExpiredLocks(now);
// SQL: UPDATE seat_lock SET status=0 WHERE status=1 AND lock_until <= now
// 将所有 lock_until 已过期 且 status=1 的记录，改为 status=0（已释放）
```

### 7.2 查询时过滤（被动清理）

`seatLockMapper.selectActiveLocks` 里带了 `lock_until > now` 条件：

```sql
SELECT * FROM seat_lock
WHERE schedule_id = ? AND status IN (1,2) AND lock_until > ?
```

即使定时任务没来得及清理，查询时也会自动过滤掉过期锁定。

---

## 八、定时任务汇总

| 定时任务 | 执行频率 | 作用 |
|---|---|---|
| 超时订单自动取消 | 每分钟 | 扫描待支付订单，超时则取消+回滚库存+释放座位 |
| seat_lock 超时清理 | 每分钟 | 批量将 `lock_until <= now` 的记录 status 改为 0 |
| 脏数据对账（`reconcileStock`） | 每5分钟 | 从 DB 拉真值强制覆盖 Redis，保证最终一致 |
| 每日排片日期刷新 | 每天0:05 | 排片日期整体平移到今天，保证演示数据不过期 |
| 每日库存预热 | 每天0:05 | 所有场次库存重新刷入 Redis |

---

## 九、整体数据流全景图

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                            用户视角                                          │
│   ① 打开选座页 → GET /api/seat/layout → 座位布局（含锁定/已售状态）             │
│   ② 点击座位 → POST /api/seat/lock → 返回锁定结果（含截止时间）                 │
│   ③ 确认下单 → POST /api/order/create → 返回订单号                             │
│   ④ 去支付 → POST /api/payment/pay → 积分扣减 → 订单完成                       │
│   ⑤ 想看 → POST /api/wish/add → 实时想看数                                   │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                         HTTP 请求层                                          │
│  ┌────────────────────────────────────────────────────────────────────────┐ │
│  │ @RateLimit 注解 → RateLimitAspect → RateLimiterService（Redis）           │ │
│  │                                                                        │ │
│  │ 令牌桶算法（抢座/下单）：容量5/10个，速率2/3每秒                           │ │
│  │ 滑动窗口算法（场次查询）：N个请求/60秒窗口                                 │ │
│  └────────────────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
        ┌─────────────────────────────┼─────────────────────────────┐
        │                             │                             │
        ▼                             ▼                             ▼
┌───────────────────┐    ┌───────────────────┐    ┌───────────────────┐
│   同步路径          │    │   MQ 异步路径      │    │   纯查询路径        │
│   (无MQ降级时)      │    │   (正常路径)       │    │   (SELECT)         │
│                    │    │                   │    │                   │
│ 直接调用            │    │ 发 MQ 消息        │    │ L1 Caffeine 查询   │
│ SeatService        │    │ 返回 requestId    │    │ → L2 Redis 查询    │
│ .lockSeats()       │    │                   │    │ → DB 回源         │
│ (含分布式锁+DB)     │    │ Controller 挂起    │    │                   │
│                    │    │ future.get(5s)   │    │                   │
└───────────────────┘    │                   │    └───────────────────┘
                         │ SeatLockConsumer  │
                         │ 处理消息          │
                         │                   │
                         │ 限流②：消费端令牌桶│
                         │ 10请求/秒          │
                         │                   │
                         │ Redisson 分布式锁  │
                         │ lock:seat:123     │
                         │ (场次级)          │
                         │                   │
                         │ DB 查+写 seat_lock │
                         │ (唯一索引兜底)     │
                         │                   │
                         │ completeRequest() │
                         │ future.complete() │
                         │ → Controller 唤醒  │
                         └───────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                              下单流程                                         │
│  ┌────────────────────────────────────────────────────────────────────────┐ │
│  │ Redis Lua 预扣库存（第一道防线，毫秒级）                                   │ │
│  │   HGET schedule:stock:123 → 判断 → DECR → 返回剩余库存                    │ │
│  └────────────────────────────────────────────────────────────────────────┘ │
│                                    ↓                                         │
│  ┌────────────────────────────────────────────────────────────────────────┐ │
│  │ DB 乐观锁扣减（第二道防线，最终一致性）                                     │ │
│  │   UPDATE movie_schedule SET available_seats-=N, version+=1             │ │
│  │   WHERE version=? AND available_seats>=N                               │ │
│  └────────────────────────────────────────────────────────────────────────┘ │
│                                    ↓                                         │
│  ┌────────────────────────────────────────────────────────────────────────┐ │
│  │ 插入 ticket_order（status=0 待支付）                                    │ │
│  │ 插入 order_seat（座位购买记录）                                         │ │
│  └────────────────────────────────────────────────────────────────────────┘ │
│                                    ↓                                         │
│  ┌────────────────────────────────────────────────────────────────────────┐ │
│  │ 发 MQ：OrderEvent(ORDER_CREATED) → 清除热映电影缓存                      │ │
│  └────────────────────────────────────────────────────────────────────────┘ │
│                                    ↓                                         │
│  ┌────────────────────────────────────────────────────────────────────────┐ │
│  │ 用户在 15 分钟内支付                                                     │ │
│  │ 积分扣减 → 更新 status=PAID → 更新 seat_lock status=2（已购买）          │ │
│  │ 支付超时 → 定时任务取消 → 回滚库存（Redis+DB）→ 释放 seat_lock            │ │
│  └────────────────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 十、三层缓存架构

```
请求 → L1 Caffeine（进程内，JVM 级别）
         ↓ 未命中
      L2 Redis（跨进程，网络级别）
         ↓ 未命中
      DB（最终数据源）
         ↓ 命中
      回填 L2 Redis（TTL 10分钟 + 随机偏移防雪崩）
      回填 L1 Caffeine（TTL 60秒）

缓存穿透防护：DB 返回 null → 仍写入 L2（短 TTL）→ 避免大量请求穿透到 DB
缓存击穿防护：Caffeine.get() 内部 singleflight → 同 key 只一个线程回源
缓存雪崩防护：TTL 加 0~3 分钟随机偏移 → 避免大批 key 同时过期

降级策略：Redis 挂了 → 只用 L1 Caffeine → L1 也挂了 → 直连 DB
```

---

## 十一、防超卖三层保障总结

```
┌────────────────────────────────────────────────────────────────────────────┐
│  第一层：Redis Lua 预扣库存（毫秒级，拦住 99% 的无效请求）                     │
│         schedule:stock:{scheduleId}                                          │
│         只有 Redis 预扣成功，才会继续往下走                                    │
│                                                                            │
│  第二层：DB 乐观锁扣减（最终一致性保障）                                       │
│         UPDATE ... WHERE version=? AND available_seats>=?                   │
│         MyBatis-Plus @Version 自动递增版本号                                  │
│         两个请求同时到达，version 冲突，只有1个成功                            │
│                                                                            │
│  第三层：MQ 异步通知 + 定时任务对账                                          │
│         每5分钟 reconcileStock：从 DB 拉真值强制覆盖 Redis                    │
│         保证 Redis 和 DB 最终一致                                            │
└────────────────────────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────────────────────────┐
│  座位锁定三层保障：                                                           │
│                                                                            │
│  第一层：Redisson 分布式锁（场次级，串行化查+写）                              │
│         lock:seat:{scheduleId}                                              │
│         同一场次所有锁座请求排队，Redis SETNX 保证互斥                        │
│                                                                            │
│  第二层：MySQL 唯一索引（并发兜底）                                          │
│         UNIQUE INDEX (schedule_id, row_num, col_num, status)                 │
│         即使 Redis 锁失效，并发 insert 同一座位也会被唯一索引拦住              │
│                                                                            │
│  第三层：TTL 自动过期 + 定时清理                                             │
│         lock_until = now + 15分钟                                           │
│         超时自动释放座位；定时任务每分钟清理过期记录                          │
└────────────────────────────────────────────────────────────────────────────┘
```

---

## 十二、各组件在流程中的精确位置

```
整个请求生命周期中，数据的走向：

1. SeatController.lockSeats()
   → RateLimitAspect.isAllowedTokenBucket()   [Redis 滑动窗口/令牌桶]
   → SeatRequestBufferService.submitLockRequest()
       → pendingRequests.put(requestId, future)  [JVM 内存]
       → rocketMQTemplate.syncSend()            [RocketMQ]

2. SeatLockConsumer.onMessage()
   → RateLimiterService.isAllowedTokenBucket() [Redis 令牌桶，消费端限速]
   → SeatService.lockSeats()
       → DistributedLockService.executeWithLock() [Redis Redisson 分布式锁]
           → ScheduleMapper.selectById()        [MySQL]
           → SeatLockMapper.releaseUserLocks()  [MySQL UPDATE]
           → SeatLockMapper.selectActiveLock()   [MySQL SELECT × seatCount]
           → OrderSeatMapper.selectPurchasedSeats() [MySQL SELECT]
           → SeatLockMapper.insert()              [MySQL INSERT × seatCount]
       → bufferService.completeRequest()
           → future.complete(result)            [JVM 内存]
           → INCR seat:queue:depth               [Redis]

3. OrderController.createOrder()
   → RateLimitAspect.isAllowedTokenBucket()    [Redis 令牌桶]
   → OrderBiz.createOrder()
       → StockService.getScheduleFromCache()    [Redis Hash]
           → ScheduleMapper.selectById()        [MySQL]
           → StockService.initScheduleDetail()   [Redis Hash HSET]
       → StockService.preDeduct()               [Redis Lua 原子预扣]
       → ScheduleMapper.deductStock()          [MySQL UPDATE 乐观锁]
       → OrderMapper.insert()                   [MySQL INSERT]
       → rocketMQTemplate.syncSend(ORDER_CREATED) [RocketMQ]

4. OrderEventConsumer.onMessage(ORDER_CREATED)
   → MultiLevelCacheService.evict("hot_movies")
       → Caffeine.invalidate()                  [JVM 内存]
       → redisTemplate.delete()                 [Redis]

5. PaymentController.payOrder()
   → OrderMapper.selectOne()                   [MySQL]
   → UserMapper.deductPoints()                 [MySQL UPDATE 乐观锁]
   → OrderMapper.updateById()                  [MySQL UPDATE]
   → SeatLockMapper.markAsPurchased()          [MySQL UPDATE]
   → RocketMQTemplate.syncSend(ORDER_PAID)     [RocketMQ]

6. WishService.addWish()
   → redisTemplate.opsForSet().add()          [Redis Set 去重]
   → redisTemplate.opsForHash().increment()    [Redis Hash 计数]
   → rocketMQTemplate.syncSend(WISH_WRITEBACK) [RocketMQ]

7. WishWriteBackConsumer.onMessage()
   → UserWishMapper.insert()                   [MySQL INSERT]
   → MovieMapper.updateById()                  [MySQL UPDATE]
```
