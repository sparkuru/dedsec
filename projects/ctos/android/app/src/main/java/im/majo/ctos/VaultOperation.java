package im.majo.ctos;

import java.util.UUID;

/** Opaque module-only capability. There is no MethodChannel constructor or serialization. */
final class VaultOperation {
    enum Consumer { TOTP, SSH }
    enum Purpose { TOTP_CODE, TOTP_SAVE, TOTP_DELETE, SSH_CREDENTIAL, SSH_FILES, SSH_PRIVATE_KEY,
        SSH_SAVE, SSH_DELETE, SSH_TRUST_UPDATE, SSH_BUNDLE_WRITE }
    final String requestId = UUID.randomUUID().toString();
    final Consumer consumer;
    final Purpose purpose;
    final String resource;
    final String target;
    final String connectionId;
    final UUID vaultId;
    final long generation;
    final long epoch;
    final long deadline;
    final byte[] snapshotBinding;
    boolean consumed;
    volatile boolean cancelled;

    VaultOperation(Consumer consumer, Purpose purpose, String resource, String target,
            String connectionId, VaultEnvelope envelope, long epoch, long deadline) {
        this.consumer = consumer;
        this.purpose = purpose;
        this.resource = resource;
        this.target = target;
        this.connectionId = connectionId;
        vaultId = envelope.id;
        generation = envelope.generation;
        snapshotBinding = envelope.binding();
        this.epoch = epoch;
        this.deadline = deadline;
    }
}
