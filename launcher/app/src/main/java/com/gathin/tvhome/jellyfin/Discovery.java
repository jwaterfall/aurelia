package com.gathin.tvhome.jellyfin;

import android.util.Log;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds Jellyfin servers on the local network the way Jellyfin's own apps do: "who is JellyfinServer?" over UDP to
 * port 7359, which every server answers with its name and address.
 *
 * A server in Docker hears the broadcast only with host networking: Docker forwards packets addressed to its
 * host, not broadcasts. The account panel also checks the servers used before directly, which finds those anyway.
 */
public final class Discovery {

    public static final class Server {
        public final String id;
        public final String name;
        public final String address;

        Server(String id, String name, String address) {
            this.id = id;
            this.name = name;
            this.address = address;
        }
    }

    private static final int PORT = 7359;
    private static final byte[] QUESTION = "who is JellyfinServer?".getBytes(StandardCharsets.UTF_8);

    private Discovery() { }

    /** Servers that answered within {@code timeoutMs}, each once. Blocks; call it in the background. */
    public static List<Server> find(int timeoutMs) {
        Map<String, Server> found = new LinkedHashMap<>();
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            for (InetAddress to : broadcastAddresses()) {
                try {
                    socket.send(new DatagramPacket(QUESTION, QUESTION.length, to, PORT));
                } catch (Exception e) {
                    Log.w("Jellyfin.Discovery", "Could not broadcast to " + to, e);
                }
            }
            long end = System.currentTimeMillis() + timeoutMs;
            byte[] buffer = new byte[4096];
            while (true) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) break;
                socket.setSoTimeout((int) left);
                DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(reply);
                } catch (SocketTimeoutException e) {
                    break;
                }
                Server s = parse(new String(reply.getData(), 0, reply.getLength(), StandardCharsets.UTF_8));
                if (s != null && !found.containsKey(s.id)) found.put(s.id, s);
            }
        } catch (Exception e) {
            Log.w("Jellyfin.Discovery", "Discovery failed", e);
        }
        return new ArrayList<>(found.values());
    }

    private static Server parse(String text) {
        try {
            JSONObject o = new JSONObject(text);
            String address = Api.normalizeAddress(o.optString("Address", ""));
            if (address.isEmpty()) return null;
            String id = o.optString("Id", address);
            return new Server(id, o.optString("Name", address), address);
        } catch (Exception e) {
            return null;
        }
    }

    /** The limited broadcast address, plus each network's own, which some routers forward when they drop the other. */
    private static Set<InetAddress> broadcastAddresses() {
        Set<InetAddress> out = new LinkedHashSet<>();
        try {
            out.add(InetAddress.getByName("255.255.255.255"));
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                for (InterfaceAddress a : nif.getInterfaceAddresses()) {
                    if (a.getBroadcast() != null) out.add(a.getBroadcast());
                }
            }
        } catch (Exception e) {
            Log.w("Jellyfin.Discovery", "Could not list networks", e);
        }
        return out;
    }
}
