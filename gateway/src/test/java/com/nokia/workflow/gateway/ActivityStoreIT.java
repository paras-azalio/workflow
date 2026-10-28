package com.nokia.workflow.gateway;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Statement;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Activity declarations against a real PostgreSQL, in a throw-away schema. Skipped without GATEWAY_TEST_JDBC_URL. */
public class ActivityStoreIT {

    private static final String SCHEMA = "cr_gateway_activity_test";
    private static final String NT = "PGW_RDS";
    private static final String SA = "1051_SUBSCRIBER_PROFILE_CONFIGURATION";
    private Db db;
    private UserStore users;
    private ActivityStore store;

    @BeforeEach
    public void freshSchema() throws Exception {
        String url = System.getenv("GATEWAY_TEST_JDBC_URL");
        Assumptions.assumeTrue(url != null, "GATEWAY_TEST_JDBC_URL not set");
        db = new Db(url, System.getenv("GATEWAY_TEST_JDBC_USER"), System.getenv("GATEWAY_TEST_JDBC_PASSWORD"));
        dropSchema();
        users = new UserStore(db, SCHEMA, new UserStore.Settings(Duration.ofHours(1), Duration.ofHours(2), 5, Duration.ofMinutes(1)));
        users.ensureSchema();
        store = new ActivityStore(db, SCHEMA, "CR_AUTOMATION", users, Set.of("MOP_GENERATION", "MOP_EXECUTION"));
        store.ensureSchema();
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

    @Test
    public void aDeclarationStartsAsAnEmptyDraftAndIsUniquePerNodeTypeAndSubActivity() throws Exception {
        Map<String, Object> d = store.declare(NT, SA, "1051", "admin", users);
        assertEquals("DRAFT", d.get("state"));
        assertEquals(Map.of(), store.version(NT, SA, 1).content().get("steps"));
        assertEquals(409, assertThrows(UserStore.Rejected.class, () -> store.declare(NT, SA, "again", "admin", users)).status);
        assertEquals(400, assertThrows(UserStore.Rejected.class, () -> store.declare("PGW RDS", SA, "x", "admin", users)).status);
        assertEquals(404, assertThrows(UserStore.Rejected.class, () -> store.versions(NT, "1058_UNKNOWN")).status);
        assertEquals(1, store.list().size());
    }

    @Test
    public void aStepNameNoFlowRecordsCannotBePublished() throws Exception {
        store.declare(NT, SA, "1051", "admin", users);
        store.updateDraft(NT, SA, 1, "{\"steps\":{\"MOP_EXECUTON\":{\"params\":{\"A\":\"1\"}}}}", 1, "admin");
        UserStore.Rejected e = assertThrows(UserStore.Rejected.class, () -> store.publish(NT, SA, 1, "admin"));
        assertEquals(400, e.status);
        assertTrue(e.getMessage().contains("MOP_EXECUTON: no flow records a step of that name"), e.getMessage());
        store.updateDraft(NT, SA, 1, "{\"steps\":{\"MOP_EXECUTION\":{\"params\":{\"A\":\"1\"}}}}", 2, "admin");
        store.publish(NT, SA, 1, "admin");
        assertEquals("PUBLISHED", store.version(NT, SA, 1).state());
    }

    @Test
    public void anInvalidConfigurationIsSavedWithItsProblemsButNotPublished() throws Exception {
        store.declare(NT, SA, "1051", "admin", users);
        assertTrue(!store.updateDraft(NT, SA, 1, "{\"steps\":{\"MOP_EXECUTION\":{\"login\":{\"M2MPASSWORD1\":\"plain\"}}}}", 1, "admin").isEmpty());
        assertEquals(400, assertThrows(UserStore.Rejected.class, () -> store.publish(NT, SA, 1, "admin")).status);
    }

    @Test
    public void versionsFollowTheSameLifecycleAsProcesses() throws Exception {
        store.declare(NT, SA, "1051", "admin", users);
        store.publish(NT, SA, 1, "admin");
        assertEquals(409, assertThrows(UserStore.Rejected.class, () -> store.updateDraft(NT, SA, 1, "{}", 1, "admin")).status);
        assertEquals(2, store.createDraft(NT, SA, null, "admin").version());
        store.discardDraft(NT, SA, 2, "admin");
        assertNull(store.version(NT, SA, 2));
        assertEquals(3, store.createDraft(NT, SA, null, "admin").version());
        assertEquals(409, assertThrows(UserStore.Rejected.class, () -> store.retire(NT, SA, 1, "admin")).status);
    }
}
