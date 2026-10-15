package im.majo.ctos;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Native-only synchronous loan; content arrays belong to the verified manifest. */
final class SshFileSnapshot {
    static final class Metadata {
        final VaultManifest.FileType type;
        final String id;
        final String path;
        final String identity;
        final int contentBytes;
        final List<String> references;

        private Metadata(VaultManifest.FileObject file) {
            type = file.type;
            id = file.id;
            path = file.path;
            identity = file.identity;
            contentBytes = file.content.length;
            references = VaultCollections.copyList(file.references);
        }
    }

    final String requestId;
    final String rootPath;
    final String connectionId;
    final UUID vaultId;
    final long generation;
    final long epoch;
    final long deadline;
    final List<Metadata> metadata;
    final List<VaultManifest.FileObject> publicFiles;
    private final byte[] snapshotBinding;

    SshFileSnapshot(VaultOperation operation, List<VaultManifest.FileObject> closure) {
        requestId = operation.requestId;
        rootPath = operation.resource;
        connectionId = operation.connectionId;
        vaultId = operation.vaultId;
        generation = operation.generation;
        epoch = operation.epoch;
        deadline = operation.deadline;
        snapshotBinding = operation.snapshotBinding.clone();
        List<Metadata> descriptions = new ArrayList<>();
        List<VaultManifest.FileObject> publicObjects = new ArrayList<>();
        for (VaultManifest.FileObject file : closure) {
            descriptions.add(new Metadata(file));
            if (file.type != VaultManifest.FileType.PRIVATE_KEY) publicObjects.add(file);
        }
        metadata = VaultCollections.copyList(descriptions);
        publicFiles = VaultCollections.copyList(publicObjects);
    }

    byte[] binding() { return snapshotBinding.clone(); }
}
