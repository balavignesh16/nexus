package com.nexus.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs the standalone Python validator (the schema authority) once, in one bounded {@code python -I} batch with no network, over a corpus
 * manifest, and returns its per-item results. A missing interpreter or validator fails the caller; it is never skipped.
 */
public final class PythonOracle {
    private static final ObjectMapper JSON = new ObjectMapper();

    private PythonOracle() {}

    public static Path repositoryRoot() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null && !Files.isRegularFile(directory.resolve("contracts/versions.json"))) directory = directory.getParent();
        if (directory == null) throw new AssertionError("contracts/versions.json not found above the working directory");
        return directory;
    }

    public static Map<String, JsonNode> validateCorpus(Path manifest) throws Exception {
        Path sdk = repositoryRoot().resolve("sdk/python");
        Path output = Files.createTempFile("nexus-contract-oracle", ".json");
        String boot = "import sys; sys.path.insert(0, sys.argv.pop(1)); from nexus_contract.cli import main; raise SystemExit(main(sys.argv[1:]))";
        String python = System.getenv().getOrDefault("NEXUS_PYTHON", "python");
        ProcessBuilder builder = new ProcessBuilder(python, "-I", "-c", boot, sdk.toString(), "validate-corpus", "--manifest", manifest.toString(), "--output", output.toString());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String log = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("the Python validator did not finish within 120 s");
        }
        if (process.exitValue() != 0) throw new AssertionError("python validator failed (a missing interpreter or package fails the gate): " + log);
        Map<String, JsonNode> results = new LinkedHashMap<>();
        JSON.readTree(Files.readAllBytes(output)).get("results").fields().forEachRemaining(e -> results.put(e.getKey(), e.getValue()));
        Files.deleteIfExists(output);
        return results;
    }

    /** Runs a short Python expression program and returns its standard output (used for independent known-answer vectors). */
    public static String run(String program) throws Exception {
        String python = System.getenv().getOrDefault("NEXUS_PYTHON", "python");
        Process process = new ProcessBuilder(python, "-I", "-c", program).redirectErrorStream(true).start();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) throw new AssertionError("python failed: " + out);
        return out;
    }
}
