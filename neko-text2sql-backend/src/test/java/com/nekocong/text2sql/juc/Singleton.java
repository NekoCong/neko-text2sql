package com.nekocong.text2sql.juc;

// 经典双重检查锁单例（DCL）
public class Singleton {
    // 必须加 volatile，否则可能拿到半初始化对象
    private static volatile Singleton instance;

    public static Singleton getInstance() {
        if (instance == null) {
            synchronized (Singleton.class) {
                if (instance == null) {
                    instance = new Singleton(); // 这行不是原子操作
                }
            }
        }
        return instance;
    }
}