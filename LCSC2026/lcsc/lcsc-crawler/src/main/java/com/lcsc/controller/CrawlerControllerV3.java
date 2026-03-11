package com.lcsc.controller;

import java.util.ArrayList;
import java.io.File;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.lcsc.common.Result;
import com.lcsc.config.CrawlerConfig;
import com.lcsc.entity.CategoryLevel2Code;
import com.lcsc.entity.Product;
import com.lcsc.service.crawler.v3.CategoryCrawlerWorkerPool;
import com.lcsc.service.crawler.v3.CategorySyncService;
import com.lcsc.service.crawler.v3.CrawlerTaskQueueService;

/**
 * 爬虫控制器V3
 * 提供全新的爬虫控制REST API
 *
 * @author lcsc-crawler
 * @since 2025-10-08
 */
@RestController
@RequestMapping("/api/v3/crawler")
@CrossOrigin(origins = {"http://localhost:5173", "http://localhost:5174"})
public class CrawlerControllerV3 {

    private static final Logger log = LoggerFactory.getLogger(CrawlerControllerV3.class);

    @Autowired
    private CategorySyncService syncService;

    @Autowired
    private CrawlerTaskQueueService queueService;

    @Autowired
    private CategoryCrawlerWorkerPool workerPool;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private com.lcsc.mapper.ProductMapper productMapper;

    @Autowired
    private CrawlerConfig crawlerConfig;

    // ✅ 第一步：注入三级分类 Service
    @Autowired
    private com.lcsc.service.CategoryLevel3CodeService categoryLevel3CodeService;

    // 👇👇👇 新增：注入下载状态服务 👇👇👇
    @Autowired
    private com.lcsc.service.crawler.DownloadStatusService downloadStatusService;
    // 👆👆👆 结束 👆👆👆

    @Value("${crawler.storage.base-path:#{systemProperties['user.dir']}/data}")
    private String storageBasePath;

    // 👇👇👇 新增：注入文件下载服务 👇👇👇
    @Autowired
    private com.lcsc.service.crawler.FileDownloadService fileDownloadService;

