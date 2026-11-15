import cn.qingye.business.*;
import cn.qingye.db.*;
import cn.qingye.model.*;
import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Experiment-only adapter from frozen strategy-A classifications to Qingye's
 * unchanged single-item Gate 3 compiler and Gate 4 fixed business reads.
 *
 * <p>The adapter does not call a model, reinterpret Chinese, or grant batch
 * execution authority.  Each frozen LOCATE item is independently compiled by
 * {@link AssistantContract#directoryQuery(AssistantHint, String)} and an
 * accepted plan is submitted through {@link AssistantService#query}.</p>
 *
 * <p>This class deliberately reuses the frozen H2 fixture and release-boundary
 * assertions from FourGateProbe.  The runner compiles both sources against the
 * current checkout and records their hashes in the evidence manifest.</p>
 */
public final class AssistantClassifierAReplayProbe {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    private static Map<String,Object> itemMap(JsonNode item) {
        return JSON.convertValue(item, new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>() {});
    }

    private static AssistantHint hint(JsonNode item) {
        return JSON.convertValue(item, AssistantHint.class);
    }

    private static Map<String,Object> compileRecord(JsonNode item, AssistantContract.DirectoryCompilation compiled) {
        var result = new LinkedHashMap<String,Object>();
        result.put("item", itemMap(item));
        result.put("accepted", compiled.accepted());
        if (compiled.accepted()) result.put("plan", compiled.query());
        else result.put("failure", compiled.failure().name());
        return result;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("usage: <business-review.json> <output-directory>");
        Path input = Path.of(args[0]);
        Path output = Path.of(args[1]);
        Files.createDirectories(output);
        Path results = output.resolve("results.jsonl");
        if (Files.exists(results)) throw new IllegalStateException("refuse to overwrite " + results);

        JsonNode review = JSON.readTree(Files.readString(input, StandardCharsets.UTF_8));
        JsonNode cases = review.path("cases");
        if (!cases.isArray() || cases.size() != 1564) throw new IllegalStateException("expected 1564 frozen provider tasks");

        FourGateProbe.fixture();
        var initialSnapshot = FourGateProbe.snapshot();
        FourGateProbe.write(output.resolve("fixture.json"), initialSnapshot);
        FourGateProbe.write(output.resolve("references.json"), Map.of(
            "references", FourGateProbe.references,
            "missingFacts", FourGateProbe.missingFacts,
            "clock", FourGateProbe.BASE_TIME.toString(),
            "actor", FourGateProbe.ACTOR
        ));

        int complete = 0;
        try (var writer = Files.newBufferedWriter(results, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
            for (JsonNode task : cases) {
                // A fresh service keeps each original user task independent and
                // prevents the in-memory confirmation receipt cap from turning
                // a 1564-task evidence run into a product failure.
                var clock = new FourGateProbe.ProbeClock();
                var planner = new FourGateProbe.ObservedPlanner(clock, "http://127.0.0.1:1", "frozen-no-model-call");
                var capacity = new LoanService(FourGateProbe.loans, FourGateProbe.activities,
                    new Access(new UserStore(FourGateProbe.sql)), new MessageStore(FourGateProbe.sql), clock);
                var service = new AssistantService(FourGateProbe.activities, FourGateProbe.loans, capacity, planner, clock);
                var row = new LinkedHashMap<String,Object>();
                String provider = task.path("provider").asText();
                String caseId = task.path("caseId").asText();
                String modelInput = task.path("modelInput").asText();
                row.put("provider", provider);
                row.put("caseId", caseId);
                row.put("userTask", task.path("userTask").asText());
                row.put("modelInput", modelInput);
                row.put("expected", JSON.convertValue(task.path("expected"), Object.class));
                row.put("initialBusiness", JSON.convertValue(task.path("initial"), Object.class));
                row.put("initialClassification", task.path("initialClassification").isMissingNode() || task.path("initialClassification").isNull()
                    ? null : JSON.convertValue(task.path("initialClassification"), Object.class));

                var compilations = new ArrayList<Map<String,Object>>();
                var sections = new ArrayList<Map<String,Object>>();
                var stopItems = new ArrayList<Map<String,Object>>();
                String nativeError = null;
                boolean subject = true, fields = true, canary = true;
                try {
                    String intakeFailure = AssistantIntake.reject(
                        FourGateProbe.ACTOR,
                        "classifier-a-replay-" + provider + "-" + caseId,
                        List.of(modelInput),
                        null
                    );
                    row.put("intakeFailure", intakeFailure);
                    JsonNode classification = task.path("initialClassification");
                    if (intakeFailure == null && !classification.isMissingNode() && !classification.isNull()) {
                        String control = classification.path("control").asText();
                        row.put("control", control);
                        // OVERFLOW means the whole task exceeds the supported
                        // four-item boundary.  The accompanying preview items
                        // are evidence for that decision, not partial execution
                        // grants.
                        if (!"DONE".equals(control)) {
                            row.put("controlItems", JSON.convertValue(classification.path("items"), Object.class));
                        } else
                        for (JsonNode item : classification.path("items")) {
                            if (!"LOCATE".equals(item.path("action").asText())) {
                                stopItems.add(itemMap(item));
                                continue;
                            }
                            AssistantContract.DirectoryCompilation compiled = AssistantContract.directoryQuery(hint(item), modelInput);
                            var compilation = compileRecord(item, compiled);
                            compilations.add(compilation);
                            if (!compiled.accepted()) continue;

                            String session = "classifier-a-replay-" + provider + "-" + caseId + "-" + sections.size();
                            Map<String,Object> result = service.query(FourGateProbe.ACTOR, compiled.query(), session);
                            List<Map<String,Object>> pages = FourGateProbe.pages(service, result, session);
                            if (!pages.isEmpty()) result = pages.get(pages.size() - 1);
                            var section = new LinkedHashMap<String,Object>();
                            section.put("item", itemMap(item));
                            section.put("plan", compiled.query());
                            section.put("result", result);
                            section.put("pages", pages);
                            sections.add(section);
                            for (Map<String,Object> page : pages) {
                                subject &= FourGateProbe.privateSubject(page);
                                fields &= FourGateProbe.fieldsAllowed(page);
                                canary &= !JSON.writeValueAsString(page).contains(FourGateProbe.PRIVATE_CANARY);
                            }
                        }
                    }
                } catch (Exception failure) {
                    nativeError = failure.getClass().getSimpleName() + ": " + failure.getMessage();
                }
                boolean readonly = initialSnapshot.equals(FourGateProbe.snapshot());
                row.put("compilations", compilations);
                row.put("sections", sections);
                row.put("stopItems", stopItems);
                row.put("nativeError", nativeError);
                row.put("security", Map.of("readonly", readonly, "subject", subject, "fields", fields, "canary", canary));
                writer.write(JSON.writeValueAsString(row));
                writer.newLine();
                complete++;
                if (!readonly || !subject || !fields || !canary) throw new IllegalStateException("boundary failure at " + provider + "/" + caseId);
            }
        }
        FourGateProbe.write(output.resolve("probe-summary.json"), Map.of(
            "state", "COMPLETED",
            "tasks", complete,
            "modelCalls", 0,
            "source", input.toString(),
            "thirdGate", "AssistantContract.directoryQuery unchanged",
            "fourthGate", "AssistantService.query fixed business read unchanged"
        ));
    }
}
