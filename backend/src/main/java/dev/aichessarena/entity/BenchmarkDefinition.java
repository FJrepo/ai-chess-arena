package dev.aichessarena.entity;

import java.util.List;

/** Immutable conditions and matchups captured when a benchmark run is created. */
public record BenchmarkDefinition(
        String suite, List<Opening> openings, List<String> modelIds, List<Integer> targetStrengths,
        List<String> modelColors, String engineSha256, int searchNodes, int threads, int hashMiB
) {
    public record Opening(String id, String name, List<String> moves) {}
}