    /**
     * 检查系统状态
     * 包括：分类是否已同步、爬虫是否运行中、队列状态
     */
    @GetMapping("/status")
    public Result<Map<String, Object>> checkStatus() {
        try {
            log.debug("检查系统状态");

            boolean categoriesSynced = syncService.isCategoriesSynced();

            // 👇👇👇 核心修复 1：将 API 状态和下载状态合并判断 👇👇👇
            boolean isApiRunning = workerPool.isRunning();
            boolean isDownloading = false;

            if (fileDownloadService != null) {
                // 如果下载队列里还有任务，或者正在下载中，都判定为 isDownloading = true
                isDownloading = fileDownloadService.getPendingTaskCount() > 0 || fileDownloadService.getActiveTaskCount() > 0;
            }
            // 👆👆👆 修复结束 👆👆👆

            Map<String, Object> categoryStats = syncService.getCategoryStatistics();
            // 获取原有的爬取任务队列状态（强转为可变 Map，以便后面加数据）
            Map<String, Object> queueStatus = new java.util.HashMap<>(queueService.getQueueStatus());
            Long totalProductsInDb = productMapper.selectCount(null);

            // 把图片和PDF的下载进度塞进去
            if (downloadStatusService != null) {
                Map<String, Object> downloadStatus = downloadStatusService.getStatusMap();
                queueStatus.putAll(downloadStatus);
            }

            // 获取后端的 PDF 开关真实状态
            Object pdfFlag = redisTemplate.opsForValue().get("crawler:config:savePdf");
            boolean currentPdfEnabled = pdfFlag != null ? "true".equalsIgnoreCase(pdfFlag.toString()) : true; // 默认true

            // 👇👇👇 获取后端的 图片 下载开关真实状态 👇👇👇
            Object imgFlag = redisTemplate.opsForValue().get("crawler:config:saveImages");
            boolean currentImgEnabled = imgFlag != null ? "true".equalsIgnoreCase(imgFlag.toString()) : crawlerConfig.getSaveImages();
            // 👆👆👆

            // 👇👇👇 新增：获取全局暂停状态给前端 👇👇👇
            Object pausedFlag = redisTemplate.opsForValue().get("crawler:global:paused");
            boolean isPaused = pausedFlag != null && "true".equalsIgnoreCase(pausedFlag.toString());
            // 👆👆👆

            // 👇👇👇 核心修复 2：只有当 API 在跑或者在下载，且没被暂停时，系统才处于运行状态
            boolean isRunning = (isApiRunning || isDownloading) && !isPaused;

            // 🚨🚨 注意：这里我帮你把 "savePdfEnabled" 和 "saveImagesEnabled" 补充进去了 🚨🚨
            Map<String, Object> status = Map.of(
                    "categoriesSynced", categoriesSynced,
                    "isRunning", isRunning,
                    "queueStatus", queueStatus,
                    "categoryStats", categoryStats,
                    "workerThreadCount", workerPool.getWorkerThreadCount(),
                    "totalProductsInDb", totalProductsInDb != null ? totalProductsInDb : 0,
                    "savePdfEnabled", currentPdfEnabled,
                    "saveImagesEnabled", currentImgEnabled, // <--- 补充这一行，前端才能真正收到图片开关状态
                    "isPaused", isPaused // <--- 👇新增：把暂停状态传给前端👇
            );

            return Result.success(status);

        } catch (Exception e) {
            log.error("检查系统状态失败", e);
            return Result.error("检查系统状态失败: " + e.getMessage());
        }
    }
    // 👇👇👇 补上缺失的同步分类接口 👇👇👇
    /**
     * 爬取/同步官方分类数据
     */
    @PostMapping("/sync-categories")
    public Result<Map<String, Object>> syncCategories() {
        try {
            log.info("========== 收到同步官方分类请求 ==========");
            // 调用 CategorySyncService 中的异步方法，并使用 join() 等待结果返回
            Map<String, Object> syncResult = syncService.crawlAndSyncCategories().join();

            // 判断服务层返回的成功状态
            if ((Boolean) syncResult.getOrDefault("success", false)) {
                return Result.success(syncResult);
            } else {
                return Result.error((String) syncResult.getOrDefault("message", "同步失败"));
            }
        } catch (Exception e) {
            log.error("执行同步官方分类时发生异常", e);
            return Result.error("同步分类异常: " + e.getMessage());
        }
    }
    // 👆👆👆 补充结束 👆👆👆

    /**
     * 第二步：开始全量爬取
     * 为所有二级分类创建爬取任务并启动Worker池
     */
    @PostMapping("/start-full")
    public Result<Map<String, Object>> startFullCrawl() {
        try {
            log.info("========== 收到全量爬取请求 ==========");

            // 1. 检查分类是否已同步
            if (!syncService.isCategoriesSynced()) {
                return Result.error("请先同步分类信息");
            }

            // 👇👇👇 核心修复开始：确保彻底清理旧状态 👇👇👇
            log.info("开始全量爬取前，重置所有队列和统计数据...");

            // 1. 清空主队列
            queueService.clearAllQueues();
            queueService.initializeState();

            // 2. 清空各种统计计数器及暂停标记
            List<String> keysToDelete = new ArrayList<>();
            keysToDelete.add("crawler:status:total");
            keysToDelete.add("crawler:status:completed");
            keysToDelete.add("crawler:status:failed");
            keysToDelete.add("crawler:status:subTaskCount");
            keysToDelete.add("crawler:global:paused"); // 起爬时移除暂停标记
            redisTemplate.delete(keysToDelete);

            // 3. 清空下载任务
            if (fileDownloadService != null) {
                fileDownloadService.prepareForNewCrawl(); //
            }
            // 👆👆👆 修复结束 👆👆👆

            // 3. 智能创建任务（自动判断二级或三级分类）
            log.info("开始智能创建任务...");
            List<String> taskIds = queueService.createSmartCategoryTasks(
                    CrawlerTaskQueueService.PRIORITY_AUTO);

            log.info("任务创建完成: 成功创建={} 个", taskIds.size());

            // 4. 启动Worker池
            workerPool.start();

            Map<String, Object> result = Map.of(
                    "success", true,
                    "createdTasks", taskIds.size(),
                    "message", "全量爬取已启动（支持三级分类）"
            );

            log.info("========== 全量爬取启动完成 ==========");
            return Result.success(result);

        } catch (Exception e) {
            log.error("启动全量爬取失败", e);
            return Result.error("启动失败: " + e.getMessage());
        }
    }

