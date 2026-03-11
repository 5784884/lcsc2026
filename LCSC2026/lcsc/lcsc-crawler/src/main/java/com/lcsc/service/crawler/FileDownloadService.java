package com.lcsc.service.crawler;

import com.google.common.util.concurrent.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class FileDownloadService implements InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(FileDownloadService.class);

    private static final String STATS_COMPLETED_IMAGES = "crawler:stats:completed_images";
    private static final String STATS_COMPLETED_PDFS = "crawler:stats:completed_pdfs";
    private static final String STATS_FAILED_DOWNLOADS = "crawler:stats:failed_downloads";

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private DownloadStatusService downloadStatusService;

    // 👇 修复编译报错：改用 @Value 直接读取配置文件，提供默认值，完美绕开 CrawlerConfig 找不到方法的报错
    @Value("${crawler.image-thread-pool-size:5}")
    private int imageThreadPoolSize;

    @Value("${crawler.pdf-thread-pool-size:3}")
    private int pdfThreadPoolSize;

    @Value("${crawler.image-delay:1000}")
    private long imageDelay;

    @Value("${crawler.pdf-delay:2000}")
    private long pdfDelay;

    @Value("${crawler.image-max-retry:3}")
    private int imageMaxRetry;

    @Value("${crawler.pdf-max-retry:3}")
    private int pdfMaxRetry;

    @Value("${crawler.retry-interval:3000}")
    private long retryInterval;

    // 👇 核心升级：独立线程池与限流器
    private ThreadPoolExecutor imageExecutor;
    private ThreadPoolExecutor pdfExecutor;
    private RateLimiter imageRateLimiter;
    private RateLimiter pdfRateLimiter;

    private final AtomicInteger activeTaskCount = new AtomicInteger(0);
    private volatile boolean isRunning = false;

    // 👇 双重保险标志位 (完全保留你的原有逻辑)
    private volatile boolean abortCurrentTask = false; // 控制是否中断当前流 (刹车)
    private volatile boolean acceptTasks = true;       // 控制是否接收新任务 (大门)

    // 👇 新增：暂停标志位
    private volatile boolean isPaused = false;         // 控制正在排队或重试的任务挂起睡眠

    private static final int CONNECT_TIMEOUT = 15000;
    private static final int READ_TIMEOUT = 45000;

    private record DownloadTask(String url, String localPath, String type) {}

    @Override
    public void afterPropertiesSet() { start(); }

    @Override
    public void destroy() { stop(); }

    public synchronized void start() {
        if (isRunning) return;
        isRunning = true;

        // 1. 初始化图片并发池和间隔控制
        int imgThreads = imageThreadPoolSize > 0 ? imageThreadPoolSize : 5;
        imageExecutor = new ThreadPoolExecutor(imgThreads, imgThreads, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(200000));
        double imgDelayMs = imageDelay > 0 ? imageDelay : 1000L;
        imageRateLimiter = RateLimiter.create(1000.0 / imgDelayMs);

        // 2. 初始化PDF并发池和间隔控制
        int pdfThreads = pdfThreadPoolSize > 0 ? pdfThreadPoolSize : 3;
        pdfExecutor = new ThreadPoolExecutor(pdfThreads, pdfThreads, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(200000));
        double pdfDelayMs = pdfDelay > 0 ? pdfDelay : 2000L;
        pdfRateLimiter = RateLimiter.create(1000.0 / pdfDelayMs);

        log.info("========== 文件下载服务已启动 (双线程池并发与间隔限流已开启) ==========");
    }

    public synchronized void stop() {
        isRunning = false;
        forceStopAll();
        if (imageExecutor != null) imageExecutor.shutdownNow();
        if (pdfExecutor != null) pdfExecutor.shutdownNow();
        log.info("========== 文件下载服务已停止 ==========");
    }

    /**
     * 正常提交任务入口
     */
    public void submitDownloadTask(String url, String localPath, String type) {
        // 👇 1. 如果大门关了，直接拒收临死前的爬虫发来的任务
        if (!acceptTasks) {
            log.debug("下载大门已关闭，拒收遗留任务: {}", localPath);
            return;
        }

        if (url == null || url.isBlank() || localPath == null || localPath.isBlank()) return;

        DownloadTask task = new DownloadTask(url, localPath, type);
        forceSubmitTask(task); // 调用底层派发
    }

    /**
     * 底层派发（这是爬虫主线程发来的全新任务，需要增加 Total 总数）
     */
    private void forceSubmitTask(DownloadTask task) {
        if ("image".equalsIgnoreCase(task.type())) {
            downloadStatusService.addTotalImages(1);
        } else if ("pdf".equalsIgnoreCase(task.type())) {
            downloadStatusService.addTotalPdfs(1);
        }
        // 调用重排方法进行底层线程池投递
        requeueTask(task);
    }

    /**
     * 重新排队派发（专用于暂停恢复等内部调度，【绝对不增加 Total 总数】，保持进度定格）
     */
    private void requeueTask(DownloadTask task) {
        if ("image".equalsIgnoreCase(task.type())) {
            if (imageExecutor != null && !imageExecutor.isShutdown()) {
                imageExecutor.submit(() -> handleTaskWithControl(task, imageRateLimiter, imageMaxRetry, retryInterval));
            }
        } else if ("pdf".equalsIgnoreCase(task.type())) {
            if (pdfExecutor != null && !pdfExecutor.isShutdown()) {
                pdfExecutor.submit(() -> handleTaskWithControl(task, pdfRateLimiter, pdfMaxRetry, retryInterval));
            }
        }
    }

    /**
     * 核心调度逻辑（包含：暂停阻塞、限流、重试控制）
     */
    private void handleTaskWithControl(DownloadTask task, RateLimiter rateLimiter, int maxRetry, long retryIntervalMs) {
        int attempt = 0;

        // 👇 修复编译报错：基本数据类型 int/long 无法使用 != null 比较，直接判断 > 0 即可
        int actualMaxRetry = maxRetry > 0 ? maxRetry : 1;
        long actualRetryInterval = retryIntervalMs > 0 ? retryIntervalMs : 3000L;

        while (attempt <= actualMaxRetry) {
            try {
                // 1. 【暂停控制】：如果点下了暂停，线程在此挂起，不消耗性能
                while (isPaused) {
                    Thread.sleep(1000);
                    if (!isRunning) return; // 如果期间系统关闭了，直接退出
                }

                // 2. 【间隔控制】：通过 Guava RateLimiter 获取令牌，实现配置的毫秒级间隔限流
                if (rateLimiter != null) {
                    rateLimiter.acquire();
                }

                // 3. 【执行下载】
                activeTaskCount.incrementAndGet();
                boolean shouldRetry = false;
                try {
                    doActualDownload(task);
                    recordSuccess(task); // 成功记录
                    return; // 成功则直接退出循环
                } catch (Exception e) {
                    // 👇 4. 监测是被强行踩刹车的，还是真的网络异常 👇
                    if (abortCurrentTask) {
                        cleanupFile(task); // 清理残缺文件
                        // 如果是因为"暂停"导致的踩刹车，为了不丢进度，我们把任务重新塞回队列尾部！
                        if (isPaused) {
                            requeueTask(task); // 🚨 修正：使用 requeueTask，不让 Total 分母膨胀
                        }
                        return; // 中断退出
                    }

                    // 否则就是网络异常，进入【重试控制】
                    cleanupFile(task);
                    attempt++;
                    if (attempt <= actualMaxRetry) {
                        log.warn("{} 下载失败, 准备第 {} 次重试: {}", task.type(), attempt, task.url());
                        Thread.sleep(actualRetryInterval);
                        shouldRetry = true;
                    } else {
                        log.error("{} 下载失败, 超过最大重试次数: {}", task.type(), task.url(), e);
                        recordFailure(task);
                        return; // 彻底放弃
                    }
                } finally {
                    activeTaskCount.decrementAndGet();
                }

                if (shouldRetry) continue;

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * 完完全全保留你原本的流复制与监控逻辑
     */
    private void doActualDownload(DownloadTask task) throws Exception {
        Path filePath = Paths.get(task.localPath());

        // 文件存在直接跳过
        if (Files.exists(filePath)) {
            return;
        }

        Files.createDirectories(filePath.getParent());
        String processedUrl = processUrl(task.url());
        URLConnection connection = new URL(processedUrl).openConnection();
        connection.setRequestProperty("User-Agent", "Mozilla/5.0");
        connection.setConnectTimeout(CONNECT_TIMEOUT);
        connection.setReadTimeout(READ_TIMEOUT);

        try (InputStream inputStream = connection.getInputStream();
             java.io.OutputStream outputStream = Files.newOutputStream(filePath)) {

            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                // 👇 2. 实时监测刹车：抛出异常触发外层的重试或重新排队
                if (abortCurrentTask) {
                    throw new java.io.IOException("强制中断流");
                }
                outputStream.write(buffer, 0, bytesRead);
            }
        }
    }

    private void cleanupFile(DownloadTask task) {
        try { Files.deleteIfExists(Paths.get(task.localPath())); } catch (Exception ex) {}
    }

    private void recordSuccess(DownloadTask task) {
        if ("image".equalsIgnoreCase(task.type())) {
            redisTemplate.opsForValue().increment(STATS_COMPLETED_IMAGES);
            downloadStatusService.incrementImageSuccess();
        } else if ("pdf".equalsIgnoreCase(task.type())) {
            redisTemplate.opsForValue().increment(STATS_COMPLETED_PDFS);
            downloadStatusService.incrementPdfSuccess();
        }
    }

    private void recordFailure(DownloadTask task) {
        redisTemplate.opsForValue().increment(STATS_FAILED_DOWNLOADS);
        if ("image".equalsIgnoreCase(task.type())) downloadStatusService.incrementImageFailed();
        else if ("pdf".equalsIgnoreCase(task.type())) downloadStatusService.incrementPdfFailed();
    }

    private String processUrl(String url) { return url.startsWith("http") ? url : "https:" + url; }

    // ==========================================
    // 👇👇👇 各种控制信号方法 (完美支持暂停/继续/清空) 👇👇👇
    // ==========================================

    // 专门用于【清空/停止爬虫】：关门 + 踩刹车 + 彻底清空队列
    public void forceStopAll() {
        acceptTasks = false;       // 1. 关门：拒收临死爬虫的遗留任务
        abortCurrentTask = true;   // 2. 刹车：阻断当前流
        isPaused = false;          // 解除暂停阻塞（让线程直接抛异常退出）

        if (imageExecutor != null) imageExecutor.getQueue().clear(); // 3. 清空队列
        if (pdfExecutor != null) pdfExecutor.getQueue().clear();

        resetStats();
        log.info("========== 物理刹车：已强行中止，并清空队列拒收新任务 ==========");
    }

    // 专门用于【启动全量/批量爬虫】：开门 + 松刹车 + 清零重来
    public void prepareForNewCrawl() {
        acceptTasks = true;        // 1. 开门
        abortCurrentTask = false;  // 2. 松刹车
        isPaused = false;

        if (imageExecutor != null) imageExecutor.getQueue().clear();
        if (pdfExecutor != null) pdfExecutor.getQueue().clear();

        resetStats();
        log.info("========== 下载大门开启，准备接收新一轮任务 ==========");
    }

    // 👇👇👇 专门用于【暂停】: 关门 + 踩刹车断流 + 不清空队列 👇👇👇
    public void pauseDownloads() {
        acceptTasks = false;       // 1. 关门：主爬虫暂停时，不再接收新任务
        isPaused = true;           // 2. 挂起：使拿到的任务进入睡眠等待
        abortCurrentTask = true;   // 3. 刹车：强行切断当前正在下载的那几个网络流

        // 注意：这里【绝对不能】调用 clear() 和 resetStats()！
        // 因为被截断的网络流抛出异常后，会被我的代码捕获并判断 isPaused 为 true，从而自动把任务重新塞回队列尾部，进度丝毫不丢。
        log.info("========== 下载服务已暂停，队列中为您保留了待下载任务进度 ==========");
    }

    // 👇👇👇 专门用于【继续】: 开门 + 松刹车 👇👇👇
    public void resumeDownloads() {
        acceptTasks = true;        // 1. 开门：允许接收主爬虫发来的新任务
        abortCurrentTask = false;  // 2. 松开流切断刹车
        isPaused = false;          // 3. 唤醒所有在排队睡眠的线程接着干活

        log.info("========== 下载服务已继续，开始处理剩余的排队任务 ==========");
    }

    private void resetStats() {
        redisTemplate.delete(STATS_COMPLETED_IMAGES);
        redisTemplate.delete(STATS_COMPLETED_PDFS);
        redisTemplate.delete(STATS_FAILED_DOWNLOADS);
        if (downloadStatusService != null) downloadStatusService.reset();
    }

    // 完美对接你的前端控制台显示
    public int getPendingTaskCount() {
        int imgCount = imageExecutor != null ? imageExecutor.getQueue().size() : 0;
        int pdfCount = pdfExecutor != null ? pdfExecutor.getQueue().size() : 0;
        return imgCount + pdfCount;
    }
    public int getActiveTaskCount() { return activeTaskCount.get(); }
    public long getCompletedImageCount() { String v = redisTemplate.opsForValue().get(STATS_COMPLETED_IMAGES); return v != null ? Long.parseLong(v) : 0L; }
    public long getCompletedPdfCount() { String v = redisTemplate.opsForValue().get(STATS_COMPLETED_PDFS); return v != null ? Long.parseLong(v) : 0L; }
    public long getFailedTaskCount() { String v = redisTemplate.opsForValue().get(STATS_FAILED_DOWNLOADS); return v != null ? Long.parseLong(v) : 0L; }

    public Map<String, Object> getStatus() {
        return Map.of("isRunning", isRunning, "pending", getPendingTaskCount(), "processing", getActiveTaskCount(),
                "completedImages", getCompletedImageCount(), "completedPdfs", getCompletedPdfCount(), "failed", getFailedTaskCount());
    }

    /**
     * 检查是否还有图片/PDF正在下载或排队
     * 供主爬虫线程判断是否可以安全停止
     */
    public boolean isDownloading() {
        return getActiveTaskCount() > 0 || getPendingTaskCount() > 0;
    }
}