package im.majo.ctos;

/** Host-owned capability declaration; no arbitrary Root command or file access. */
final class ToolExecutionContext {
    enum Requirement { APP, ROOT }
    enum Operation {
        HFTP_SERVICE("hftp", Requirement.APP),
        HFTP_NETWORK_RELAY("hftp.networkRelay", Requirement.ROOT);
        final String id;
        final Requirement requirement;
        Operation(String id, Requirement requirement) { this.id = id; this.requirement = requirement; }
    }

    final Operation operation;
    final Requirement requirement;
    private ToolExecutionContext(Operation operation) {
        this.operation = operation;
        requirement = operation.requirement;
    }

    static ToolExecutionContext declare(String id, Requirement requirement) {
        if (id == null || requirement == null) throw new IllegalArgumentException("An operation and capability requirement are required");
        for (Operation operation : Operation.values()) {
            if (!operation.id.equals(id)) continue;
            if (operation.requirement != requirement) throw new IllegalArgumentException("The declared operation and capability requirement do not match");
            return new ToolExecutionContext(operation);
        }
        throw new IllegalArgumentException("Unknown tool operation");
    }

    void requireRootRelay() {
        if (operation != Operation.HFTP_NETWORK_RELAY || requirement != Requirement.ROOT)
            throw new IllegalArgumentException("Only hftp.networkRelay may use the Root operation adapter");
    }
}