    /**
     * 开始指定分类爬取（手动触发，高优先级）
     */
    @PostMapping("/start-category/{catalogId}")
    public Result<Map<String, Object>> startCategoryCrawl(@PathVariable Integer catalogId) {
        try {
            log.info("========== 收到单分类爬取请求: catalogId={} ==========", catalogId);

            // 创建任务（优先级=10，手动）
            String taskId = queueService.createCategoryTask(catalogId,
                    CrawlerTaskQueueService.PRIORITY_MANUAL);

            log.info("高优先级任务已创建: taskId={}", taskId);

            // 如果Worker未运行，启动
            if (!workerPool.isRunning()) {
                log.info("Worker池未运行，正在启动...");
                workerPool.start();
            }

            Map<String, Object> result = Map.of(
                    "success", true,
                    "taskId", taskId,
                    "catalogId", catalogId,
                    "priority", "HIGH",
                    "message", "高优先级爬取任务已创建"
            );

            return Result.success(result);

        } catch (Exception e) {
            log.error("创建分类爬取任务失败: catalogId={}", catalogId, e);
            return Result.error("创建任务失败: " + e.getMessage());
        }
    }

    // 👇👇👇 新增：暂停功能 👇👇👇
    /**
     * 暂停爬虫（挂起正在执行的任务，不清除队列数据）
     */
    @PostMapping("/pause")
    public Result<Map<String, Object>> pauseCrawler() {
        try {
            log.info("========== 收到暂停爬虫请求 ==========");

            // 1. 设置Redis全局暂停标记
            redisTemplate.opsForValue().set("crawler:global:paused", "true");

            // 2. 停止主爬虫 Worker 池，不再消费新任务
            if (workerPool.isRunning()) {
                workerPool.stop();
            }

            // 3. 暂停文件下载（图片和PDF流挂起）
            if (fileDownloadService != null) {
                fileDownloadService.pauseDownloads();
            }

            Map<String, Object> result = Map.of(
                    "success", true,
                    "message", "爬虫已成功暂停",
                    "isRunning", false,
                    "isPaused", true
            );
            return Result.success(result);

        } catch (Exception e) {
            log.error("暂停爬虫失败", e);
            return Result.error("暂停失败: " + e.getMessage());
        }
    }
    // 👆👆👆 新增结束 👆👆👆

    // 👇👇👇 新增：彻底清空爬取功能 👇👇👇
    /**
     * 彻底清空所有爬取任务、队列状态、下载缓存
     */
    @PostMapping("/clear")
    public Result<Map<String, Object>> clearCrawler() {
        try {
            log.info("========== 收到清空爬虫状态请求 ==========");

            // 1. 如果还在运行，先强制停止
            if (workerPool.isRunning()) {
                workerPool.stop();
            }

            // 2. 彻底清空主队列和去重集合
            queueService.clearAllQueues();
            queueService.initializeState();

            // 3. 彻底清空 Redis 里的各种统计计数器和暂停标记
            List<String> keysToDelete = new ArrayList<>();
            keysToDelete.add("crawler:status:total");
            keysToDelete.add("crawler:status:completed");
            keysToDelete.add("crawler:status:failed");
            keysToDelete.add("crawler:status:subTaskCount");
            keysToDelete.add("crawler:global:paused");
            redisTemplate.delete(keysToDelete);

            // 4. 彻底清空文件下载队列和状态
            if (fileDownloadService != null) {
                fileDownloadService.prepareForNewCrawl();
            }

            Map<String, Object> result = Map.of(
                    "success", true,
                    "message", "爬虫队列及统计状态已全部清空"
            );
            return Result.success(result);

        } catch (Exception e) {
            log.error("清空爬虫状态失败", e);
            return Result.error("清空失败: " + e.getMessage());
        }
    }
    // 👆👆👆 新增结束 👆👆👆

