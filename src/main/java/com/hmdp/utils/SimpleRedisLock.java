package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.BooleanUtil;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

/*Redis分布式简单锁*/
public class SimpleRedisLock implements ILock{

    private static final String LOCK_PREFIX = "lock:";
    //true: 去除横线
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";
    private String name;

    private StringRedisTemplate stringRedisTemplate;

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public Boolean tryLock(Long time) {

        //改进：UUID + 释放前检查
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        Boolean flag = stringRedisTemplate.opsForValue()
                .setIfAbsent(LOCK_PREFIX + name, threadId, time, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    @Override
    public void unLock() {
        //获取线程标识
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        String id = stringRedisTemplate.opsForValue().get(LOCK_PREFIX + name);
        //一致则释放锁，不一致，忽略操作，线程结束
        if(threadId.equals(id)){
            stringRedisTemplate.delete(LOCK_PREFIX+name);
        }
    }
}
