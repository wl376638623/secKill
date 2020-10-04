package cn.wanglu.dao.cache;

import cn.wanglu.entity.Seckill;
import cn.wanglu.utils.JedisUtils;
import com.dyuproject.protostuff.LinkedBuffer;
import com.dyuproject.protostuff.ProtostuffIOUtil;
import com.dyuproject.protostuff.runtime.RuntimeSchema;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.UUID;
import java.util.function.Function;

/**
 * Created by wanglu on 17/2/17.
 */
public class RedisDao {
    private final JedisPool jedisPool;

    public RedisDao(String ip, int port) {
        jedisPool = new JedisPool(ip, port);
    }

    private RuntimeSchema<Seckill> schema = RuntimeSchema.createFrom(Seckill.class);

    public Seckill getSeckill(long seckillId) {
        return getSeckill(seckillId, null);
    }

    /**
     * 从redis获取信息
     *
     * @param seckillId id
     * @return 如果不存在，则返回null
     */
    public Seckill getSeckill(long seckillId, Jedis jedis) {
        boolean hasJedis = jedis != null;
        //redis操作逻辑
        try {
            if (!hasJedis) {
                jedis = jedisPool.getResource();
            }
            try {
                String key = getSeckillRedisKey(seckillId);
                //并没有实现哪部序列化操作
                //采用自定义序列化
                //protostuff: pojo.
                byte[] bytes = jedis.get(key.getBytes());
                //缓存重获取到
                if (bytes != null) {
                    Seckill seckill = schema.newMessage();
                    ProtostuffIOUtil.mergeFrom(bytes, seckill, schema);
                    //seckill被反序列化

                    return seckill;
                }
            } finally {
                if (!hasJedis) {
                    jedis.close();
                }
            }
        } catch (Exception e) {

        }
        return null;
    }

    /**
     * 从缓存获取，如果没有，则从数据库获取
     * 会用到分布式锁
     *
     * @param seckillId     id
     * @param getDataFromDb 从数据库获取的方法
     * @return 返回商品信息
     */
    public Seckill getOrPutSeckill(long seckillId, Function<Long, Seckill> getDataFromDb) {

        String lockKey = "seckill:locks:getSeckill:" + seckillId;
        String lockRequestId = UUID.randomUUID().toString();
        Jedis jedis = jedisPool.getResource();

        try {
            // 循环直到获取到数据
            while (true) {
                Seckill seckill = getSeckill(seckillId, jedis);
                if (seckill != null) {
                    return seckill;
                }
                // 尝试获取锁。
                // 锁过期时间是防止程序突然崩溃来不及解锁，而造成其他线程不能获取锁的问题。过期时间是业务容忍最长时间。
                boolean getLock = JedisUtils.tryGetDistributedLock(jedis, lockKey, lockRequestId, 1000);
                if (getLock) {
                    // 获取到锁，从数据库拿数据, 然后存redis
                    seckill = getDataFromDb.apply(seckillId);
                    putSeckill(seckill, jedis);
                    return seckill;
                }

                // 获取不到锁，睡一下，等会再出发。sleep的时间需要斟酌，主要看业务处理速度
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ignored) {
                }
            }
        } catch (Exception ignored) {
        } finally {
            // 无论如何，最后要去解锁
            JedisUtils.releaseDistributedLock(jedis, lockKey, lockRequestId);
            jedis.close();
        }
        return null;
    }

    /**
     * 根据id获取redis的key
     *
     * @param seckillId 商品id
     * @return redis的key
     */
    private String getSeckillRedisKey(long seckillId) {
        return "seckill:" + seckillId;
    }

    public String putSeckill(Seckill seckill) {
        return putSeckill(seckill, null);
    }

    public String putSeckill(Seckill seckill, Jedis jedis) {
        boolean hasJedis = jedis != null;
        try {
            if (!hasJedis) {
                jedis = jedisPool.getResource();
            }
            try {
                String key = getSeckillRedisKey(seckill.getSeckillId());
                byte[] bytes = ProtostuffIOUtil.toByteArray(seckill, schema,
                        LinkedBuffer.allocate(LinkedBuffer.DEFAULT_BUFFER_SIZE));
                //超时缓存，1小时
                int timeout = 60 * 60;
                String result = jedis.setex(key.getBytes(), timeout, bytes);

                return result;
            } finally {
                if (!hasJedis) {
                    jedis.close();
                }
            }
        } catch (Exception e) {

        }

        return null;
    }
}
