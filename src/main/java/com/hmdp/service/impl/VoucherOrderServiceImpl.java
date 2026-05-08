package com.hmdp.service.impl;
import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {


    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisWorker redisWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;
    /**
     * 加载Lua脚本
     */
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }
    // 线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    // 获取消息队列中的消息
    private class VoucherOrderHandler implements Runnable {
        String queueName = "stream.orders";
        @Override
        public void run() {
            while (true) {
                try {
                    // 1.获取消息队列中的订单信息
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    // 2. 判断消息获取是否成功
                    //2.1如果获取失败，说明没有消息，继续下一次循环
                    if (list == null || list.isEmpty()) continue;
                    // 3. 如果获取成功，可以下单
                    //解析消息中的订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 4. 创建订单
//                    createVoucherOrder(voucherOrder);
                    handleVoucherOrder(voucherOrder);
                    // 5. ACK确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
//                    log.error("获取订单信息异常",e);
                    try {
                        handlePendingList();
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                }
            }
        }

        private void handlePendingList() throws InterruptedException {
            while (true) {
                try {
                    // 1.获取消息队列中的订单信息
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    // 2. 判断消息获取是否成功
                    //2.1如果获取失败，说明没有消息，说明都已读，退出循环
                    if (list == null || list.isEmpty()) break;
                    // 3. 如果获取成功，可以下单
                    //解析消息中的订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 4. 创建订单
                    handleVoucherOrder(voucherOrder);
                    // 5. ACK确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
//                    log.error("获取订单信息异常",e);
                    Thread.sleep(20);
                }
            }
        }
    }


//    // 阻塞队列
//    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
//    // 用于线程池处理的任务
//    // 当初始化完毕后，就会去从对列中去拿信息
//
//    private class VoucherOrderHandler implements Runnable {
//        /**
//         * 1.获取队列中的订单信息
//         * 2.创建订单
//         * 3.下单完成删除队列中的订单信息
//         * 4.返回结果
//         */
//        @Override
//        public void run() {
//            while (true) {
//                try {
//                    // 1.获取队列中的订单信息
//                    VoucherOrder voucherOrder = orderTasks.take();
//                    // 2.创建订单
//                    handleVoucherOrder(voucherOrder);
//                } catch (Exception e) {
//                    log.error("获取订单信息异常",e);
//                }
//            }
//        }
//    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        // 1. 获取用户
        Long userId = voucherOrder.getUserId();
        // 2. 创建锁对象
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        //获取锁对象
        boolean isLock = lock.tryLock();
        //加锁失败
        if (!isLock) {
            log.error("不允许重复下单");
            return;
        }
        try {
//            proxy.createVoucherOrder(voucherOrder);
            createVoucherOrder(voucherOrder);
        } finally {
            //释放锁
            lock.unlock();
        }
    }

    private IVoucherOrderService proxy;
    //redis拆分秒杀功能，存入优惠券id与用户id，减库存方法.redis消息队列替换阻塞队列
    @Override
    public Result seckillVoucher(Long voucherId) {
        // 1. 获取用户
        Long userId = UserHolder.getUser().getId();
        // 生成订单ID
        long orderId = redisWorker.nextId("order");
        // 2. 执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                // 键
                Collections.emptyList(),
                // 值
                voucherId.toString(),
                userId.toString(),
                String.valueOf(orderId)
        );
        int r = result.intValue();
        // 3. 判断结果是否为0
        // 3.1 不为0，已购买==》无购买资格，返回
        if (r != 0) return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
//        // 放入阻塞队列
//        // 3.2 有购买资格，
//        VoucherOrder voucherOrder = new VoucherOrder();
//        // 3.3 填充订单订单ID、用户ID、代金券id
//        voucherOrder.setId(orderId);
//        voucherOrder.setUserId(userId);
//        voucherOrder.setVoucherId(voucherId);
//        // 3.4 加入阻塞队列
//        orderTasks.add(voucherOrder);
        //获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        return Result.ok(orderId);
    }
//    //redis拆分秒杀功能，存入优惠券id与用户id，减库存方法
//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        // 1. 获取用户
//        Long userId = UserHolder.getUser().getId();
//        // 2. 执行lua脚本
//        Long result = stringRedisTemplate.execute(
//                SECKILL_SCRIPT,
//                // 键
//                Collections.emptyList(),
//                // 值
//                voucherId.toString(),
//                userId.toString()
//        );
//        int r = result.intValue();
//        // 3. 判断结果是否为0
//        // 3.1 不为0，已购买==》无购买资格，返回
//        if (r != 0) return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
//        // 放入阻塞队列
//        // 3.2 有购买资格，生成订单ID
//        Long orderId = redisWorker.nextId("order");
//        VoucherOrder voucherOrder = new VoucherOrder();
//        // 3.3 填充订单订单ID、用户ID、代金券id
//        voucherOrder.setId(orderId);
//        voucherOrder.setUserId(userId);
//        voucherOrder.setVoucherId(voucherId);
//        // 3.4 加入阻塞队列
//        orderTasks.add(voucherOrder);
//        //获取代理对象
//        proxy = (IVoucherOrderService) AopContext.currentProxy();
//        return Result.ok(orderId);
//    }


//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        // 1. 查询优惠券
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        // 2. 判断秒杀是否开始
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) return Result.fail("秒杀尚未开始!");
//        // 3. 判断秒杀是否结束
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())) return Result.fail("秒杀已经结束!");
//        // 4. 判断库存是否充足
//        if (voucher.getStock() < 1) return Result.fail("库存不足!");
//
//
//        // 7. 返回订单id （only）
////        return Result.ok(orderId);
////        Long userId = UserHolder.getUser().getId();
////        synchronized (userId.toString().intern()) {
////            // 获取代理对象（事务）
////            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
////            return proxy.createVoucherOrder(voucherId);
////        }
//
//        Long userId = UserHolder.getUser().getId();
//
//        //创建锁对象(新增代码)
////        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
//        RLock lock = redissonClient.getLock("lock:order:" + userId);
//        //获取锁对象
////        boolean isLock = lock.tryLock(1200);
//        boolean isLock = lock.tryLock();
//        //加锁失败
//        if (!isLock) {
//            return Result.fail("不允许重复下单");
//        }
//        try {
//            //获取代理对象(事务)
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        } finally {
//            //释放锁
//            lock.unlock();
//        }
//    }

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //一人一单
        Long userId = voucherOrder.getUserId();
        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        // 5.2.判断是否存在
        if (count > 0) {
            // 用户已经购买过了
            log.error("用户已经购买过一次!");
            return;
        }
        // 5.扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0)
                .update();
        if (!success) {
            log.error("库存不足!");
            return;
        }
//        // 6. 创建订单
//        VoucherOrder voucherOrder = new VoucherOrder();
//        // 6.1 生成订单ID
//        long orderId = redisWorker.nextId("order");
//        voucherOrder.setId(orderId);
//        // 6.2 用户ID
//        voucherOrder.setUserId(userId);
//        // 6.3 代金券ID
//        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);
//        return Result.ok(orderId);
    }


}
