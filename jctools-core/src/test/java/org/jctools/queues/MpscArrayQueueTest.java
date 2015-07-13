package org.jctools.queues;

import java.util.Queue;
import java.util.concurrent.*;

/**
 * Date: 15/7/13
 *
 * @author jiachun.fjc
 */
public class MpscArrayQueueTest {

    public static void main(String[] args) throws Exception {
        final int size = 1024;

        loop(new ArrayBlockingQueue<Object>(size));

        loop(new LinkedBlockingQueue<Object>(size));

        loop(new ConcurrentLinkedQueue<Object>());

        loop(new LinkedTransferQueue<Object>());

        loop(new org.jctools.queues.MpscLinkedQueue7<Object>());

        loop(new MpscArrayQueue<Object>(size));
    }

    private static void loop(final Queue<Object> queue) throws InterruptedException {
        for (int i = 0; i < 10; ++i) {
            queue.offer(i);
        }

        final int THREAD_COUNT = 3;
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch endLatch = new CountDownLatch(THREAD_COUNT);

        for (int i = 0; i < THREAD_COUNT; ++i) {
            final Thread thread = new Thread() {
                @SuppressWarnings("AccessStaticViaInstance")
                public void run() {
                    try {
                        startLatch.await();
                    } catch (InterruptedException e) { e.printStackTrace(); }

                    try {
                        for (int i = 0; i < 1000000; ++i) {
                            queue.offer(i);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        endLatch.countDown();
                    }
                }
            };
            thread.start();
        }

        Thread pollThread = new Thread() {
            public void run() {
                try {
                    startLatch.await();
                } catch (InterruptedException e) { e.printStackTrace(); }

                try {
                    for (int i = 0; i < 1000000 * THREAD_COUNT; ++i) {
                        queue.poll();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    endLatch.countDown();
                }
            }
        };
        pollThread.start();

        long startMillis = System.currentTimeMillis();
        startLatch.countDown();
        endLatch.await();
        long millis = System.currentTimeMillis() - startMillis;
        System.out.println(queue.getClass().getName() + " : " + millis);
    }
}
