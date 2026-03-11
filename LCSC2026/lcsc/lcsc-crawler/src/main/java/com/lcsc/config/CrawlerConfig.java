package com.lcsc.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
/**
 * 爬虫配置类
 * * @author lcsc-crawler
 * @since 2026-02-23
 */
@Primary // 👇 2. 加上这行注解，解决 Bean 冲突报错！
@Configuration
@ConfigurationProperties(prefix = "crawler")
public class CrawlerConfig {

    /** 爬取间隔(毫秒) */
    private Long delay = 2000L;

    /** 超时时间(毫秒) */
    private Long timeout = 10000L;

    /** User-Agent */
    private String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    /** 是否启用无头模式 */
    private Boolean headless = true;

    /** 并发线程数 */
    private Integer threadPoolSize = 5;

    /** 最大重试次数 */
    private Integer maxRetry = 10;

    /** 重试之间的等待间隔(毫秒) */
    private Long retryInterval = 5000L;

    /** 是否自动重试 */
    private Boolean autoRetry = true;

    /** 是否保存图片 */
    private Boolean saveImages = true;

    /** 是否开启 PDF 下载 */
    private Boolean enablePdfDownload = true;

    // --- 资源下载并发控制 ---

    /** 图片下载最大并发数 */
    private Integer imageConcurrency = 5;

    /** 图片下载间隔(毫秒) */
    private Long imageDelay = 500L;

    /** PDF 下载最大并发数 */
    private Integer pdfConcurrency = 1;

    /** PDF 下载间隔(毫秒) */
    private Long pdfDelay = 1500L;

    /** API地址配置 */
    private Api api = new Api();

    /** 存储配置 */
    private Storage storage = new Storage();

    /**
     * API地址配置类
     */
    public static class Api {
        private String catalogUrl = "https://wmsc.lcsc.com/ftps/wm/product/catalogs/search";
        private String searchParamGroupUrl = "https://wmsc.lcsc.com/ftps/wm/product/search/param/group";
        private String searchListUrl = "https://wmsc.lcsc.com/ftps/wm/product/search/list";

        public String getCatalogUrl() { return catalogUrl; }
        public void setCatalogUrl(String catalogUrl) { this.catalogUrl = catalogUrl; }
        public String getSearchParamGroupUrl() { return searchParamGroupUrl; }
        public void setSearchParamGroupUrl(String searchParamGroupUrl) { this.searchParamGroupUrl = searchParamGroupUrl; }
        public String getSearchListUrl() { return searchListUrl; }
        public void setSearchListUrl(String searchListUrl) { this.searchListUrl = searchListUrl; }
    }

    /**
     * 存储配置类
     */
    public static class Storage {
        private String basePath = ".";
        private String dataDir = "data";
        private String exportDir = "exports";
        private String imageDir = "images";
        private String pdfDir = "pdfs";

        public String getBasePath() { return basePath; }
        public void setBasePath(String basePath) { this.basePath = basePath; }
        public String getDataDir() { return dataDir; }
        public void setDataDir(String dataDir) { this.dataDir = dataDir; }
        public String getExportDir() { return exportDir; }
        public void setExportDir(String exportDir) { this.exportDir = exportDir; }
        public String getImageDir() { return imageDir; }
        public void setImageDir(String imageDir) { this.imageDir = imageDir; }
        public String getPdfDir() { return pdfDir; }
        public void setPdfDir(String pdfDir) { this.pdfDir = pdfDir; }
    }

    // --- Getters and Setters ---

    public Long getDelay() { return delay; }
    public void setDelay(Long delay) { this.delay = delay; }

    public Long getTimeout() { return timeout; }
    public void setTimeout(Long timeout) { this.timeout = timeout; }

    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }

    public Boolean getHeadless() { return headless; }
    public void setHeadless(Boolean headless) { this.headless = headless; }

    public Integer getThreadPoolSize() { return threadPoolSize; }
    public void setThreadPoolSize(Integer threadPoolSize) { this.threadPoolSize = threadPoolSize; }

    public Integer getMaxRetry() { return maxRetry; }
    public void setMaxRetry(Integer maxRetry) { this.maxRetry = maxRetry; }

    public Long getRetryInterval() { return retryInterval; }
    public void setRetryInterval(Long retryInterval) { this.retryInterval = retryInterval; }

    public Boolean getAutoRetry() { return autoRetry; }
    public void setAutoRetry(Boolean autoRetry) { this.autoRetry = autoRetry; }

    public Boolean getSaveImages() { return saveImages; }
    public void setSaveImages(Boolean saveImages) { this.saveImages = saveImages; }

    public Boolean getEnablePdfDownload() { return enablePdfDownload; }
    public void setEnablePdfDownload(Boolean enablePdfDownload) { this.enablePdfDownload = enablePdfDownload; }

    public Integer getImageConcurrency() { return imageConcurrency; }
    public void setImageConcurrency(Integer imageConcurrency) { this.imageConcurrency = imageConcurrency; }

    public Long getImageDelay() { return imageDelay; }
    public void setImageDelay(Long imageDelay) { this.imageDelay = imageDelay; }

    public Integer getPdfConcurrency() { return pdfConcurrency; }
    public void setPdfConcurrency(Integer pdfConcurrency) { this.pdfConcurrency = pdfConcurrency; }

    public Long getPdfDelay() { return pdfDelay; }
    public void setPdfDelay(Long pdfDelay) { this.pdfDelay = pdfDelay; }

    public Api getApi() { return api; }
    public void setApi(Api api) { this.api = api; }

    public Storage getStorage() { return storage; }
    public void setStorage(Storage storage) { this.storage = storage; }
}