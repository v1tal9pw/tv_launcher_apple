package com.gathin.tvhome;

import android.content.Context;
import android.util.Base64;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import javax.crypto.Cipher;

/** Local-only ADB transport adapted from techgenius1/tvOS-Launcher (Apache-2.0). */
final class LocalAdbClient {
    private static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f;
    private static final int OKAY = 0x59414b4f, CLSE = 0x45534c43, WRTE = 0x45545257;
    private static final byte[] SHA1_PREFIX = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
            0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};
    private static KeyPair keyPair;

    private LocalAdbClient() { }

    static boolean portOpen() {
        for (String host : localHosts()) if (portOpen(host)) return true;
        return false;
    }

    private static boolean portOpen(String host) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, 5555), 600);
            return true;
        } catch (Exception ignored) { return false; }
    }

    private static java.util.List<String> localHosts() {
        java.util.List<String> hosts = new java.util.ArrayList<>();
        hosts.add("127.0.0.1");
        try {
            for (NetworkInterface network : java.util.Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!network.isUp() || network.isLoopback()) continue;
                String name = network.getName();
                if (name != null && (name.startsWith("tun") || name.startsWith("tap")
                        || name.startsWith("ppp") || name.startsWith("wg")
                        || name.startsWith("rmnet") || name.startsWith("dummy"))) continue;
                for (InetAddress address : java.util.Collections.list(network.getInetAddresses()))
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        String ip = address.getHostAddress();
                        if (ip != null && !hosts.contains(ip)) hosts.add(ip);
                    }
            }
        } catch (Exception ignored) { }
        return hosts;
    }

    private static Socket connectLocal() {
        for (String host : localHosts()) {
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(host, 5555), 1000);
                return socket;
            } catch (Exception ignored) {
                try { socket.close(); } catch (Exception closeIgnored) { }
            }
        }
        throw new IllegalStateException("ADB_PORT_5555_CLOSED");
    }

    static String run(Context context, String command) throws Exception {
        KeyPair keys = keys(context);
        try (Socket socket = connectLocal()) {
            socket.setSoTimeout(35000); // The TV may ask to trust this app's ADB key.
            DataInputStream input = new DataInputStream(socket.getInputStream());
            OutputStream output = socket.getOutputStream();
            send(output, CNXN, 0x01000000, 4096, "host::\0".getBytes(StandardCharsets.UTF_8));
            boolean signed = false;
            for (int attempts = 0; attempts < 8; attempts++) {
                Packet packet = read(input);
                if (packet.command == CNXN) break;
                if (packet.command != AUTH || packet.arg0 != 1) throw new IllegalStateException("ADB_AUTH_FAILED");
                if (!signed) {
                    signed = true;
                    byte[] digest = new byte[SHA1_PREFIX.length + packet.data.length];
                    System.arraycopy(SHA1_PREFIX, 0, digest, 0, SHA1_PREFIX.length);
                    System.arraycopy(packet.data, 0, digest, SHA1_PREFIX.length, packet.data.length);
                    Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
                    cipher.init(Cipher.ENCRYPT_MODE, keys.getPrivate());
                    send(output, AUTH, 2, 0, cipher.doFinal(digest));
                } else send(output, AUTH, 3, 0, publicKey((RSAPublicKey) keys.getPublic()));
                if (attempts == 7) throw new IllegalStateException("ADB_NOT_AUTHORIZED");
            }
            socket.setSoTimeout(15000);
            send(output, OPEN, 1, 0, ("shell:" + command + "; echo __TVHOME_EXIT__$?\0")
                    .getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (int packets = 0; packets < 4096; packets++) {
                Packet packet = read(input);
                if (packet.command == WRTE) {
                    send(output, OKAY, 1, packet.arg0, null);
                    if (result.length() + packet.data.length > 256 * 1024) throw new IllegalStateException("ADB_OUTPUT_TOO_LARGE");
                    result.append(new String(packet.data, StandardCharsets.UTF_8));
                } else if (packet.command == CLSE) {
                    send(output, CLSE, 1, packet.arg0, null);
                    return checkedOutput(result.toString());
                } else if (packet.command != OKAY) throw new IllegalStateException("ADB_PROTOCOL_ERROR");
            }
            throw new IllegalStateException("ADB_OUTPUT_TOO_LARGE");
        }
    }

    static String checkedOutput(String result) {
        int marker = result.lastIndexOf("__TVHOME_EXIT__");
        if (marker < 0 || !result.substring(marker + 15).trim().equals("0"))
            throw new IllegalStateException("ADB_COMMAND_FAILED: " + result);
        return result.substring(0, marker).trim();
    }

    private static void send(OutputStream out, int command, int arg0, int arg1, byte[] data) throws Exception {
        int size = data == null ? 0 : data.length, sum = 0;
        if (data != null) for (byte b : data) sum += b & 255;
        ByteBuffer header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(command).putInt(arg0).putInt(arg1).putInt(size).putInt(sum).putInt(~command);
        out.write(header.array());
        if (size > 0) out.write(data);
        out.flush();
    }

    private static Packet read(DataInputStream input) throws Exception {
        byte[] header = new byte[24]; input.readFully(header);
        ByteBuffer fields = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        int command = fields.getInt(), arg0 = fields.getInt(), arg1 = fields.getInt();
        int size = fields.getInt(), checksum = fields.getInt(), magic = fields.getInt();
        if (size < 0 || size > 4096 || magic != ~command) throw new IllegalStateException("ADB_PROTOCOL_ERROR");
        byte[] data = new byte[size]; input.readFully(data);
        int actual = 0; for (byte b : data) actual += b & 255;
        if (actual != checksum) throw new IllegalStateException("ADB_CHECKSUM_ERROR");
        return new Packet(command, arg0, data);
    }

    private static byte[] publicKey(RSAPublicKey key) {
        BigInteger modulus = key.getModulus(), r32 = BigInteger.ONE.shiftLeft(32), r = BigInteger.ONE.shiftLeft(2048);
        ByteBuffer value = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN);
        value.putInt(64).putInt(r32.subtract(modulus.mod(r32).modInverse(r32)).intValue());
        value.put(littleEndian(modulus)).put(littleEndian(r.multiply(r).mod(modulus)));
        value.putInt(key.getPublicExponent().intValue());
        return (Base64.encodeToString(value.array(), Base64.NO_WRAP) + " tvhome@tv\0")
                .getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] littleEndian(BigInteger number) {
        byte[] big = number.toByteArray(), little = new byte[256];
        for (int i = 0; i < little.length && i < big.length; i++) little[i] = big[big.length - i - 1];
        return little;
    }

    private static synchronized KeyPair keys(Context context) throws Exception {
        if (keyPair != null) return keyPair;
        File privateFile = new File(context.getFilesDir(), "local_adb_private.key");
        File publicFile = new File(context.getFilesDir(), "local_adb_public.key");
        if (privateFile.isFile() && publicFile.isFile()) {
            try {
                KeyFactory rsa = KeyFactory.getInstance("RSA");
                keyPair = new KeyPair(rsa.generatePublic(new X509EncodedKeySpec(readFile(publicFile))),
                        rsa.generatePrivate(new PKCS8EncodedKeySpec(readFile(privateFile))));
                return keyPair;
            } catch (Exception ignored) { }
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        keyPair = generator.generateKeyPair();
        try (FileOutputStream out = new FileOutputStream(privateFile)) { out.write(keyPair.getPrivate().getEncoded()); }
        try (FileOutputStream out = new FileOutputStream(publicFile)) { out.write(keyPair.getPublic().getEncoded()); }
        return keyPair;
    }

    private static byte[] readFile(File file) throws Exception {
        if (file.length() > 8192) throw new IllegalStateException("ADB_KEY_INVALID");
        byte[] bytes = new byte[(int) file.length()];
        try (FileInputStream in = new FileInputStream(file)) { new DataInputStream(in).readFully(bytes); }
        return bytes;
    }

    private static final class Packet {
        final int command, arg0; final byte[] data;
        Packet(int command, int arg0, byte[] data) { this.command = command; this.arg0 = arg0; this.data = data; }
    }
}
