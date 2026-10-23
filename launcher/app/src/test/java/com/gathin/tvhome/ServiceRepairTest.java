package com.gathin.tvhome;

import org.junit.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ServiceRepairTest {
    @Test
    public void parseListSkipsEmptyAndNullToken() {
        Set<String> s = ServiceRepair.parseList("a:b::c");
        assertEquals(3, s.size());
        assertTrue(s.contains("a"));
        assertTrue(s.contains("c"));
        assertTrue(ServiceRepair.parseList(null).isEmpty());
        assertTrue(ServiceRepair.parseList("null").isEmpty());
    }

    @Test
    public void joinUsesColonPreservingOrder() {
        LinkedHashSet<String> s = new LinkedHashSet<>();
        s.add("com.gathin.tvhome/.HomeKeyService");
        s.add("other/.X");
        assertEquals("com.gathin.tvhome/.HomeKeyService:other/.X", ServiceRepair.join(s));
    }
}
