package io.jsonspecs.compiler;

import java.util.List;
import java.util.Map;

/** A resolved step in a compiled pipeline or condition. */
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
