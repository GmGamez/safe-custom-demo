package com.camunda.loanoriginationmortgage;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory cache of process variables written by job workers as they complete.
 * Lets the task inbox read full variable context without relying on the
 * Camunda variables/search API, which has inconsistent availability for
 * recently-set variables in SaaS environments.
 */
@Component
public class ProcessVariableCache {

    private final ConcurrentHashMap<Long, Map<String, Object>> store = new ConcurrentHashMap<>();

    /** Merge vars into the cache entry for this process instance. Later writes win. */
    public void merge(long processInstanceKey, Map<String, Object> vars) {
        store.compute(processInstanceKey, (k, existing) -> {
            Map<String, Object> m = existing != null ? existing : new LinkedHashMap<>();
            m.putAll(vars);
            return m;
        });
    }

    /** Return a copy of all cached variables for this process instance. */
    public Map<String, Object> get(long processInstanceKey) {
        Map<String, Object> entry = store.get(processInstanceKey);
        return entry != null ? new LinkedHashMap<>(entry) : new LinkedHashMap<>();
    }
}
