package com.hmdp.utils;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    //set函数,设置TTL
    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    //设置逻辑过期时间
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        //设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        //写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }


    //缓存穿透解决，提取函数泛化其它场景
    public <R,ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type , Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        //1.从redis中查询店铺数据
        String json = stringRedisTemplate.opsForValue().get(key);
        R r = null;
        //2.判断缓存是否命中
        if (StrUtil.isNotBlank(json)) {
            // 2.1 缓存命中，返回
            r = JSONUtil.toBean(json, type);
            return r;
        }
        // 缓存未命中，判断是否为空值
        if (json != null) {
            //返回错误信息
            return null;
        }
        //2.2 缓存未命中，查询数据库
        r = dbFallback.apply(id);
        //4.数据库未命中，返回错误
        if (r == null) {
            //将空值写入redis，防止穿透
            this.set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //5.数据库命中，写入redis
        this.set(key, r, time, unit);
        //6.返回
        return r;
    }

    //缓存击穿解决
    public <R,ID> R queryWithMutex(String keyPrefix, ID id, Class<R> type , Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        //判断缓存是否命中
        R r = getFromCache(key,type);
        if (Objects.nonNull(r)) {
            // 缓存命中，直接返回
            return r;
        }
        String lock = LOCK_SHOP_KEY + id;
        try {
            // 缓存未命中，需要重建缓存，判断是否能够获取互斥锁
            //4.1 获取互斥锁
            boolean isLock = tryLock(lock);
            //判断是否成功
            if (!isLock) {
                // 4.2 获取锁失败，等待重试
                Thread.sleep(50);
                return queryWithMutex(keyPrefix, id, type, dbFallback, time, unit);
            }
            // 4.2 获取锁成功，判断缓存是否已经重建，防止堆积的线程全部请求数据库（所以说双检是很有必要的）
            r = getFromCache(key,type);
            if (Objects.nonNull(r)) {
                //缓存命中，直接返回
                return r;
            }
            //4.3 获取锁成功，查询数据库
            r = dbFallback.apply(id);
            if (r == null) {
                //将空值写入redis，防止穿透
                this.set(key,"", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //5.数据库命中，写入redis
            this.set(key, r, time, unit);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            unlock(lock);
        }
        return r;
    }

    //判断缓存是否命中
    private <R> R getFromCache(String key, Class<R> type) {
        String json = stringRedisTemplate.opsForValue().get(key);
        // 判断缓存是否命中
        if (StrUtil.isNotBlank(json)) {
            // 缓存数据有值，说明缓存命中了，直接返回店铺数据
            return JSONUtil.toBean(json, type);
        }
        // 判断缓存中查询的数据是否是空字符串(isNotBlank把 null 和 空字符串 给排除了)
        if (Objects.nonNull(json)) {
            // 当前数据是空字符串，说明缓存也命中了（该数据是之前缓存的空对象），直接返回失败信息
            return null;
        }
        // 缓存未命中（缓存数据既没有值，又不是空字符串）
        return null;
    }

    //定义锁
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        // 拆箱要判空，防止NPE（空指针异常）
        return BooleanUtil.isTrue(flag);
    }

    //释放锁
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }


    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    //逻辑过期解决缓存击穿
    public <R,ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type , Function<ID, R> dbFallback, Long time, TimeUnit unit){
        String key = keyPrefix + id;
        // 1.从redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if (StrUtil.isBlank(json)) {
            // 3.存在，直接返回
            return null;
        }
        // 4.命中，需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 5.判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())) {
            // 5.1.未过期，直接返回店铺信息
            return r;
        }
        // 5.2.已过期，需要缓存重建
        // 6.缓存重建
        // 6.1.获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        // 6.2.判断是否获取锁成功
        if (isLock){
            // 6.3.成功，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 查询数据库
                    R newR = dbFallback.apply(id);
                    // 重建缓存
                    this.setWithLogicalExpire(key, newR, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    // 释放锁
                    unlock(lockKey);
                }
            });
        }
        // 6.4.返回过期的商铺信息
        return r;
    }





}
