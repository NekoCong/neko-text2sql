package com.nekocong.text2sql.ThreadLocal;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class ThreadLocalLeakDemo {

    // 模拟用户上下文
    private static final ThreadLocal<String> USER_CONTEXT = new ThreadLocal<>();

    public static void main(String[] args) throws InterruptedException {

        // 使用单线程线程池，保证两个任务复用同一个线程，方便复现问题
        ExecutorService executor = Executors.newSingleThreadExecutor();

        System.out.println("========== 场景一：忘记 remove()，发生数据串号 ==========");

        // 第一个任务：设置用户为“张三”，但故意不 remove()
        executor.submit(() -> {
            try {
                USER_CONTEXT.set("张三");
                System.out.println(Thread.currentThread().getName()
                        + " 处理张三的业务，设置 USER_CONTEXT = 张三");
            } finally {
                // 故意注释掉 remove()，模拟忘记清理
                // USER_CONTEXT.remove();
            }
        });

        // 第二个任务：没有 set，直接 get，看是否读到上一个任务残留的值
        executor.submit(() -> {
            String user = USER_CONTEXT.get();
            System.out.println(Thread.currentThread().getName()
                    + " 当前用户：" + user + "  <-- 如果输出“张三”，说明发生了数据串号");
        });

        // 等待上面两个任务执行完
        Thread.sleep(500);

        System.out.println();
        System.out.println("========== 场景二：正确 remove()，数据被清理 ==========");

        // 第三个任务：设置用户为“李四”，并在 finally 中正确 remove()
        executor.submit(() -> {
            try {
                USER_CONTEXT.set("李四");
                System.out.println(Thread.currentThread().getName()
                        + " 处理李四的业务，设置 USER_CONTEXT = 李四");
            } finally {
                USER_CONTEXT.remove(); // 正确清理
            }
        });

        // 第四个任务：再次 get，看是否还能读到值
        executor.submit(() -> {
            String user = USER_CONTEXT.get();
            System.out.println(Thread.currentThread().getName()
                    + " 当前用户：" + user + "  <-- 如果输出 null，说明清理成功");
        });

        // 关闭线程池
        executor.shutdown();
        executor.awaitTermination(1, TimeUnit.SECONDS);

        System.out.println();
        System.out.println("========== 主线程中的 ThreadLocal（不受线程池影响）==========");
        System.out.println("主线程 USER_CONTEXT = " + USER_CONTEXT.get());
    }
}