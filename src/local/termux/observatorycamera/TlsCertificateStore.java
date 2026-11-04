/*
 * Copyright (C) 2026 David González López-Tercero
 * SPDX-License-Identifier: GPL-3.0-or-later
 * See LICENSE in the repository root for licensing details.
 */

package local.termux.observatorycamera;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/** Loads an explicitly provisioned app-private PKCS#12 server identity; startup never creates one. */
final class TlsCertificateStore {
    static final String KEYSTORE_FILE = "https-server.p12";
    static final String PASSWORD_FILE = "https-server.password";
    static final String IMPORT_KEYSTORE_FILE = "https-server.import.p12";
    static final String IMPORT_PASSWORD_FILE = "https-server.import.password";
    static final String PUBLIC_CERTIFICATE_FILE = "https-server.pem";
    static final int MAX_KEYSTORE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_PASSWORD_BYTES = 256;
    private static final Object IMPORT_LOCK = new Object();

    static final class Identity {
        final SSLContext context;
        final X509Certificate certificate;
        final String fingerprint;
        final String expiresAt;

        Identity(SSLContext context, X509Certificate certificate, String fingerprint, String expiresAt) {
            this.context = context;
            this.certificate = certificate;
            this.fingerprint = fingerprint;
            this.expiresAt = expiresAt;
        }

        String publicPem() throws Exception {
            String encoded = java.util.Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(certificate.getEncoded());
            return "-----BEGIN CERTIFICATE-----\n" + encoded + "\n-----END CERTIFICATE-----\n";
        }
    }

    private TlsCertificateStore() {}

    static Identity load(File directory) throws Exception {
        return load(new File(directory, KEYSTORE_FILE), new File(directory, PASSWORD_FILE));
    }

    private static Identity load(File keyStoreFile, File passwordFile) throws Exception {
        if (!keyStoreFile.isFile() || !passwordFile.isFile())
            throw new IOException("https-certificate-not-provisioned");
        if (keyStoreFile.length() <= 0 || keyStoreFile.length() > MAX_KEYSTORE_BYTES)
            throw new IOException("https-keystore-size-invalid");
        requirePrivate(keyStoreFile);
        requirePrivate(passwordFile);
        char[] password = readPassword(passwordFile);
        try {
            KeyStore store = KeyStore.getInstance("PKCS12");
            try (InputStream input = new FileInputStream(keyStoreFile)) {
                store.load(input, password);
            }

            String selectedAlias = null;
            X509Certificate leaf = null;
            PrivateKey privateKey = null;
            java.util.Enumeration<String> aliases = store.aliases();
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                if (!store.isKeyEntry(alias)) continue;
                Certificate certificate = store.getCertificate(alias);
                Key key = store.getKey(alias, password);
                if (certificate instanceof X509Certificate && key instanceof PrivateKey) {
                    selectedAlias = alias;
                    leaf = (X509Certificate) certificate;
                    privateKey = (PrivateKey) key;
                    break;
                }
            }
            if (selectedAlias == null || leaf == null || privateKey == null)
                throw new IOException("https-keystore-missing-server-key");
            Certificate[] chain = store.getCertificateChain(selectedAlias);
            if (chain == null || chain.length == 0) throw new IOException("https-certificate-chain-missing");
            Date now = new Date();
            for (Certificate item : chain) {
                if (!(item instanceof X509Certificate)) throw new IOException("https-certificate-chain-invalid");
                ((X509Certificate) item).checkValidity(now);
            }
            verifyKeyPair(privateKey, leaf);
            KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            managers.init(store, password);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(managers.getKeyManagers(), null, null);
            return new Identity(context, leaf, fingerprint(leaf), formatDate(leaf.getNotAfter()));
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    static Identity importPending(File directory) throws Exception {
        synchronized (IMPORT_LOCK) {
            File candidateKeyStore = new File(directory, IMPORT_KEYSTORE_FILE);
            File candidatePassword = new File(directory, IMPORT_PASSWORD_FILE);
            File liveKeyStore = new File(directory, KEYSTORE_FILE);
            File livePassword = new File(directory, PASSWORD_FILE);
            File backupKeyStore = new File(directory, KEYSTORE_FILE + ".backup");
            File backupPassword = new File(directory, PASSWORD_FILE + ".backup");
            boolean hadKeyStore = liveKeyStore.exists();
            boolean hadPassword = livePassword.exists();
            boolean movedKeyStore = false;
            boolean movedPassword = false;
            boolean installedKeyStore = false;
            boolean installedPassword = false;
            try {
                load(candidateKeyStore, candidatePassword);
                backupKeyStore.delete();
                backupPassword.delete();
                if (hadKeyStore) {
                    if (!liveKeyStore.renameTo(backupKeyStore)) throw new IOException("https-certificate-backup-failed");
                    movedKeyStore = true;
                }
                if (hadPassword) {
                    if (!livePassword.renameTo(backupPassword)) throw new IOException("https-password-backup-failed");
                    movedPassword = true;
                }
                if (!candidateKeyStore.renameTo(liveKeyStore))
                    throw new IOException("https-certificate-activation-failed");
                installedKeyStore = true;
                if (!candidatePassword.renameTo(livePassword)) throw new IOException("https-certificate-activation-failed");
                installedPassword = true;
                Identity activated = load(directory);
                writePublicCertificate(directory, activated);
                backupKeyStore.delete();
                backupPassword.delete();
                return activated;
            } catch (Exception e) {
                if (installedKeyStore) liveKeyStore.delete();
                if (installedPassword) livePassword.delete();
                if (movedKeyStore) backupKeyStore.renameTo(liveKeyStore);
                if (movedPassword) backupPassword.renameTo(livePassword);
                try {
                    if (liveKeyStore.isFile() && livePassword.isFile())
                        writePublicCertificate(directory, load(directory));
                    else new File(directory, PUBLIC_CERTIFICATE_FILE).delete();
                } catch (Exception ignored) { }
                throw e;
            } finally {
                candidateKeyStore.delete();
                candidatePassword.delete();
            }
        }
    }

    static void discardPending(File directory) {
        new File(directory, IMPORT_KEYSTORE_FILE).delete();
        new File(directory, IMPORT_PASSWORD_FILE).delete();
    }

    static void writePublicCertificate(File directory, Identity identity) throws IOException {
        File live = new File(directory, PUBLIC_CERTIFICATE_FILE);
        File temporary = new File(directory, PUBLIC_CERTIFICATE_FILE + ".tmp");
        byte[] bytes;
        try { bytes = identity.publicPem().getBytes(StandardCharsets.US_ASCII); }
        catch (Exception e) { throw new IOException("https-public-certificate-export-failed"); }
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(bytes);
            output.getFD().sync();
        } catch (IOException e) {
            temporary.delete();
            throw e;
        }
        if (!temporary.setReadable(false, false) || !temporary.setReadable(true, true)
            || !temporary.setWritable(false, false) || !temporary.setWritable(true, true)) {
            temporary.delete();
            throw new IOException("https-file-permissions-unavailable");
        }
        if (!temporary.renameTo(live)) {
            temporary.delete();
            throw new IOException("https-public-certificate-export-failed");
        }
    }

