package com.lcsc.service.crawler;

import org.springframework.stereotype.Service;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Map;
import java.util.HashMap;

/**
 * 下载状态追踪服务
 */
@Service
public class DownloadStatusService {
    // 图片统计
    private final AtomicLong totalImages = new AtomicLong(0);
    private final AtomicLong downloadedImages = new AtomicLong(0);
    private final AtomicLong failedImages = new AtomicLong(0);

    // PDF 统计
    private final AtomicLong totalPdfs = new AtomicLong(0);
    private final AtomicLong downloadedPdfs = new AtomicLong(0);
    private final AtomicLong failedPdfs = new AtomicLong(0);

    // --- 增加任务总数 ---
    public void addTotalImages(int count) { totalImages.addAndGet(count); }
    public void addTotalPdfs(int count) { totalPdfs.addAndGet(count); }

    // --- 记录成功 ---
    public void incrementImageSuccess() { downloadedImages.incrementAndGet(); }
    public void incrementPdfSuccess() { downloadedPdfs.incrementAndGet(); }

    // --- 记录失败 ---
    public void incrementImageFailed() { failedImages.incrementAndGet(); }
    public void incrementPdfFailed() { failedPdfs.incrementAndGet(); }

    // --- 获取统计状态 ---
    public Map<String, Object> getStatusMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("totalImages", totalImages.get());
        map.put("downloadedImages", downloadedImages.get());
        map.put("failedImages", failedImages.get());

        map.put("totalPdfs", totalPdfs.get());
        map.put("downloadedPdfs", downloadedPdfs.get());
        map.put("failedPdfs", failedPdfs.get());
        return map;
    }

    // 重置统计（可选，在爬虫开始前调用）
    public void reset() {
        totalImages.set(0); downloadedImages.set(0); failedImages.set(0);
        totalPdfs.set(0); downloadedPdfs.set(0); failedPdfs.set(0);
    }
}