package com.dtflys.forest.backend;

import com.dtflys.forest.config.ForestConfiguration;
import com.dtflys.forest.handler.LifeCycleHandler;
import com.dtflys.forest.http.ForestRequest;
import com.dtflys.forest.reflection.MethodLifeCycleHandler;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Forest异步请求执行器
 *
 * @author gongjun [dt_flys@hotmail.com]
 * @since 1.5.12
 */
public class AsyncHttpExecutor implements HttpExecutor {

    public final static Integer DEFAULT_MAX_THREAD_SIZE = 100;

    protected final ForestConfiguration configuration;

    /**
     * Forest同步请求执行器
     */
    protected final HttpExecutor syncExecutor;

    /**
     * Forest响应对象处理器
     */
    protected final ResponseHandler responseHandler;

    /**
     * 异步线程计数
     */
    private final static AtomicInteger threadCount = new AtomicInteger(0);

    /**
     * 异步请求的执行线程池
     */
    protected static ThreadPoolExecutor pool;

    /**
     * 异步线程池是否已初始化
     */
    protected static volatile boolean initialized = false;

    /**
     * 初始化异步请求线程池
     *
     * @param maxAsyncThreadSize 最大异步线程数
     */
    public static synchronized void initAsyncThreads(Integer maxAsyncThreadSize) {
        maxAsyncThreadSize = maxAsyncThreadSize != null ? maxAsyncThreadSize : DEFAULT_MAX_THREAD_SIZE;
        int coreSize = maxAsyncThreadSize > 10 ? 10 : maxAsyncThreadSize;
        pool = new ThreadPoolExecutor(
                coreSize, maxAsyncThreadSize != null ? maxAsyncThreadSize : DEFAULT_MAX_THREAD_SIZE,
                3, TimeUnit.MINUTES,
                new SynchronousQueue<>(),
                tf -> {
                    Thread thread = new Thread(tf, "forest-async-" + threadCount.getAndIncrement());
                    thread.setDaemon(true);
                    return thread;
                }, new AsyncAbortPolicy());
    }

    /**
     *
     * @return
     */
    public static int getMaxAsyncThreadSize() {
        return pool.getMaximumPoolSize();
    }

    public static int getAsyncThreadSize() {
        return pool.getPoolSize();
    }

    public AsyncHttpExecutor(ForestConfiguration configuration, HttpExecutor syncExecutor, ResponseHandler responseHandler) {
        this.configuration = configuration;
        this.syncExecutor = syncExecutor;
        this.responseHandler = responseHandler;
    }

    public static class AsyncTask implements Runnable {

        private final CompletableFuture future;

        private final HttpExecutor executor;

        private final LifeCycleHandler lifeCycleHandler;

        private String threadName;

        public AsyncTask(CompletableFuture future, HttpExecutor executor, LifeCycleHandler lifeCycleHandler) {
            this.future = future;
            this.executor = executor;
            this.lifeCycleHandler = lifeCycleHandler;
        }

        public HttpExecutor getExecutor() {
            return executor;
        }

        public CompletableFuture getFuture() {
            return future;
        }

        public LifeCycleHandler getLifeCycleHandler() {
            return lifeCycleHandler;
        }

        @Override
        public void run() {
            try {
                this.threadName = Thread.currentThread().getName();
                executor.execute(lifeCycleHandler);
                if (lifeCycleHandler instanceof MethodLifeCycleHandler) {
                    Object result = ((MethodLifeCycleHandler<?>) lifeCycleHandler).getResultData();
                    future.complete(result);
                } else {
                    future.complete(null);
                }
            } catch (Throwable th) {
                future.completeExceptionally(th);
            }

        }
    }

    @Override
    public ForestRequest getRequest() {
        return syncExecutor.getRequest();
    }

    @Override
    public void execute(LifeCycleHandler lifeCycleHandler) {
        if (pool == null) {
            synchronized (this) {
                if (pool == null) {
                    initAsyncThreads(configuration.getMaxAsyncThreadSize());
                }
            }
        }
        final CompletableFuture future = new CompletableFuture();
        pool.submit(new AsyncTask(future, syncExecutor, lifeCycleHandler));
        responseHandler.handleFuture(future);
    }

    @Override
    public ResponseHandler getResponseHandler() {
        return responseHandler;
    }

    @Override
    public void close() {

    }
}
