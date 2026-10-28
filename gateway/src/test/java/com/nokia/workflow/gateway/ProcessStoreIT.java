package com.nokia.workflow.gateway;

import com.nokia.workflow.wrapper.SimpleJson;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Process versions against a real PostgreSQL, in a throw-away schema. Skipped without GATEWAY_TEST_JDBC_URL. */
public class ProcessStoreIT {

    private static final String SCHEMA = "cr_gateway_process_test";
    private static final String P = "CR_AUTOMATION";
    private static final ProcessStore.PublishCheck OK = def -> List.of();
    private Db db;
    private ProcessStore store;
    private String shipped;

    @BeforeEach
    public void freshSchema() throws Exception {
        String url = System.getenv("GATEWAY_TEST_JDBC_URL");
        Assumptions.assumeTrue(url != null, "GATEWAY_TEST_JDBC_URL not set");
        db = new Db(url, System.getenv("GATEWAY_TEST_JDBC_USER"), System.getenv("GATEWAY_TEST_JDBC_PASSWORD"));
        dropSchema();
        UserStore users = new UserStore(db, SCHEMA, new UserStore.Settings(Duration.ofHours(1), Duration.ofHours(2), 5,
                Duration.ofMinutes(1)));
        users.ensureSchema();
        store = new ProcessStore(db, SCHEMA, users);
        store.ensureSchema();
        shipped = ProcessDefinitionTest.shipped();
        assertTrue(store.seed(P, "test", shipped));
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
    public void theShippedDefinitionIsSeededOnceAsPublishedVersion1() throws Exception {
        assertFalse(store.seed(P, "again", shipped));
        ProcessStore.Version v = store.latestPublished(P);
        assertEquals(1, v.version());
        assertEquals("PUBLISHED", v.state());
    }

    @Test
    public void aPublishedVersionIsNeverChanged() throws Exception {
        assertEquals(409, assertThrows(UserStore.Rejected.class, () -> store.updateDraft(P, 1, shipped, 1, "admin")).status);
    }

    @Test
    public void oneDraftAtATimeAndStaleSavesAreRefused() throws Exception {
        ProcessStore.Version d = store.createDraft(P, null, "admin");
        assertEquals(2, d.version());
        assertEquals("DRAFT", d.state());
        assertEquals(409, assertThrows(UserStore.Rejected.class, () -> store.createDraft(P, 1, "admin")).status);
        assertTrue(store.updateDraft(P, 2, shipped, 1, "admin").isEmpty());
        assertEquals(409, assertThrows(UserStore.Rejected.class, () -> store.updateDraft(P, 2, shipped, 1, "other admin")).status,
                "revision 1 was already saved over");
    }

    @Test
    public void anInvalidDraftIsSavedButCannotBePublished() throws Exception {
        store.createDraft(P, null, "admin");
        List<String> problems = store.updateDraft(P, 2, "{\"namespace\": \"cr_automation\", \"steps\": []}", 1, "admin");
        assertFalse(problems.isEmpty());
        assertEquals(400, assertThrows(UserStore.Rejected.class, () -> store.publish(P, 2, "admin", OK)).status);
        assertEquals(1, store.latestPublished(P).version());
    }

    @Test
    public void publishingChecksTheOutsideWorldThenRetiresThePreviousVersion() throws Exception {
        store.createDraft(P, null, "admin");
        assertEquals(400, assertThrows(UserStore.Rejected.class,
                () -> store.publish(P, 2, "admin", def -> List.of("flow x does not exist"))).status);
        store.publish(P, 2, "admin", OK);
        assertEquals(2, store.latestPublished(P).version());
        assertEquals("RETIRED", store.version(P, 1).state());
        assertEquals(1, store.version(P, 1).version(), "an old version stays readable for the orders pinned to it");
        assertEquals(409, assertThrows(UserStore.Rejected.class, () -> store.publish(P, 2, "admin", OK)).status, "not a draft");
    }

    @Test
    public void onlyADraftCanBeDiscarded() throws Exception {
        store.createDraft(P, null, "admin");
        assertEquals(409, assertThrows(UserStore.Rejected.class, () -> store.discardDraft(P, 1, "admin")).status);
        store.discardDraft(P, 2, "admin");
        assertEquals(null, store.version(P, 2));
        assertEquals(3, store.createDraft(P, null, "admin").version(), "a discarded draft's number is never reused (audit)");
    }

    @Test
    public void theOnlyPublishedVersionCannotBeRetired() throws Exception {
        assertEquals(409, assertThrows(UserStore.Rejected.class, () -> store.retire(P, 1, "admin")).status);
        assertEquals(404, assertThrows(UserStore.Rejected.class, () -> store.retire(P, 9, "admin")).status);
    }

    @Test
    public void aDraftCanStartFromAnOlderVersion() throws Exception {
        store.createDraft(P, null, "admin");
        @SuppressWarnings("unchecked")
        Map<String, Object> def = (Map<String, Object>) SimpleJson.parse(shipped);
        def.put("namespace", "cr_automation");
        store.updateDraft(P, 2, SimpleJson.writeCompact(def), 1, "admin");
        store.publish(P, 2, "admin", OK);
        ProcessStore.Version d = store.createDraft(P, 1, "admin");
        assertEquals(3, d.version());
        assertEquals(store.version(P, 1).definition(), d.definition());
    }
}
