package eu.siacs.conversations.ui.preview;

import static org.junit.Assert.*;

import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.storage.secure.SecureMessagePayloadMode;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.w3c.dom.Element;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=34)
public class JpegIntegrationTest {
    private static final String URL="https://example.org/a.jpg";
    private static final class TextMessage extends Message {
        TextMessage() {super(null);setBody(URL);}
    }
    @Test public void verifiedProtectedTextNeedsCurrentPermitAndUnavailableNeverFallsBack() {
        JpegLoader loader=new JpegLoader(request->{throw new AssertionError("no network");});
        JpegLoader.Permit permit=loader.permitForTests();
        TextMessage message=new TextMessage();
        assertNotNull(JpegPolicy.firstReadable(message,permit));
        message.setSecureMessagePayloadMode(SecureMessagePayloadMode.PROTECTED);
        assertNull(JpegPolicy.firstReadable(message,permit));
        message.setVerifiedProtectedBody(URL);
        assertNotNull(JpegPolicy.firstReadable(message,permit));
        assertNull(JpegPolicy.firstReadable(message,null));
        loader.invalidateForPrivacy();
        assertNull(JpegPolicy.firstReadable(message,permit));
        permit=loader.permitForTests();
        assertNotNull(JpegPolicy.firstReadable(message,permit));
        for(SecureMessagePayloadMode mode:new SecureMessagePayloadMode[]{
                SecureMessagePayloadMode.PENDING,SecureMessagePayloadMode.UNAVAILABLE}) {
            message.setSecureMessagePayloadMode(mode);
            // Even a stale String in the presentation slot cannot bypass the mode check.
            message.setBody(URL);
            assertNull(JpegPolicy.firstReadable(message,permit));
        }
        message.setSecureMessagePayloadMode(SecureMessagePayloadMode.PROTECTED);
        message.clearVerifiedProtectedBody();
        assertNull(JpegPolicy.firstReadable(message,permit));
        loader.closeForTests();
    }
    @Test public void adapterBindsOnceAndUsesProtectedContentGuard() throws Exception {
        String adapter=new String(Files.readAllBytes(Path.of("src/main/java/eu/siacs/conversations/ui/adapter/MessageAdapter.java")), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(1,adapter.split("viewHolder\\.externalJpeg\\.bind\\(",-1).length-1);
        assertTrue(adapter.contains("JpegPolicy.firstReadable(message, previewAccess)"));
    }
    @Test public void exactlyOnePreviewMenuEntryAtRootForBothChatTypes() throws Exception {
        var factory=DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);
        var document=factory.newDocumentBuilder().parse("src/main/res/menu/fragment_conversation.xml");
        var items=document.getElementsByTagName("item");int count=0;
        for(int i=0;i<items.getLength();i++) {
            Element item=(Element)items.item(i);
            if("@+id/action_external_jpeg".equals(item.getAttributeNS("http://schemas.android.com/apk/res/android","id"))) {
                count++;assertSame(document.getDocumentElement(),item.getParentNode());
                assertNotEquals("false",item.getAttributeNS("http://schemas.android.com/apk/res/android","visible"));
                assertNotEquals("false",item.getAttributeNS("http://schemas.android.com/apk/res/android","enabled"));
            }
        }
        assertEquals(1,count);
    }
    @Test public void localizedSettingsContainOneToggleAndNoHttpPermission() throws Exception {
        for(String locale:new String[]{"values","values-ru","values-de"}) {
            String xml=new String(Files.readAllBytes(Path.of("src/main/res",locale,"strings.xml")), java.nio.charset.StandardCharsets.UTF_8);
            assertFalse(xml.contains("external_jpeg_http"));
            assertTrue(xml.contains("name=\"external_jpeg_enable\""));
            assertTrue(xml.contains("HTTPS"));
        }
        String preferences=new String(Files.readAllBytes(Path.of("src/main/java/eu/siacs/conversations/ui/preview/JpegPreferences.java")), java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(preferences.contains(".http"));
    }
}
