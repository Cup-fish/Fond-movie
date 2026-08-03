# 仿猫眼电影系统 — 目标架构设计（修订版）

> **本文档定位**：这是在 [architecture-resilience-design.md](architecture-resilience-design.md) 评审基础上**重新推导**的目标架构，而非补丁集合。
>
> 核心修正了上一版的四个根本性问题：
> 1. 砍掉「异步建单 + 前端轮询」——那是秒杀场景的误移植，对号选座的争抢点在锁座不在建单
> 2. 锁定态与已售态**职责分离**——锁归 Redis（可丢），已售归 DB（不可丢）
> 3. 用 DB 事务消除 Redis/MQ **双写不原子**问题
> 4. 座位锁改为**单座自带 TTL**，消除「共享 Hash TTL + 靠 MQ 释放锁」的循环依赖
>
> 另修正 ORDERLY 滥用、JWT 误用、同人重复锁座幂等 bug，补齐全局排队、热点 Key 治理、Warm-up 重建已售态。

---

## 目录

1. [设计第一性原理：争抢点在哪里](#1-设计第一性原理争抢点在哪里)
2. [数据权威性划分（最重要的一张表）](#2-数据权威性划分最重要的一张表)
3. [Redis 数据结构设计](#3-redis-数据结构设计)
4. [流程一：查询场次与座位图](#4-流程一查询场次与座位图)
5. [流程二：锁座（同步闭环）](#5-流程二锁座同步闭环)
6. [流程三：创建订单（同步，无轮询）](#6-流程三创建订单同步无轮询)
7. [流程四：支付闭环](#7-流程四支付闭环)
8. [流程五：超时释放与最终一致性](#8-流程五超时释放与最终一致性)
9. [全局排队：开售惊群的真正解法](#9-全局排队开售惊群的真正解法)
10. [中间件宕机降级矩阵](#10-中间件宕机降级矩阵)
11. [崩溃恢复与缓存重建](#11-崩溃恢复与缓存重建)
12. [完整全景链路图](#12-完整全景链路图)
13. [与旧方案的差异总账](#13-与旧方案的差异总账)
14. [落地行动清单](#14-落地行动清单)

---

## 1. 设计第一性原理：争抢点在哪里

一切设计决策都从一个问题出发：**这个系统的并发争抢点到底在哪里？**

```
秒杀（抢茅台 / 抢 0 元购）：
  争抢点 = 库存扣减（一万人抢一个同质 SKU）
  → 瓶颈在写库存 → 需要 MQ 异步削峰 + 前端轮询等结果

对号选座票务（电影 / 演唱会）：
  争抢点 = 锁定某个具体座位（5排6号只有一个人能拿到）
  → 瓶颈在「座位锁的互斥」，不在建单
  → 锁一旦拿到，建单是这个用户私有的、无争抢的 INSERT
```

**关键洞察**：当 Redis Lua 对座位完成原子锁定的那一刻，争抢已经结束了。这个用户的 5排6号已经 100% 属于他，不存在第二个人来抢。此时创建订单只是一条 `INSERT INTO orders`，orderNo 全局唯一、无行锁争用、无热点——MySQL 单机每秒几万条都轻松。

**它根本不是瓶颈，因此不需要任何削峰。**

> 上一版为「建单削峰」引入的异步消费、前端轮询、PROCESSING 中间态、倒计时补丁、Redis 锁定但 DB 未落库的孤儿窗口——全部是为一个**不存在的瓶颈**付出的复杂度。本版一律删除。

**最终主流程**：

```
锁座（Redis Lua 原子锁，争抢在此终结）
  └─ 同一个 HTTP 请求内，紧接着：
       开本地事务 → INSERT seat_lock + INSERT orders → commit
  └─ 直接返回 orderNo 给前端 → 前端跳支付页

MQ 退居二线：只做「下游解耦」（出票/短信/积分）和「超时关单」，
            不参与建单关键路径，丢了也能从 DB 重建。
```

---

## 2. 数据权威性划分（最重要的一张表）

整套架构的稳定性，取决于一件事想清楚没有：**每种状态，谁是权威（source of truth）？**

| 状态 | 性质 | 权威存储 | Redis 的角色 | 丢失后果 |
|------|------|---------|-------------|---------|
| **座位锁定态（未付款）** | 短暂、可撤销、**可丢** | **Redis**（单座 Key + TTL） | 权威 | Redis 丢锁 = 座位释放，这是**安全的失败方向** |
| **座位已售态（已付款）** | 持久、财务级、**绝不可丢** | **MySQL**（`seat_lock.status=2` + `orders`） | **只读投影** | Redis 丢已售 = 从 DB 重建，永不反向信任 Redis |
| **余票数（展示用）** | 可短暂不一致 | MySQL（`schedule.available_seats`） | 实时计数缓存 | Redis 丢 = 回退 DB 字段 |
| **订单状态** | 财务级 | **MySQL**（`orders.status`） | 不缓存 | — |
| **积分余额** | 财务级 | **MySQL**（`user_account`） | 不缓存 | — |

**这张表是上一版一半风险的解药。** 上一版把「短暂可丢的锁」和「持久不可丢的已售」塞进同一个 `seat:status` Hash 的 Value 里（`1:user` vs `2:user`），导致：
- Hash 的 TTL 该设多长？锁要 15 分钟过期，已售要永久——冲突
- Redis 崩了已售座位怎么办？已售不能丢——但它在 Redis 里
- 于是需要凌晨对账 Job 去补——补的是自己制造的问题

**本版铁律**：
> **Redis 只对「锁定态」有权威。已售态一律以 DB 为准，Redis 里的已售标记只是用于渲染的只读副本，崩溃后无条件从 DB 重建。**

---

## 3. Redis 数据结构设计

基于上面的权威划分，Redis 结构重新设计如下。

### 3.1 座位锁：单座独立 Key（核心变更）

上一版用一个大 Hash `seat:status:{scheduleId}` 装全场座位，带来共享 TTL 和热点 Key 两个问题。本版拆成**单座独立 Key**：

```
锁定一个座位：
  Key:   seat:lock:{scheduleId}:{row}_{col}     例: seat:lock:101:5_6
  Value: {userId}                                例: 1001
  TTL:   900 秒（15 分钟）—— 每个座位锁自带独立 TTL，到期 Redis 自动释放
```

**这一个改动同时解决三件事**：
1. **TTL 不再共享**：A 的座位锁到期不会影响 B 的座位锁，各过各的
2. **释放不再依赖 MQ**：到期 Redis 自动 `DEL`，锁的释放是 Redis 自给自足的，不需要延时消息回来改状态。延时消息只负责**关订单**（DB 层），不再负责释放锁
3. **天然无单点热点 Hash**：座位锁分散在不同 Key 上，可被 Redis Cluster 分片打散

**代价**：连座锁定要在一次 Lua 里操作多个 Key——这正是 Lua 擅长的，见 §5。

### 3.2 已售座位：只读投影（DB 权威）

```
渲染选座图时需要知道「哪些座位已售」，做一个只读投影：
  Key:   seat:sold:{scheduleId}                  ← Set 结构
  Member: {row}_{col}                            例: 5_6, 5_7
  来源:  支付成功后写入；崩溃后从 DB orders/seat_lock 重建
  TTL:   场次结束时间 + 缓冲（冷数据，不参与锁的生命周期）
```

关键：`seat:sold` 是 DB 的投影，**任何时候都可以丢弃后从 DB 重建**，系统不依赖它的持久性，只依赖它的「渲染加速」价值。

### 3.3 余票计数（展示用）

```
  Key:   seat:count:{scheduleId}                 ← String 整型
  Value: 当前可售余票数
  用途:  仅用于「场次列表页」展示余票，不参与锁座链路的前置拦截
```

> **重申上一轮的结论**：用户在选座页已经点了具体座位，`count > 0` 并不能保证目标座位可用，Lua 直接校验目标座位即可。`seat:count` 不做锁座前置门，只做列表页余票展示。

### 3.4 选座图渲染缓存（治热点）

```
  Key:   seat:layout:rendered:{scheduleId}       ← String（序列化后的座位图 VO）
  TTL:   3~5 秒（极短）
  用途:  选座页高频刷新时，避免每次都 SCAN 单座锁 Key + 查 sold Set 重新组装
```

---

## 4. 流程一：查询场次与座位图

### 4.1 场次列表（静态/动态分层，沿用上一版正确部分）

静态数据走 L1 Caffeine + L2 Redis 多级缓存；余票走 `seat:count` 实时覆盖。这部分上一版设计是对的，保留：

```
读 L1(60s) → 读 L2 Redis(10min+随机偏移) → 回源 DB 并回填
返回 VO 前，用 seat:count:{scheduleId} 覆盖余票字段（Redis 挂则用 DB 字段降级）
```

### 4.2 选座图（读链路）

```
GET /api/seat/layout?scheduleId=101

1. 先查渲染缓存 seat:layout:rendered:101（3~5s TTL）
   命中 → 直接返回（挡住绝大部分高频刷新）

2. 未命中 → 组装：
   a. 读静态底图 hall:layout:{hallId}（冷数据，排片时预热）
   b. 已售座位：SMEMBERS seat:sold:101（DB 投影）
   c. 锁定座位：用 SCAN/pipeline 批量探测 seat:lock:101:* 
      （或维护一个 seat:locked:{scheduleId} 辅助 Set，见下方说明）
   d. 内存组装二维矩阵，识别 currentUserId：
        在 seat:sold 中            → status=1（已售，红）
        seat:lock 存在且=我        → status=3（我锁定，黄）
        seat:lock 存在且≠我        → status=2（他人锁定，灰）
        底图标记不可用             → status=-1
        其余                       → status=0（可选，绿）
   e. 写回 seat:layout:rendered:101，TTL 3~5s
```

> **关于「锁定座位」的读取**：`SCAN seat:lock:101:*` 在生产中不推荐（SCAN 在大库下有性能问题）。落地用一个辅助结构 `seat:locked:{scheduleId}`（Set），锁座 Lua 里同步 `SADD`，但**这个 Set 不承载权威，只做渲染加速**；座位锁的权威仍是带 TTL 的单座 Key。Set 里的脏成员（对应锁已 TTL 过期）在渲染时用单座 Key 是否存在来校正。

### 4.3 Redis 宕机时的读降级

| 读操作 | Redis 正常 | Redis 宕机 |
|-------|-----------|-----------|
| 场次列表 | L1/L2 | L1 Caffeine 60s 兜底 → DB 回源 |
| 余票 | seat:count | DB `available_seats` 字段 |
| 选座图 | Redis 组装 | **从 DB 实时查 `seat_lock`/`orders` 组装**（降级查询，QPS 受熔断保护） |

> 注意：本版选座图在 Redis 宕机时**可以降级查 DB**（而非像上一版返回「维护中」），因为已售态本来就在 DB，锁定态丢了也是安全方向。读降级查 DB 由 Sentinel 限流保护，避免击穿。

---

## 5. 流程二：锁座（同步闭环）

### 5.1 接口

```
POST /api/seat/lock
Body: { scheduleId, seats: [{row,col}, ...] }
Header: Authorization: Bearer <用户登录JWT>
```

### 5.2 两层结构（去掉 seat:count 前置门）

```
第一层：@RateLimit AOP 令牌桶
  key = seat:lock:user:{userId}, capacity=5, refillRate=2/s
  防恶意刷、连点、脚本重放

第二层：Redis Lua 原子锁多座（单座 Key 版本）
  成功 → 进入 §6 同步建单 → 返回 orderNo
  失败 → 返回「座位已被锁定 / 场次不可售」
```

### 5.3 Lua 脚本（单座 Key + 同人幂等修正）

```lua
-- KEYS = 各座位锁 key:  seat:lock:{scheduleId}:{row}_{col} ...
-- ARGV[1] = userId
-- ARGV[2] = TTL 秒 (900)
-- 返回: 1=全部锁定成功; 0=有座位被他人占用

local user_id = ARGV[1]
local ttl     = tonumber(ARGV[2])

-- 【第一阶段】预检：每个座位要么空闲，要么已被「本人」持有（幂等）
for i = 1, #KEYS do
    local owner = redis.call('GET', KEYS[i])
    if owner and owner ~= user_id then
        return 0   -- 被他人锁定，原子拒绝，无回滚负担
    end
end

-- 【第二阶段】提交：批量写锁，带独立 TTL（重锁刷新自己的 TTL，幂等安全）
for i = 1, #KEYS do
    redis.call('SET', KEYS[i], user_id, 'EX', ttl)
end

return 1
```

**修正点（对应评审 P1 幂等 bug）**：预检时 `owner == user_id` 视为本人持有，放行刷新 TTL，而不是返回失败。这样用户网络抖动重试、双击（在令牌额度内）不会被自己刚写的锁挡住、不会给座位拥有者弹错误。

**连座 all-or-nothing 仍然成立**：Lua 单线程执行，预检阶段任一座位被他人占用立即 `return 0`，绝不会出现「锁了 2 个第 3 个失败」的脏数据。

> 注意：本版 Lua **不再扣 `seat:count`**（计数器只用于列表展示，由支付成功/超时释放时另行维护，且允许最终一致），也**不再操作大 Hash**。锁的写入就是 N 个 `SET ... EX NX` 语义的批量版本。

### 5.4 lockToken：不透明随机 ID，不用 JWT（对应评审 P1）

```
锁座成功后，服务端生成不透明随机 lockToken（UUID），
并在 Redis 存映射:  seat:locktoken:{lockToken} -> {userId, scheduleId, seats}, TTL 900s
返回给前端。
```

**为什么放弃 JWT**：JWT 的价值是「无状态自验证」，但锁是**有状态、可撤销**的——取消/超时后 JWT 依然签名合法未过期，所以服务端在建单/支付时**无论如何都要回查真实锁状态**。既然每次都要回查，JWT 的自包含一文不值，只徒增 token 长度和签名开销。防盗用（owner 比对）用 Redis 里存的 userId 比对，效果完全一样。

> 本版因为锁座成功后**同步建单**（见 §6），lockToken 的存在感其实很弱——它主要用于「锁座」和「建单」万一被拆成两步时的串联。若锁座建单完全合一（推荐），lockToken 可省略。

---

## 6. 流程三：创建订单（同步，无轮询）

### 6.1 核心：与锁座在同一请求内完成

```
锁座 Lua 返回 1 之后，同一个 HTTP 请求继续执行：

开启本地事务 @Transactional:
  1. INSERT seat_lock
       (schedule_id, row_num, col_num, user_id, status=1, lock_until, order_no)
       唯一索引 uk(schedule_id, row_num, col_num) 兜底防重
  2. INSERT orders
       (order_no, user_id, schedule_id, status=WAIT_PAY,
        expire_time=now+15min, total_price, ...)
commit

返回 { orderNo, expireTime, totalPrice, seats }
前端直接跳支付页，倒计时从 expireTime 算（DB 权威时间）
```

**消失的四个问题**（对比上一版）：
- ❌ 前端轮询接口 `/order/status` → 不需要，直接返回 orderNo
- ❌ PROCESSING 中间态 → 不存在
- ❌ 「倒计时从 createTime 重算」补丁 → expireTime 本来就是 DB 生成
- ❌ Redis 锁定但 DB 订单未落库的孤儿窗口 → 锁和单在同一事务，要么都成功要么都回滚

### 6.2 双写原子性：Redis 锁 vs DB 订单（对应评审 P0 双写）

仍然存在一个理论上的双写顺序：**Redis 已锁 → DB 事务**。如果 Redis 锁成功后、DB 事务提交前进程崩溃：

```
Redis 有锁，DB 无订单 → 座位被锁住但没人能支付
```

本版的处理（比上一版的「靠凌晨巡检」干净得多）：

1. **Redis 锁自带 15 分钟 TTL** → 即使进程崩在中间，**座位锁 15 分钟后自动释放**，孤儿窗口从「24 小时」缩短到「≤15 分钟」，且无需任何补偿任务
2. **DB 事务失败则显式回滚 Redis 锁**：
   ```
   try { 开事务; INSERT seat_lock + orders; commit }
   catch (Exception e) {
       // 补偿：删除刚写的 Redis 锁（仅删本人持有的）
       releaseLockIfOwner(scheduleId, seats, userId);  // Lua 校验 owner 再 DEL
       throw e;
   }
   ```
3. **极端崩溃**（catch 都没执行到）由 #1 的 TTL 兜底

> 关键差异：上一版「锁在 Redis、单靠 MQ 异步落库」，崩溃窗口里锁和单是**长期**分离的；本版锁和单**同步在一个事务边界内**，崩溃只会落在一个极窄的瞬间，且有 TTL 自愈。这就是「同步建单」顺带解决双写的红利。

### 6.3 MQ 在建单中的角色：事后解耦，可丢可重建

```
DB 事务 commit 后，发一条 ORDER_CREATED 消息（best-effort）:
  - 用途: 维护 seat:count 余票计数、预热渲染缓存失效、统计
  - 丢了没关系: 这些都是可从 DB 重建的派生数据
  - 不再用 MQ 建单, 因此「MQ 宕机→同步落库」的复杂降级也不需要了
```

---

## 7. 流程四：支付闭环

支付链路上一版设计基本正确，保留并微调（已售态写 DB，Redis 只更新投影）。

```
POST /api/payment/pay  Body: { orderNo }

第一层：@RateLimit 令牌桶 (payment:pay:user:{userId}, cap=5, rate=2/s)

第二层：幂等 + 懒过期校验
  订单属于当前 userId？
  status == WAIT_PAY？
  now < expire_time？  ← 懒过期：已过期则顺手关单+释放锁，拒绝支付

第三层：强一致本地事务（内部积分支付，无外部网关）
  A. CAS 扣积分:
     UPDATE user_account SET points=points-? 
     WHERE user_id=? AND points>=?      affected=0 → 积分不足，回滚
  B. 状态机推进:
     UPDATE orders SET status='PAID', pay_time=now 
     WHERE order_no=? AND status='WAIT_PAY'   affected=0 → 已支付/已关，回滚
  C. 座位落定（DB 权威）:
     UPDATE seat_lock SET status=2 WHERE order_no=?

commit → 此刻「钱票两讫」，已售态以 DB 为准已确立

事务后（best-effort，可丢可重建）:
  广播 ORDER_PAID:
    - 更新 Redis 投影: SADD seat:sold:{scheduleId} 各座位; DEL seat:lock:{...}
    - 出票/二维码、短信、积分奖励、风控审计
```

**对比上一版的改进**：操作 C 的已售态写入 DB 是**权威**的；Redis 的 `seat:sold` 更新放在事务后的 best-effort 广播里，**丢了也能从 DB 重建**，不影响「已售」这一事实的正确性。上一版「Redis Hash 改 2」是把财务态写进缓存，本版彻底纠正。

---

## 8. 流程五：超时释放与最终一致性

### 8.1 锁的释放：Redis 自给自足（核心简化）

```
座位锁 seat:lock:{scheduleId}:{seat} 自带 900s TTL
→ 用户超时未支付 → 锁 Key 到期 → Redis 自动 DEL → 座位回归可选

这一步不需要任何外部组件参与。锁的释放是 Redis 自己的事。
```

> 这是 §3.1 单座 Key 设计的最大红利：**消除了上一版「Redis 锁释放要依赖 MQ 延时消息回来改状态」的循环依赖**。Redis 释放自己的锁，不再需要 MQ + DB 绕一圈。

### 8.2 订单的关闭：延时消息只管 DB 层

锁会自己过期，但 DB 里的 `orders.status=WAIT_PAY` 和 `seat_lock.status=1` 需要推进到 CLOSED/作废。这是 MQ 延时消息的**唯一职责**（不再负责释放 Redis 锁）：

```
锁座建单成功后，发一条延时消息 ORDER_TIMEOUT_CHECK（delay≈15min）

消费者到点唤醒:
  查 orders.status:
    == PAID    → 用户已付，丢弃消息(ACK)
    == CLOSED  → 已被懒过期关掉，丢弃(ACK)
    == WAIT_PAY→ 本地事务: 
                 UPDATE orders SET status=CLOSED WHERE order_no=? AND status=WAIT_PAY
                 UPDATE seat_lock SET status=3 WHERE order_no=?   (3=作废)
                 commit
                 (Redis 锁此时多半已自然过期; 若未过期则 best-effort DEL)
  失败 → RECONSUME_LATER (MQ 阶梯重试)
```

### 8.3 RocketMQ 延时精度说明（对应上一版批判保留）

> RocketMQ 4.x 开源版只有 18 个固定延时级别（无精确 15min，最近为 level 14=10min / level 15=20min）。
> 方案：用 RocketMQ 5.x（任意精度延时，推荐）；或用 level 15(20min)，靠 §7 懒过期补精度——反正锁 15min 已自动释放，订单晚 5 分钟关无副作用。

### 8.4 ORDERLY 移除（对应评审 P1）

上一版用 `ConsumeMode.ORDERLY` + Hash Key=scheduleId，这会把**热门场次的所有消息塞进同一个 queue 单线程消费**，与「打散到多 queue 提吞吐」的初衷自相矛盾，且本就不需要顺序：

- 锁的顺序已被 Redis 单线程定死
- DB 写有唯一索引 + 状态机 CAS 幂等
- 超时回调读的是订单**当前**状态，与到达顺序无关

```
结论: 改用普通并发消费(ConsumeMode.CONCURRENTLY) + 幂等。吞吐高得多。
```

### 8.5 兜底对账（降级为低频巡检）

因为锁会自己过期、已售在 DB、超时由延时消息+懒过期双保险，**对账 Job 的负担大幅减轻**，仅作最后兜底：

```
每日凌晨低峰执行（不再需要高频 5min 对账）:
  1. 余票计数校正: 按 DB 真实未售座位数, 覆盖重建 seat:count
  2. 已售投影校正: 按 DB seat_lock.status=2, 覆盖重建 seat:sold Set
  3. 孤儿订单: 扫 orders 中 WAIT_PAY 且 expire_time 已过很久的, 强制关单
     (正常情况延时消息已处理, 这里只兜 MQ 极端故障漏网的)
```

---

## 9. 全局排队：开售惊群的真正解法（对应评审 P2）

这是上一版相对业界**最明显的缺失**，本版补齐。

**问题**：现有防护全是**单用户维度**令牌桶 + 单资源熔断。但开售瞬间的惊群是**全局流量**问题——1 万个**不同**用户同时涌入，每人都在自己的 5 令牌额度内，单用户限流一个都拦不住，全压到热点场次上。

**业界标准做法：虚拟排队 / 等候室（Waiting Room）**，做全局准入控制。大麦的「排队中」、Ticketmaster 的 waiting room 都是这个。

```
开售前/开售瞬间，热门场次进入排队模式:

1. 用户进入选座页 → 先申请「入场令牌」
   POST /api/queue/enter?scheduleId=101
   
2. 全局准入控制（Redis 计数 + 令牌发放）:
   维护「当前允许进入选座的并发用户数」上限 N（如 N=可售座位数×3）
   - 未满 → 发入场令牌, 放行进入选座/锁座
   - 已满 → 进入排队, 返回 {position, estimateWait}, 前端展示「前面还有 X 人」
   
3. 排队推进:
   有用户完成支付 or 锁座超时释放 → 准入计数 -1 → 队首用户被放行
   
4. 持有入场令牌者才能调 /api/seat/lock（网关层校验令牌）
```

**效果**：把「1 万人同时砸锁座接口」削成「最多 N 人有资格锁座」，热点 Hash/Key 的压力被全局准入挡在门外，而不是等打到 Redis 再用熔断兜底。**熔断是最后防线，排队是主动控流**——两者配合。

> 落地分级：非热门场次（绝大多数）不开排队，直接放行；只有运营标记的热门首映/演唱会级场次才开启 Waiting Room。用一个 `schedule:hot:{scheduleId}` 标记位控制。

---

## 10. 中间件宕机降级矩阵

### 10.1 Redis 宕机

| 链路 | 正常 | Redis 宕机策略 | 失败方向是否安全 |
|------|------|--------------|----------------|
| 选座图读 | Redis 组装 | 降级查 DB 组装（Sentinel 限流保护） | ✅ 安全（已售在 DB） |
| 令牌桶限流 | Lua 精确 | fail-open + 本地 AtomicLong 兜底限流 | ⚠️ 精度降但不失守 |
| **锁座写（核心）** | Redis Lua | **Sentinel 熔断，拒绝锁座写** | ✅ 拒绝优于打穿 DB |
| 余票展示 | seat:count | DB available_seats 字段 | ✅ 安全 |

**为什么锁座写必须熔断而非降级打 DB**（保留上一版正确判断）：

```
Redis 宕机 → Lua 无法执行 → 若降级直接并发 INSERT seat_lock
→ 唯一索引能防超卖（数据不错）
→ 但 N 个并发写无序列化直打 DB, 热点座位异常率飙升 + 连接池打满
结论: Redis 死 = 防洪堤决堤, 唯一正确是熔断锁座写, 宁可少卖几分钟也不击穿 DB
```

### 10.2 RocketMQ 宕机（大幅简化）

> 因为本版 **MQ 不再参与建单关键路径**（建单已同步落 DB），MQ 宕机的影响面比上一版小得多。

| MQ 用途 | MQ 宕机影响 | 处理 |
|---------|-----------|------|
| 建单 | **无影响**（建单不走 MQ） | — |
| ORDER_CREATED（维护 count/缓存） | 派生数据短暂不一致 | 本地 outbox 表暂存，恢复后补发；或等凌晨对账重建 |
| ORDER_TIMEOUT_CHECK（关单） | 超时订单晚关 | **锁已自带 TTL 自动释放**，座位不受影响；订单关闭由 §7 懒过期 + 凌晨兜底对账补 |
| ORDER_PAID（出票/短信） | 下游延迟 | 本地 outbox 表，恢复后补发 |

**本地 Outbox 表（替代上一版分散的 try-catch 降级）**：

```
关键事件不直接裸发 MQ, 而是:
  在业务本地事务内, 同时 INSERT outbox_event (event_type, payload, status=PENDING)
  事务 commit 后, 异步线程/定时任务轮询 outbox → 发 MQ → 成功则标记 SENT
  
→ MQ 宕机时事件不丢（躺在 outbox 表里）, 恢复后自动补发
→ 这是「事务消息」的轻量实现, 保证「DB 改了 ⟺ 事件最终会发出」
```

---

## 11. 崩溃恢复与缓存重建

### 11.1 Redis 重启后的冷启动（对应评审 P2：Warm-up 必须重建已售）

上一版 Warm-up 只重建 `seat:count` 和 detail，**漏了已售态**——Redis 清空后若不重建已售，已售座位会显示成可选 → **直接超卖**。本版补齐：

```
应用启动 / Redis 恢复后, 对「近 N 天有效场次」批量重建:

for each active schedule:
  1. seat:count   ← DB 真实未售座位数
  2. seat:sold    ← SADD DB 中 seat_lock.status=2 的所有座位 (重建已售投影)  ★必须
  3. hall:layout  ← 影厅静态底图
  4. 锁定态(status=1)不重建: 让它从 DB 视角存在, Redis 锁视角为空
     → 后果: 这些未付款锁定的座位在 Redis 看来「可被重新锁」
     → 由 INSERT seat_lock 唯一索引 + 支付时 DB 校验兜底, 不会超卖
     → 最坏是两个用户都「锁定中」, 但只有一个能支付成功(DB CAS), 体验降级但数据正确
```

> 锁定态不重建是有意为之：锁本就是可丢的，重启丢锁 = 座位释放（安全方向）。已售态必须重建，因为它不可丢。这正是 §2 权威划分的实际应用。

### 11.2 各类崩溃的恢复路径总览

| 崩溃点 | 后果 | 自愈机制 | 最大影响时长 |
|--------|------|---------|------------|
| 锁座后 / 建单事务前 | Redis 有锁，DB 无单 | 锁 TTL 自动过期 + catch 补偿 DEL | ≤15min（多数 <1s） |
| 建单事务中 | 事务回滚，无脏数据 | DB 事务原子性 | 0 |
| 支付事务中 | 事务回滚，积分/订单不变 | DB 事务原子性 | 0 |
| 支付后 / 发 ORDER_PAID 前 | DB 已售，Redis 投影未更新 | outbox 补发 + 凌晨对账重建 sold | 到下次对账 |
| Redis 整体宕机 | 锁全失，已售投影失 | 熔断锁座写 + 恢复后重建已售 | 宕机时长 |
| MQ 整体宕机 | 派生事件积压 | outbox 暂存恢复补发 | 宕机时长 |

---

## 12. 完整全景链路图

```
                          ┌─────────────────────────┐
                          │  热门场次? (schedule:hot) │
                          └────────────┬────────────┘
                            是│          │否
                              ▼          │
              ┌───────────────────────┐  │
              │ §9 虚拟排队 / 等候室    │  │
              │ 全局准入 N 个并发名额   │  │
              │ 满则排队, 发入场令牌    │  │
              └───────────┬───────────┘  │
                          │持令牌         │
                          └──────┬───────┘
                                 ▼
用户进入选座页 ──────────────────────────────────────────────┐
  GET /api/seat/layout                                        │
   1. seat:layout:rendered (3~5s) 命中→返回                   │ 读链路
   2. 未命中→组装(底图 + seat:sold[DB投影] + 单座锁) →回填    │ Redis挂→降级查DB
                                                              │
用户点座位 + 确认 ────────────────────────────────────────────┘
  POST /api/seat/lock
        │
        ▼
┌─────────────────────────────────────────────────────────────┐
│ 第一层: @RateLimit 令牌桶 (user维度, 5/2)                     │
│   Redis挂 → 本地 AtomicLong 兜底                             │
└───────────────────────────┬─────────────────────────────────┘
                            ▼
┌─────────────────────────────────────────────────────────────┐
│ 第二层: Redis Lua 原子锁多座 (单座Key + 同人幂等)             │
│   预检: 每座空闲 or 本人持有 → 否则 return 0                  │
│   提交: 批量 SET seat:lock:{sch}:{seat}=userId EX 900        │
│   Redis整体宕机 → Sentinel 熔断, 拒绝锁座写                   │
└───────────────────────────┬─────────────────────────────────┘
                            │ Lua=1 (争抢在此终结)
                            ▼
┌─────────────────────────────────────────────────────────────┐
│ §6 同步建单 (同一请求内, 无 MQ 无轮询)                        │
│   @Transactional:                                            │
│     INSERT seat_lock (status=1, uk兜底)                      │
│     INSERT orders (WAIT_PAY, expire=now+15min)              │
│     INSERT outbox_event (ORDER_CREATED, PENDING)            │
│   commit                                                     │
│   catch → 补偿 DEL 本人 Redis 锁 (兜底: 锁 TTL 自动过期)     │
└───────────────────────────┬─────────────────────────────────┘
                            │ 返回 { orderNo, expireTime }
                            ▼
                  前端直接跳支付页 (倒计时=DB expireTime)
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│ §7 支付  POST /api/payment/pay                               │
│   令牌桶 + 幂等 + 懒过期(now<expire_time)                     │
│   @Transactional 强一致:                                     │
│     A. CAS 扣积分 (>= 防超扣)                                │
│     B. orders WAIT_PAY→PAID (状态机CAS, 防重复支付)          │
│     C. seat_lock status→2 (已售, DB权威!)                    │
│   commit ── 钱票两讫                                          │
│   事务后 best-effort: ORDER_PAID →                           │
│       SADD seat:sold; DEL seat:lock; 出票/短信/积分           │
└───────────────────────────┬─────────────────────────────────┘
                            │
        ┌───────────────────┴───────────────────┐
   未支付超时                                 已支付
        ▼                                        ▼
┌──────────────────────┐              下游异步 (outbox→MQ)
│ §8 超时释放           │              出票/二维码/短信/积分
│ 锁: Redis TTL 自动DEL │
│     (无需MQ, 自给自足) │
│ 单: 延时消息关单       │
│     CONCURRENTLY+幂等  │
│     +懒过期+凌晨对账    │
└──────────────────────┘
```

---

## 13. 与旧方案的差异总账

| 维度 | 旧方案（resilience-design） | 本版（target-design） | 解决的问题 |
|------|---------------------------|---------------------|-----------|
| 建单方式 | MQ 异步消费建单 + 前端轮询 | **同步建单, 直接返回 orderNo** | 删除轮询/PROCESSING/倒计时补丁/孤儿窗口 |
| 锁定 vs 已售 | 同一 Hash Value(1 vs 2) | **锁归Redis, 已售归DB** | TTL冲突/孤儿缓存/对账负担的总根源 |
| 座位锁结构 | 大 Hash `seat:status` 共享TTL | **单座Key独立TTL** | 误杀全场/热点Key/释放依赖MQ |
| 锁释放 | 靠MQ延时消息回来改Hash | **Redis TTL 自动释放** | 消除「锁释放依赖MQ」循环依赖 |
| 双写原子性 | Redis锁+MQ落库长期分离 | **锁+单同事务, TTL自愈** | 崩溃孤儿窗口 24h→≤15min |
| MQ 角色 | 建单关键路径 | **事后解耦, 可丢可重建** | MQ宕机降级大幅简化 |
| 消费模式 | ORDERLY(scheduleId) | **CONCURRENTLY + 幂等** | 热门场次被串行化的吞吐瓶颈 |
| lockToken | JWT 自包含 | **不透明随机ID + 服务端态** | JWT在有状态锁上无收益 |
| 同人重锁 | `val≠0` 直接拒绝(bug) | **本人持有视为幂等放行** | 重试/双击误报「座位被占」 |
| 惊群控制 | 仅单用户限流+熔断 | **+ 全局虚拟排队** | 万人不同用户的全局惊群 |
| 事件可靠性 | 分散 try-catch | **本地 Outbox 表** | DB改了⟺事件最终发出 |
| Warm-up | 只重建 count | **+ 重建已售投影** | 漏建已售→超卖 |

---

## 14. 落地行动清单

### P0 — 架构骨架（决定整体形态）
- [ ] 锁座成功后**同步建单**，删除 MQ 异步建单消费者、`/order/status` 轮询接口、PROCESSING 态
- [ ] 座位锁改为**单座独立 Key** `seat:lock:{sch}:{seat}` + 900s TTL，Lua 批量操作多 Key
- [ ] Lua 预检加入**同人幂等**：`owner == userId` 放行刷新 TTL
- [ ] 已售态以 **DB 为权威**，Redis `seat:sold` Set 仅作只读投影
- [ ] 建单事务 catch 块**补偿删除本人 Redis 锁**

### P1 — 可靠性与正确性
- [ ] 引入**本地 Outbox 表**，所有关键事件经 outbox 投递，替代裸发 MQ
- [ ] MQ 消费改 **CONCURRENTLY + 幂等**（唯一索引 + 状态机 CAS），移除 ORDERLY
- [ ] 延时消息**只负责关订单**，不再负责释放 Redis 锁
- [ ] lockToken 改**不透明随机 ID**（或锁座建单合一后直接省略）
- [ ] `seat_lock` 唯一索引确认：`status=3(作废)` 不阻塞同座重新锁定

### P2 — 流量治理与恢复
- [ ] **虚拟排队 / 等候室**：热门场次（`schedule:hot` 标记）全局准入控制
- [ ] **选座图渲染缓存** `seat:layout:rendered`（3~5s TTL）治热点
- [ ] **Warm-up 重建已售投影**（`seat:sold` ← DB `seat_lock.status=2`）★防超卖
- [ ] 本地 **AtomicLong 降级限流**，Redis 熔断时接管
- [ ] **Sentinel** 接入：锁座写资源、选座图读降级资源配熔断/限流规则

### P3 — 可观测性
- [ ] Sentinel Dashboard、DLQ 告警、outbox 积压告警
- [ ] `seat:count` / `seat:sold` 与 DB 的定期对账（降为每日凌晨低频）
- [ ] 排队系统指标：当前准入数、排队长度、平均等待
