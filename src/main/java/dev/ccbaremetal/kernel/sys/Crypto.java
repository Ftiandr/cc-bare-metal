package dev.ccbaremetal.kernel.sys;

import dev.ccbaremetal.kernel.Errno;
import dev.ccbaremetal.kernel.fs.FsException;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.EdECPrivateKey;
import java.security.interfaces.EdECPublicKey;
import java.security.interfaces.XECPrivateKey;
import java.security.interfaces.XECPublicKey;
import java.security.spec.EdECPoint;
import java.security.spec.EdECPrivateKeySpec;
import java.security.spec.EdECPublicKeySpec;
import java.security.spec.NamedParameterSpec;
import java.security.spec.XECPrivateKeySpec;
import java.security.spec.XECPublicKeySpec;

/**
 * Modern cryptography for guest programs (ssh), done by the JDK instead of WebAssembly: X25519 key agreement,
 * Ed25519 signatures, ChaCha20-Poly1305 encryption, SHA-256 and HMAC-SHA-256. Keys are the raw 32-byte encodings of
 * RFC 7748 / RFC 8032, as in OpenSSH and libsodium.
 */
public final class Crypto {
    private static final SecureRandom RANDOM = new SecureRandom();

    private Crypto() {
    }

    private static FsException fail(GeneralSecurityException e) {
        return new FsException(Errno.EINVAL, e.getMessage());
    }

    private static byte[] reverse(byte[] b) {
        var out = new byte[b.length];
        for (var i = 0; i < b.length; i++) out[i] = b[b.length - 1 - i];
        return out;
    }

    /** Little-endian, exactly 32 bytes. */
    private static byte[] le32(BigInteger v) {
        var be = v.toByteArray();
        var out = new byte[32];
        for (var i = 0; i < 32 && i < be.length; i++) out[i] = be[be.length - 1 - i];
        return out;
    }

    // ---- X25519 ----

    /** {private, public} */
    public static byte[][] x25519Keypair() throws FsException {
        try {
            var kp = KeyPairGenerator.getInstance("X25519").generateKeyPair();
            var priv = ((XECPrivateKey) kp.getPrivate()).getScalar().orElseThrow();
            var pub = le32(((XECPublicKey) kp.getPublic()).getU());
            return new byte[][]{priv, pub};
        } catch (GeneralSecurityException e) {
            throw fail(e);
        }
    }

    public static byte[] x25519(byte[] priv, byte[] peerPub) throws FsException {
        try {
            var kf = KeyFactory.getInstance("X25519");
            var privKey = kf.generatePrivate(new XECPrivateKeySpec(NamedParameterSpec.X25519, priv));
            var u = new BigInteger(1, reverse(peerPub));
            var pubKey = kf.generatePublic(new XECPublicKeySpec(NamedParameterSpec.X25519, u));
            var ka = KeyAgreement.getInstance("X25519");
            ka.init(privKey);
            ka.doPhase(pubKey, true);
            return ka.generateSecret();
        } catch (GeneralSecurityException e) {
            throw fail(e);
        }
    }

    // ---- Ed25519 ----

    /** {private seed, public} */
    public static byte[][] ed25519Keypair() throws FsException {
        try {
            var kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            var seed = ((EdECPrivateKey) kp.getPrivate()).getBytes().orElseThrow();
            return new byte[][]{seed, encode(((EdECPublicKey) kp.getPublic()).getPoint())};
        } catch (GeneralSecurityException e) {
            throw fail(e);
        }
    }

    /** RFC 8032 encoding: y little-endian, the top bit is x's parity. */
    private static byte[] encode(EdECPoint point) {
        var out = le32(point.getY());
        if (point.isXOdd()) out[31] |= (byte) 0x80;
        return out;
    }

    private static EdECPoint decode(byte[] pub) {
        var b = pub.clone();
        var xOdd = (b[31] & 0x80) != 0;
        b[31] &= 0x7f;
        return new EdECPoint(xOdd, new BigInteger(1, reverse(b)));
    }

    public static byte[] ed25519Sign(byte[] seed, byte[] message) throws FsException {
        try {
            var priv = KeyFactory.getInstance("Ed25519").generatePrivate(new EdECPrivateKeySpec(NamedParameterSpec.ED25519, seed));
            var sig = Signature.getInstance("Ed25519");
            sig.initSign(priv);
            sig.update(message);
            return sig.sign();
        } catch (GeneralSecurityException e) {
            throw fail(e);
        }
    }

    public static boolean ed25519Verify(byte[] pub, byte[] message, byte[] signature) {
        try {
            var key = KeyFactory.getInstance("Ed25519").generatePublic(new EdECPublicKeySpec(NamedParameterSpec.ED25519, decode(pub)));
            var sig = Signature.getInstance("Ed25519");
            sig.initVerify(key);
            sig.update(message);
            return sig.verify(signature);
        } catch (GeneralSecurityException | RuntimeException e) {
            return false;
        }
    }

    // ---- ChaCha20-Poly1305 (RFC 8439) ----

    public static byte[] seal(byte[] key, byte[] nonce, byte[] aad, byte[] plain) throws FsException {
        try {
            var c = Cipher.getInstance("ChaCha20-Poly1305");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "ChaCha20"), new IvParameterSpec(nonce));
            if (aad.length > 0) c.updateAAD(aad);
            return c.doFinal(plain);
        } catch (GeneralSecurityException e) {
            throw fail(e);
        }
    }

    /** The plaintext, or null when the message was tampered with (or the key is wrong). */
    public static byte[] open(byte[] key, byte[] nonce, byte[] aad, byte[] sealed) {
        try {
            var c = Cipher.getInstance("ChaCha20-Poly1305");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "ChaCha20"), new IvParameterSpec(nonce));
            if (aad.length > 0) c.updateAAD(aad);
            return c.doFinal(sealed);
        } catch (GeneralSecurityException e) {
            return null;
        }
    }

    // ---- Hashes ----

    public static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] hmacSha256(byte[] key, byte[] data) throws FsException {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.length == 0 ? new byte[1] : key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw fail(e);
        }
    }

    public static byte[] random(int n) {
        var out = new byte[n];
        RANDOM.nextBytes(out);
        return out;
    }
}