    private static char[] readPassword(File file) throws IOException {
        if (file.length() <= 0 || file.length() > MAX_PASSWORD_BYTES)
            throw new IOException("https-password-file-size-invalid");
        byte[] bytes = new byte[MAX_PASSWORD_BYTES + 1];
        int used = 0;
        try (InputStream input = new FileInputStream(file)) {
            int count;
            while ((count = input.read(bytes, used, bytes.length - used)) != -1) {
                used += count;
                if (used > MAX_PASSWORD_BYTES) throw new IOException("https-password-file-size-invalid");
            }
        }
        if (used > 0 && bytes[used - 1] == '\n') used--;
        if (used > 0 && bytes[used - 1] == '\r') used--;
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, 0, used))
                .toString().toCharArray();
        } catch (java.nio.charset.CharacterCodingException e) {
            throw new IOException("https-password-file-not-utf8");
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private static void verifyKeyPair(PrivateKey key, X509Certificate certificate) throws Exception {
        String algorithm;
        if (key.getAlgorithm().equalsIgnoreCase("RSA")) algorithm = "SHA256withRSA";
        else if (key.getAlgorithm().equalsIgnoreCase("EC")) algorithm = "SHA256withECDSA";
        else throw new IOException("https-private-key-algorithm-unsupported");
        byte[] challenge = new byte[32];
        new java.security.SecureRandom().nextBytes(challenge);
        Signature signer = Signature.getInstance(algorithm);
        signer.initSign(key);
        signer.update(challenge);
        byte[] signed = signer.sign();
        Signature verifier = Signature.getInstance(algorithm);
        verifier.initVerify(certificate.getPublicKey());
        verifier.update(challenge);
        Arrays.fill(challenge, (byte) 0);
        if (!verifier.verify(signed)) throw new IOException("https-certificate-key-mismatch");
        Arrays.fill(signed, (byte) 0);
    }

    private static String fingerprint(X509Certificate certificate) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
        StringBuilder text = new StringBuilder();
        for (byte item : digest) {
            if (text.length() > 0) text.append(':');
            text.append(String.format(Locale.ROOT, "%02X", item & 0xff));
        }
        Arrays.fill(digest, (byte) 0);
        return text.toString();
    }

    private static String formatDate(Date date) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(date);
    }

    private static void requirePrivate(File file) throws IOException {
        if (!file.setReadable(false, false) || !file.setWritable(false, false)
            || !file.setReadable(true, true) || !file.setWritable(true, true))
            throw new IOException("https-file-permissions-unavailable");
    }
}
