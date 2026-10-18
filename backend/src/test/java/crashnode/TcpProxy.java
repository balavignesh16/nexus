package crashnode;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A minimal TCP forwarder used by the crash-recovery campaign (ledger P4-066, P4-067) to take a dependency away from a child backend process
 * WITHOUT touching the dependency itself: {@link #cut()} closes every live connection and makes new connections end at once (what a network
 * partition or a stopped server looks like to the client), {@link #restore()} lets traffic flow again. Loopback only; test code.
 */
public final class TcpProxy implements Closeable {
    private final ServerSocket server;
    private final String targetHost;
    private final int targetPort;
    private final Set<Socket> live = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean cut = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public TcpProxy(String targetHost, int targetPort) throws IOException {
        this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        this.targetHost = targetHost;
        this.targetPort = targetPort;
        Thread acceptor = new Thread(this::acceptLoop, "tcp-proxy-accept-" + server.getLocalPort());
        acceptor.setDaemon(true);
        acceptor.start();
    }

    public int port() { return server.getLocalPort(); }

    public boolean isCut() { return cut.get(); }

    /** Closes every connection now and refuses to forward new ones until {@link #restore()}. */
    public void cut() {
        cut.set(true);
        for (Socket socket : live) closeQuietly(socket);
        live.clear();
    }

    public void restore() { cut.set(false); }

    private void acceptLoop() {
        while (!closed.get()) {
            try {
                Socket client = server.accept();
                if (cut.get()) { closeQuietly(client); continue; }
                Socket upstream;
                try {
                    upstream = new Socket(targetHost, targetPort);
                } catch (IOException unreachable) {
                    closeQuietly(client);
                    continue;
                }
                live.add(client);
                live.add(upstream);
                pump(client, upstream);
                pump(upstream, client);
            } catch (IOException e) {
                if (closed.get()) return;
            }
        }
    }

    private void pump(Socket from, Socket to) {
        Thread t = new Thread(() -> {
            byte[] buffer = new byte[8192];
            try {
                InputStream in = from.getInputStream();
                OutputStream out = to.getOutputStream();
                int n;
                while ((n = in.read(buffer)) >= 0) {
                    if (cut.get()) break;
                    out.write(buffer, 0, n);
                    out.flush();
                }
            } catch (IOException ignored) {
                // a closed side ends the pump
            } finally {
                closeQuietly(from);
                closeQuietly(to);
                live.remove(from);
                live.remove(to);
            }
        }, "tcp-proxy-pump");
        t.setDaemon(true);
        t.start();
    }

    private static void closeQuietly(Socket socket) {
        try { socket.close(); } catch (IOException ignored) { /* already closed */ }
    }

    @Override
    public void close() {
        closed.set(true);
        cut();
        try { server.close(); } catch (IOException ignored) { /* closing */ }
    }
}
