package eu.siacs.conversations.xmpp;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPrivateKeySpec;
import java.util.Base64;
import org.junit.Test;

public class WebPushCryptoTest {
    // RFC 8291 §5 independent published ciphertext and keys.
    private static final String BODY =
            "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27ml"
                    + "mlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPT"
                    + "pK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN";
    private static final String PUBLIC =
            "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4";

    private static byte[] b64(final String value) {
        return Base64.getUrlDecoder().decode(value);
    }

    private static byte[] privateKey() throws Exception {
        final AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
        params.init(new ECGenParameterSpec("secp256r1"));
        final ECParameterSpec curve = params.getParameterSpec(ECParameterSpec.class);
        return KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(
                new BigInteger(1, b64("q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94")), curve)).getEncoded();
    }

    @Test
    public void decryptsPublishedRfc8291Vector() throws Exception {
        final byte[] decrypted = WebPushCrypto.decrypt(
                b64(BODY), privateKey(), b64(PUBLIC), b64("BTBZMqHH6r4Tts7J_aSIgg"));
        assertEquals("When I grow up, I want to be a watermelon",
                new String(decrypted, StandardCharsets.UTF_8));
    }

    @Test
    public void rejectsTamperingInvalidCurveAndExtraRecords() throws Exception {
        final byte[] body = b64(BODY);
        body[body.length - 1] ^= 1;
        rejected(() -> WebPushCrypto.decrypt(body, privateKey(), b64(PUBLIC),
                b64("BTBZMqHH6r4Tts7J_aSIgg")));
        final byte[] invalid = new byte[65];
        invalid[0] = 4;
        rejected(() -> WebPushCrypto.decodePublicKey(invalid));
        final byte[] longBody = b64(BODY);
        longBody[16] = 0;
        longBody[17] = 0;
        longBody[18] = 0;
        longBody[19] = 18;
        rejected(() -> WebPushCrypto.decrypt(longBody, privateKey(), b64(PUBLIC),
                b64("BTBZMqHH6r4Tts7J_aSIgg")));
    }

    @Test
    public void generatedKeysHaveValidPublicPointAndAuthLength() throws Exception {
        final WebPushCrypto.Keys keys = WebPushCrypto.generate();
        assertEquals(65, keys.publicKey.length);
        assertEquals(16, keys.auth.length);
        WebPushCrypto.decodePublicKey(keys.publicKey);
    }

    private interface CryptoAction { void run() throws Exception; }

    private static void rejected(final CryptoAction action) throws Exception {
        try {
            action.run();
            fail("Expected cryptographic failure");
        } catch (final GeneralSecurityException expected) {
            // Fail closed.
        }
    }
}
