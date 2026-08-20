# Maoyan Movie Ticketing System

一个前后端分离的仿猫眼电影票务系统，重点实现电影浏览、影院/场次查询、选座锁座、下单、支付、想看计数，以及高并发抢票场景下的限流、防超卖和库存一致性方案。

> 本项目仅用于学习和面试展示，和猫眼官方无关。

## 项目亮点

- **选座下单链路**：座位图查询、同步锁座、创建待支付订单、支付成功后写入最终售出座位。
- **三层防超卖**：Redis Lua 原子预扣库存、MySQL 乐观锁扣减库存、超时取消与 DB/Redis 双向回滚。
- **高并发保护**：AOP 令牌桶限流、Redisson 场次级分布式锁、业务过期时间、表唯一索引兜底。
- **缓存体系**：L1 Caffeine + L2 Redis 多级缓存，场次库存 Redis 实时展示，DB 作为最终数据源。
- **异步能力**：RocketMQ 用于订单事件、想看写回、可选锁座缓冲削峰。
- **工程化部署**：支持本地 H2 快速启动，也支持 Docker Compose 启动 MySQL、Redis、RocketMQ、后端、前端、Nginx。

## 演示截图

<table>
  <tr>
    <td><img src="docs/screenshots/demo-01.png" alt="演示截图 1" width="360"></td>
    <td><img src="docs/screenshots/demo-02.png" alt="演示截图 2" width="360"></td>
    <td><img src="docs/screenshots/demo-03.png" alt="演示截图 3" width="360"></td>
  </tr>
  <tr>
    <td><img src="docs/screenshots/demo-04.png" alt="演示截图 4" width="360"></td>
    <td><img src="docs/screenshots/demo-05.png" alt="演示截图 5" width="360"></td>
    <td><img src="docs/screenshots/demo-06.png" alt="演示截图 6" width="360"></td>
  </tr>
  <tr>
    <td><img src="docs/screenshots/demo-07.png" alt="演示截图 7" width="360"></td>
    <td><img src="docs/screenshots/demo-08.png" alt="演示截图 8" width="360"></td>
    <td><img src="docs/screenshots/demo-09.png" alt="演示截图 9" width="360"></td>
  </tr>
  <tr>
    <td><img src="docs/screenshots/demo-10.png" alt="演示截图 10" width="360"></td>
    <td><img src="docs/screenshots/demo-11.png" alt="演示截图 11" width="360"></td>
    <td><img src="docs/screenshots/demo-12.png" alt="演示截图 12" width="360"></td>
  </tr>
</table>

## 技术栈

| 模块 | 技术 |
| --- | --- |
| 前端 | Next.js 14, React 18, TypeScript, Tailwind CSS, Zustand, Axios |
| 后端 | Spring Boot 3, Java 17, MyBatis-Plus, Maven 多模块 |
| 数据库 | H2 本地开发, MySQL 8 Docker/生产 |
| 缓存/锁 | Redis, Caffeine, Redisson |
| 消息队列 | RocketMQ |
| 部署 | Docker Compose, Nginx |

## 目录结构

```text
maoyan
├── backend/                 # Spring Boot 多模块后端
│   ├── common/              # 公共常量、工具、JWT
│   ├── domain/              # DTO/VO/PO/事件对象
│   ├── dao/                 # Mapper / XML
│   ├── service/             # 核心服务、缓存、限流、锁、MQ 消费者
│   ├── biz/                 # 业务编排层
│   └── provider/            # Controller、启动类、配置、SQL 初始化
├── frontend/                # Next.js 前端
├── docker/                  # MySQL / RocketMQ / Nginx / SSL 配置
├── docs/                    # 流程文档
├── docker-compose.yml
├── .env.example
└── README.md
```

## 快速启动：本地开发

本地开发默认使用 H2 文件数据库（`backend/data/maoyan.mv.db`，重启数据不丢失），不依赖 MySQL、Redis、RocketMQ，适合先看功能。

### 1. 启动后端

