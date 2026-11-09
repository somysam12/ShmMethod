package top.niunaijun.blackbox.core;

import android.content.Context;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import top.niunaijun.blackbox.BlackBoxCore;

/**
 * Manages the rootd privileged-memory daemon.
 *
 * rootd runs as root (via `su`) and serves /proc/<pid>/mem read/write requests
 * over a Unix-domain socket at /dev/rootd.sock.  Virtual apps that share the
 * host-app UID still cannot access system-process memory without real root;
 * rootd bridges that gap.
 */
public class RootBridge {
    private static final String TAG = "RootBridge";
    private static final String SOCK_PATH = "/dev/rootd.sock";
    private static final int MAX_DATA = 65536;

    private static final int CMD_READ_MEM  = 1;
    private static final int CMD_WRITE_MEM = 2;
    private static final int CMD_EXEC_CMD  = 3;
    private static final int CMD_PING      = 4;

    private static final int STATUS_OK    = 0;
    private static final int STATUS_ERROR = 1;

    private static final RootBridge sInstance = new RootBridge();

    private volatile boolean mStarted = false;

    public static RootBridge get() { return sInstance; }

    public synchronized boolean ensureStarted() {
        if (mStarted && ping()) return true;
        mStarted = false;

        File rootdBin = deployBinary();
        if (rootdBin == null) {
            Log.e(TAG, "Failed to deploy rootd binary");
            return false;
        }

        try {
            String cmd = "su -c \"" + rootdBin.getAbsolutePath() + "\" &";
            Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch rootd", e);
            return false;
        }

        for (int i = 0; i < 20; i++) {
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
            if (ping()) { mStarted = true; return true; }
        }
        Log.e(TAG, "rootd did not start in time");
        return false;
    }

    public boolean ping() {
        try (LocalSocket sock = connect()) {
            if (sock == null) return false;
            sendRequest(sock, CMD_PING, 0, 0, null);
            int[] hdr = readResponseHeader(sock);
            return hdr != null && hdr[0] == STATUS_OK;
        } catch (Exception e) {
            return false;
        }
    }

    public byte[] readMem(int pid, long offset, int length) {
        try (LocalSocket sock = connect()) {
            if (sock == null) return null;
            sendRequest(sock, CMD_READ_MEM, pid, offset, null);
            int[] hdr = readResponseHeader(sock);
            if (hdr == null || hdr[0] != STATUS_OK || hdr[1] <= 0) return null;
            byte[] data = new byte[hdr[1]];
            sock.getInputStream().read(data);
            return data;
        } catch (Exception e) {
            Log.e(TAG, "readMem failed", e);
            return null;
        }
    }

    public int writeMem(int pid, long offset, byte[] data) {
        try (LocalSocket sock = connect()) {
            if (sock == null) return -1;
            sendRequest(sock, CMD_WRITE_MEM, pid, offset, data);
            int[] hdr = readResponseHeader(sock);
            if (hdr == null || hdr[0] != STATUS_OK) return -1;
            return hdr[1];
        } catch (Exception e) {
            Log.e(TAG, "writeMem failed", e);
            return -1;
        }
    }

    public String execCommand(String cmd) {
        try (LocalSocket sock = connect()) {
            if (sock == null) return null;
            byte[] payload = cmd.getBytes("UTF-8");
            sendRequest(sock, CMD_EXEC_CMD, 0, 0, payload);
            int[] hdr = readResponseHeader(sock);
            if (hdr == null || hdr[0] != STATUS_OK || hdr[1] <= 0) return null;
            byte[] out = new byte[hdr[1]];
            sock.getInputStream().read(out);
            return new String(out, "UTF-8");
        } catch (Exception e) {
            Log.e(TAG, "execCommand failed", e);
            return null;
        }
    }

    private LocalSocket connect() {
        try {
            LocalSocket sock = new LocalSocket();
            sock.connect(new LocalSocketAddress(SOCK_PATH, LocalSocketAddress.Namespace.FILESYSTEM));
            return sock;
        } catch (Exception e) {
            return null;
        }
    }

    private void sendRequest(LocalSocket sock, int cmd, int pid, long offset, byte[] payload)
            throws Exception {
        int payloadLen = payload != null ? payload.length : 0;
        ByteBuffer buf = ByteBuffer.allocate(20 + payloadLen).order(ByteOrder.nativeOrder());
        buf.putInt(cmd);
        buf.putInt(pid);
        buf.putLong(offset);
        buf.putInt(payloadLen);
        if (payload != null) buf.put(payload);
        sock.getOutputStream().write(buf.array());
    }

    private int[] readResponseHeader(LocalSocket sock) throws Exception {
        byte[] hdr = new byte[8];
        int read = 0;
        InputStream in = sock.getInputStream();
        while (read < 8) {
            int n = in.read(hdr, read, 8 - read);
            if (n < 0) return null;
            read += n;
        }
        ByteBuffer buf = ByteBuffer.wrap(hdr).order(ByteOrder.nativeOrder());
        return new int[]{ buf.getInt(), buf.getInt() };
    }

    private File deployBinary() {
        try {
            Context ctx = BlackBoxCore.getContext();
            File src = new File(ctx.getApplicationInfo().nativeLibraryDir, "librootd.so");
            if (!src.exists()) {
                Log.e(TAG, "librootd.so not found at " + src.getAbsolutePath());
                return null;
            }
            File dst = new File(ctx.getFilesDir(), "rootd");
            if (!dst.exists() || dst.length() != src.length()) {
                copy(src, dst);
                dst.setExecutable(true, false);
            }
            return dst;
        } catch (Exception e) {
            Log.e(TAG, "deployBinary failed", e);
            return null;
        }
    }

    private static void copy(File src, File dst) throws Exception {
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }
}
