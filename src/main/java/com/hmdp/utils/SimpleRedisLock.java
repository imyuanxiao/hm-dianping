package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

/*Redis分布式简单锁*/
public class SimpleRedisLock implements ILock{

    private static final String LOCK_PREFIX = "lock:";
    private String name;

    private StringRedisTemplate stringRedisTemplate;

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public Boolean tryLock(Long time) {
        long threadId = Thread.currentThread().getId();
        Boolean flag = stringRedisTemplate.opsForValue()
                .setIfAbsent(LOCK_PREFIX + name, threadId+"", time, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    @Override
    public void unLock() {
        stringRedisTemplate.delete(LOCK_PREFIX+name);
    }
}
