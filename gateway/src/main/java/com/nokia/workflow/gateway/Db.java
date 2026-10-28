package com.nokia.workflow.gateway;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/** A small JDBC connection pool: idle connections are reused, a connection that saw an error is closed. */
final class Db {

    @FunctionalInterface
    interface Work<T> {
        T run(Connection c) throws SQLException;
    }

    private static final int MAX_IDLE = 8;

    private final String url;
    private final String user;
    private final String password;
    private final BlockingQueue<Connection> idle = new LinkedBlockingQueue<>();

    Db(String url, String user, String password) {
        this.url = url;
        this.user = user;
        this.password = password;
    }

    <T> T with(Work<T> work) throws SQLException {
        Connection c = take();
        boolean ok = false;
        try {
            T result = work.run(c);
            ok = true;
            return result;
        } finally {
            give(c, ok);
        }
    }

    /** One transaction: committed when the work returns, rolled back when it throws. */
    <T> T transaction(Work<T> work) throws SQLException {
        return with(c -> {
            c.setAutoCommit(false);
            try {
                T result = work.run(c);
                c.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        });
    }

    private Connection take() throws SQLException {
        Connection c;
        while ((c = idle.poll()) != null) {
            if (c.isValid(2)) {
                return c;
            }
            closeQuietly(c);
        }
        return DriverManager.getConnection(url, user, password);
    }

    private void give(Connection c, boolean ok) {
        if (ok && idle.size() < MAX_IDLE) {
            idle.offer(c);
        } else {
            closeQuietly(c);
        }
    }

    private static void closeQuietly(Connection c) {
        try {
            c.close();
        } catch (SQLException ignored) {
            // a broken connection is dropped either way
        }
    }
}
