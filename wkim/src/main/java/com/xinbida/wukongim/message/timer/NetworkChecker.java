package com.xinbida.wukongim.message.timer;

import android.util.Log;

import com.xinbida.wukongim.WKIM;
import com.xinbida.wukongim.WKIMApplication;
import com.xinbida.wukongim.message.WKConnection;
import com.xinbida.wukongim.message.type.WKConnectReason;
import com.xinbida.wukongim.message.type.WKConnectStatus;
import com.xinbida.wukongim.utils.DateUtils;
import com.xinbida.wukongim.utils.WKLoggerUtils;

public class NetworkChecker {
    private final Object lock = new Object(); // 添加锁对象
    public boolean isForcedReconnect;
    public boolean checkNetWorkTimerIsRunning = false;
    // half-open 看门狗阈值(秒): 心跳 60s 一次, 2.5 个周期内无任何入站帧即判定链路死亡强制重连。
    // 大于单个心跳周期以容忍延迟 pong, 小于常见运营商 NAT 空闲回收窗口。
    private static final long PONG_TIMEOUT_SECONDS = 150;

    public void startNetworkCheck() {
        TimerManager.getInstance().addTask(
                TimerTasks.NETWORK_CHECK,
                () -> {
                    synchronized (lock) {
                        checkNetworkStatus();
                    }
                },
                0,
                1000
        );
    }

    private void checkNetworkStatus() {
        boolean is_have_network = WKIMApplication.getInstance().isNetworkConnected();
        if (!is_have_network) {
            isForcedReconnect = true;
            WKIM.getInstance().getConnectionManager().setConnectionStatus(WKConnectStatus.noNetwork, WKConnectReason.NoNetwork);
            WKLoggerUtils.getInstance().e("无网络连接...");
            WKConnection.getInstance().checkSendingMsg();
        } else {
            //有网络
            if (WKConnection.getInstance().connectionIsNull() || isForcedReconnect) {
                // 网络恢复时，重置重连计数，给予完整的重连机会
                if (isForcedReconnect) {
                    WKConnection.getInstance().resetConnCount();
                }
                WKConnection.getInstance().reconnection();
                isForcedReconnect = false;
            } else {
                // half-open 看门狗: transport 自报存活但 OS 网络稳定时, 上面分支不会触发;
                // NAT 静默回收/进程冻结导致 socket 半开时, 靠入站帧时间戳兜底强制重连。
                checkPongTimeout();
            }
        }
        checkNetWorkTimerIsRunning = true;
    }

    // 链路存活看门狗: transport 自报存活(connectionIsNull=false)但超阈值无任何入站帧 → 强制重连
    private void checkPongTimeout() {
        long lastMsg = WKConnection.getInstance().getLastMsgTime();
        if (lastMsg == 0) return; // 尚未连接成功或正处于重连窗口
        long idle = DateUtils.getInstance().getCurrentSeconds() - lastMsg;
        if (idle > PONG_TIMEOUT_SECONDS) {
            WKLoggerUtils.getInstance().e("NetworkChecker",
                    "[watchdog] half-open 检测: " + idle + "s 无入站帧, 强制重连");
            WKConnection.getInstance().reconnection();
        }
    }
}
