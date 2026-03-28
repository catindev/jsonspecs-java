package ru.jsonspecs.compiler;

import java.util.List;
import java.util.Map;

/** A resolved step in a compiled pipeline or condition. *
 * <p><b>Internal API.</b> This class is an implementation detail of the jsonspecs engine.
 * It is not part of the stable public API and may change without notice between versions.
 * Use {@link ru.jsonspecs.Engine} as the only entry point.
 */
public sealed interface CompiledStep
    permits CompiledStep.RuleStep, CompiledStep.PipelineStep, CompiledStep.ConditionStep {

    record RuleStep(
        String ruleId,
        String stepId,
        String ref
    ) implements CompiledStep {}

    record PipelineStep(
        String pipelineId,
        String stepId
    ) implements CompiledStep {}

    record ConditionStep(
        String conditionId,
        String stepId
    ) implements CompiledStep {}
}
