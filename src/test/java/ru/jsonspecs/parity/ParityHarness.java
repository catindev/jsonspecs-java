package ru.jsonspecs.parity;

import ru.jsonspecs.*;
import ru.jsonspecs.operators.OperatorPack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class ParityHarness {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Usage: java ... ru.jsonspecs.parity.ParityHarness <compile-fail|runtime> <case-dir>");
            System.exit(2);
        }
        String mode = args[0];
        Path caseDir = Path.of(args[1]);
        Map<String, Object> out = switch (mode) {
            case "compile-fail" -> runCompileFail(caseDir);
            case "runtime" -> runRuntime(caseDir);
            default -> throw new IllegalArgumentException("Unsupported mode: " + mode);
        };
        System.out.println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(out));
    }

    private static Map<String, Object> runCompileFail(Path caseDir) {
        try {
            var artifacts = readArtifacts(caseDir.resolve("artifacts.json"));
            Engine.create(OperatorPack.standard()).compile(artifacts);
            return mapOf(
                    "kind", "compile-fail",
                    "compileOk", true,
                    "errors", List.of()
            );
        } catch (CompilationException e) {
            List<String> errors = new ArrayList<>(e.getErrors());
            Collections.sort(errors);
            return mapOf(
                    "kind", "compile-fail",
                    "compileOk", false,
                    "errors", errors
            );
        } catch (Exception e) {
            return mapOf(
                    "kind", "compile-fail",
                    "compileOk", false,
                    "errors", List.of(String.valueOf(e.getMessage()))
            );
        }
    }

    private static Map<String, Object> runRuntime(Path caseDir) {
        try {
            var artifacts = readArtifacts(caseDir.resolve("artifacts.json"));
            var engine = Engine.create(OperatorPack.standard());
            var compiled = engine.compile(artifacts);
            var payload = readObject(caseDir.resolve("payload.json"));
            String pipelineId = selectPipelineId(artifacts, caseDir);
            PipelineResult result = engine.runPipeline(compiled, pipelineId, payload);
            return mapOf(
                    "kind", "runtime",
                    "compileOk", true,
                    "pipelineId", pipelineId,
                    "status", result.getStatus().name(),
                    "control", result.getControl().name(),
                    "issues", normalizeIssues(result.getIssues())
            );
        } catch (CompilationException e) {
            List<String> errors = new ArrayList<>(e.getErrors());
            Collections.sort(errors);
            return mapOf(
                    "kind", "runtime",
                    "compileOk", false,
                    "errors", errors
            );
        } catch (Exception e) {
            return mapOf(
                    "kind", "runtime",
                    "compileOk", false,
                    "errors", List.of(String.valueOf(e.getMessage()))
            );
        }
    }

    private static List<Map<String, Object>> readArtifacts(Path path) throws IOException {
        return MAPPER.readValue(Files.readString(path), new TypeReference<>() {});
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readObject(Path path) throws IOException {
        return MAPPER.readValue(Files.readString(path), new TypeReference<>() {});
    }

    private static String selectPipelineId(List<Map<String, Object>> artifacts, Path caseDir) throws IOException {
        Path explicit = caseDir.resolve("pipeline-id.txt");
        if (Files.exists(explicit)) {
            return Files.readString(explicit).trim();
        }
        for (Map<String, Object> artifact : artifacts) {
            if (Objects.equals("pipeline", artifact.get("type")) && Boolean.TRUE.equals(artifact.get("entrypoint"))) {
                return String.valueOf(artifact.get("id"));
            }
        }
        for (Map<String, Object> artifact : artifacts) {
            if (Objects.equals("pipeline", artifact.get("type"))) {
                return String.valueOf(artifact.get("id"));
            }
        }
        throw new IllegalArgumentException("No pipeline artifact found");
    }

    private static List<Map<String, Object>> normalizeIssues(List<Issue> issues) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Issue i : issues) {
            out.add(mapOf(
                    "level", i.getLevel(),
                    "code", i.getCode(),
                    "message", i.getMessage(),
                    "field", i.getField()
            ));
        }
        out.sort(Comparator.comparing(m -> MAPPER.valueToTree(m).toString()));
        return out;
    }

    private static Map<String, Object> mapOf(Object... pairs) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return m;
    }
}
