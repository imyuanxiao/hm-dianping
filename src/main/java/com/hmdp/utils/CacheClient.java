package com.hmdp.utils;

import cn.hutool.core.lang.func.Func;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
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

    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    //适用于逻辑过期
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit){
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData), time, unit);
    }

    //使用泛型，可查询任何类型的对象
    public <R, ID> R queryWithPassTrough(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback,
            Long time, TimeUnit unit){
        //根据id查询redis
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        //存在，返回redis数据
        if(StrUtil.isNotBlank(json)){
            return JSONUtil.toBean(json, type);
        }
        //不存在或者空值，先判断是否为空值
        if("".equals(json) ){
            return null;
        }

        //不存在，去数据库查询，此处需要调用者传递函数
        R r = dbFallback.apply(id);

        //数据库不存在，返回错误码
        if(r == null){
            //写入空值
            stringRedisTemplate.opsForValue().set(key, "",CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }

        //数据库存在，r转为json，存入redis并返回
        this.set(key, r, time, unit);
        return r;
    }


    /*线程池*/
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /*解决缓存击穿（1个热点key）-逻辑过期
     * 此类数据需要提前存进去，例如热点数据，若查询不存在，即不是热点数据
     * */
    public <R, ID> R queryWithLogicalExpire(
            String KeyPrefix, String LockPrefix, ID id, Class<R> type, Function<ID, R> dbFallBack,
            Long time, TimeUnit unit ){
        //根据id查询redis
        String key = KeyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        //未命中，返回null
        if(StrUtil.isBlank(json)){
            return null;
        }
        //命中，反序列化，判断是否过期
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        JSONObject data = (JSONObject)redisData.getData();
        R r = JSONUtil.toBean(data, type);
        //未过期，返回商铺信息
        if(expireTime.isAfter(LocalDateTime.now())){
            return r;
        }
        //过期，获取互斥锁并更新
        String lockKey = LockPrefix+id;
        Boolean flag = tryLock(lockKey);
        //获取互斥锁成功
        if(BooleanUtil.isTrue(flag)){
            /*TODO double check expire time*/
            //开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    //重建缓存
                    //先查询数据库
                    R r1 = dbFallBack.apply(id);
                    //写入redis
                    this.setWithLogicalExpire(key, r1, time, unit);
                }catch (Exception e){
                    throw new RuntimeException(e);
                }finally {
                    unLock(lockKey);
                }
            });
        }
        //返回过期信息
        return r;
    }

    private Boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }


}
