package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Autowired
    private ShopServiceImpl shopService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
        //id <= 0 直接返回
        if(id <= 0){
            return Result.ok("商店不存在");
        }

        /*解决缓存穿透（多个key)-写入空值
        * 调用工具类
        * */
        Shop shop = cacheClient.queryWithPassTrough(CACHE_SHOP_KEY, id, Shop.class,
                this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        /*解决缓存击穿（1个热点key）-逻辑过期
         * 此类数据需要提前存进去，例如热点数据，若查询不存在，即不是热点数据
         * */
        Shop shop2 = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY, LOCK_SHOP_KEY, id, Shop.class,
                this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        if(shop == null){
            return Result.ok("商店不存在");
        }
        return Result.ok(shop);
    }

    /*解决缓存击穿（1个热点key)-利用互斥锁(含解决缓存穿透)*/
    private Shop queryWithMutex(Long id) {
        //根据id查询redis
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //存在，返回redis数据
        if(StrUtil.isNotBlank(shopJson)){
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //不存在或者空值，先判断是否为空值
        if("".equals(shopJson) ){
            return null;
        }
        //不存在，实现缓存重建
        String lockKey = LOCK_SHOP_KEY+id;
        Shop shop = null;
//        try {
//            Boolean flag = tryLock(lockKey);
//            if(BooleanUtil.isFalse(flag)){
//                //获取互斥锁失败，休眠一段时间
//                Thread.sleep(100);
//                return queryWithMutex(id);
//            }
//            //存在，存入数据库
//            shop = shopService.getById(id);
//            //数据库不存在，返回错误码
//            if(shop == null){
//                //写入空值
//                stringRedisTemplate.opsForValue().set(key, "",CACHE_NULL_TTL, TimeUnit.MINUTES);
//                unLock(lockKey);
//                return null;
//            }
//            //数据库存在，shop转为json，存入redis并返回
//            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
//        } catch (InterruptedException e) {
//            throw new RuntimeException(e);
//        } finally {
//            unLock(lockKey);
//        }
        return shop;
    }

    @Override
    @Transactional
    public Result updateShop(Shop shop) {
        if(shop.getId() == null){
            return Result.fail("店铺ID不能为空");
        }
        shopService.updateById(shop);
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shop.getId());
        return Result.ok();
    }
}
