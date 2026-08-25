package com.collection.admin.web.facade;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class FacadeCanonicalJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void sortsKeysAndCompacts() throws Exception {
        assertEquals(
                "{\"a\":1,\"event\":\"session.completed\"}",
                FacadeCanonicalJson.dumps(
                        mapper.readTree("{\"event\":\"session.completed\",\"a\":1}")));
    }

    @Test
    void escapesNonAsciiLikePython() throws Exception {
        assertEquals(
                "{\"s\":\"\\u627f\\u8bfa\"}",
                FacadeCanonicalJson.dumps(mapper.readTree("{\"s\":\"承诺\"}")));
    }
}
