package com.bakdata.streams_store;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.URI;
import java.security.cert.X509Certificate;

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
        poolConfig.setNumTestsPerEvictionRun(3);
        poolConfig.setBlockWhenExhausted(true);
        return poolConfig;
    }

    private final JedisPoolConfig poolConfig = buildPoolConfig();

    public static RedisClient getInstance(final int port, final String host, final URI uri) {

        if (instance == null) {
            synchronized (RedisClient.class) {
                if (instance == null) {
                    instance = new RedisClient(port, host, uri);
                }
            }
        }
        return instance;
    }

    private RedisClient(int port, String host, URI uri) {

        try {
            if (jedisPool == null) {

                boolean useHostAndPort = (host != null) && host != "" && port != 0;

                boolean useUri = (uri != null) && !uri.equals("") && !useHostAndPort;

                if (useHostAndPort) {
                    System.out.println("**************************** redis is using host '" + host + "' and port " + port);
                    jedisPool = new JedisPool(poolConfig, host, port);
                }
                else if (useUri) {
                    System.out.println("**************************** redis is using uri " + uri);

                    TrustManager bogusTrustManager = new X509TrustManager() {
                        public X509Certificate[] getAcceptedIssuers() {
                            return null;
                        }
                        public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                        public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                    };

                    SSLContext sslContext = SSLContext.getInstance("SSL");
                    sslContext.init(null, new TrustManager[] { bogusTrustManager }, new java.security.SecureRandom());

                    HostnameVerifier bogusHostnameVerifier = (hostname, session) -> true;

                    jedisPool = new JedisPool(
                            poolConfig,
                            uri,
                            sslContext.getSocketFactory(),
                            sslContext.getDefaultSSLParameters(),
                            bogusHostnameVerifier
                    );
                }
            }
        } catch (Exception e) {
            System.out.println("Unable to connect to Redis: " + e.getMessage());
        }
    }

    public String get(final String key) {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.get(key);
        } catch (Exception ex) {
            System.out.println("Exception caught in get():  " + ex.getMessage());
        }
        return null;
    }

    public String add(final String key, String value) {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.set(key, value);
        } catch (Exception ex) {
            System.out.println("Exception caught in add(): " + ex.getMessage());
        }
        return null;
    }

    public Long remove(final String key, String value) {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.del(key, value);
        } catch (Exception ex) {
            System.out.println("Exception caught in remove(): " + ex.getMessage());
        }
        return null;
    }

    public void destroyInstance() {
        jedisPool.close();
        jedisPool = null;
        instance = null;
    }
}
