package eu.siacs.conversations.services;

import static org.junit.Assert.assertEquals;

import eu.siacs.conversations.Config;

import org.junit.Test;

public class MessageArchiveServicePagingPolicyTest {

    @Test
    public void automaticCatchupUsesFullUpstreamPageSizeForSecureMuc() {
        assertEquals(
                Config.PAGE_SIZE,
                MessageArchiveService.requestedPageSize(true, true, true));
    }

    @Test
    public void manualSecureMucHistoryKeepsBoundedPageSize() {
        assertEquals(
                Math.min(Config.PAGE_SIZE, Config.SECURE_MUC_MAM_PAGE_SIZE),
                MessageArchiveService.requestedPageSize(true, true, false));
    }

    @Test
    public void nonMucAndNonSecureQueriesUseFullPageSize() {
        assertEquals(
                Config.PAGE_SIZE,
                MessageArchiveService.requestedPageSize(false, true, false));
        assertEquals(
                Config.PAGE_SIZE,
                MessageArchiveService.requestedPageSize(true, false, false));
    }
}
