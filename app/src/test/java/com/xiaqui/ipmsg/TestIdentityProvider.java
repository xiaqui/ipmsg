package com.xiaqui.ipmsg;

import java.security.*;
import java.security.cert.Certificate;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;

/** JVM fixture only: MainActivity exercises its real identity-loading path without device Keystore. */
public final class TestIdentityProvider extends Provider {
    public TestIdentityProvider() {
        super("IpmsgTestIdentity", 1.0, "Synthetic identity for lifecycle tests");
        put("KeyStore.AndroidKeyStore", Keys.class.getName());
    }
    public static final class Keys extends KeyStoreSpi {
        private static final KeyPair PAIR;
        static {
            try {
                KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
                generator.initialize(2048);
                PAIR = generator.generateKeyPair();
            } catch (GeneralSecurityException error) { throw new AssertionError(error); }
        }
        private final Certificate certificate = new Certificate("TEST") {
            public byte[] getEncoded() { return PAIR.getPublic().getEncoded(); }
            public void verify(PublicKey key) { }
            public void verify(PublicKey key, String provider) { }
            public String toString() { return "synthetic test certificate"; }
            public PublicKey getPublicKey() { return PAIR.getPublic(); }
        };
        public Key engineGetKey(String alias, char[] password) { return PAIR.getPrivate(); }
        public Certificate[] engineGetCertificateChain(String alias) { return new Certificate[]{certificate}; }
        public Certificate engineGetCertificate(String alias) { return certificate; }
        public Date engineGetCreationDate(String alias) { return new Date(0); }
        public void engineSetKeyEntry(String a, Key k, char[] p, Certificate[] c) { throw new UnsupportedOperationException(); }
        public void engineSetKeyEntry(String a, byte[] k, Certificate[] c) { throw new UnsupportedOperationException(); }
        public void engineSetCertificateEntry(String a, Certificate c) { throw new UnsupportedOperationException(); }
        public void engineDeleteEntry(String alias) { throw new UnsupportedOperationException(); }
        public Enumeration<String> engineAliases() { return Collections.enumeration(Collections.singleton("ipmsg-identity-v1")); }
        public boolean engineContainsAlias(String alias) { return alias.equals("ipmsg-identity-v1"); }
        public int engineSize() { return 1; }
        public boolean engineIsKeyEntry(String alias) { return true; }
        public boolean engineIsCertificateEntry(String alias) { return false; }
        public String engineGetCertificateAlias(Certificate cert) { return "ipmsg-identity-v1"; }
        public void engineStore(java.io.OutputStream stream, char[] password) { }
        public void engineLoad(java.io.InputStream stream, char[] password) { }
    }
}
