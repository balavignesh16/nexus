package com.nexus.intelligence.evidence;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ledger P6-017 (references are stable and resolvable) and P6-018 (unknown evidence is not negative evidence). */
class EvidenceModelTest {
    @Test
    void p6_017_aReferenceResolvesToExactlyOneItemAndADuplicateIsRefused() {
        EvidenceBundle bundle = new EvidenceBundle().add(EvidenceItem.present("command:1/ack/status", "ack", "NONE")).add(EvidenceItem.absent("command:1/ack/deadlineAt", "deadline"));
        assertEquals("NONE", bundle.resolve("command:1/ack/status").orElseThrow().value());
        assertTrue(bundle.resolve("command:1/ack/missing").isEmpty());
        assertTrue(bundle.resolve(null).isEmpty());
        assertEquals(2, bundle.size());
        assertThrows(IllegalArgumentException.class, () -> bundle.add(EvidenceItem.present("command:1/ack/status", "again", "X")));
        assertThrows(UnsupportedOperationException.class, () -> bundle.items().clear(), "the bundle is read-only to its consumers");
    }

    @Test
    void p6_018_theThreePresenceStatesAreDistinctAndOnlyAPresentItemCarriesAValue() {
        var present = EvidenceItem.present("a", "a", 0);          // zero is a value, not an absence
        var absent = EvidenceItem.absent("b", "b");
        var unknown = EvidenceItem.notRecorded("c", "c");
        assertEquals(Presence.PRESENT, present.presence());
        assertEquals(Presence.ABSENT, absent.presence());
        assertEquals(Presence.NOT_RECORDED, unknown.presence());
        assertNull(absent.value());
        assertNull(unknown.value());
        assertFalse(absent.equals(unknown), "recorded-as-empty and no-record are different facts");
        assertThrows(IllegalArgumentException.class, () -> new EvidenceItem("d", "d", "x", Presence.ABSENT));
        assertThrows(IllegalArgumentException.class, () -> new EvidenceItem("e", "e", null, Presence.PRESENT));
        assertEquals(Presence.ABSENT, EvidenceItem.presentOrAbsent("f", "f", null).presence());
        assertEquals(Presence.PRESENT, EvidenceItem.presentOrAbsent("g", "g", false).presence(), "false is a value");
    }
}
