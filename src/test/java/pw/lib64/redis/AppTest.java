package pw.lib64.redis;

import java.util.List;
import java.util.concurrent.TimeUnit;

import junit.framework.TestCase;

import org.apache.log4j.Logger;
import org.junit.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import pw.lib64.redis.RedisHAConnectionFactory;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import com.google.common.collect.Lists;

/**
 * Unit test for simple App.
 */
public class AppTest extends TestCase {
    private static final Logger logger = Logger.getLogger(AppTest.class);

    @org.junit.Test
    public void testJedisPool() throws InterruptedException {
        JedisPoolConfig jedisPoolConfig = new JedisPoolConfig();
        jedisPoolConfig.setTestOnBorrow(true);

        JedisPool jedisPool = new JedisPool(jedisPoolConfig, "localhost", 7379, 2000, "sh00t", 0);
        for (int i = 0; i < 100000; i++) {
            Jedis j = null;
            try {
                System.out.println("before getResource.");
                j = jedisPool.getResource();
                System.out.println("after getResource.");
                String key = "foo" + i;
                j.set(key, key);
                String ret = j.get(key);
                System.out.println("getting key=" + key + ", value=" + ret);
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                if (j != null) {
                    jedisPool.returnResource(j);
                }
            }
            TimeUnit.SECONDS.sleep(1);
        }
    }

    @Test
    public void test2() throws InterruptedException {
        JedisPoolConfig jedisPoolConfig = new JedisPoolConfig();
//        jedisPoolConfig.setTestOnBorrow(true);

        List<String> hostAndPorts = Lists.newArrayList();
        hostAndPorts.add("localhost:7379");
        hostAndPorts.add("localhost:8379");
        String auth = "sh00t";
        int dbIndex = 0;

        RedisHAConnectionFactory factory = new RedisHAConnectionFactory(jedisPoolConfig, hostAndPorts, auth, dbIndex);
        factory.postConstruct();

        StringRedisTemplate template = new StringRedisTemplate(factory);
        for (int i = 0; i < 100000; i++) {
            Jedis j = null;
            try {
                String key = "foo" + i;
//                template.opsForValue().set(key, key);
                String ret = template.opsForValue().get(key);
                System.out.println("getting key=" + key + ", value=" + ret);
            } catch (Exception e) {
                logger.error("redis operation failed, error=" + e.getMessage());
            }
            TimeUnit.SECONDS.sleep(1);
        }
    }
}
