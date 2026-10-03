package eu.siacs.conversations.persistance;

import java.util.UUID;

public class FilePath {
    public final UUID uuid;
    public final String path;

    public FilePath(String uuid, String path) {
        this.uuid = UUID.fromString(uuid);
        this.path = path;
    }
}
