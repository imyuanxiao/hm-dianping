package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIDMaker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

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

    @Override
    public Result seckillVoucher(Long voucherId) {

        //查询优惠券信息
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);

        //判断秒杀是否开始
        if(voucher.getBeginTime().isAfter(LocalDateTime.now())){
            return Result.fail("活动未开始");
        }
        //判断秒杀是否结束
        if(voucher.getEndTime().isBefore(LocalDateTime.now())){
            return Result.fail("活动已结束");
        }
        //判断库存是否充足
        if(voucher.getStock() <= 0){
            return Result.fail("库存不足");
        }

        /*在方法外加锁，锁对象为用户id,如果用方法锁，实际为this锁，是所有用户共享的锁*/
        Long userId = UserHolder.getUser().getId();
        //创建锁对象，以 业务+用户ID 作为锁
        SimpleRedisLock lock = new SimpleRedisLock("order" + userId, stringRedisTemplate);
        Boolean getLock = lock.tryLock(10L);
        if(!getLock){
            return Result.fail("一个用户只允许订购一单");
        }
        //获取代理对象
        try {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            lock.unLock();
        }
    }

    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        /*如果在方法内加锁，仍然可能存在单个用户并发问题，可能会调用多个本方法*/
        //判断是否一人一单
        //通过intern()从常量池中找字符串，避免每次锁的字符串对象都不一样
        Long userId = UserHolder.getUser().getId();
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if(count > 0){
            return Result.fail("超出可购买上限");
        }

        //减库存，添加乐观锁解决负数库存的问题
        boolean success = seckillVoucherService.update().setSql("stock = stock - 1").
                eq("voucher_id", voucherId).gt("stock", 0).update();
        if(!success){
            return Result.fail("创建订单失败");
        }
        //创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        //订单id
        long orderId = redisIDMaker.nextID("order");
        voucherOrder.setId(orderId);
        //用户id
        voucherOrder.setUserId(UserHolder.getUser().getId());
        //代金券id
        voucherOrder.setVoucherId(voucherId);
        this.save(voucherOrder);
        //返回订单ID
        return Result.ok(orderId);
    }
}
