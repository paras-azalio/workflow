package com.nokia.workflow.gateway;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Statement;
import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The user store against a real PostgreSQL, in its own throw-away schema (never the gateway's real one).
 * Needs GATEWAY_TEST_JDBC_URL / _USER / _PASSWORD; skipped without them.
 */
public class UserStoreIT {

    private static final String SCHEMA = "cr_gateway_test";
    private static final String ADMIN = "admin@test.local";
    private static final String PW = "Admin password 1";
    private UserStore store;
    private Db db;

    @BeforeEach
    public void freshSchema() throws Exception {
        String url = System.getenv("GATEWAY_TEST_JDBC_URL");
        Assumptions.assumeTrue(url != null, "GATEWAY_TEST_JDBC_URL not set");
        db = new Db(url, System.getenv("GATEWAY_TEST_JDBC_USER"), System.getenv("GATEWAY_TEST_JDBC_PASSWORD"));
        dropSchema();
        store = new UserStore(db, SCHEMA, new UserStore.Settings(Duration.ofSeconds(2), Duration.ofSeconds(5), 3,
                Duration.ofSeconds(2)));
        store.ensureSchema();
        assertTrue(store.bootstrapAdmin(ADMIN, PW));
    }

    @AfterEach
    public void dropSchema() throws Exception {
        if (db != null) {
            db.with(c -> {
                try (Statement st = c.createStatement()) {
                    st.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
                }
                return null;
            });
        }
    }

    private UserStore.User login(String user, String pw) throws Exception {
        return store.authenticate(user, pw, "127.0.0.1").orElseThrow();
    }

    @Test
    public void theFirstAdminIsCreatedOnlyOnce() throws Exception {
        assertFalse(store.bootstrapAdmin("other@test.local", PW));
        assertEquals(1, store.listUsers().size());
    }

    @Test
    public void unknownUserWrongPasswordAndDisabledUserAllFailTheSameWay() throws Exception {
        store.createUser(ADMIN, Role.ADMIN, "viewer@test.local", "V", "VIEWER", "Viewer password 1");
        assertTrue(store.authenticate("nobody@test.local", PW, "ip").isEmpty());
        assertTrue(store.authenticate(ADMIN, "wrong password 99", "ip").isEmpty());
        store.updateUser(ADMIN, Role.ADMIN, "viewer@test.local", null, false, null, null);
        assertTrue(store.authenticate("viewer@test.local", "Viewer password 1", "ip").isEmpty());
        assertEquals("ADMIN", login(" Admin@Test.Local ", PW).role().name());
    }

    @Test
    public void repeatedFailuresLockTheUserForAWhileEvenWithTheRightPassword() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertTrue(store.authenticate(ADMIN, "wrong password 99", "ip").isEmpty());
        }
        assertTrue(store.authenticate(ADMIN, PW, "ip").isEmpty(), "locked");
        Thread.sleep(2200);
        assertTrue(store.authenticate(ADMIN, PW, "ip").isPresent(), "unlocked after the lock time");
    }

    @Test
    public void sessionsEndWhenIdleOrTooOldOrLoggedOut() throws Exception {
        UserStore.User admin = login(ADMIN, PW);
        String idle = store.createSession(admin, "ip", "ua");
        String used = store.createSession(admin, "ip", "ua");
        assertTrue(store.session(idle).isPresent());
        Thread.sleep(2300);
        assertTrue(store.session(idle).isEmpty(), "idle too long");
        String out = store.createSession(admin, "ip", "ua");
        store.endSession(out);
        assertTrue(store.session(out).isEmpty(), "logged out");
        assertTrue(store.session("forged-token").isEmpty());
        assertTrue(store.session(used).isEmpty(), "also idle: never used since creation");
    }

    @Test
    public void changingRolePasswordOrEnabledEndsTheUsersSessionsButNotADisplayNameChange() throws Exception {
        store.createUser(ADMIN, Role.ADMIN, "ops@test.local", "Ops", "EXECUTOR", "Executor password 1");
        String s1 = store.createSession(login("ops@test.local", "Executor password 1"), "ip", "ua");
        store.updateUser(ADMIN, Role.ADMIN, "ops@test.local", null, null, "Operations", null);
        assertTrue(store.session(s1).isPresent(), "display name only");
        store.updateUser(ADMIN, Role.ADMIN, "ops@test.local", "VIEWER", null, null, null);
        assertTrue(store.session(s1).isEmpty(), "role changed");
        String s2 = store.createSession(login("ops@test.local", "Executor password 1"), "ip", "ua");
        store.updateUser(ADMIN, Role.ADMIN, "ops@test.local", null, null, null, "New executor pw 2");
        assertTrue(store.session(s2).isEmpty(), "password changed");
        String s3 = store.createSession(login("ops@test.local", "New executor pw 2"), "ip", "ua");
        store.updateUser(ADMIN, Role.ADMIN, "ops@test.local", null, false, null, null);
        assertTrue(store.session(s3).isEmpty(), "disabled");
    }

    @Test
    public void theLastEnabledAdminCanNeverBeRemoved() throws Exception {
        assertEquals(409, assertThrows(UserStore.Rejected.class,
                () -> store.updateUser(ADMIN, Role.ADMIN, ADMIN, "EXECUTOR", null, null, null)).status);
        assertEquals(409, assertThrows(UserStore.Rejected.class,
                () -> store.updateUser(ADMIN, Role.ADMIN, ADMIN, null, false, null, null)).status);
        assertEquals(409, assertThrows(UserStore.Rejected.class, () -> store.deleteUser(ADMIN, Role.ADMIN, ADMIN)).status);
        store.createUser(ADMIN, Role.ADMIN, "second@test.local", "Second", "ADMIN", "Second admin pw 1");
        store.updateUser(ADMIN, Role.ADMIN, ADMIN, "VIEWER", null, null, null);
        assertEquals(409, assertThrows(UserStore.Rejected.class,
                () -> store.deleteUser(ADMIN, Role.ADMIN, "second@test.local")).status);
    }

    @Test
    public void badInputIsRefusedWithAReadableReason() {
        assertEquals(400, assertThrows(UserStore.Rejected.class,
                () -> store.createUser(ADMIN, Role.ADMIN, "not-an-email", "x", "VIEWER", "Some password 12")).status);
        assertEquals(400, assertThrows(UserStore.Rejected.class,
                () -> store.createUser(ADMIN, Role.ADMIN, "v@test.local", "x", "VIEWER", "short")).status);
        assertEquals(400, assertThrows(UserStore.Rejected.class,
                () -> store.createUser(ADMIN, Role.ADMIN, "v@test.local", "x", "SUPERUSER", "Some password 12")).status);
        assertEquals(409, assertThrows(UserStore.Rejected.class,
                () -> store.createUser(ADMIN, Role.ADMIN, ADMIN, "x", "VIEWER", "Some password 12")).status);
        assertEquals(404, assertThrows(UserStore.Rejected.class,
                () -> store.updateUser(ADMIN, Role.ADMIN, "ghost@test.local", "VIEWER", null, null, null)).status);
    }

    @Test
    public void anOwnPasswordChangeNeedsTheCurrentPassword() throws Exception {
        UserStore.User admin = login(ADMIN, PW);
        assertEquals(403, assertThrows(UserStore.Rejected.class,
                () -> store.changeOwnPassword(admin, "wrong password 99", "Another admin pw 2", "ip")).status);
        store.changeOwnPassword(admin, PW, "Another admin pw 2", "ip");
        Optional<UserStore.User> again = store.authenticate(ADMIN, "Another admin pw 2", "ip");
        assertTrue(again.isPresent());
    }
}
