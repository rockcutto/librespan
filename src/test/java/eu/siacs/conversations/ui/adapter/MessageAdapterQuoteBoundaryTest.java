package eu.siacs.conversations.ui.adapter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MessageAdapterQuoteBoundaryTest {

    private static final int QUOTE_END = "quote".length();

    @Test
    public void zeroOneTwoOrThreeBreaksNormalizeToSameCompactSeparator() {
        assertNormalized("quotetext");
        assertNormalized("quote\ntext");
        assertNormalized("quote\n\ntext");
        assertNormalized("quote\n\n\ntext");
    }

    @Test
    public void existingDoubleBreakStillGetsNormalizationPlan() {
        final MessageAdapter.QuoteBoundaryPlan plan =
                MessageAdapter.trailingQuoteBoundaryPlan("quote\n\ntext", QUOTE_END);

        assertTrue(plan.applies());
        assertEquals(QUOTE_END, plan.replaceStart);
        assertEquals(QUOTE_END + 2, plan.replaceEnd);
    }

    @Test
    public void quoteAtEndDoesNotGainTrailingWhitespace() {
        assertNoPlan("quote");
        assertNoPlan("quote\n");
        assertNoPlan("quote\n\n\n");
    }

    private static void assertNormalized(final String source) {
        final MessageAdapter.QuoteBoundaryPlan plan =
                MessageAdapter.trailingQuoteBoundaryPlan(source, QUOTE_END);

        assertTrue(plan.applies());
        final String normalized =
                new StringBuilder(source)
                        .replace(plan.replaceStart, plan.replaceEnd, "\n\n")
                        .toString();
        assertEquals("quote\n\ntext", normalized);
    }

    private static void assertNoPlan(final String source) {
        final MessageAdapter.QuoteBoundaryPlan plan =
                MessageAdapter.trailingQuoteBoundaryPlan(source, QUOTE_END);
        assertFalse(plan.applies());
    }
}
