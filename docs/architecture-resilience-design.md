# 仿猫眼电影系统 — 架构完整评审与容灾设计方案

> **阅读说明**：本文档基于对项目现有源码的逐文件审阅，以及用户提出的新架构方案进行对比评估。批判性视角贯穿全文——指出设计亮点的同时，也精确点出生产级落地时的坑与补丁。

---

## 目录

1. [新旧架构核心差异](#1-新旧架构核心差异对照)
2. [流程一：查询场次与座位图](#2-流程一查询场次与座位图)
3. [流程二：锁座（Redis Lua 核心链路）](#3-流程二锁座redis-lua-核心链路)
4. [流程三：创建订单与支付闭环](#4-流程三创建订单与支付闭环)
5. [流程四：订单超时取消与座位释放](#5-流程四订单超时取消与座位释放)
6. [中间件宕机降级方案](#6-中间件宕机降级方案)
7. [架构批判：六个必须正视的风险点](#7-架构批判六个必须正视的风险点)
8. [完整全景链路图](#8-完整全景链路图)
9. [P0 级行动清单](#9-p0-级行动清单)

---

## 1. 新旧架构核心差异对照

在正式评审新方案之前，先精确点出新架构与**现有代码**的根本差异，避免评审产生歧义。

| 维度 | 现有代码实现 | 新架构方案 |
|------|------------|-----------|
| **座位锁定方式** | Redisson 分布式锁 + 同步写 `seat_lock` 表 | Redis Lua 原子脚本操作 Hash，异步 MQ 落盘 |
| **座位状态存储** | `seat_lock` 表（DB 为主）+ `schedule:stock` 计数器 | `seat:status:{scheduleId}` Hash（Redis 为主）+ `seat:count` 计数器 |
| **lockToken 类型** | UUID 随机字符串 | JWT（含 userId/scheduleId/seats 签名） |
| **锁座返回时机** | 同步等待 DB 写入后才返回 | Lua 成功后立即返回，MQ 异步落盘 |
| **前端交互** | 锁座即返回完整结果 | 锁座返回 JWT → 前端轮询等订单生成 |
| **订单创建时机** | 锁座后用户手动调创建订单接口 | MQ 消费者自动创建订单 |
| **超时释放机制** | Spring `@Scheduled` 定时扫描 | RocketMQ 延时消息（主动）+ 支付懒过期（被动）+ 凌晨对账（兜底）|
| **seat_lock vs order_seat** | 两张表分别存锁座记录和已售记录 | 只用 `seat_lock` 一张表，status 演进：锁定→已售 |

**结论**：新方案是一次较彻底的推倒重建，核心思路从"同步串行保障一致性"转向"Redis 为状态主存 + 最终一致性兜底"。方向正确，但有若干生产级细节必须补齐。

---

## 2. 流程一：查询场次与座位图

### 2.1 场次数据分层架构

场次数据按变化频率分为两层，这是正确的设计决策：

**静态数据层（L1 Caffeine + L2 Redis 多级缓存）**

| 数据类型 | 缓存 Key 示例 | TTL 策略 |
|---------|-------------|---------|
| 电影基础信息（名称/导演/演员/海报） | `movie:detail:{movieId}` | L1 60s，L2 10min + 随机偏移 |
| 影院/城市信息 | `cities`，`cinema:list:{cityId}` | L1 1h，L2 24h |
| 场次元数据（时间/影厅/版本/价格） | `schedule:list:{movieId}:{showDate}` | L1 60s，L2 10min |

**动态数据层（Redis 实时读取，不走多级缓存）**

| 数据类型 | 存储结构 | 说明 |
|---------|--------|------|
| 场次余票计数器 | `seat:count:{scheduleId}` (String) | 排片时初始化，Lua 脚本原子扣减 |
| 座位实时状态 | `seat:status:{scheduleId}` (Hash) | Field: `row_col`，Value: `0/1:userId/2:userId` |

**库存装配阶段（返回场次列表前的关键步骤）**

```
从 L1/L2 拿场次基础信息（含"最后已知剩余座位数"）
           ↓
从 Redis 读 seat:count:{scheduleId} 实时计数器覆盖库存字段
           ↓
    Redis 正常 → 使用实时值（精确）
    Redis 宕机 → 降级使用缓存里的最后已知值（允许短暂不一致）
           ↓
返回 ScheduleVO 给前端
```

**缓存一致性策略**

| 触发场景 | 处理方式 |
|---------|---------|
| TTL 自然过期 | L1 60s，L2 10min，到期自动回源 |
| 排片变更（影院/时间变化） | 主动按前缀清除 L1 + L2 |
| 普通下单（仅影响库存） | **不需要清场次列表缓存**，返回 VO 时实时覆盖 `seat:count` 字段 |
| Redis 库存回滚失败 | 写入 `stock:dirty:rollback`，定时对账任务从 DB 拉真值重建 Redis |

### 2.2 查询座位图

**Redis 数据结构**

```
seat:status:{scheduleId}   ← Hash
  Field: "5_6"             ← row_col 格式
  Value: "0"               ← 可选
  Value: "1:1001"          ← 用户 1001 已锁定（锁定中，未支付）
  Value: "2:1001"          ← 用户 1001 已购买（已售出）

seat:count:{scheduleId}    ← String（整型计数器）
  Value: "238"             ← 当前可售余票数
```

**座位图组装逻辑（后端内存完成）**

```
1. 从 Redis 读静态底图模板（hall:layout:{hallId}，后台排片时预热，冷数据永不过期）
2. 一次 HGETALL seat:status:{scheduleId} 获取所有非空闲座位状态
3. 内存组装，识别 currentUserId：
   - Value 不存在 → status = 0（可选，绿色）
   - Value = "1:X" 且 X == currentUserId → status = 3（我锁定，黄色）
   - Value = "1:X" 且 X != currentUserId → status = 2（他人锁定，灰色）
   - Value = "2:X" → status = 1（已售，红色）
   - 不可用座位（底图中标注）→ status = -1
4. 返回二维矩阵 VO
```

> **批判点**：当前代码 `SeatService.getSeatLayout()` 查询的是 `seatLockMapper` 和 `orderSeatMapper`（DB 查询），不是 Redis Hash，与新架构完全不同。新架构中，座位图核心读链路完全依赖 Redis，需要确保 Redis 宕机时有合理降级（见第 6 节）。

---

## 3. 流程二：锁座（Redis Lua 核心链路）

### 3.1 为什么选 Redis Lua，而不是 SETNX 或 Redisson

**SETNX 方案的致命缺陷（连座场景）**

```
买 3 个连座：
SETNX seat:5_1 → 成功（第 1 次网络 I/O）
SETNX seat:5_2 → 成功（第 2 次网络 I/O）
SETNX seat:5_3 → 失败（被抢，第 3 次网络 I/O）
→ 必须 DEL seat:5_1 和 seat:5_2 回滚
→ 如果回滚前服务宕机 → "死座"，直到 TTL 过期
```

**Redisson 方案的性能缺陷**

```
获取场次锁（第 1 次网络 I/O）
  → 查 3 个座位状态（第 2 次网络 I/O）
  → 写 3 个座位状态（第 3 次网络 I/O）
  → 释放场次锁（第 4 次网络 I/O）
= 4 次网络往返，且持锁期间全场次串行化，线程池崩溃风险极高
```

**Redis Lua 的优势**

```
一次网络调用 + Redis 单线程原子执行：
1. HGET 遍历目标座位，全部为 0 才继续（否则直接 return 0）
2. 批量 HSET 标记 1:userId
3. DECRBY seat:count:{scheduleId} 扣减计数器
4. EXPIRE 刷新整个 Hash 的 TTL
return 1
```

原子性由 Redis 单线程保证，无需分布式锁，零 partial failure 风险。

### 3.2 Lua 脚本设计（精确规范）

```lua
-- KEYS[1] = seat:status:{scheduleId}  (Hash)
-- KEYS[2] = seat:count:{scheduleId}   (String 计数器)
-- ARGV[1] = userId
-- ARGV[2] = TTL 秒数 (15分钟 = 900)
-- ARGV[3...N] = 座位号列表 "5_6", "5_7", ...

local hash_key    = KEYS[1]
local count_key   = KEYS[2]
local user_id     = ARGV[1]
local ttl         = tonumber(ARGV[2])
local seat_count  = #ARGV - 2

-- 【第一阶段】预检：全部座位必须空闲
for i = 3, #ARGV do
    local val = redis.call('HGET', hash_key, ARGV[i])
    if val and val ~= '0' then
        return 0  -- 有座位被占，原子拒绝，无需回滚
    end
end

-- 【第二阶段】提交：原子批量写入
for i = 3, #ARGV do
    redis.call('HSET', hash_key, ARGV[i], '1:' .. user_id)
end

-- 【第三阶段】扣减计数器
redis.call('DECRBY', count_key, seat_count)

-- 刷新 Hash TTL（注意：不能依赖 EXPIRE 作为唯一的释放机制）
redis.call('EXPIRE', hash_key, ttl)

return 1
```

> **批判点 1**：`EXPIRE` 只能给整个 Hash 设置 TTL，而不是 Hash 里的某个 Field。这意味着如果同一场次的不同用户分别锁座，一个人的 15 分钟到期会导致整个 Hash 被删除，其他人的锁定状态也消失。**正确做法**：不依赖 Hash 的 TTL 过期来释放座位。TTL 只作为兜底保险，真正的释放靠 MQ 延时消息或主动调用释放脚本。Hash 的 TTL 应设为整个场次结束时间 + 若干缓冲（而非 15 分钟）。

> **批判点 2**：`seat:count` 计数器允许被扣为负数（如果多个请求并发扣减，而 HGET 检查尚未执行）。但由于 Lua 的原子性，实际上不会出现这种情况。不过，计数器减到 0 以下时应该做边界保护：`redis.call('SET', count_key, math.max(0, remaining))`。

### 3.3 锁座接口完整链路

```
POST /api/seat/lock
  {scheduleId, seats: [{row, col}, ...]}

第一层：@RateLimit AOP（令牌桶）
  key = "seat:lock:user:" + userId
  capacity = 5，refillRate = 2/s
  → 防恶意刷票、连续点击、脚本重复提交

第二层：Redis Lua 原子锁座
  → 成功：内存组装 JWT lockToken，同步返回前端（≤ 5ms）
  → 失败：返回"座位已被锁定"或"场次不可售"

注：seat:count 计数器不作为锁座链路的前置拦截门。
用户已选定具体座位，"计数器 > 0"并不能保证目标座位可用，
Lua 脚本直接校验目标 Field 即可，多一次 GET 只是增加 RTT。
seat:count 仅用于场次列表页的余票展示。

返回成功的同时（异步，不阻塞响应）：
  发送 MQ 消息 A（立即消息）：SEAT_LOCK_EVENT，用于 DB 落盘
  发送 MQ 消息 B（延迟消息）：SEAT_TIMEOUT_EVENT，15分钟后触发
```

### 3.4 JWT lockToken 设计

```json
// Payload（HMAC-SHA256 签名）
{
  "userId": 1001,
  "scheduleId": 101,
  "seats": ["5_6", "5_7"],
  "lockTime": 1718000000,
  "exp": 1718000900   // 15分钟后过期
}
```

**安全性保证**：
- 黑客拿到 lockToken 但无法修改（签名校验失败）
- 黑客用自己账号携带别人的 lockToken（`JWT.userId != request.userId`，后端拒绝）
- Token 内容可见（base64 编码，非加密），但不含敏感信息，可接受

### 3.5 MQ 顺序消息发送策略

```
MessageQueueSelector 的 Hash Key = scheduleId
```

**为什么是 scheduleId 而不是 orderNo？**

在锁座阶段，orderNo 尚未生成（由 MQ 消费者创建）。以 scheduleId 为 Key，保证同一场次的"锁座"和"超时释放"消息落在同一 MessageQueue，保障顺序执行，避免"已释放的座位又被重新标记为锁定"。

---

## 4. 流程三：创建订单与支付闭环

### 4.1 前端轮询机制

```
用户点击"确认选座"后：
  Lua 返回成功（≤ 5ms）
  → 后端返回 {lockToken: "eyJ..."}
  → 前端弹出"正在为您排队生成订单..."遮罩层

前端每隔 1.5s 轮询：
  GET /api/order/status?lockToken=<JWT>
  
后端处理：
  解析 JWT → 获取 scheduleId + userId + seats
  查询 seat_lock 表（按 scheduleId + userId + status=1 定位）
  → 未找到 → 返回 {status: "PROCESSING"}，前端继续转圈
  → 找到 → 返回 {status: "SUCCESS", orderNo: "MO2024..."}

前端拿到 orderNo：
  关闭遮罩，跳转支付页
  支付倒计时从 order.createTime 开始（而非锁座时间）
  → 确保用户即使转了 3 分钟圈圈，进支付页仍有完整 15 分钟
```

> **批判点**：轮询接口不应直接用 JWT 作为查询参数（JWT 较长，建议解析后用 `userId + scheduleId` 查询，或在锁座时在 Redis 缓存一个轻量的 `seat:request:{requestId} → orderNo` 映射，轮询用 requestId）。另外，轮询超时（如 30s 内都是 PROCESSING）需要前端给用户明确提示，而不是无限转圈。

### 4.2 MQ 消费端：创建订单并落盘

```
消费 SEAT_LOCK_EVENT 消息：
  解析 userId、scheduleId、seats、lockToken

开启本地事务：
  INSERT seat_lock (schedule_id, seat_id, user_id, lock_token, status=1, lock_until, ...)
    → 唯一索引 (schedule_id, seat_id) 兜底幂等，防重复消费
  INSERT order (order_no, user_id, schedule_id, lock_token, status=WAIT_PAY, expire_time, ...)
  
事务 Commit 后：
  给 RocketMQ 返回 CONSUME_SUCCESS（手动 ACK）
```

消费端**只有在事务 Commit 后才 ACK**，确保消息不会因消费者宕机而丢失。

### 4.3 支付闭环（强一致性本地事务）

```
POST /api/payment/pay
  {orderNo}

第一步：幂等校验
  订单属于当前 userId？
  订单状态 == WAIT_PAY？
  当前时间 < order.expire_time？
  → 任一不满足：拒绝支付（懒过期处理见第 5.2 节）

第二步：本地事务（三个原子操作）
  操作 A：CAS 扣积分
    UPDATE user_account
    SET points = points - {amount}
    WHERE user_id = ? AND points >= {amount}
    → affected == 0 → 积分不足，事务回滚

  操作 B：状态机推进
    UPDATE orders
    SET status = 'PAID', pay_time = NOW()
    WHERE order_no = ? AND status = 'WAIT_PAY'
    → affected == 0 → 并发重复支付或订单已过期，事务回滚

  操作 C：座位就地固化（无需 order_seat 表）
    UPDATE seat_lock
    SET status = 2   -- 2 = 已真实售出
    WHERE order_no = ?

事务 Commit 后：
  异步 MQ 广播 ORDER_PAID 事件
  下游消费：
    → Redis Hash 座位状态从 "1:userId" 改为 "2:userId"（缓存固化）
    → 生成电子票/二维码
    → 发短信通知、积分奖励等
```

> **批判点**：操作 C 依赖 `seat_lock` 表中存在记录（由消费 SEAT_LOCK_EVENT 的 MQ 消费者写入）。如果出现以下极端情况：**MQ 消费延迟 → 支付发生在 seat_lock 落库之前**，操作 C 的 UPDATE 会 affected = 0。虽然不会造成资金问题（操作 A、B 已成功），但座位状态无法正确固化。**建议**：将 MQ 落盘消费者与创建订单合并，确保 seat_lock 和 order 在同一事务中写入，支付前检查订单必然意味着 seat_lock 已存在。

---

## 5. 流程四：订单超时取消与座位释放

### 5.1 三重兜底机制

```
                    锁座成功
                       │
           ┌───────────┴──────────┐
           │ 第一重               │ 第二重
    RocketMQ 延时消息(15min)   支付接口懒过期
    到期消费者唤醒，           校验 expire_time
    检查订单状态               过期则关单回滚
           │                      │
           └───────────┬──────────┘
                    第三重
              每日凌晨离线巡检
             扫描 Redis 孤儿锁定
             对照 DB 强制纠错
```

### 5.2 第一重：RocketMQ 延时消息

> **批判点（重要）**：RocketMQ **开源版**不支持任意精度延时，只有 18 个固定延时级别：1s/5s/10s/30s/1m/2m/3m/4m/5m/6m/7m/8m/9m/10m/20m/30m/1h/2h。**没有 15 分钟这个精确级别**，最接近的是 level 14（10 分钟）或 level 15（20 分钟）。
>
> **解决方案（三选一）**：
> - 使用 RocketMQ 5.x（支持任意时间精度延时消息，推荐）
> - 使用 RocketMQ 商业版（阿里云 ARMS）
> - 降级到 level 15（20 分钟），结合支付接口的被动懒过期补偿精度

消费者收到延时消息后执行：

```
1. 防误杀：查 DB，order.status == PAID？→ 丢弃消息，ACK
2. 开启本地事务：
   UPDATE orders SET status = 'CLOSED'
   WHERE order_no = ? AND status = 'WAIT_PAY'   ← 乐观锁状态机
   
   UPDATE seat_lock SET status = 3              ← 3 = 已作废
   WHERE order_no = ?
   
3. 事务 Commit 后，调用 Redis Lua 释放脚本：
   对 seat:status:{scheduleId} 中对应座位 HDEL 或 HSET 为 0
   对 seat:count:{scheduleId} 执行 INCRBY seatCount
   
4. 广播 ORDER_CANCELLED 事件（前端 WebSocket 刷新座位图）
5. 如果 Redis 操作失败 → 返回 RECONSUME_LATER（MQ 重试，不要自己写脏数据表）
```

### 5.3 第二重：支付接口被动懒过期

```java
// PaymentService.java 中，支付校验阶段
if (LocalDateTime.now().isAfter(order.getExpireTime())) {
    // 顺手触发关单，无需等 MQ 延时消息
    closeOrderAndReleaseSeats(order);
    throw new BizException("订单已超时，座位已释放，请重新选座");
}
```

### 5.4 第三重：离线巡检 Job

```
每日凌晨 2 点执行（低峰期）：
  SCAN Redis，找所有 seat:status:{scheduleId} Hash
  HGETALL → 找出 Value 以 "1:" 开头（锁定中）的座位
  计算该 Field 的写入时间是否超过 20 分钟（超过则可疑）
  拿 scheduleId + userId 去 DB 查 seat_lock / order 表
  → DB 无有效记录 → 强制 HSET 归零 + INCR count_key
  → 记录修复日志
```

> **批判点**：Hash Field 本身没有写入时间戳。获取"Field 写入时间"需要在 Lua 锁座时额外写一个时间戳 Key，或者在 Hash Value 中编码时间戳（如 `1:1001:1718000000`）。**建议**：Value 格式改为 `{status}:{userId}:{lockTime}`，巡检时直接解析 lockTime。

---

## 6. 中间件宕机降级方案

### 6.1 RocketMQ 宕机：同步降级落库

**为什么这个降级在新架构下依然安全**

当代码执行到"准备发送 MQ 消息"时，说明前置的两层已经完成：
1. 令牌桶 AOP 过滤了绝大部分无效请求
2. Redis Lua 原子锁座成功（有效并发 ≤ 剩余座位数，如一个影厅 ≤ 几百）

对于 MySQL 来说，瞬间处理几百个带唯一索引的 INSERT，完全在能力范围之内。

```java
// SeatService 或 MQ 发送封装层
public void sendSeatLockEvent(SeatLockEvent event) {
    try {
        rocketMQTemplate.syncSend(SEAT_LOCK_TOPIC + ":LOCK", event, 2000);
        rocketMQTemplate.syncSend(SEAT_TIMEOUT_TOPIC + ":TIMEOUT", delayEvent, 2000);
    } catch (Exception e) {
        log.warn("[MQ] RocketMQ unavailable, falling back to sync DB write: {}", e.getMessage());
        // 降级：直接同步写 seat_lock 和 order 表
        // 安全前提：Redis Lua 已完成削峰，并发量可控
        seatLockFallbackService.syncPersist(event);
        // 延时消息降级为 @Scheduled 定时扫描（不如 MQ 精准，但不会丢）
    }
}
```

**用户体验对比**：

| 状态 | 锁座耗时 | 用户体验 |
|------|---------|---------|
| MQ 正常 | ~5ms（Lua 成功立即返回） | 极速，轮询短暂等待 |
| MQ 宕机（降级） | ~50ms（同步写 DB） | 等待稍长，但无感知 |

### 6.2 Redis 宕机：分层处理

**读链路（对新架构影响较大）**

| 读操作 | Redis 正常 | Redis 宕机 |
|-------|-----------|-----------|
| 场次列表 | L1/L2 缓存 | L1 Caffeine 60s 兜底，到期后 DB 回源 |
| 余票计数 | `seat:count` 实时值 | 降级显示 DB 中的 `available_seats` 字段 |
| 座位图 | Hash 毫秒级返回 | **无法获取实时状态**，返回"场次火爆，请稍后查看" |

> 座位图在 Redis 宕机时无法提供实时状态（数据主存在 Redis），这是新架构选择"Redis 为主"带来的代价。对用户展示一个降级提示页，比展示一个错误的座位状态要好。

**写链路（购票写路径）**

| 组件 | Redis 正常 | Redis 宕机策略 |
|------|-----------|--------------|
| 令牌桶限流 | Lua 精确限流 | fail-open（放行）+ 本地 `AtomicLong` 降级限流（精度降低但不失守）|
| Redis Lua 锁座（核心） | 正常 | **直接触发 Sentinel 熔断，拒绝所有购票写请求** |

**为什么 Redis 宕机时必须熔断购票写路径，而不是降级到 DB？**

```
Redis 整体宕机 → Lua 无法执行 → 无法原子锁座
→ 降级到 DB 直接写 → 并发 N 个请求同时 INSERT seat_lock
→ DB 唯一索引仍能拦截超卖（数据正确）
→ 但 N 个并发写不经过任何序列化直接打 DB
→ 同一场次热门座位高并发异常率飙升 + DB 连接池压力剧增

正确结论：Redis 宕机 = 防洪堤决堤，唯一正确选择是熔断购票写路径
宁可牺牲几分钟交易量，保住 DB 不被击穿
```

### 6.3 Sentinel 熔断规则配置

```java
@PostConstruct
public void initSentinelRules() {
    // 针对 Redis Lua 锁座的熔断规则（RT > 200ms 或异常比例 > 50%）
    DegradeRule seatLuaRule = new DegradeRule("seat:lock:redis")
        .setGrade(RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO)
        .setCount(0.5)       // 异常比例 50% 触发熔断
        .setTimeWindow(30)   // 熔断 30 秒
        .setMinRequestAmount(10);

    // 针对 RocketMQ 发送的熔断规则
    DegradeRule mqSendRule = new DegradeRule("seat:lock:mq")
        .setGrade(RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO)
        .setCount(0.5)
        .setTimeWindow(15);

    DegradeRuleManager.loadRules(List.of(seatLuaRule, mqSendRule));
}
```

---

## 7. 架构批判：六个必须正视的风险点

### 风险 1：Redis Hash TTL 的语义陷阱（高优先级）

**问题**：Lua 脚本对整个 `seat:status:{scheduleId}` Hash 执行 `EXPIRE 900`（15 分钟）。这意味着：

```
场景：100 人陆续锁座，每次锁座都刷新 Hash TTL
用户 A 在 t=0 锁座 → Hash TTL = 15min
用户 B 在 t=14min 锁座 → Hash TTL 被刷新为 15min（从 t=14min 起算）
用户 A 的 15min（t=15min）到了，RocketMQ 延时消息触发
但 Hash 还没过期，延时消息里的 Lua 回滚把 A 的座位正确归零 ✓

但如果 Redis 在 t=14min30s 宕机，主从切换后 TTL 丢失：
Hash 永不过期 → 用户 B 的座位永远显示锁定 → 死座
```

**修复方案**：Hash 的 TTL 应设为整个场次（演出结束时间 + 1h），而不是单个用户的锁定时间。单个用户座位的释放完全由 MQ 延时消息和支付懒过期负责，不依赖 Hash TTL。

### 风险 2：轮询接口暴露 JWT 至 GET 参数（中优先级）

**问题**：`GET /api/order/status?lockToken=eyJ...`，JWT 出现在 URL 中，会被：
- Nginx access log 记录
- 浏览器历史缓存
- CDN/Proxy 缓存

**修复方案**：轮询接口改为 POST，或在锁座时生成一个短的 `requestId`（UUID），用 requestId 轮询。JWT 本身通过 Authorization Header 传递。

### 风险 3：MQ 宕机时延时消息消失（高优先级）

**问题**：锁座时同时发送立即消息和延时消息。如果 MQ 宕机导致延时消息发送失败，降级路径只处理了立即消息（同步写 DB），但**延时消息（超时回调）永远丢失**。

**后果**：MQ 恢复后，15 分钟的超时回调不会触发 → 用户超时不支付 → 座位无法通过第一重机制释放 → 依赖 MQ 恢复后手动补偿，或等待每日凌晨巡检。

**修复方案**：在降级路径中，补充发布一个定时扫描任务的创建（记录到 DB 的 `order_timeout_task` 表），作为对 MQ 延时消息的替代。巡检任务每 1 分钟扫描一次此表即可（数量可控，只有 MQ 宕机期间的订单才会进此表）。

### 风险 4：座位计数器与 Hash 状态的最终一致性

**问题**：`seat:count` 和 `seat:status` Hash 是两个独立的 Key。虽然 Lua 脚本在锁座时原子地同时更新两者，但在**释放**场景下：

```
延时消息消费者执行释放：
  修改 seat:status Hash → 成功
  INCRBY seat:count → 此时 Redis 抖动 → 失败
→ 座位状态已归零（可选），但计数器没有加回来
→ 计数器偏低 → 后续快速检查可能误判"无票"而拒绝用户
```

**修复方案**：将"释放 Hash 状态 + 递增计数器"也封装在一个 Lua 脚本中，保证原子性。如果 Lua 执行失败，返回 `RECONSUME_LATER`，由 MQ 重试。

### 风险 5：前端轮询的最大等待时间未界定

**问题**：如果 MQ 严重积压（非宕机，只是处理慢），消费者可能 1 分钟后才落库。前端轮询策略文档中未界定最大轮询时长和降级提示。

**建议**：
- 最大轮询时长：30 秒
- 超时后提示："系统繁忙，您的选座请求已记录，稍后可在'我的订单'查看"
- 同时在 Redis 缓存一个 `seat:request:{requestId} → PENDING/SUCCESS/FAIL` 状态，即使 JWT 解析失败也能通过 requestId 查询

### 风险 6：`seat_lock` 表成为超售最后防线，索引设计关键

**问题**：在新架构中，`seat_lock` 表的唯一索引是防止 MQ 重复消费导致重复落盘的最后一道线。索引设计必须精确：

```sql
-- 正确设计：场次 + 座位位置的唯一约束
CREATE UNIQUE INDEX uk_schedule_seat
ON seat_lock (schedule_id, row_num, col_num)
WHERE status IN (1, 2);  -- 仅对有效记录施加唯一约束（MySQL 5.7+部分索引不支持，可用函数索引或额外字段）

-- 更稳妥做法（兼容 MySQL 5.7）：
-- 加 status 到唯一索引，但需要保证 status=3（已作废）的记录不阻塞新的锁座插入
```

---

## 8. 完整全景链路图

```
用户进入选座页
      │
      ▼
┌─────────────────────────────────────────────────────────────────────┐
│ 查询座位图（纯 Redis 读链路）                                         │
│  HGETALL seat:status:{scheduleId}                                    │
│  后端内存组装二维矩阵（识别 currentUserId 标记黄色座位）               │
│  Redis 宕机 → 返回维护提示（不显示错误状态）                           │
└─────────────────────────────────┬───────────────────────────────────┘
                                  │
用户点击座位，点击"确认选座"
                                  │
                                  ▼
┌─────────────────────────────────────────────────────────────────────┐
│ 第一层：@RateLimit 令牌桶（AOP）                                      │
│  key = "seat:lock:user:{userId}"，capacity=5，rate=2/s              │
│  Redis 宕机 → 本地 AtomicLong 降级限流                               │
└─────────────────────────────────┬───────────────────────────────────┘
                                  │通过
                                  ▼
┌─────────────────────────────────────────────────────────────────────┐
│ 第二层：Redis Lua 原子锁座                                             │
│  校验所有目标座位 Field == 0                                           │
│  → 有占用：return 0，返回"座位已被锁定"                               │
│  → 全空闲：批量 HSET + DECRBY + EXPIRE → return 1                   │
│                                                                     │
│  Redis 整体宕机 → Sentinel 熔断，拒绝购票写请求（保护 DB）             │
└─────────────────────────────────┬───────────────────────────────────┘
                                  │Lua 成功（≤ 5ms）
                                  ▼
               ┌──────────────────────────────────────┐
               │ 签发 JWT lockToken，立即返回前端       │
               │ 前端：弹出等待遮罩 + 开始轮询          │
               └──────────────────┬───────────────────┘
                                  │（异步，不阻塞响应）
                         ┌────────┴────────┐
                         ▼                 ▼
                  发 MQ 立即消息      发 MQ 延时消息（15min）
                  SEAT_LOCK_EVENT     SEAT_TIMEOUT_EVENT
                         │
                  MQ 宕机 → 同步写 DB 降级
                         │
                         ▼
┌─────────────────────────────────────────────────────────────────────┐
│ MQ 消费端：落盘（顺序消费，Hash Key = scheduleId）                     │
│  本地事务：INSERT seat_lock（唯一索引幂等）+ INSERT orders             │
│  Commit 后 → ACK（手动确认）                                          │
│  失败 → RECONSUME_LATER（MQ 阶梯重试 16 次）                          │
│  超过重试次数 → 进入 DLQ，告警人工介入                                 │
└─────────────────────────────────┬───────────────────────────────────┘
                                  │落库完成
前端轮询命中 {status: "SUCCESS", orderNo: "MO..."}
                                  ▼
┌─────────────────────────────────────────────────────────────────────┐
│ 支付（强一致性本地事务）                                               │
│  @RateLimit 令牌桶（payment:pay:user:{userId}）                      │
│  校验：userId / WAIT_PAY 状态 / expire_time（懒过期）                 │
│  本地事务三合一：扣积分 CAS + 推进订单状态 + seat_lock status→2        │
│  Commit → 异步广播 ORDER_PAID 事件                                   │
└─────────────────────────────────┬───────────────────────────────────┘
                                  │
               ┌──────────────────┴──────────────────┐
               ▼                                     ▼
       Redis seat:status                       电子票/短信/积分
       "1:userId" → "2:userId"                 下游异步消费
```

---

## 9. P0 级行动清单

### 立即必须修复（影响数据正确性）

- [ ] **Lua 脚本 EXPIRE 语义修复**：将 `EXPIRE hash_key 900` 改为 `EXPIRE hash_key {场次结束时间-当前时间}`，座位释放由 MQ 延时消息负责，不依赖 Hash 整体过期
- [ ] **释放 Lua 脚本**：封装"HDEL/HSET 归零 + INCRBY count"为原子 Lua，避免半成功状态
- [ ] **MQ 宕机时延时消息补偿**：降级路径需额外在 DB 写一条 `order_timeout_task` 记录，由定时扫描兜底替代丢失的 MQ 延时消息
- [ ] **seat_lock 唯一索引确认**：确保 `(schedule_id, row_num, col_num)` 的唯一约束对 status=3（作废）的记录不生效，允许同一座位在作废后被重新锁定

### 应该实现（影响健壮性）

- [ ] **轮询接口改为 POST 或用 requestId**：避免 JWT 出现在 URL 参数中
- [ ] **轮询最大超时与降级提示**：30s 超时后告知用户去"我的订单"查看，而不是无限转圈
- [ ] **缓存热身 Runner**：应用启动时批量预热近 7 天场次的 `seat:count` 和 `seat:status` Hash
- [ ] **RocketMQ 版本确认**：确认是否使用 5.x（支持任意精度延时），开源 4.x 无 15 分钟级别
- [ ] **本地降级限流器**：`RateLimiterService` 新增 `AtomicLong` 路径，Redis 熔断时接管限流

### 上线前补充（影响可观测性）

- [ ] **Sentinel Dashboard 接入**：实时查看 Redis Lua 资源、MQ 发送资源的熔断状态
- [ ] **DLQ 告警**：消费组 `%DLQ%seat-lock-group` 有消息即触发钉钉告警
- [ ] **seat:count 计数器与 DB available_seats 定期对账**：建议每 5 分钟对比一次高频场次的计数器与 DB 真实值，偏差 > 5% 触发告警


先说结论，再逐条拆。

读完 [architecture-resilience-design.md](docs/architecture-resilience-design.md) 当前版本，我的判断是：**骨架是对的，且符合业界主流（Lua 原子锁 + DB 唯一索引兜底 + 懒过期 + Redis 死亡时熔断而非降级打 DB），但有几个关键决策要么是『秒杀架构』的错误移植，要么是用了 buzzword 而非合适的工具。** 真正的大厂对号选座票务（大麦、猫眼、淘票票）不会完全这么做。

下面按严重程度排，前 4 个是我会在评审会上打回的。

---

## 🔴 P0：异步建单 + 前端轮询，大概率是过度设计

这是我最想挑战的一点。

**核心质疑**：MQ 削峰的前提是『DB 写入是瓶颈』。但在这套架构里——

```
真正的高并发争抢（选同一个座位）已经被 Redis Lua 串行化吃掉了。
Lua 返回成功的那一刻，这个用户的座位已经 100% 锁定。
此时建单是什么操作？一条 INSERT INTO orders，orderNo 唯一、无锁、无争抢。
```

一条无争抢的 INSERT，MySQL 单机每秒几万条轻轻松松。**它根本不是瓶颈，没有任何削峰的必要。**

而为了这个不存在的瓶颈，你引入了：异步消费建单、前端轮询接口、PROCESSING 中间态、"倒计时从 DB createTime 算"的补丁、以及最致命的——**Redis 已锁定但 DB 订单还没落库的孤儿窗口**。

> 秒杀（无座位、抢同质 SKU）才需要异步建单 + 轮询削峰。**对号选座票务的争抢点在锁座，不在建单。** 大麦/猫眼的主流程是锁座成功后**同步建单**，用户直接拿到 orderNo 跳支付页。轮询模式是从秒杀场景误搬过来的。

**建议**：锁座成功后同步建单（同一请求内完成 Redis Lua + INSERT order），直接返回 orderNo。删掉轮询、PROCESSING、倒计时补丁、孤儿窗口——一次性消失四个问题。MQ 只用来做『状态写回 DB seat_lock』和『下游解耦（出票/短信/积分）』，不参与建单关键路径。

---

## 🔴 P0：Redis Hash 里存『已售(2)』= 把财务级持久数据放进缓存

`seat:status` Hash 的 Value 语义是 `0空闲 / 1:userId锁定 / 2:userId已售`。

锁定态(1)放 Redis 完全合理——锁是**短暂、可丢的**，Redis 崩了座位释放反而是『安全的失败方向』。但**已售态(2)是用户花了钱的持久财务状态，绝不能以 Redis 为权威。**

把『短暂可丢的锁』和『持久不可丢的已售』塞进同一个结构，是文档里一半风险（TTL 混乱、孤儿缓存、对账 Job）的**总根源**。

**业界做法**：职责分离。
- **锁定态** → Redis 权威（短暂，可丢，丢了就释放）
- **已售态** → DB 权威（`seat_lock.status=2` / `order`），Redis 里的 2 只是**只读投影**，崩溃后从 DB 重建，永远不反向信任 Redis 的已售。

---

## 🔴 P0：Redis 锁 + MQ 发送是『双写』，不是原子的

流程是：Lua 成功 → **先返回用户** → **再异步发两条 MQ**。

```
如果 App 在『返回用户』和『发 MQ』之间崩溃（或进程被 kill）：
  Redis 里座位已锁 → 但落库消息没发出 → 订单永不创建
  延时消息也没发出 → 超时回调永不触发
  Hash 又不按单座过期（见下）
  → 这个座位锁死，唯一的解药是『每日凌晨巡检』
  → 孤儿窗口长达 24 小时
```

文档的『MQ 宕机→同步落库』只覆盖了 `send()` 抛异常的情况，**没覆盖 Redis 成功后、发 MQ 前的崩溃窗口**。这是典型的分布式双写问题。

**业界做法**：事务型 outbox（本地消息表，和锁状态写在一起）或 RocketMQ 事务消息（half-message）。如果采纳上面 P0-1 的同步建单，这个问题直接消失大半——锁和订单在一个 DB 事务里，MQ 只做事后解耦，丢了也能从 DB 重建。

---

## 🔴 P0：共享 Hash TTL + 单座释放依赖 MQ → 循环依赖

文档已经点出『Hash 整体 EXPIRE 会误杀全场』，但更深的问题是：**如果不靠 TTL，Redis 自己根本无法释放单个座位的锁**——它必须等 MQ 延时消息回来、查 DB 订单状态、再反向改 Redis。

这意味着所谓『Redis 是实时锁权威』是假的：**Redis 释放自己的锁，却要依赖 MQ + DB。** 这是个循环依赖，让整条链路很脆。

**业界两种干净做法（择一）**：
1. **单座独立 Key**：`SET seat:lock:{schedule}:{seat} {userId} EX 900 NX`，每座自带 TTL，到期自动释放，根本不需要延时消息来释放锁（延时消息只用来关订单）。代价是连座要用 Lua 包多个 key。
2. **Value 内嵌过期时间戳**：`1:{userId}:{expireAt}`，读取/锁座时在 Lua 里判断 `expireAt < now` 视为可用。释放是惰性的，不依赖外部。

现在的『共享 TTL + 靠 MQ 释放』是两者里最别扭的组合。

---

## 🟠 P1：ORDERLY 按 scheduleId 分区，会把热门场次串行化

文档说顺序消费 Hash Key = scheduleId。但这跟你原始设想里『打散到 16-64 个 queue、吞吐翻数十倍』**自相矛盾**：

```
按 scheduleId 分区 → 一个热门首映场的所有消息全进同一个 queue
→ 单线程消费 → 这正是你想避免的串行瓶颈
```

而且——**你其实根本不需要 ORDERLY**。锁的顺序已经被 Redis 单线程定死了；DB 写有唯一索引幂等；超时回调读的是订单当前状态。并发消费 + 幂等就够了，吞吐高得多。

**建议**：去掉 ORDERLY，改普通并发消费 + 幂等（唯一索引 / 状态机 CAS）。需要严格顺序的场景这里没有。

---

## 🟠 P1：JWT 当 lockToken，是 buzzword 用错地方

JWT 的价值是**无状态自验证**。但锁是**有状态、可撤销**的：

```
用户取消 / 超时释放后，那个 JWT 依然『签名合法、未过期』。
所以服务端在 /order/create、/pay 时，无论如何都要回查 Redis/DB 真实锁状态。
既然每次都要回查状态 → JWT 的『自包含』买不到任何东西。
防盗用（JWT.userId != 请求userId）→ 把 owner 存 Redis 里比对，效果完全一样。
```

结论：JWT 在这里只增加了 token 长度和签名开销，没带来独占收益。**业界锁票据普遍是『不透明随机 ID + 服务端状态』**，正因为锁是有状态可撤销的。

---

## 🟠 P1：同一用户重复锁座会报『座位已被占』（幂等 bug）

Lua 里 `if val ~= '0' then return 0`。用户网络抖动重试、或双击（在 5 个令牌额度内），第二次请求看到自己刚写的 `1:userId`，直接返回失败，给真正的座位拥有者弹『座位已被锁定』。

**修**：Lua 校验时，`val == '1:'..userId`（同人）应视为成功/幂等放行，只有 `被他人锁` 或 `已售` 才 return 0。

---

## 🟡 P2：缺『全局准入 / 排队系统』，热点仍会击穿

现在的防护全是**单用户维度**令牌桶 + 单资源熔断。但开售瞬间的惊群（thundering herd）是**全局流量**问题——一万个不同用户同时涌入，每人都在自己的 5 令牌额度内，限流器一个都拦不住，全压到那个热点 Hash 上。

大厂票务前面都有**虚拟排队 / 等候室**（大麦的排队、Ticketmaster 的 waiting room）做全局准入。这是这套架构相对业界最明显的缺失。

## 🟡 P2：单 Hash 热点 Key + 每次 HGETALL

`seat:status:{scheduleId}` 一场一个 key，热门场的所有锁/读/放全压在 Redis 单分片单 key 上；选座页每次刷新都 `HGETALL` 整个 300 座 Hash。高 QPS 下既是热点 key 又是带宽问题。需要考虑：座位图渲染结果短 TTL 缓存 / 用已经提到的 WebSocket 增量推送，而不是每次全量拉。

## 🟡 P2：Warm-up 没重建『已售/锁定』状态

5.2 节的缓存热身只提了重建 `seat:count` 和 detail。但 Redis 被清空后，**已售座位(2)必须从 DB seat_lock/order 重建**，否则热身完的 Hash 是空的，已售座位会显示成可选 → 直接超卖。这是 P0-2（已售态以 DB 为权威）的必然配套。

---

## 客观地说，这些是对的（别改）

- Lua 多座原子锁（预检全空→批量写）——正确，连座 all-or-nothing 漂亮
- DB 唯一索引做最终防线——正确
- 支付接口懒过期——好的纵深防御
- **Redis 死亡时熔断购票写、而非降级打 DB**——这点很成熟，是高级工程师的判断
- MQ 三阶段防丢（同步发送+SYNC_MASTER+手动 ACK+DLQ）——教科书级
- 静态/动态数据分层、L1/L2 多级缓存——正确且表述清晰

---

## 一句话总评

> **这是一份『合格偏上』的设计，防超卖和容灾的硬骨头都啃对了。但它把秒杀的『异步建单+轮询』错误移植到了对号选座场景，又用 Redis 同时承载了可丢的锁和不可丢的已售——这两个决策制造了文档里大半的风险点。砍掉异步建单、把已售态归还给 DB，复杂度和故障面会大幅缩小，反而更接近大麦/猫眼的真实形态。**

---

要不要我把上面认同的几条（尤其 P0 的同步建单、锁/售职责分离、双写原子性、单座 TTL）直接重构进文档？我可以出一版『修订后的目标架构』，而不是只在末尾堆批判点。
