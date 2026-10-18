package com.nexus.intelligence.evidence;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** The read-only set of persisted facts an explanation may cite, keyed by reference. Built from authoritative rows only, never from a model. */
public final class EvidenceBundle {
    private final Map<String, EvidenceItem> items = new LinkedHashMap<>();

    public EvidenceBundle add(EvidenceItem item) {
        if (items.putIfAbsent(item.ref(), item) != null) throw new IllegalArgumentException("duplicate evidence reference " + item.ref());
        return this;
    }

    public Optional<EvidenceItem> resolve(String ref) {
        return Optional.ofNullable(ref == null ? null : items.get(ref));
    }

    public Collection<EvidenceItem> items() {
        return Collections.unmodifiableCollection(items.values());
    }

    public int size() {
        return items.size();
    }
}
