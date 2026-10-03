package eu.siacs.conversations.ui.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class TimelineRefreshGateTest {
    @Test
    public void attachmentOnlyLifecycleRequiresNoAdapterWork() {
        assertEquals(
                TimelineRefreshGate.RefreshAction.NONE,
                TimelineRefreshGate.evaluate(false, false, 8L, 8L, 3L, 3L, 5L, 5L));
    }

    @Test
    public void transientPresentationChangeRequiresRebindOnly() {
        assertEquals(
                TimelineRefreshGate.RefreshAction.REBIND,
                TimelineRefreshGate.evaluate(false, false, 8L, 8L, 3L, 3L, 5L, 6L));
    }

    @Test
    public void timelineMutationAndStructuralNavigationRequireRebuild() {
        assertEquals(
                TimelineRefreshGate.RefreshAction.REBUILD,
                TimelineRefreshGate.evaluate(false, false, 8L, 9L, 3L, 3L, 5L, 5L));
        assertEquals(
                TimelineRefreshGate.RefreshAction.REBUILD,
                TimelineRefreshGate.evaluate(true, false, 8L, 8L, 3L, 3L, 5L, 5L));
    }

    @Test
    public void dynamicMucStatusTransitionRequiresRebuild() {
        assertEquals(
                TimelineRefreshGate.RefreshAction.REBUILD,
                TimelineRefreshGate.evaluate(false, false, 8L, 8L, 3L, 4L, 5L, 5L));
    }
}
