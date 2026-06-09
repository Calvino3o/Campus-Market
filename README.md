# 雅鉴生活志

<div align="center">

[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-2.3.7-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![MyBatis-Plus](https://img.shields.io/badge/MyBatis--Plus-3.4.3-blue.svg)](https://baomidou.com/)
[![Redis](https://img.shields.io/badge/Redis-6.x-red.svg)](https://redis.io/)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

基于 Spring Boot + Redis 的高并发电商平台，支持秒杀、发帖、关注推送等核心功能

</div>

---

## 📋 项目简介

一个综合性的本地生活服务平台，融合了电商秒杀、社交分享、内容互动等功能。项目采用前后端分离架构，通过多级缓存、分布式锁、消息队列等技术手段，实现了高并发场景下的稳定运行。

### 核心功能

- **商家管理**：商铺信息查询、分类展示、校园商户
- **秒杀系统**：优惠券秒杀、库存扣减、一人一单限制
- **内容社区**：贴子发布、点赞互动、热度排行
- **社交功能**：用户关注、共同关注、Feed流推送
- **用户体系**：短信登录、Token续期、签到统计

---

## 🚀 技术栈

### 后端技术

| 技术 | 版本 | 说明 |
|------|------|------|
| Spring Boot | 2.3.7 | 核心框架 |
| MyBatis-Plus | 3.4.3 | ORM框架 |
| MySQL | 5.7+ | 关系型数据库 |
| Redis | 6.x | 缓存与分布式锁 |
| Redisson | 3.13.6 | Redis客户端 |
| Hutool | 5.7.17 | Java工具类库 |
| Lombok | - | 简化代码 |

### 核心技术亮点

- **多级缓存架构**：缓存穿透、击穿、雪崩解决方案
- **分布式锁**：自定义Redis锁 + Redisson可重入锁
- **高并发秒杀**：Lua脚本原子操作 + Redis Stream异步下单
- **分布式ID**：基于Redis的全局唯一ID生成器
- **Feed流设计**：推模式实现关注动态推送
- **高效签到**：Redis Bitmap节省95%存储空间

---

---

## 💡 核心功能详解

### 1. 用户会话管理

**技术方案**：Redis + UUID + 双拦截器

- 使用UUID生成Token，用户信息序列化存储到Redis
- `LoginInterceptor`：校验Token有效性
- `RefreshTokenInterceptor`：Token自动续期，提升用户体验
- 解决集群环境下的Session共享问题

### 2. 多级缓存架构

**封装通用缓存工具类 `CacheClient`**

| 策略 | 适用场景 | 实现方式 |
|------|---------|---------|
| 缓存空值 | 缓存穿透 | 查询为空时写入空字符串，设置短TTL |
| 互斥锁 | 缓存击穿 | SETNX获取锁，失败则重试 |
| 逻辑过期 | 热点Key | 不设置TTL，后台线程异步重建缓存 |

**性能提升**：接口响应速度提升62%，数据库压力降低80%

### 3. 分布式锁实现

#### 方案一：自定义Redis锁（SimpleRedisLock）

**特点**：轻量级，防止误删，但不支持可重入

#### 方案二：Redisson可重入锁
**特点**：支持可重入、看门狗自动续期、适用于复杂场景

### 4. 高并发秒杀系统

**架构设计**：三层防护 + 异步下单
**核心流程**：

1. **前端限流**：按钮置灰，防止重复点击
2. **Redis预校验**：Lua脚本原子执行库存判断、一人一单判断、扣减库存
3. **消息队列削峰**：订单信息写入Redis Stream，异步处理
4. **分布式锁保护**：Redisson锁防止重复下单
5. **数据库最终一致性**：事务保证订单创建和库存扣减

**性能对比**：

| 优化阶段 | QPS | 说明 |
|---------|-----|------|
| 初始版本 | ~200 | 直接查库+ synchronized |
| Redis优化 | ~1000 | Lua脚本 + 阻塞队列 |
| Stream异步 | ~2000+ | Redis Stream + 单线程消费 |

### 5. 分布式ID生成器

**基于Redis实现雪花算法变种**

**优势**：
- 全局唯一，趋势递增
- 避免数据库自增ID暴露业务量
- 支持每秒百万级ID生成

### 6. Feed流推送设计

**推模式（Write-Fan-Out）**

**滚动加载**：使用ZSet反向范围查询，基于时间戳分页

### 7. 点赞与排行榜

**数据结构**：Redis ZSet

- **Key**：`blog:liked:{blogId}`
- **Member**：userId
- **Score**：点赞时间戳

**功能实现**：
- 点赞/取消：`ZADD` / `ZREM`
- 判断是否点赞：`ZSCORE`
- Top5排行榜：`ZRANGE key 0 4`

### 8. 共同关注

**利用Set交集运算**

相比数据库JOIN查询，性能提升10倍以上

### 9. 签到系统

**Redis Bitmap高效存储**

**空间优化**：单个用户每月仅需4字节，节省95%+存储空间

---

