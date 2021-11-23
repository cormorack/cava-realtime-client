package com.bakdata.streams_store;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.lang.ref.SoftReference;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryCache implements Cache {

    private final ConcurrentHashMap<String, SoftReference<CacheObject>> cache = new ConcurrentHashMap<>();

    public InMemoryCache() {}

    @Override
    public void add(String key, Object value) {

        if (key == null) {
            return;
        }
        if (value == null) {
            cache.remove(key);
        } else {
            cache.put(key, new SoftReference<>(new CacheObject(value)));
        }
    }

    @Override
    public void remove(String key) {
        cache.remove(key);
    }

    @Override
    public Object get(String key) {

        return Optional.ofNullable(
                cache.get(key)).map(
                        SoftReference::get).filter(
                                cacheObject -> !cacheObject.isExpired()).map(
                                        CacheObject::getValue).orElse(null);
    }

    @Override
    public void clear() {
        cache.clear();
    }

    @AllArgsConstructor
    private static class CacheObject {

        @Getter
        private Object value;

        boolean isExpired() {
            return false;
        }
    }
}
