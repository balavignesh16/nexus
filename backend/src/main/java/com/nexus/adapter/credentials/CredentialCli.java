package com.nexus.adapter.credentials;

import com.nexus.adapter.http.KeyHasher;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.security.SecureRandom;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * {@code nexus-credentials}: the ONLY way to create, rotate, revoke, list and audit device credentials (decision D7). It is a local command run
 * by a person who has the operating-system account and the database login; it is not a service and opens no socket. The database login comes
 * from {@code NEXUS_ADMIN_DB_URL}, {@code NEXUS_ADMIN_DB_USER} and {@code NEXUS_ADMIN_DB_PASSWORD}, the pepper from
 * {@code NEXUS_DEVICE_KEY_PEPPER} (or {@code NEXUS_DEVICE_KEY_PEPPER_FILE}); none is ever an argument. A new key is printed once, or written once to
 * a new owner-only file ({@code --output-file}) that is never overwritten; it is never stored, logged or echoed again. The tool uses no logger.
 *
 * <pre>
 *   create --device &lt;uuid&gt; [--output-file &lt;path&gt;]
 *   rotate --device &lt;uuid&gt; [--retire-after PT24H] [--output-file &lt;path&gt;]
 *   revoke --credential &lt;lookupId&gt;
 *   list   --device &lt;uuid&gt;
 *   audit  --device &lt;uuid&gt;
 * </pre>
 * Exit status: 0 done, 1 refused or failed (a fixed message, never a secret), 2 usage.
 */
public final class CredentialCli {
    private CredentialCli() {}

    public static void main(String[] args) {
        System.exit(run(args, System.getenv(), System.out, System.err, System.getProperty("user.name", "unknown")));
    }

    public static int run(String[] args, Map<String, String> env, PrintStream out, PrintStream err, String account) {
        if (args.length == 0) return usage(err);
        String operation = args[0];
        Map<String, String> options;
        try {
            options = options(args);
        } catch (IllegalArgumentException e) {
            return usage(err);
        }
        if (!Set.of("create", "rotate", "revoke", "list", "audit").contains(operation)) return usage(err);

        KeyHasher hasher;
        try {
            hasher = KeyHasher.fromEnvironment(env);
        } catch (IllegalStateException e) {
            err.println("refused: " + e.getMessage());
            return 1;
        }
        String url = env.get("NEXUS_ADMIN_DB_URL"), user = env.get("NEXUS_ADMIN_DB_USER"), password = env.get("NEXUS_ADMIN_DB_PASSWORD");
        if (url == null || user == null || password == null) {
            err.println("refused: set NEXUS_ADMIN_DB_URL, NEXUS_ADMIN_DB_USER and NEXUS_ADMIN_DB_PASSWORD");
            return 1;
        }
        CredentialAdmin admin = new CredentialAdmin(() -> DriverManager.getConnection(url, user, password), hasher, new SecureRandom(), account);
        try {
            switch (operation) {
                case "create" -> issue(() -> admin.create(device(options)), options.get("output-file"), out);
                case "rotate" -> {
                    Duration retire = options.containsKey("retire-after") ? Duration.parse(options.get("retire-after")) : CredentialAdmin.DEFAULT_RETIRE_AFTER;
                    UUID device = device(options);
                    issue(() -> admin.rotate(device, retire), options.get("output-file"), out);
                }
                case "revoke" -> {
                    admin.revoke(required(options, "credential"));
                    out.println("revoked");
                }
                case "list" -> {
                    List<CredentialAdmin.Row> rows = admin.list(device(options));
                    rows.forEach(r -> out.println(r.lookupId() + " " + r.status() + " created=" + r.createdAt() + " retire=" + r.retireAt() + " revoked=" + r.revokedAt() + " lastContact=" + r.lastValidContactAt()));
                    if (rows.isEmpty()) out.println("no credentials");
                }
                default -> {
                    List<CredentialAdmin.Event> events = admin.audit(device(options));
                    events.forEach(e -> out.println(e.occurredAt() + " " + e.eventType() + " " + e.lookupId() + " " + e.actor()));
                    if (events.isEmpty()) out.println("no events");
                }
            }
            return 0;
        } catch (CredentialAdmin.AdminException e) {
            err.println("refused: " + e.getMessage());
            return 1;
        } catch (IllegalArgumentException | java.time.format.DateTimeParseException e) {
            return usage(err);
        } catch (IOException e) {
            err.println("refused: the key file could not be created (it must not exist already); no credential was kept");
            return 1;
        }
    }

    /**
     * Issues a credential and delivers its key exactly once. With {@code --output-file} the NEW owner-only file is created BEFORE the credential
     * (so an existing file refuses the whole operation and no credential is created whose key could not be delivered); if the operation then
     * fails, the empty file is removed. The key is printed (or written) only after the transaction has committed.
     */
    private static void issue(java.util.function.Supplier<CredentialAdmin.Issued> operation, String outputFile, PrintStream out) throws IOException {
        if (outputFile == null) {
            CredentialAdmin.Issued issued = operation.get();
            out.println("credential " + issued.lookupId() + " created. The key is shown ONCE and cannot be recovered:");
            out.println(issued.key());
            return;
        }
        Path path = Path.of(outputFile);
        CredentialAdmin.Issued issued;
        try (OutputStream stream = createOwnerOnly(path)) {
            try {
                issued = operation.get();
            } catch (RuntimeException failure) {
                stream.close();
                Files.deleteIfExists(path);
                throw failure;
            }
            stream.write((issued.key() + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
        }
        out.println("credential " + issued.lookupId() + " created; the key was written once to " + path);
    }

    /** A NEW file readable by its owner only; an existing file is never overwritten (createFile fails if it exists). */
    static OutputStream createOwnerOnly(Path path) throws IOException {
        Path absolute = path.toAbsolutePath();
        if (java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            FileAttribute<Set<PosixFilePermission>> ownerOnly = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));
            Files.createFile(absolute, ownerOnly);
            return Files.newOutputStream(absolute, StandardOpenOption.WRITE);
        }
        Files.createFile(absolute);
        // The stream is opened BEFORE the owner-only ACL replaces the inherited one: an open handle keeps its access, which a restrictive ACL set
        // afterwards would otherwise deny to this very process on Windows.
        OutputStream stream = Files.newOutputStream(absolute, StandardOpenOption.WRITE);
        AclFileAttributeView acl = Files.getFileAttributeView(absolute, AclFileAttributeView.class);
        if (acl != null) {
            UserPrincipal owner = Files.getOwner(absolute);
            acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
                    .setPermissions(java.util.EnumSet.allOf(AclEntryPermission.class)).build()));
        }
        return stream;
    }

    private static Map<String, String> options(String[] args) {
        java.util.HashMap<String, String> map = new java.util.HashMap<>();
        for (int i = 1; i < args.length; i += 2) {
            if (!args[i].startsWith("--") || i + 1 >= args.length) throw new IllegalArgumentException();
            if (map.put(args[i].substring(2), args[i + 1]) != null) throw new IllegalArgumentException();
        }
        return map;
    }

    private static String required(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null) throw new IllegalArgumentException();
        return value;
    }

    private static UUID device(Map<String, String> options) {
        return UUID.fromString(required(options, "device"));
    }

    private static int usage(PrintStream err) {
        err.println("usage: nexus-credentials create|rotate|revoke|list|audit  (see the class documentation; the database login and the pepper come from the environment)");
        return 2;
    }
}
