package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIDMaker;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIDMaker redisIDMaker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static{
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    /*阻塞队列*/
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024*1024);

    /*线程池，用于开启独立线程处理阻塞队列中的订单*/
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    /*利用spring注解，SECKILL_ORDER_EXECUTOR初始化完毕后就载入VoucherOrderHandler*/
    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    /*内部类，线程内容*/
    private class VoucherOrderHandler implements Runnable{
        @Override
        public void run() {
            while(true){
                try {
                    // 1.获取队列中的订单信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    // 2.创建订单
                    handleVoucherOrder(voucherOrder);

                } catch (InterruptedException e) {
                    log.error("处理订单异常", e);
                }
            }
        }
    }

    private IVoucherOrderService proxy;
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        //Redisson实现可重入锁
        //创建锁对象，以 业务+用户ID 作为锁
        RLock lock = redissonClient.getLock("lock:order" + userId);
        //尝试获取锁
        boolean getLock = lock.tryLock();
        //判断
        if(!getLock){
            log.error("不允许重复下单");
        }
        //获取代理对象
        try {
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            lock.unlock();
        }
    }

    /*判断用户购买资格优化-lua脚本*/
    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        // 1.执行lua脚本
        Long res = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString()
        );
        // 2.判断结果
        // 2.1.不为0，代表没有购买资格
        int r = res.intValue();
        if(r != 0){
            return Result.fail(r == 1 ? "库存不足" : "不可重复下单");
        }
        // 2.2.为0，有购买资格，保存下单信息到阻塞队列
        // 创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        // 订单id
        long orderId = redisIDMaker.nextID("order");
        voucherOrder.setId(orderId);
        // 用户id
        voucherOrder.setUserId(UserHolder.getUser().getId());
        // 代金券id
        voucherOrder.setVoucherId(voucherId);
        this.save(voucherOrder);
        // 加入阻塞队列
        orderTasks.add(voucherOrder);
        // 在主线程中获取代理对象，
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        // 3.3.返回订单id
        return Result.ok(orderId);
    }


    /*Java代码判断用户购买资格*/
//    @Override
//    public Result seckillVoucher(Long voucherId) {
//
//        //查询优惠券信息
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//
//        //判断秒杀是否开始
//        if(voucher.getBeginTime().isAfter(LocalDateTime.now())){
//            return Result.fail("活动未开始");
//        }
//        //判断秒杀是否结束
//        if(voucher.getEndTime().isBefore(LocalDateTime.now())){
//            return Result.fail("活动已结束");
//        }
//        //判断库存是否充足
//        if(voucher.getStock() <= 0){
//            return Result.fail("库存不足");
//        }
//
//        /*在方法外加锁，锁对象为用户id,如果用方法锁，实际为this锁，是所有用户共享的锁*/
//        Long userId = UserHolder.getUser().getId();
//
//        //Redisson实现可重入锁
//        //创建锁对象，以 业务+用户ID 作为锁
//        RLock lock = redissonClient.getLock("lock:order" + userId);
//        //尝试获取锁
//        boolean getLock = lock.tryLock();
//        //判断
//        if(!getLock){
//            //失败，获取锁失败，直接返回或者重试
//            return Result.fail("一个用户只允许订购一单");
//        }
//        //获取代理对象
//        try {
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        } finally {
//            lock.unlock();
//        }
//    }

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        /*如果在方法内加锁，仍然可能存在单个用户并发问题，可能会调用多个本方法*/
        //判断是否一人一单
        //通过intern()从常量池中找字符串，避免每次锁的字符串对象都不一样
        Long userId = UserHolder.getUser().getId();
        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder).count();
        if(count > 0){
            log.error("超出可购买上限");
            return;
        }
        //减库存，添加乐观锁解决负数库存的问题
        boolean success = seckillVoucherService.update().setSql("stock = stock - 1").
                eq("voucher_id", voucherOrder).gt("stock", 0).update();
        if(!success){
            log.error("创建订单失败");
            return;
        }
        this.save(voucherOrder);
        //返回订单ID
    }
}
