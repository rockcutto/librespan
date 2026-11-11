package eu.siacs.conversations.ui.preview;

import static org.junit.Assert.*;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import okhttp3.HttpUrl;
import org.junit.Test;

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner.class)
@org.robolectric.annotation.Config(manifest = org.robolectric.annotation.Config.NONE, sdk = 34)
public class JpegPolicyTest {
    @Test public void firstEligibleLinkInTextAndStandalone() {
        assertEquals("https://example.org/a.JPG",JpegPolicy.first("photo https://example.org/a.JPG").toString());
        assertEquals("http://example.org/b.jpeg",JpegPolicy.first("http://example.org/b.jpeg").toString());
        assertEquals("/b.jpg",JpegPolicy.first("https://example.org/a.png https://example.org/b.jpg https://example.org/c.jpg").encodedPath());
    }
    @Test public void queryFragmentCredentialsAndPortsExcluded() {
        for(String url:List.of("https://example.org/a.jpg?token=x","https://example.org/a.jpg#x",
                "https://u:p@example.org/a.jpg","https://example.org:8443/a.jpg","file:///a.jpg")) assertNull(JpegPolicy.first(url));
    }
    @Test public void localAndSpecialIpv4AndIpv6AreBlocked() throws Exception {
        for(String ip:List.of("127.0.0.1","10.0.0.1","172.31.2.3","192.168.1.1","100.64.0.1",
                "169.254.169.254","192.0.2.1","198.18.0.1","224.0.0.1","0.0.0.0","::1","::",
                "fe80::1","fc00::1","ff02::1","2001:db8::1","2002:7f00:1::","::ffff:127.0.0.1"))
            assertFalse(ip,JpegPolicy.publicAddress(InetAddress.getByName(ip)));
        assertTrue(JpegPolicy.publicAddress(InetAddress.getByName("8.8.8.8")));
        assertTrue(JpegPolicy.publicAddress(InetAddress.getByName("2606:4700:4700::1111")));
    }
    @Test public void mixedDnsAnswerAndRebindingAreRejected() throws Exception {
        var publicIp=InetAddress.getByName("8.8.8.8"); var local=InetAddress.getByName("127.0.0.1");
        assertEquals(List.of(publicIp),JpegLoader.checkedDns("example.org",h->List.of(publicIp)));
        assertThrows(UnknownHostException.class,()->JpegLoader.checkedDns("example.org",h->List.of(publicIp,local)));
        assertThrows(UnknownHostException.class,()->JpegLoader.checkedDns("example.org",h->List.of(local)));
    }
    @Test public void imageDimensionsAreBoundedBeforeAllocation() {
        assertEquals(0,JpegPolicy.sample(65535,65535)); assertEquals(0,JpegPolicy.sample(0,20));
        assertEquals(16,JpegPolicy.sample(8000,4000));
        assertTrue((8000+JpegPolicy.sample(8000,4000)-1)/JpegPolicy.sample(8000,4000)<=512);
    }
    @Test public void truncatedOrNonJpegEnvelopeRejected() {
        assertFalse(JpegPolicy.jpegEnvelope(new byte[]{1,2,3,4}));
        assertFalse(JpegPolicy.jpegEnvelope(new byte[]{(byte)255,(byte)216,1,2}));
    }
}
