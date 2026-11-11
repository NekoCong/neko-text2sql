package com.nekocong.text2sql.juc;

public class VisibilityDemo {
    // 不加 volatile，子线程可能永远读不到 flag 的变化
    private static volatile boolean flag = false;
    /*
    修改 volatile 变量时，会做两件事：
    将当前处理器缓存行的数据写回主内存
    这个写回操作会让其他 CPU 里缓存了该内存地址的数据失效（MESI 缓存一致性协议）
     */
    public static void main(String[] args) throws InterruptedException {
        new Thread(() -> {
            System.out.println("子线程开始循环...");
            while (!flag) {
                // 空转，等待 flag 变为 true
            }
            System.out.println("子线程检测到 flag = true，退出循环");
        }).start();

        Thread.sleep(5000);
        flag = true;
        System.out.println("主线程已把 flag 改为 true");
    }
}