    /**
     * 停止爬虫（无条件强行终止所有任务）
     */
    @PostMapping("/stop")
    public Result<Map<String, Object>> stopCrawler() {
        try {
            log.info("========== 收到停止爬虫请求 ==========");

            // 👇👇👇 核心修复 3：无条件踩下所有刹车！不管主程序停没停，只要点了停止，全部强杀 👇👇👇

            // 1. 停止主爬虫 Worker（解析网页的线程）
            if (workerPool.isRunning()) {
                workerPool.stop();
            }

            // 2. 强行终止所有的 PDF 和图片下载流，并清空下载队列 (修复为 forceStopAll)
            if (fileDownloadService != null) {
                fileDownloadService.forceStopAll(); // <--- 修复：以前是 pauseDownloads，会导致停不干净
            }
            // 👆👆👆 修复结束 👆👆👆

            // 获取最新状态返回给前端
            Map<String, Object> queueStatus = queueService.getQueueStatus();
            int pendingTasks = (int) queueStatus.getOrDefault("pending", 0);
            int processingTasks = (int) queueStatus.getOrDefault("processing", 0);

            log.info("停止信号已全面发送，所有任务即将强行终止");

            Map<String, Object> result = Map.of(
                    "success", true,
                    "message", "已强制停止所有爬取和下载任务",
                    "isRunning", false,
                    "pendingTasks", pendingTasks,
                    "processingTasks", processingTasks
            );
            return Result.success(result);

        } catch (Exception e) {
            log.error("停止爬虫失败", e);
            return Result.error("停止失败: " + e.getMessage());
        }
    }
    /**
     * 继续爬虫（从队列继续弹出任务）
     */
    @PostMapping("/resume")
    public Result<Map<String, Object>> resumeCrawler() {
        try {
            log.info("========== 收到继续爬虫请求 ==========");

            // 👇👇👇 修复 4：先移除暂停标记，并且恢复文件下载任务 👇👇👇
            redisTemplate.delete("crawler:global:paused");
            if (fileDownloadService != null) {
                fileDownloadService.resumeDownloads();
            }
            // 👆👆👆

            // 检查队列是否有待处理任务
            Map<String, Object> queueStatus = queueService.getQueueStatus();
            int pending = (int) queueStatus.get("pending");

            // 👇👇👇 修复：API任务跑完了，但可能图片/PDF还在下载，所以不应直接抛异常阻止继续 👇👇👇
            if (pending > 0 && !workerPool.isRunning()) {
                // 启动Worker
                workerPool.start();
            } else if (pending == 0) {
                log.info("API 队列中没有待处理任务，但已恢复资源下载执行");
            }

            Map<String, Object> result = Map.of(
                    "success", true,
                    "pendingTasks", pending,
                    "message", "爬虫已继续运行"
            );

            log.info("爬虫已继续，待处理任务数: {}", pending);
            return Result.success(result);

        } catch (Exception e) {
            log.error("继续爬虫失败", e);
            return Result.error("继续失败: " + e.getMessage());
        }
    }

    /**
     * 获取队列状态
     */
    @GetMapping("/queue-status")
    public Result<Map<String, Object>> getQueueStatus() {
        try {
            Map<String, Object> status = queueService.getQueueStatus();
            return Result.success(status);
        } catch (Exception e) {
            log.error("获取队列状态失败", e);
            return Result.error("获取失败: " + e.getMessage());
        }
    }

    /**
     * 获取分类爬取进度
     */
    @GetMapping("/progress/category/{catalogId}")
    public Result<Map<Object, Object>> getCategoryProgress(@PathVariable Integer catalogId) {
        try {
            Map<Object, Object> progress = redisTemplate.opsForHash()
                    .entries("crawler:progress:" + catalogId);

            if (progress.isEmpty()) {
                return Result.error("该分类暂无进度信息");
            }

            return Result.success(progress);

        } catch (Exception e) {
            log.error("获取分类进度失败: catalogId={}", catalogId, e);
            return Result.error("获取失败: " + e.getMessage());
        }
    }

