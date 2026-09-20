package com.pluginfence.bootstrap;

/**
 * The kind of sensitive runtime operation intercepted by the agent.
 * Kept deliberately coarse: finer classification (sensitive file, project file,
 * secret environment variable, ...) is done by the policy engine in the IDE plugin.
 */
public enum OperationType {
    FILE_READ("File read"),
    FILE_WRITE("File write"),
    ENV_READ("Environment read"),
    ENV_ENUMERATE("Environment enumeration"),
    PROCESS_EXEC("Process execution"),
    NETWORK_CONNECT("Network connection");

    private final String displayName;

    OperationType(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
