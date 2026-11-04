package com.aurebesh.teclado.llm;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * Grupo fijo de hilos de trabajo para los bucles paralelos del motor. El hilo llamante trabaja
 * como trabajador 0 y los demas son hilos persistentes (no se crea nada por operacion: hay unas
 * 170 multiplicaciones por token). El reparto es dinamico: un contador atomico entrega trozos de
 * filas, asi los nucleos pequenos del telefono no frenan a los grandes. Los trabajadores giran
 * brevemente esperando trabajo y despues se aparcan (LockSupport) para no gastar bateria.
 */
final class Workers {

    /** Trabajo sobre el rango [from, to) de indices; worker identifica el hilo (0 = llamante). */
    interface Task {
        void run(int worker, int from, int to);
    }

    private final Thread[] threads;
    private final int size;
    private final AtomicInteger next = new AtomicInteger();
    private final AtomicInteger pending = new AtomicInteger();
    private volatile int generation;
    private volatile boolean closed;
    private volatile Task task;
    private volatile int count;
    private volatile int chunk;
    private volatile Throwable failure;

    private static final int SPIN_YIELDS = 2000;

    Workers(int size) {
        this.size = Math.max(1, size);
        threads = new Thread[this.size - 1];
        for (int i = 0; i < threads.length; i++) {
            final int worker = i + 1;
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    loop(worker);
                }
            }, "llm-worker-" + worker);
            t.setDaemon(true);
            threads[i] = t;
            t.start();
        }
    }

    int size() {
        return size;
    }

    private void loop(int worker) {
        int seen = 0;
        while (!closed) {
            int spins = 0;
            while (generation == seen && !closed) {
                if (spins < SPIN_YIELDS) {
                    spins++;
                    Thread.yield();
                } else {
                    LockSupport.park(this);
                }
            }
            if (closed) return;
            seen = generation;
            try {
                work(worker);
            } catch (Throwable t) {
                failure = t;
            } finally {
                pending.decrementAndGet();
            }
        }
    }

    private void work(int worker) {
        final Task t = task;
        final int n = count;
        final int c = chunk;
        while (true) {
            int from = next.getAndAdd(c);
            if (from >= n) return;
            int to = Math.min(n, from + c);
            t.run(worker, from, to);
        }
    }

    /** Ejecuta task sobre [0, count) en trozos de chunk indices, repartidos entre todos los hilos. */
    void parallel(int count, int chunk, Task t) {
        if (count <= 0) return;
        if (size == 1 || count <= chunk) {
            t.run(0, 0, count);
            return;
        }
        this.task = t;
        this.count = count;
        this.chunk = Math.max(1, chunk);
        this.failure = null;
        next.set(0);
        pending.set(threads.length);
        generation++;
        for (Thread th : threads) LockSupport.unpark(th);
        try {
            work(0);
        } catch (Throwable e) {
            failure = e;
        }
        int spins = 0;
        while (pending.get() != 0) {
            if (spins < SPIN_YIELDS) {
                spins++;
                Thread.yield();
            } else {
                LockSupport.parkNanos(this, 50_000L);
            }
        }
        this.task = null;
        Throwable f = failure;
        if (f != null) {
            failure = null;
            if (f instanceof RuntimeException) throw (RuntimeException) f;
            if (f instanceof Error) throw (Error) f;
            throw new RuntimeException(f);
        }
    }

    void shutdown() {
        closed = true;
        for (Thread th : threads) LockSupport.unpark(th);
    }
}