    /**
     * 获取任务详情
     */
    @GetMapping("/task/{taskId}")
    public Result<Map<Object, Object>> getTaskDetails(@PathVariable String taskId) {
        try {
            Map<Object, Object> taskDetails = queueService.getTaskDetails(taskId);

            if (taskDetails.isEmpty()) {
                return Result.error("任务不存在");
            }

            return Result.success(taskDetails);

        } catch (Exception e) {
            log.error("获取任务详情失败: taskId={}", taskId, e);
            return Result.error("获取失败: " + e.getMessage());
        }
    }

    /**
     * 获取分类统计信息
     */
    @GetMapping("/category-stats")
    public Result<Map<String, Object>> getCategoryStatistics() {
        try {
            Map<String, Object> stats = syncService.getCategoryStatistics();
            return Result.success(stats);
        } catch (Exception e) {
            log.error("获取分类统计失败", e);
            return Result.error("获取失败: " + e.getMessage());
        }
    }

    /**
     * 清空所有队列（调试用，慎用）
     */
    @PostMapping("/debug/clear-queues")
    public Result<String> clearAllQueues() {
        try {
            log.warn("========== 清空所有队列（调试操作） ==========");

            if (workerPool.isRunning()) {
                return Result.error("请先停止爬虫再清空队列");
            }

            queueService.clearAllQueues();
            queueService.initializeState();

            log.info("所有队列已清空");
            return Result.success("所有队列已清空");

        } catch (Exception e) {
            log.error("清空队列失败", e);
            return Result.error("清空失败: " + e.getMessage());
        }
    }

    /**
     * 批量爬取指定分类（智能支持三级分类）
     * @param catalogIds 二级分类ID列表
     */
    @PostMapping("/start-batch")
    public Result<Map<String, Object>> startBatchCrawl(@RequestBody List<Integer> catalogIds) {
        try {
            log.info("========== 收到批量爬取请求: {} 个分类 ==========", catalogIds.size());

            if (catalogIds == null || catalogIds.isEmpty()) {
                return Result.error("分类ID列表不能为空");
            }

            // 1. 检查分类是否已同步
            if (!syncService.isCategoriesSynced()) {
                return Result.error("请先同步分类信息");
            }

            // 👇👇👇 核心修复开始：彻底清理旧的队列、任务和统计状态 👇👇👇
            log.info("开始批量爬取前，重置所有队列和统计数据...");

            // 1. 清空主队列状态
            queueService.clearAllQueues();
            queueService.initializeState();

            // 2. 手动清空 Redis 中的各种统计计数器（双重保险）及暂停标记
            List<String> keysToDelete = new ArrayList<>();
            keysToDelete.add("crawler:status:total");
            keysToDelete.add("crawler:status:completed");
            keysToDelete.add("crawler:status:failed");
            keysToDelete.add("crawler:status:subTaskCount");
            keysToDelete.add("crawler:global:paused");
            redisTemplate.delete(keysToDelete);

            // 3. 彻底清零文件下载队列
            if (fileDownloadService != null) {
                fileDownloadService.prepareForNewCrawl();
            }
            // 👆👆👆 修复结束 👆👆👆

            // 2. 智能批量创建任务（优先级=5，手动批量）
            // 自动判断每个二级分类是否有三级分类，并创建相应的任务
            List<String> taskIds = queueService.createSmartBatchTasks(catalogIds, 5);

            log.info("批量任务创建完成: 成功创建={} 个", taskIds.size());

            // 3. 如果Worker未运行，启动
            if (!workerPool.isRunning()) {
                log.info("Worker池未运行，正在启动...");
                workerPool.start();
            }

            Map<String, Object> result = Map.of(
                    "success", true,
                    "totalCategories", catalogIds.size(),
                    "createdTasks", taskIds.size(),
                    "message", "批量爬取任务已创建（支持三级分类）"
            );

            return Result.success(result);

        } catch (Exception e) {
            log.error("批量爬取失败", e);
            return Result.error("批量爬取失败: " + e.getMessage());
        }
    }
    /**
     * 获取所有分类及其爬取状态（仅返回已提交过爬取任务的分类）
     * ✅ 第二步：修改 getCategoriesWithStatus 方法，改为查询三级分类
     */
    @GetMapping("/categories-with-status")
    public Result<List<Map<String, Object>>> getCategoriesWithStatus() {
        try {
            log.debug("获取所有三级分类及爬取状态");

            // ✅ 修改点 1：改用 categoryLevel3CodeService 并调用我们写好的关联查询方法
            List<com.lcsc.entity.CategoryLevel3Code> categories = categoryLevel3CodeService.getAllCategoryLevel3List();

            List<Map<String, Object>> result = categories.stream()
                    .map(cat -> {
                        Map<String, Object> item = new HashMap<>();
                        item.put("id", cat.getId());
                        item.put("categoryLevel1Id", cat.getCategoryLevel1Id());
                        item.put("categoryLevel2Id", cat.getCategoryLevel2Id()); // 增加 ID 关联

                        // ✅ 修改点 2：把三级分类的名字塞进去！
                        item.put("categoryLevel3Name", cat.getCategoryLevel3Name());
                        item.put("categoryLevel2Name", cat.getCategoryLevel2Name()); // parentName

                        String crawlStatus = cat.getCrawlStatus() != null ? cat.getCrawlStatus().toLowerCase() : "pending";
                        item.put("crawlStatus", crawlStatus);
                        item.put("crawlProgress", cat.getCrawlProgress() != null ? cat.getCrawlProgress() : 0);
                        item.put("lastCrawlTime", cat.getLastCrawlTime());
                        item.put("totalProducts", cat.getTotalProducts() != null ? cat.getTotalProducts() : 0);
                        item.put("errorMessage", cat.getErrorMessage());

                        return item;
                    })
                    // 过滤逻辑保持不变，确保只返回有意义的数据
                    .filter(item -> {
                        String status = (String) item.get("crawlStatus");
                        Long productCount = ((Number) item.get("totalProducts")).longValue();
                        return !"pending".equals(status) || productCount > 0 || item.get("lastCrawlTime") != null;
                    })
                    .collect(Collectors.toList());

            return Result.success(result);
        } catch (Exception e) {
            log.error("获取分类状态失败", e);
            return Result.error("获取失败: " + e.getMessage());
        }
    }

