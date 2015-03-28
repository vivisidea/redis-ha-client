/*
 * @(#) RedisHAConnectionFactory.java Mar 24, 2015
 * 
 * Copyright 2010 lib64.pw, Inc. All rights reserved.
 */
package pw.lib64.redis;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import javax.annotation.PostConstruct;

import org.apache.log4j.Logger;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.ExceptionTranslationStrategy;
import org.springframework.data.redis.PassThroughExceptionTranslationStrategy;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.jedis.JedisConnection;
import org.springframework.data.redis.connection.jedis.JedisConverters;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.Protocol;
import redis.clients.jedis.exceptions.JedisConnectionException;

import com.google.common.collect.Lists;

/**
 * 简单的基于客户端的高可用负载均衡的 redis 连接工厂
 * 
 * 假设有 N 个 redis 节点
 * 1. 同时创建 N 个到 redis 节点的连接池列表
 * 2. 需要连接的时候，随机（实际实现为轮询）选择一个连接池获取连接
 * 3. 如果从该连接池获取连接失败，将该连接池从连接池列表中移除，添加到重连队列，尝试获取下一个连接池
 * 4. 启动一个监控线程监控重连队列中的连接池，如果连接池恢复正常（ping-pong测试），则加回到连接池列表中
 * 
 * @author vivi
 * @version Mar 24, 2015
 */
public class RedisHAConnectionFactory implements RedisConnectionFactory {
    private static final Logger logger = Logger.getLogger(RedisHAConnectionFactory.class);
    private static final ExceptionTranslationStrategy EXCEPTION_TRANSLATION = new PassThroughExceptionTranslationStrategy(
                    JedisConverters.exceptionConverter());
    private boolean convertPipelineAndTxResults = true;
    private long requestId = 0; // 使用数组下标来实现节点轮询
    private List<JedisPool> jedisPools = Lists.newArrayList(); // 正常的连接池队列
    private BlockingQueue<JedisPool> reconnectQueue = new LinkedBlockingQueue<JedisPool>();// 重连队列
    private int dbIndex = 0;
    private Object lock = new Object();// 多线程加锁同步

    /**
     * 构造函数
     * @param jedisPoolConfig 连接池配置
     * @param hostAndPorts 服务器列表
     * @param password 连接密码
     * @param dbIndex 数据库
     */
    public RedisHAConnectionFactory(JedisPoolConfig jedisPoolConfig, List<String> hostAndPorts, String password, int dbIndex) {
        this.dbIndex = dbIndex;
        for (String hostAndPort : hostAndPorts) {
            String[] splits = hostAndPort.split(":");
            String host = splits[0];
            int port = Integer.valueOf(splits[1]);
            JedisPool pool = new JedisPool(jedisPoolConfig, host, port, Protocol.DEFAULT_TIMEOUT, password, dbIndex);
            jedisPools.add(pool);
        }
    }

    public RedisConnection getConnection() {
        JedisPool pool = null;
        Jedis jedis = null;
        synchronized (lock) {
            int size = jedisPools.size();
            while (size > 0) {// 没有可用的连接直接就抛出异常
                int nextIndex = (int) (requestId++ % size);
//                logger.info("next index = " + nextIndex);
                pool = jedisPools.get(nextIndex);
                jedis = getResource(pool);
                if (jedis == null) {
                    pool = null;
                    size--;
                    reconnectQueue.add(jedisPools.remove(nextIndex));
                } else {
                    break;
                }
            }
        }
        JedisConnection connection = new JedisConnection(jedis, pool, dbIndex);
        connection.setConvertPipelineAndTxResults(convertPipelineAndTxResults);
        return connection;
    }

    /**
     * 构造函数执行完成之后启动监控线程
     */
    @PostConstruct
    public void postConstruct() {
        new JedisPoolWatchThread().start();
        logger.info("JedisPoolWatchThread started.");
    }

    /**
     * 尝试从连接池里获取连接
     * @param pool
     * @return
     */
    private Jedis getResource(JedisPool pool) {
        Jedis jedis = null;
        try {
            jedis = pool.getResource();
        } catch (JedisConnectionException e) {
            if (null != jedis) {
                pool.returnBrokenResource(jedis);
                jedis = null;
            }
        } catch (Throwable e) {
            if (jedis != null) {
                pool.returnBrokenResource(jedis);
                jedis = null;
            }
        }
        return jedis;
    }

    /**
     * 监控 jedis pool 状态
     *
     * @author vivi
     * @version Mar 24, 2015
     */
    private class JedisPoolWatchThread extends Thread {
        @Override
        public void run() {
            while (true) {
                try {
                    JedisPool jedisPool = reconnectQueue.take();
                    if (checkPoolStatus(jedisPool)) {
                        synchronized (lock) {
                            logger.info("recovering jedisPool... reconnect queue size=" + reconnectQueue.size());
                            jedisPools.add(jedisPool);
                        }
                    } else {
                        reconnectQueue.add(jedisPool);// 重新加到队列里面，等待下一次重连检查
                    }
                } catch (Exception e) {
                    // silent
                } finally {
                    logger.info("trying next recovering in 2000ms... reconnect queue size=" + reconnectQueue.size());
                    sleepQuietly(2000);// 连接池检查之间的间隔
                }
            }
        }

        /**
         * You are supposed to use returnBrokenResource when the state of the object is unrecoverable. 
         * A Jedis object represents a connection to Redis. It becomes unusable when the physical connection is broken, 
         * or when the synchronization between the client and server is lost.
         * 
         * With Jedis, these errors are represented by the JedisConnectionException. 
         * So I would use returnBrokenResource for this exception, and not the other ones.
         * 
         * 检查一个连接池的健康状态，只要 ping 能返回 pong，那就认为 pool 状态已经恢复正常
         * @return
         */
        private boolean checkPoolStatus(JedisPool pool) {
            Jedis jedis = null;
            boolean ret = false;
            try {
                jedis = pool.getResource();
                String result = jedis.ping();
                if (result.equals("PONG")) {
                    ret = true;
                } else {
                    ret = false;
                }
            } catch (JedisConnectionException e) {
                if (null != jedis) {
                    pool.returnBrokenResource(jedis);
                    jedis = null;
                }
            } catch (Throwable e) {
                ret = false;
            } finally {
                if (null != jedis) {
                    pool.returnResource(jedis);
                }
            }
            return ret;
        }
    }

    private void sleepQuietly(long millis) {
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        } catch (Exception e) {
            // silent
        }
    }

    public boolean getConvertPipelineAndTxResults() {
        return convertPipelineAndTxResults;
    }

    public DataAccessException translateExceptionIfPossible(RuntimeException ex) {
        return EXCEPTION_TRANSLATION.translate(ex);
    }
}
