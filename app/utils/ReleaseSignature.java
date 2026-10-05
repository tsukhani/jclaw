package utils;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.SignatureException;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Verifies the ECDSA signature Jenkins puts on a release's {@code SHA256SUMS}. {@code jclaw.sh upgrade}
 * runs it out of process on the installed tree's compiled classes alone, so it must stay on the JDK:
 * {@code ReleaseSignatureTest.theVerifierStandsAlone} pins that.
 */
public final class ReleaseSignature {

    private ReleaseSignature() {}

    /**
     * @param publicKeyPem an X.509 {@code PUBLIC KEY} PEM block holding an EC key
     * @param signature the ASN.1 DER signature {@code openssl dgst -sha256 -sign} writes
     * @return whether {@code signature} is that key's signature over {@code data}; a signature that is
     *     not well-formed DER is simply not one
     * @throws GeneralSecurityException when the key cannot be read
     * @throws IllegalArgumentException when the PEM body is not Base64
     */
    public static boolean verifies(String publicKeyPem, byte[] signature, byte[] data) throws GeneralSecurityException {
        var der = Base64.getMimeDecoder().decode(publicKeyPem.replaceAll("-----[A-Z ]+-----", ""));
        var verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(der)));
        verifier.update(data);
        try {
            return verifier.verify(signature);
        } catch (SignatureException _) {
            return false;
        }
    }

    /** Arguments {@code <public-key.pem> <signature> <signed file>}; returns 0 verified, 1 not, 2 could not check. */
    public static int run(String[] args, PrintStream err) {
        if (args.length != 3) {
            err.println("usage: ReleaseSignature <public-key.pem> <signature> <signed file>");
            return 2;
        }
        try {
            var pem = Files.readString(Path.of(args[0]), StandardCharsets.US_ASCII);
            return verifies(pem, Files.readAllBytes(Path.of(args[1])), Files.readAllBytes(Path.of(args[2]))) ? 0 : 1;
        } catch (IOException | GeneralSecurityException | IllegalArgumentException e) {
            err.println("ReleaseSignature: " + e);
            return 2;
        }
    }

    public static void main(String[] args) {
        System.exit(run(args, System.err));
    }
}