    /**
     * 获取所有分类列表（包括未爬取的，用于分类选择器）
     * ✅ 第三步：修改 getAllCategories 方法，同样改用三级分类
     */
    /**
     * 获取所有分类列表（包括三级分类和【纯二级分类】）
     * 修复：解决只有二级分类的类目不显示的问题
     */
    // 1. 在类顶部注入 Level1Mapper
    @Autowired
    private com.lcsc.mapper.CategoryLevel1CodeMapper categoryLevel1CodeMapper;

    /**
     * 获取所有分类列表
     * 修复：1. 包含纯二级分类  2. 修复一级分类名称显示
     */
    @GetMapping("/all-categories")
    public Result<List<Map<String, Object>>> getAllCategories() {
        try {
            log.debug("获取全部分类列表");
            List<Map<String, Object>> result = new ArrayList<>();

            // ================== 修复名称逻辑开始 ==================
            // 1. 先查出所有一级分类，建立 ID -> Name 的映射 map
            List<com.lcsc.entity.CategoryLevel1Code> l1List = categoryLevel1CodeMapper.selectList(null);
            Map<Integer, String> l1NameMap = new HashMap<>();
            for (com.lcsc.entity.CategoryLevel1Code l1 : l1List) {
                l1NameMap.put(l1.getId(), l1.getCategoryLevel1Name());
            }
            // ================== 修复名称逻辑结束 ==================

            // 2. 获取所有三级分类
            List<com.lcsc.entity.CategoryLevel3Code> l3List = categoryLevel3CodeService.getAllCategoryLevel3List();
            java.util.Set<Integer> l2IdsHasChildren = new java.util.HashSet<>();

            for (com.lcsc.entity.CategoryLevel3Code l3 : l3List) {
                Map<String, Object> item = new HashMap<>();
                item.put("id", l3.getId());
                item.put("categoryLevel", "level3");

                item.put("categoryLevel1Id", l3.getCategoryLevel1Id());
                // ✅ 修复：从 map 中取真实名称，取不到则用兜底
                String realL1Name = l1NameMap.getOrDefault(l3.getCategoryLevel1Id(), "L1-" + l3.getCategoryLevel1Id());
                item.put("categoryLevel1Name", realL1Name);

                item.put("categoryLevel2Id", l3.getCategoryLevel2Id());
                item.put("categoryLevel2Name", l3.getCategoryLevel2Name());
                item.put("categoryName", l3.getCategoryLevel3Name());
                item.put("categoryLevel3Name", l3.getCategoryLevel3Name());
                item.put("crawlStatus", l3.getCrawlStatus() != null ? l3.getCrawlStatus().toLowerCase() : "pending");
                item.put("totalProducts", l3.getTotalProducts());

                result.add(item);

                if (l3.getCategoryLevel2Id() != null) l2IdsHasChildren.add(l3.getCategoryLevel2Id());
            }

            // 3. 获取所有二级分类并补全纯二级分类
            List<CategoryLevel2Code> l2List = syncService.getAllLevel2Categories();
            for (CategoryLevel2Code l2 : l2List) {
                if (!l2IdsHasChildren.contains(l2.getId())) {
                    Map<String, Object> item = new HashMap<>();
                    item.put("id", l2.getId());
                    item.put("categoryLevel", "level2");
                    item.put("categoryLevel1Id", l2.getCategoryLevel1Id());
                    // ✅ 修复：同样从 map 取真实名称
                    String realL1Name = l1NameMap.getOrDefault(l2.getCategoryLevel1Id(), "L1-" + l2.getCategoryLevel1Id());
                    item.put("categoryLevel1Name", realL1Name);

                    item.put("categoryLevel2Id", l2.getId());
                    item.put("categoryLevel2Name", l2.getCategoryLevel2Name());
                    item.put("categoryName", l2.getCategoryLevel2Name());
                    item.put("categoryLevel3Name", null);
                    item.put("crawlStatus", l2.getCrawlStatus() != null ? l2.getCrawlStatus().toLowerCase() : "pending");
                    item.put("totalProducts", l2.getTotalProducts());

                    result.add(item);
                }
            }

            return Result.success(result);
        } catch (Exception e) {
            log.error("获取全部分类失败", e);
            return Result.error("获取全部分类失败: " + e.getMessage());
        }
    }
    /**
     * 获取存储路径信息
     * 返回图片、PDF等文件的存储路径及统计信息
     */
    @GetMapping("/storage-paths")
    public Result<Map<String, Object>> getStoragePaths() {
        try {
            // 构建完整路径
            String basePath = Paths.get(storageBasePath).toAbsolutePath().toString();
            String imagePath = Paths.get(basePath, crawlerConfig.getStorage().getImageDir()).toString();
            String pdfPath = Paths.get(basePath, crawlerConfig.getStorage().getPdfDir()).toString();
            String dataPath = Paths.get(basePath, crawlerConfig.getStorage().getDataDir()).toString();
            String exportPath = Paths.get(basePath, crawlerConfig.getStorage().getExportDir()).toString();

            // 统计文件数量
            File imageDir = new File(imagePath);
            File pdfDir = new File(pdfPath);
            File dataDir = new File(dataPath);
            File exportDir = new File(exportPath);

            int imageCount = imageDir.exists() && imageDir.isDirectory() ?
                    (imageDir.listFiles() != null ? imageDir.listFiles().length : 0) : 0;
            int pdfCount = pdfDir.exists() && pdfDir.isDirectory() ?
                    (pdfDir.listFiles() != null ? pdfDir.listFiles().length : 0) : 0;

            // 计算目录大小（简化版本）
            long imageSize = getDirectorySize(imageDir);
            long pdfSize = getDirectorySize(pdfDir);

            Map<String, Object> storageInfo = new HashMap<>();
            storageInfo.put("basePath", basePath);
            storageInfo.put("paths", Map.of(
                    "images", Map.of(
                            "path", imagePath,
                            "relativePath", crawlerConfig.getStorage().getImageDir(),
                            "exists", imageDir.exists(),
                            "fileCount", imageCount,
                            "sizeBytes", imageSize,
                            "sizeMB", round(imageSize / 1024.0 / 1024.0, 2)
                    ),
                    "pdfs", Map.of(
                            "path", pdfPath,
                            "relativePath", crawlerConfig.getStorage().getPdfDir(),
                            "exists", pdfDir.exists(),
                            "fileCount", pdfCount,
                            "sizeBytes", pdfSize,
                            "sizeMB", round(pdfSize / 1024.0 / 1024.0, 2)
                    ),
                    "data", Map.of(
                            "path", dataPath,
                            "relativePath", crawlerConfig.getStorage().getDataDir(),
                            "exists", dataDir.exists()
                    ),
                    "exports", Map.of(
                            "path", exportPath,
                            "relativePath", crawlerConfig.getStorage().getExportDir(),
                            "exists", exportDir.exists()
                    )
            ));
            storageInfo.put("saveImages", crawlerConfig.getSaveImages());
            storageInfo.put("config", Map.of(
                    "storageBasePath", storageBasePath,
                    "imageDir", crawlerConfig.getStorage().getImageDir(),
                    "pdfDir", crawlerConfig.getStorage().getPdfDir(),
                    "dataDir", crawlerConfig.getStorage().getDataDir(),
                    "exportDir", crawlerConfig.getStorage().getExportDir()
            ));

            return Result.success(storageInfo);

        } catch (Exception e) {
            log.error("获取存储路径信息失败", e);
            return Result.error("获取存储路径信息失败: " + e.getMessage());
        }
    }

