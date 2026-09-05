package dev.distantstock.link;

import com.sun.net.httpserver.HttpServer;
import dev.distantstock.config.StockConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 只听口。handler 按路径拆。 */
public final class LinkServer {
    private static final Logger LOG = LogManager.getLogger();
    private static HttpServer server;
    private static ExecutorService httpPool;
    private static ExecutorService io;

    public static synchronized void start() {
        stop();
        io = Executors.newSingleThreadExecutor(r -> daemon(r, "distantstock-io"));
        try {
            InetSocketAddress addr = LinkHttp.parseBind(StockConfig.BIND.get());
            server = HttpServer.create(addr, 0);
            server.createContext("/stock", new StockHandler());
            server.createContext("/status", new StatusHandler());
            server.createContext("/order", new OrderHandler());
            server.createContext("/package", new PackageHandler());
            httpPool = Executors.newFixedThreadPool(2, r -> daemon(r, "distantstock-http"));
            server.setExecutor(httpPool);
            server.start();
            LOG.info("listen {}", StockConfig.BIND.get());
        } catch (Exception e) {
            LOG.warn("listen failed: {}", e.getMessage());
        }
        LinkClient.start(io);
    }

    public static synchronized void stop() {
        LinkClient.stop();
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (httpPool != null) {
            httpPool.shutdownNow();
            httpPool = null;
        }
        if (io != null) {
            io.shutdownNow();
            io = null;
        }
    }

    public static void enqueue(Runnable job) {
        if (io != null) {
            io.execute(job);
        }
    }

    static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    private LinkServer() {
    }
}
