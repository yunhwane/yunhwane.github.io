package com.yunhwane.pool;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SimpleConnectionPoolTest {

    private static final String URL = "jdbc:h2:mem:step;DB_CLOSE_DELAY=-1";
    private static final String USER = "sa";
    private static final String PASSWORD = "";

    @Test
    void borrow_returnsUsableConnection() throws Exception {
        SimpleConnectionPool pool = new SimpleConnectionPool(URL, USER, PASSWORD, 2);

        Connection conn = pool.borrow();

        assertThat(conn).isNotNull();
        assertThat(conn.isValid(1)).isTrue();
    }

    @Test
    void borrow_blocksWhenPoolExhausted() throws Exception {
        SimpleConnectionPool pool = new SimpleConnectionPool(URL, USER, PASSWORD, 2);
        pool.borrow();
        pool.borrow();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Connection> third = executor.submit((java.util.concurrent.Callable<Connection>) pool::borrow);

            assertThatThrownBy(() -> third.get(300, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            third.cancel(true);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void close_closesAllConnectionsAndPreventsFurtherBorrow() throws Exception {
        SimpleConnectionPool pool = new SimpleConnectionPool(URL, USER, PASSWORD, 2);
        Connection borrowed = pool.borrow();

        pool.close();

        assertThat(borrowed.isClosed()).isTrue();
        assertThatThrownBy(pool::borrow).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void borrow_replacesInvalidConnectionWithFreshOne() throws Exception {
        SimpleConnectionPool pool = new SimpleConnectionPool(URL, USER, PASSWORD, 1);
        Connection dead = pool.borrow();
        dead.close();
        pool.release(dead);

        Connection fresh = pool.borrow();

        assertThat(fresh.isValid(1)).isTrue();
        assertThat(fresh).isNotSameAs(dead);
    }

    @Test
    void release_returnsConnectionToPool_allowingReborrowWithoutTimeout() throws Exception {
        SimpleConnectionPool pool = new SimpleConnectionPool(URL, USER, PASSWORD, 1);
        Connection first = pool.borrow();

        pool.release(first);

        Connection second = pool.borrow(100, TimeUnit.MILLISECONDS);
        assertThat(second).isSameAs(first);
    }

    @Test
    void borrowWithTimeout_throwsWhenPoolExhausted() throws Exception {
        SimpleConnectionPool pool = new SimpleConnectionPool(URL, USER, PASSWORD, 2);
        pool.borrow();
        pool.borrow();

        long start = System.nanoTime();
        assertThatThrownBy(() -> pool.borrow(200, TimeUnit.MILLISECONDS))
                .isInstanceOf(PoolTimeoutException.class);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).isBetween(150L, 600L);
    }
}
