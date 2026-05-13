package com.yunhwane.pool;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

public class SimpleConnectionPool {
    private String jdbcUrl;
    private String username;
    private String password;
    private int size;
    ArrayBlockingQueue<Connection> queue;
    private final List<Connection> allConnections = new ArrayList<>();
    private volatile boolean closed = false;


    public SimpleConnectionPool(String jdbcUrl, String username, String password, int size) throws SQLException {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        this.size = size;
        queue = new ArrayBlockingQueue<>(size);
        for (int i = 0; i < size; i++) {
            Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
            allConnections.add(connection);
            queue.offer(connection);
        }
    }

    public Connection borrow() throws InterruptedException, SQLException {
        if(closed){
            throw new IllegalStateException("pool is closed");
        }
        Connection conn = queue.take();
        return validateOrReplace(conn);
    }

    public Connection borrow(long timeout, TimeUnit unit) throws InterruptedException, SQLException {
        if(closed){
            throw new IllegalStateException("pool is closed");
        }
        Connection conn = queue.poll(timeout, unit);
        if (conn == null) {
            throw new PoolTimeoutException("borrow timed out after " + timeout + " " + unit);
        }
        return validateOrReplace(conn);
    }

    public void release(Connection conn) {
        if (conn != null) {
            queue.offer(conn);
        }
    }

    private boolean isAlive(Connection conn) {
        try {
            return conn.isValid(1);
        } catch (SQLException e) {
            return false;
        }
    }

    private void closeQuietly(Connection conn) {
        if (conn != null) {
            try {
                conn.close();
            } catch (SQLException ignored) {}
        }
    }

    private Connection validateOrReplace(Connection conn) throws SQLException {
        if(isAlive(conn)) {return conn;}
        allConnections.remove(conn);
        closeQuietly(conn);
        Connection freshConn = DriverManager.getConnection(jdbcUrl, username, password);
        allConnections.add(freshConn);
        return freshConn;
    }

    public void close() {
        closed = true;
        for(Connection c : allConnections){
            closeQuietly(c);
        }
        allConnections.clear();
        queue.clear();
    }

}
