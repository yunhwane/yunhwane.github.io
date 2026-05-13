package com.yunhwane.pool;

public class PoolTimeoutException extends RuntimeException {
    public PoolTimeoutException(String message) {
        super(message);
    }
}
