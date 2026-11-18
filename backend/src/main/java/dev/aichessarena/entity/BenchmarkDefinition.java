package dev.aichessarena.entity;

import java.util.List;
import java.util.UUID;

/** Immutable conditions and matchups captured when a benchmark run is created. */
public record BenchmarkDefinition(
        String suite, List<Opening> openings, List<String> modelIds, List<Integer> targetStrengths,
        List<String> modelColors, String engineSha256, List<Network> engineNetworks, int searchNodes, int threads, int hashMiB,
        String promptVersion, Rerun rerun
) {
    public BenchmarkDefinition {
        engineNetworks = engineNetworks == null ? List.of() : List.copyOf(engineNetworks);
    }

    public record Rerun(UUID baseId, String setting, String previousModelId, String value) {}

    public record Network(String name, String sha256) {}

    public record Opening(String id, String name, List<String> moves) {}
}
