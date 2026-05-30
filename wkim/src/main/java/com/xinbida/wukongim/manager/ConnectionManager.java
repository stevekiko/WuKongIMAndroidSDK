package com.xinbida.wukongim.manager;

import android.text.TextUtils;

import com.xinbida.wukongim.WKIM;
import com.xinbida.wukongim.WKIMApplication;
import com.xinbida.wukongim.db.WKDBHelper;
import com.xinbida.wukongim.interfaces.IConnectionStatus;
import com.xinbida.wukongim.interfaces.IGetIpAndPort;
import com.xinbida.wukongim.interfaces.IGetSocketIpAndPortListener;
import com.xinbida.wukongim.message.MessageHandler;
import com.xinbida.wukongim.message.WKConnection;
import com.xinbida.wukongim.message.type.WKTransportMode;
import com.xinbida.wukongim.utils.DateUtils;
import com.xinbida.wukongim.utils.WKLoggerUtils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 5/21/21 10:31 AM
 * connect manager
 */
public class ConnectionManager extends BaseManager {
    private final String TAG = "ConnectionManager";
    private ConnectionManager() {

    }

    private static class ConnectionManagerBinder {
        static final ConnectionManager connectManager = new ConnectionManager();
    }

    public static ConnectionManager getInstance() {
        return ConnectionManagerBinder.connectManager;
    }


    private IGetIpAndPort iGetIpAndPort;
    private ConcurrentHashMap<String, IConnectionStatus> connectionListenerMap;

    // 传输模式（默认 TCP）
    private volatile int transportMode = WKTransportMode.TCP;

    public int getTransportMode() {
        return transportMode;
    }

    public void setTransportMode(int mode) {
        this.transportMode = mode;
        // 切换模式时重置 WSS 连续失败计数，避免之前的失败记录影响新的连接
        WKConnection.getInstance().resetWssFailures();
    }

    // 连接
    public void connection() {
        if (TextUtils.isEmpty(WKIMApplication.getInstance().getToken()) || TextUtils.isEmpty(WKIMApplication.getInstance().getUid())) {
            WKLoggerUtils.getInstance().e(TAG,"connection Uninitialized UID and token");
            return;
        }
        WKIMApplication.getInstance().isCanConnect = true;
        if (WKConnection.getInstance().connectionIsNull()) {
            WKConnection.getInstance().reconnection();
        }
    }

    /**
     * 半开连接探测: transport 自报存活(connectionIsNull=false)但超过 thresholdSeconds
     * 没收到任何入站帧时返回 true。供 App 层切前台时判断是否需要强制重连。
     */
    public boolean isConnectionStale(long thresholdSeconds) {
        // 用 Fast 版(无锁, 主线程安全) — 本方法在 onFront 主线程调用, 避免 tryLock 阻塞
        if (WKConnection.getInstance().connectionIsNullFast()) return false; // 真断开交给 connection()/startChat
        long lastMsg = WKConnection.getInstance().getLastMsgTime();
        if (lastMsg == 0) return false; // 尚未连接成功或正处于重连窗口
        long idle = DateUtils.getInstance().getCurrentSeconds() - lastMsg;
        return idle > thresholdSeconds;
    }

    /**
     * 强制健康检查重连: 拆掉可能半开的旧 transport 并重新建连。
     * App 层切前台疑似半开时调用。无凭证时静默返回, 不影响登录态。
     */
    public void forceHealthCheck() {
        if (TextUtils.isEmpty(WKIMApplication.getInstance().getToken())
                || TextUtils.isEmpty(WKIMApplication.getInstance().getUid())) {
            return;
        }
        WKIMApplication.getInstance().isCanConnect = true;
        WKConnection.getInstance().reconnectInBackground(); // 后台线程重连, 不阻塞主线程(防 ANR)
    }


    public void disconnect(boolean isLogout) {
        if (TextUtils.isEmpty(WKIMApplication.getInstance().getToken())) return;
        if (isLogout) {
            logoutChat();
        } else {
            stopConnect();
        }
    }

