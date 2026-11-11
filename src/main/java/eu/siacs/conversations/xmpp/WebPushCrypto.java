package eu.siacs.conversations.xmpp;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** RFC 8291 / RFC 8188 single-record aes128gcm Web Push. No message data is logged. */
public final class WebPushCrypto {
    private static final int MAX_BODY = 4096;
    private static final byte[] KEY_INFO = "WebPush: info\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CEK_INFO = "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] NONCE_INFO = "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII);

    private WebPushCrypto() {}

    public static final class Keys {
        public final byte[] publicKey;
        public final byte[] privateKeyPkcs8;
        public final byte[] auth;

        private Keys(final byte[] publicKey, final byte[] privateKeyPkcs8, final byte[] auth) {
            this.publicKey = publicKey;
            this.privateKeyPkcs8 = privateKeyPkcs8;
            this.auth = auth;
        }
    }

    public static Keys generate() throws GeneralSecurityException {
        final KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"), new SecureRandom());
        final KeyPair pair = generator.generateKeyPair();
        final byte[] auth = new byte[16];
        new SecureRandom().nextBytes(auth);
        return new Keys(encodePublicKey((ECPublicKey) pair.getPublic()), pair.getPrivate().getEncoded(), auth);
    }

    private static ECParameterSpec curve() throws GeneralSecurityException {
        final AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
        params.init(new ECGenParameterSpec("secp256r1"));
        return params.getParameterSpec(ECParameterSpec.class);
    }

    public static PublicKey decodePublicKey(final byte[] encoded) throws GeneralSecurityException {
        if (encoded == null || encoded.length != 65 || encoded[0] != 4) {
            throw new GeneralSecurityException("Invalid P-256 point encoding");
        }
        final ECParameterSpec curve = curve();
        final BigInteger x = new BigInteger(1, Arrays.copyOfRange(encoded, 1, 33));
        final BigInteger y = new BigInteger(1, Arrays.copyOfRange(encoded, 33, 65));
        final BigInteger p = ((java.security.spec.ECFieldFp) curve.getCurve().getField()).getP();
        if (x.signum() < 0 || y.signum() < 0 || x.compareTo(p) >= 0 || y.compareTo(p) >= 0) {
            throw new GeneralSecurityException("P-256 point outside field");
        }
        final BigInteger lhs = y.modPow(BigInteger.valueOf(2), p);
        final BigInteger rhs = x.modPow(BigInteger.valueOf(3), p)
                .add(curve.getCurve().getA().multiply(x))
                .add(curve.getCurve().getB()).mod(p);
        if (!lhs.equals(rhs)) {
            throw new GeneralSecurityException("P-256 point not on curve");
        }
        return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x, y), curve));
    }

    public static byte[] encodePublicKey(final ECPublicKey key) throws GeneralSecurityException {
        final byte[] out = new byte[65];
        out[0] = 4;
        fixed(key.getW().getAffineX(), out, 1);
        fixed(key.getW().getAffineY(), out, 33);
        decodePublicKey(out);
        return out;
    }

    private static void fixed(final BigInteger value, final byte[] out, final int offset)
            throws GeneralSecurityException {
        final byte[] bytes = value.toByteArray();
        final int start = bytes.length == 33 && bytes[0] == 0 ? 1 : 0;
        if (bytes.length - start > 32) throw new GeneralSecurityException("Invalid coordinate");
        System.arraycopy(bytes, start, out, offset + 32 - (bytes.length - start), bytes.length - start);
    }

    public static byte[] decrypt(
            final byte[] body, final byte[] privateKeyPkcs8, final byte[] publicKey, final byte[] auth)
            throws GeneralSecurityException {
        if (body == null || body.length < 86 + 17 || body.length > MAX_BODY
                || privateKeyPkcs8 == null || auth == null || auth.length != 16) {
            throw new GeneralSecurityException("Invalid Web Push body or keys");
        }
        decodePublicKey(publicKey);
        final ByteBuffer header = ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN);
        final byte[] salt = new byte[16];
        header.get(salt);
        final long recordSize = header.getInt() & 0xffffffffL;
        if (header.get() != 65 || recordSize < 18 || header.remaining() < 65 + 17) {
            throw new GeneralSecurityException("Invalid aes128gcm header");
        }
        final byte[] serverKey = new byte[65];
        header.get(serverKey);
        final PublicKey serverPublic = decodePublicKey(serverKey);
        final int ciphertextLength = header.remaining();
        // RFC 8291 mandates exactly one (shorter than rs) record.
        if (ciphertextLength >= recordSize) {
            throw new GeneralSecurityException("Multiple Web Push records");
        }
        final PrivateKey ownPrivate = KeyFactory.getInstance("EC")
                .generatePrivate(new PKCS8EncodedKeySpec(privateKeyPkcs8));
        final KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(ownPrivate);
        agreement.doPhase(serverPublic, true);
        final byte[] shared = agreement.generateSecret();
        final byte[] keyInfo = concat(KEY_INFO, publicKey, serverKey);
        final byte[] ikm = hmac(hmac(auth, shared), concat(keyInfo, new byte[] {1}));
        final byte[] prk = hmac(salt, ikm);
        final byte[] cek = Arrays.copyOf(hmac(prk, concat(CEK_INFO, new byte[] {1})), 16);
        final byte[] nonce = Arrays.copyOf(hmac(prk, concat(NONCE_INFO, new byte[] {1})), 12);
        final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        final byte[] plaintext = cipher.doFinal(body, header.position(), ciphertextLength);
        Arrays.fill(shared, (byte) 0);
        Arrays.fill(ikm, (byte) 0);
        Arrays.fill(prk, (byte) 0);
        Arrays.fill(cek, (byte) 0);
        int end = plaintext.length - 1;
        while (end >= 0 && plaintext[end] == 0) end--;
        if (end < 0 || plaintext[end] != 2) {
            throw new GeneralSecurityException("Invalid Web Push padding");
        }
        final byte[] result = Arrays.copyOf(plaintext, end);
        Arrays.fill(plaintext, (byte) 0);
        return result;
    }

    private static byte[] hmac(final byte[] key, final byte[] data) throws GeneralSecurityException {
        final Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private static byte[] concat(final byte[]... parts) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (final byte[] part : parts) out.write(part, 0, part.length);
        return out.toByteArray();
    }
}
