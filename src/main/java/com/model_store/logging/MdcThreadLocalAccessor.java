package com.model_store.logging;

import io.micrometer.context.ThreadLocalAccessor;
import org.slf4j.MDC;

import java.util.Map;

public class MdcThreadLocalAccessor implements ThreadLocalAccessor<Map<String, String>> {

    public static final String KEY = "mdc";

    @Override
    public Object key() {
        return KEY;
    }

    @Override
    public Map<String, String> getValue() {
        return MDC.getCopyOfContextMap();
    }

    @Override
    public void setValue(Map<String, String> value) {
        if (value != null) {
            MDC.setContextMap(value);
        } else {
            MDC.clear();
        }
    }

    @Override
    public void setValue() {
        MDC.clear();
    }

    @Override
    public void restore(Map<String, String> previousValue) {
        if (previousValue != null) {
            MDC.setContextMap(previousValue);
        } else {
            MDC.clear();
        }
    }
}
