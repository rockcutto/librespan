package eu.siacs.conversations.ui.util;

public final class TimelineRefreshGate {
    private TimelineRefreshGate() {}

    public enum RefreshAction {
        NONE,
        REBIND,
        REBUILD
    }

    public static RefreshAction evaluate(
            boolean structuralStateChanged,
            boolean conversationChanged,
            long renderedRevision,
            long currentRevision,
            long renderedDynamicRevision,
            long currentDynamicRevision,
            long renderedPresentationRevision,
            long currentPresentationRevision) {
        if (structuralStateChanged
                || conversationChanged
                || renderedRevision != currentRevision
                || renderedDynamicRevision != currentDynamicRevision) {
            return RefreshAction.REBUILD;
        }
        if (renderedPresentationRevision != currentPresentationRevision) {
            return RefreshAction.REBIND;
        }
        return RefreshAction.NONE;
    }
}
