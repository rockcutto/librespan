package eu.siacs.conversations.ui.preview;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** Small guard for the per-chat JPEG switch: placement, Material style, and instant save. */
public class JpegMenuUiTest {
    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";

    private static String read(String relativePath) throws Exception {
        return new String(Files.readAllBytes(Paths.get(relativePath)), StandardCharsets.UTF_8);
    }

    @Test public void singleMenuItemFollowsChatBackgroundWithIconAndPriority() throws Exception {
        final String path = "src/main/res/menu/fragment_conversation.xml";
        final String xml = read(path);
        final var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        final var document = factory.newDocumentBuilder().parse(path);
        final NodeList items = document.getElementsByTagName("item");
        Element jpeg = null;
        int count = 0;
        for (int i = 0; i < items.getLength(); ++i) {
            final Element item = (Element) items.item(i);
            if ("@+id/action_external_jpeg".equals(item.getAttributeNS(ANDROID_NS, "id"))) {
                jpeg = item;
                count++;
            }
        }
        assertEquals(1, count);
        assertNotNull(jpeg);
        assertSame(document.getDocumentElement(), jpeg.getParentNode());
        assertEquals("60", jpeg.getAttributeNS(ANDROID_NS, "orderInCategory"));
        assertEquals("@drawable/ic_visibility_24dp", jpeg.getAttributeNS(ANDROID_NS, "icon"));
        assertTrue(xml.indexOf("@+id/action_set_custom_bg") < xml.indexOf("@+id/action_external_jpeg"));
        assertTrue(xml.indexOf("@+id/action_external_jpeg") < xml.indexOf("@+id/conversation_menu_lifecycle"));
    }

    @Test public void dialogUsesSharedMaterialSwitchWithoutConfirmButtonsOrExplanatoryText() throws Exception {
        final String fragment = read("src/main/java/eu/siacs/conversations/ui/ConversationFragment.java");
        final int start = fragment.indexOf("private void showExternalJpegSettings()");
        final int end = fragment.indexOf("private void togglePinned()", start);
        assertTrue(start >= 0 && end > start);
        final String method = fragment.substring(start, end);
        assertTrue(method.contains("new MaterialAlertDialogBuilder(dialogActivity)"));
        assertTrue(method.contains("R.layout.item_binary_setting"));
        assertTrue(method.contains("toggle.setOnCheckedChangeListener"));
        assertTrue(method.contains("JpegPreferences.set("));
        assertTrue(method.contains("dialog.dismiss()"));
        assertFalse(method.contains("setPositiveButton"));
        assertFalse(method.contains("setNegativeButton"));
        assertFalse(method.contains("external_jpeg_privacy"));
        final String row = read("src/main/res/layout/item_binary_setting.xml");
        assertTrue(row.contains("materialswitch.MaterialSwitch"));
        assertTrue(row.contains("@style/Widget.App.Switch"));
        assertTrue(row.contains("@+id/binary_setting_title"));
    }
}
