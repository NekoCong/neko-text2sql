package com.nekocong.text2sql.juc;

import java.util.concurrent.atomic.AtomicInteger;

public class AtomicityDemo {
	private static AtomicInteger count = new AtomicInteger(0);
//    private static volatile int count = 0;

	/**
	 * 从主内存读 count 到工作内存
	 * 执行 +1
	 * 写回主内存
	 * 两个线程可能同时读到 count = 100，各自加 1 后都写回 101，导致一次自增丢失。
	 */
	public static void main(String[] args) throws InterruptedException {
//        Thread t1 = new Thread(() -> {
//            for (int i = 0; i < 10000; i++){
//              count.incrementAndGet();
//            }
//        });
//        Thread t2 = new Thread(() -> {
//            for (int i = 0; i < 10000; i++) {
//                count.incrementAndGet();
//            }
//        });
//
//        t1.start();
//        t2.start();
//        t1.join();
//        t2.join();
//
//        // 期望 20000，实际通常小于 20000
//        System.out.println("count = " + count);
//    }

		Thread t1 = new Thread(() -> {
			for (int i = 0; i < 10000; i++) {
				count.incrementAndGet();
			}
		});
		Thread t2 = new Thread(() -> {
			for (int i = 0; i < 10000; i++) {
				count.incrementAndGet();
			}
		});

		t1.start();
		t2.start();
		t1.join();
		t2.join();

		// 期望 20000，实际通常小于 20000
		System.out.println("count = " + count);
	}
}