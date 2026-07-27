package de.palsoftware.yvoke.jsonobject.core.model;

import org.junit.jupiter.api.Test;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class JsonObjectTest {

    @Test
    void testGetDisplayValueNullOrBlank() {
        JsonObject obj = new JsonObject(UUID.randomUUID(), UUID.randomUUID(), "Col",
            Map.of("name", "Alice"), "file.json", OffsetDateTime.now());
        assertNull(obj.getDisplayValue(null));
        assertNull(obj.getDisplayValue(""));
        assertNull(obj.getDisplayValue("   "));
    }

    @Test
    void testGetDisplayValueTopLevelExact() {
        JsonObject obj = new JsonObject(UUID.randomUUID(), UUID.randomUUID(), "Col",
            Map.of("name", "Alice", "age", 30), "file.json", OffsetDateTime.now());
        assertEquals("Alice", obj.getDisplayValue("name"));
        assertEquals("30", obj.getDisplayValue("age"));
    }

    @Test
    void testGetDisplayValueTopLevelCaseInsensitive() {
        JsonObject obj = new JsonObject(UUID.randomUUID(), UUID.randomUUID(), "Col",
            Map.of("Name", "Alice"), "file.json", OffsetDateTime.now());
        assertEquals("Alice", obj.getDisplayValue("name"));
    }

    @Test
    void testGetDisplayValueNestedDotNotation() {
        Map<String, Object> customer = Map.of("id", 2, "name", "iC Consult 1IM HQ environment");
        Map<String, Object> data = Map.of("Customer", customer);
        JsonObject obj = new JsonObject(UUID.randomUUID(), UUID.randomUUID(), "Col", data,
            "file.json", OffsetDateTime.now());

        assertEquals("2", obj.getDisplayValue("Customer.id"));
        assertEquals("iC Consult 1IM HQ environment", obj.getDisplayValue("Customer.name"));
    }

    @Test
    void testGetDisplayValueNestedCaseInsensitive() {
        Map<String, Object> customer = Map.of("id", 2, "name", "iC Consult 1IM HQ environment");
        Map<String, Object> data = Map.of("Customer", customer);
        JsonObject obj = new JsonObject(UUID.randomUUID(), UUID.randomUUID(), "Col", data,
            "file.json", OffsetDateTime.now());

        assertEquals("2", obj.getDisplayValue("customer.id"));
        assertEquals("iC Consult 1IM HQ environment", obj.getDisplayValue("customer.NAME"));
    }

    @Test
    void testGetDisplayValueNestedArray() {
        Map<String, Object> jobServer1 = Map.of("name", "VM-SRV-D1IM-1");
        Map<String, Object> data = Map.of("JobServer", List.of(jobServer1));
        JsonObject obj = new JsonObject(UUID.randomUUID(), UUID.randomUUID(), "Col", data,
            "file.json", OffsetDateTime.now());

        assertEquals("VM-SRV-D1IM-1", obj.getDisplayValue("JobServer[0].name"));
        assertEquals("VM-SRV-D1IM-1", obj.getDisplayValue("jobserver.0.name"));
    }

    @Test
    void testGetDisplayValueNotFound() {
        Map<String, Object> data = Map.of("Customer", Map.of("id", 2));
        JsonObject obj = new JsonObject(UUID.randomUUID(), UUID.randomUUID(), "Col", data,
            "file.json", OffsetDateTime.now());

        assertNull(obj.getDisplayValue("Customer.nonexistent"));
        assertNull(obj.getDisplayValue("Unknown.id"));
    }
}
