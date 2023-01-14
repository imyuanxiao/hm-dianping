package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private ShopTypeServiceImpl shopTypeService;

    @Override
    public Result queryList() {
        String key = "cache:shop:typeList";
        // 1. 先从缓存查找
        String typeListJson = stringRedisTemplate.opsForValue().get(key);

        // 存在，返回
        if(StrUtil.isNotBlank(typeListJson)){
            List<ShopType> typeList = JSONUtil.toList(typeListJson, ShopType.class);
            return Result.ok(typeList);
        }
        // 2. 若不存在，从数据库查找
        List<ShopType> typeList = shopTypeService.query().orderByAsc("sort").list();
        if(typeList.isEmpty()){
            return Result.fail("类型列表不存在");
        }
        // 将结果转为Json存入redis
        String value = JSONUtil.toJsonStr(typeList);
        stringRedisTemplate.opsForValue().set(key, value);
        stringRedisTemplate.expire(key, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return Result.ok(typeList);
    }
}