    /**
     * 断开连接
     */
    private void stopConnect() {
        WKIMApplication.getInstance().isCanConnect = false;
        WKConnection.getInstance().stopAll();
    }

    /**
     * 退出登录
     */
    private void logoutChat() {
        WKLoggerUtils.getInstance().e(TAG,"exit");
        WKIMApplication.getInstance().isCanConnect = false;
        WKIMApplication.getInstance().setToken("");
        WKConnection.getInstance().stopAll();
        WKIM.getInstance().getChannelManager().clearARMCache();
        WKIM.getInstance().getReminderManager().clearAllCache();
        // 捕获当前 DBHelper 引用，延迟关闭只关这个实例：
        // 避免 500ms sleep 期间用户快速重登 → getDbHelper() 返回新实例 → 被误关
        final WKDBHelper targetDbHelper = WKIMApplication.getInstance().getDbHelper();
        new Thread(() -> {
            try {
                MessageHandler.getInstance().saveReceiveMsg();
                MessageHandler.getInstance().updateLastSendingMsgFail();
            } catch (Throwable t) {
                // Bugly#33246 防御：登出落盘若撞到 DB 关闭竞态，不让进程崩溃
                WKLoggerUtils.getInstance().e(TAG, "logout save aborted: " + t.getMessage());
            } finally {
                // 延迟关闭DB，等待其他in-flight DB操作完成
                try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                // 500ms 窗口内若用户已快速重登（token 非空），则跳过关闭——
                // 因为 WKDBHelper 同 uid 复用同一实例，关了就把新会话的活实例也关了
                if (TextUtils.isEmpty(WKIMApplication.getInstance().getToken())) {
                    WKIMApplication.getInstance().closeDbHelper(targetDbHelper);
                }
            }
        }, "logout-db").start();
    }

    public interface IRequestIP {
        void onResult(String requestId, String ip, int port);

        /** 同时返回 TCP 和 WSS 地址 */
        default void onResult(String requestId, String ip, int port, String wssAddr) {
            onResult(requestId, ip, port);
        }
    }

    public void getIpAndPort(String requestId, IRequestIP iRequestIP) {
        if (iGetIpAndPort != null) {
            runOnMainThread(() -> iGetIpAndPort.getIP(new IGetSocketIpAndPortListener() {
                @Override
                public void onGetSocketIpAndPort(String ip, int port) {
                    // 兼容旧的只返回 ip+port 的实现
                    iRequestIP.onResult(requestId, ip, port, null);
                }

                @Override
                public void onGetSocketIpAndPort(String ip, int port, String wssAddr) {
                    iRequestIP.onResult(requestId, ip, port, wssAddr);
                }
            }));
        } else {
            WKLoggerUtils.getInstance().e(TAG,"未注册获取连接地址的事件");
        }
    }

    // 监听获取IP和port
    public void addOnGetIpAndPortListener(IGetIpAndPort iGetIpAndPort) {
        this.iGetIpAndPort = iGetIpAndPort;
    }

    public void setConnectionStatus(int status, String reason) {
        if (connectionListenerMap != null && !connectionListenerMap.isEmpty()) {
            runOnMainThread(() -> {
                for (Map.Entry<String, IConnectionStatus> entry : connectionListenerMap.entrySet()) {
                    entry.getValue().onStatus(status, reason);
                }
            });
        }
    }

    // 监听连接状态
    public void addOnConnectionStatusListener(String key, IConnectionStatus iConnectionStatus) {
        if (iConnectionStatus == null || TextUtils.isEmpty(key)) return;
        if (connectionListenerMap == null) connectionListenerMap = new ConcurrentHashMap<>();
        connectionListenerMap.put(key, iConnectionStatus);
    }

    // 移除监听
    public void removeOnConnectionStatusListener(String key) {
        if (!TextUtils.isEmpty(key) && connectionListenerMap != null) {
            connectionListenerMap.remove(key);
        }
    }
}
