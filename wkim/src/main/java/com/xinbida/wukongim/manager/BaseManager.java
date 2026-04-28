package com.xinbida.wukongim.manager;

import android.os.Handler;
import android.os.Looper;

/**
 * 2020-09-21 13:48
 * 管理者
 */
public class BaseManager {

    private boolean isMainThread() {
        return Looper.getMainLooper().getThread() == Thread.currentThread();
    }

    // port from upstream 2585602: volatile + DCL，避免 Handler 重复创建和内存泄漏
    private volatile Handler mainHandler;

    void runOnMainThread(ICheckThreadBack iCheckThreadBack) {
        if (iCheckThreadBack == null) {
            return;
        }
        if (!isMainThread()) {
            if (mainHandler == null) {
                synchronized (this) {
                    if (mainHandler == null) {
                        mainHandler = new Handler(Looper.getMainLooper());
                    }
                }
            }
            mainHandler.post(iCheckThreadBack::onMainThread);
        } else iCheckThreadBack.onMainThread();
    }

    protected interface ICheckThreadBack {
        void onMainThread();
    }
}
