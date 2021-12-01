package com.bakdata.streams_store;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.*;


public class RedisClient {

    private static volatile RedisClient instance = null;

    private static JedisPool jedisPool;

    private static JedisPoolConfig buildPoolConfig() {

        final JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(128);
        poolConfig.setMaxIdle(128);
        poolConfig.setMinIdle(16);
        poolConfig.setTestOnBorrow(true);
        poolConfig.setTestOnReturn(true);
        poolConfig.setTestWhileIdle(true);
        poolConfig.setMinEvictableIdleTimeMillis(Duration.ofSeconds(60).toMillis());
        poolConfig.setTimeBetweenEvictionRunsMillis(Duration.ofSeconds(30).toMillis());
        poolConfig.setNumTestsPerEvictionRun(3);
        poolConfig.setBlockWhenExhausted(true);
        return poolConfig;
    }

    private final JedisPoolConfig poolConfig = buildPoolConfig();

    public static RedisClient getInstance(final int port) {

        if (instance == null) {
            synchronized (RedisClient.class) {
                if (instance == null) {
                    instance = new RedisClient(port);
                }
            }
        }
        return instance;
    }

    private RedisClient(int port) {
        try {
            if (jedisPool == null) {
                jedisPool = new JedisPool(poolConfig, "localhost", port);
            }
        } catch (Exception e) {
            System.out.println("Unable to connect to Redis" + e.getMessage());
        }
    }

    public String get(final String key) {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.get(key);
        } catch (Exception ex) {
            System.out.println("Exception caught in get" + ex.getMessage());
        }
        return null;
    }

    public String add(final String key, String value) {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.set(key, value);
        } catch (Exception ex) {
            System.out.println("Exception caught in set" + ex.getMessage());
        }
        return null;
    }

    public Long remove(final String key, String value) {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.del(key, value);
        } catch (Exception ex) {
            System.out.println("Exception caught in set" + ex.getMessage());
        }
        return null;
    }

    public void destroyInstance() {
        jedisPool = null;
        instance = null;
    }
}
