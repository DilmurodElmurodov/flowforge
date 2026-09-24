package com.flowforge.modules.workflow.model;

/** Where an execution goes after a step outcome: either the next step or the terminal state. */
public sealed interface Transition {

    record Advance(int stepIndex) implements Transition {
    }

    record Complete() implements Transition {
    }

    Transition COMPLETE = new Complete();

    static Transition advance(int stepIndex) {
        return new Advance(stepIndex);
    }
}
