package eu.siacs.conversations.persistance;

public class FilePathInfo extends FilePath {
    public boolean deleted;

    public FilePathInfo(String uuid, String path, boolean deleted) {
        super(uuid, path);
        this.deleted = deleted;
    }

    public boolean setDeleted(boolean deleted) {
        final boolean changed = deleted != this.deleted;
        this.deleted = deleted;
        return changed;
    }
}