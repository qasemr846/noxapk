package hev.htproxy;

/**
 * JNI entry points exported by libhev-socks5-tunnel.so
 * (https://github.com/heiher/hev-socks5-tunnel). The class name and package must match
 * the library's own JNI registration, so do not rename this class.
 */
public class TProxyService {
    static {
        System.loadLibrary("hev-socks5-tunnel");
    }

    /** The library registers this as (String, int) -> boolean (see logcat: signature "(Ljava/lang/String;I)Z"). */
    public static native boolean TProxyStartService(String configPath, int fd);

    public static native void TProxyStopService();

    /** @return {txPackets, txBytes, rxPackets, rxBytes} */
    public static native long[] TProxyGetStats();
}
