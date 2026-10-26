package com.nexus.adapter.core;

import com.nexus.adapter.spi.AdapterCapability;
import com.nexus.adapter.spi.AdapterDescriptor;
import com.nexus.adapter.spi.AdapterLifecycle;
import com.nexus.adapter.spi.TransportKind;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The adapters known to the application. An adapter is registered once (unique id, at most one adapter per transport) and its
 * declared capabilities must be exactly the capability interfaces it implements: a declaration without code, or code without a
 * declaration, stops the application at startup instead of producing a conformance claim that is not true.
 */
@Component
public class AdapterRegistry implements SmartLifecycle {
    /** The read-only view served at GET /api/v1/adapters. No credential material, payload text or identifier. */
    public record Report(String id, TransportKind transport, Set<AdapterCapability> capabilities, Set<Integer> supportedContractMajors,
                         boolean running, boolean ingestOnly, Map<String, Long> counters) {}

    private final Map<TransportKind, AdapterDescriptor> byTransport = new EnumMap<>(TransportKind.class);
    private final Map<String, AdapterDescriptor> byId = new TreeMap<>();
    private final Set<String> running = ConcurrentHashMap.newKeySet();
    private volatile boolean started;

    /** Spring: every {@link AdapterDescriptor} bean (there may be none, as before the first adapter is extracted). */
    @Autowired
    public AdapterRegistry(ObjectProvider<AdapterDescriptor> beans) {
        this(beans.orderedStream().toList());
    }

    public AdapterRegistry(List<AdapterDescriptor> adapters) {
        Set<String> seen = new HashSet<>();
        for (AdapterDescriptor adapter : adapters) {
            if (!seen.add(adapter.id())) throw new IllegalStateException("Adapter id registered twice: " + adapter.id());
            if (byTransport.containsKey(adapter.transport())) {
                throw new IllegalStateException("Two adapters serve the transport " + adapter.transport());
            }
            verifyDeclaredCapabilities(adapter);
            byTransport.put(adapter.transport(), adapter);
            byId.put(adapter.id(), adapter);
        }
    }

    private static void verifyDeclaredCapabilities(AdapterDescriptor adapter) {
        Set<AdapterCapability> declared = adapter.capabilities();
        for (Map.Entry<AdapterCapability, Class<?>> entry : AdapterCapability.contracts().entrySet()) {
            boolean implemented = entry.getValue().isInstance(adapter);
            boolean claims = declared.contains(entry.getKey());
            if (implemented != claims) {
                throw new IllegalStateException("Adapter " + adapter.id() + (claims ? " declares " : " implements but does not declare ")
                        + entry.getKey() + (claims ? " without implementing " + entry.getValue().getSimpleName() : ""));
            }
        }
    }

    public List<AdapterDescriptor> adapters() {
        return List.copyOf(byId.values());
    }

    public Optional<AdapterDescriptor> forTransport(TransportKind transport) {
        return Optional.ofNullable(byTransport.get(transport));
    }

    /** The adapter of {@code transport} as the capability interface {@code type}, if it declares that capability. */
    public <T> Optional<T> capability(TransportKind transport, Class<T> type) {
        AdapterDescriptor adapter = byTransport.get(transport);
        if (adapter == null || !type.isInstance(adapter)) return Optional.empty();
        for (AdapterCapability capability : adapter.capabilities()) {
            if (capability.contract().equals(type)) return Optional.of(type.cast(adapter));
        }
        return Optional.empty();
    }

    public List<Report> reports() {
        List<Report> reports = new ArrayList<>();
        for (AdapterDescriptor adapter : byId.values()) {
            Set<AdapterCapability> capabilities = Set.copyOf(adapter.capabilities());
            boolean hasLifecycle = adapter instanceof AdapterLifecycle;
            reports.add(new Report(adapter.id(), adapter.transport(), capabilities, Set.copyOf(adapter.supportedContractMajors()),
                    hasLifecycle ? running.contains(adapter.id()) : true, !capabilities.contains(AdapterCapability.COMMAND_EGRESS),
                    new TreeMap<>(adapter.diagnostics())));
        }
        reports.sort(Comparator.comparing(Report::id));
        return reports;
    }

    // ---- lifecycle: start every adapter that owns a connection or listener, stop them on shutdown

    @Override
    public void start() {
        for (AdapterDescriptor adapter : byId.values()) {
            if (adapter instanceof AdapterLifecycle lifecycle) {
                lifecycle.start();
                running.add(adapter.id());
            }
        }
        started = true;
    }

    @Override
    public void stop() {
        for (AdapterDescriptor adapter : byId.values()) {
            if (adapter instanceof AdapterLifecycle lifecycle) {
                try {
                    lifecycle.stop();
                } finally {
                    running.remove(adapter.id());
                }
            }
        }
        started = false;
    }

    @Override
    public boolean isRunning() {
        return started;
    }
}