```powershell
cd backend
.\mvnw.cmd -pl provider -am spring-boot:run
```

后端默认地址：

```text
http://localhost:8080
```

H2 控制台：

```text
http://localhost:8080/h2-console
JDBC URL: jdbc:h2:file:./data/maoyan
User: sa
Password: 留空
```

### 2. 启动前端

```powershell
cd frontend
npm install
npm run dev
```

前端默认地址：

```text
http://localhost:3000
```

前端会通过 Next.js rewrites 把 `/ajax`、`/api`、`/dianying` 代理到 `http://localhost:8080`。

## Docker Compose 启动

Docker 模式会启动 MySQL、Redis、RocketMQ、后端、前端、Nginx。

### 1. 准备环境变量

```powershell
Copy-Item .env.example .env
```

然后修改 `.env` 里的密码和 `JWT_SECRET`。`.env` 已经被 `.gitignore` 忽略，不要提交真实值。

### 2. 打包后端 JAR

当前后端 Dockerfile 使用本地预构建 JAR：

```powershell
cd backend
.\mvnw.cmd -q -DskipTests package
cd ..
```

### 3. 启动全栈

```powershell
docker compose up -d --build
```

默认 Nginx 使用 HTTP 初始化配置：

```text
http://localhost
```

如果要部署 HTTPS，请把 `docker/nginx/*.conf` 里的 `example.com` 改成自己的域名，并设置 `.env` 中的 `DEPLOY_DOMAIN`、`CERT_EMAIL`。

## 核心业务流程

```text
查询电影/场次
  -> 查询座位图
  -> 同步锁座，返回 lockToken
  -> 创建待支付订单，Redis 预扣 + DB 乐观锁扣库存
  -> 支付成功，写入 order_seat，座位变为已售
  -> 超时未支付，定时任务取消订单并回滚库存
```

更详细的流程与架构说明见：

- [猫眼项目完整流程-面试版](./docs/maoyan-flow.md)
- [目标架构设计](./docs/architecture-target-design.md)
- [容灾与一致性设计](./docs/architecture-resilience-design.md)
- [UI/视觉设计规范](./DESIGN.md)

## 关键一致性设计

### 锁座

- 使用 `lock:seat:{scheduleId}` 场次级 Redisson 锁串行化同场次请求。
- 使用 `seat_lock(schedule_id, row_num, col_num)` 唯一索引兜底。
- 锁座阶段只占具体座位，不扣场次总库存。
- 锁座成功后返回 `lockToken`，下单时必须校验该 token 属于当前用户和场次。

### 下单防超卖

- Redis `schedule:stock:{scheduleId}` 通过 Lua 脚本原子预扣库存。
- MySQL `movie_schedule.available_seats` 使用乐观锁版本号最终扣减。
- 创建待支付订单后，`seat_lock` 绑定 `order_no`。
- 订单超时或取消时，DB 和 Redis 库存都回滚。

### 缓存一致性

- 场次列表：L1 Caffeine -> L2 Redis -> DB。
- 库存展示：优先读 Redis 实时库存。
- 交易链路不相信展示缓存，下单时重新做 Redis Lua 和 DB 乐观锁校验。
- Redis 回滚失败会记录 `stock:dirty:rollback`，定时任务从 DB 拉真实库存覆盖 Redis。

部署相关脚本：

- HTTPS 证书初始化：`docker/init-ssl.sh`
- Docker Compose 全栈部署：`docker compose up -d --build`
- 环境变量模板：`.env.example`（复制为 `.env` 后填写真实密钥）

## 常用命令

```powershell
# 后端编译
cd backend
.\mvnw.cmd -q -DskipTests compile

# 后端打包
.\mvnw.cmd -q -DskipTests package

# 前端构建
cd ..\frontend
npm run build

# Docker 全栈启动
cd ..
docker compose up -d --build
```

## License

仅用于学习、课程设计、面试展示。正式商用前请自行补充许可证、合规声明和安全审计。
