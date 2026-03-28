package ru.jsonspecs.compiler;

import java.util.List;

/** Compiled (pre-resolved) form of a condition artifact. */
public record CompiledCondition(
    String id,
    WhenExpr when,
    List<CompiledStep> steps
) {}