    /**
     * 递归计算目录大小
     */
    private long getDirectorySize(File directory) {
        if (!directory.exists() || !directory.isDirectory()) {
            return 0;
        }

        long size = 0;
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile()) {
                    size += file.length();
                } else if (file.isDirectory()) {
                    size += getDirectorySize(file);
                }
            }
        }
        return size;
    }

    /**
     * 四舍五入保留指定小数位
     */
    private double round(double value, int places) {
        if (places < 0) throw new IllegalArgumentException();
        long factor = (long) Math.pow(10, places);
        value = value * factor;
        long tmp = Math.round(value);
        return (double) tmp / factor;
    }

    /**
     * 清理Redis队列（用于重置爬虫状态）
     * 慎用：会清除所有待处理和处理中的任务
     */
    @PostMapping("/clear-redis-queue")
    public Result<Map<String, Object>> clearRedisQueue() {
        try {
            log.warn("========== 手动清理Redis队列 ==========");

            // 1. 获取清理前的状态
            Long pendingBefore = redisTemplate.opsForZSet().size("crawler:queue:pending");
            Long processingBefore = redisTemplate.opsForSet().size("crawler:queue:processing");
            Long dedupBefore = redisTemplate.opsForSet().size("crawler:dedup:category");

            // 2. 清空所有队列
            queueService.clearAllQueues();

            // 3. 重新初始化状态
            queueService.initializeState();

            // 4. 返回清理结果
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "Redis队列已清理");
            result.put("cleared", Map.of(
                    "pending", pendingBefore != null ? pendingBefore : 0,
                    "processing", processingBefore != null ? processingBefore : 0,
                    "dedup", dedupBefore != null ? dedupBefore : 0
            ));

            log.info("Redis队列清理完成: pending={}, processing={}, dedup={}",
                    pendingBefore, processingBefore, dedupBefore);

            return Result.success(result);

        } catch (Exception e) {
            log.error("清理Redis队列失败", e);
            return Result.error("清理失败: " + e.getMessage());
        }
    }

    /**
     * 健康检查
     */
    @GetMapping("/health")
    public Result<Map<String, Object>> healthCheck() {
        Map<String, Object> health = Map.of(
                "status", "UP",
                "version", "3.0",
                "timestamp", System.currentTimeMillis()
        );
        return Result.success(health);
    }
    @PostMapping("/config/pdf")
    public Result<String> togglePdfDownload(@org.springframework.web.bind.annotation.RequestParam boolean enable) {
        try {
            // 把前端传来的开关状态存入 Redis
            redisTemplate.opsForValue().set("crawler:config:savePdf", String.valueOf(enable));
            log.info("========== 前端将全局 PDF 下载开关设置为: {} ==========", enable);
            return Result.success(enable ? "PDF下载已开启" : "PDF下载已关闭");
        } catch (Exception e) {
            log.error("设置PDF开关失败", e);
            return Result.error("设置失败: " + e.getMessage());
        }
    }

}