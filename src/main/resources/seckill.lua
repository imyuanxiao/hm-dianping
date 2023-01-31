--- 1.参数列表: key （固定前缀+id）
--- 1.1.优惠券id
local voucherId = ARGV[1]
--- 1.2.用户ID
local userId = ARGV[2]

--- 2.数据key
--- 2.1.库存key
local stockKey = 'seckill:stock:' .. voucherId
--- 2.2.订单key
local orderKey = 'seckill:order' .. voucherId

--- 3.脚本业务
--- 3.1.判断库存是否冲突
if(tonumber(redis.call('get', stockKey)) <= 0) then
    --- 3.2.库存不足，返回1
    return 1
end
--- 3.2.判断用户是否下单（从set集合检查用户)
if(redis.call('sismember', orderKey, userId) == 1) then
    --- 3.3.存在，说明重复下单，返回2
    return 2
end
--- 3.4.扣库存 incrby stockKey -1
redis.call('incrby', stockKey, -1)
--- 3.5.保存用户 sadd orderKey userId
redis.call('sadd', orderKey, userId);
return 0