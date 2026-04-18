package com.lcsc.controller;

import com.lcsc.common.Result;
import com.lcsc.dto.AdvancedExportRequest;
import com.lcsc.dto.ExportTaskItem;
import com.lcsc.service.AdvancedExportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 高级导出控制器 - 淘宝CSV格式
 */
@RestController
@RequestMapping("/api/export")
@CrossOrigin(origins = {"http://localhost:5173", "http://localhost:5174"})
public class AdvancedExportController {

    @Autowired
    private AdvancedExportService advancedExportService;

    // 异步任务状态存储（内存，重启丢失，但导出任务是短暂的）
    private static final ConcurrentHashMap<String, ExportJobStatus> jobStatusMap = new ConcurrentHashMap<>();
    private static final ExecutorService exportExecutor = Executors.newFixedThreadPool(3);

    private static class ExportJobStatus {
        String status; // pending / done / error
        String filename;
        String errorMsg;
        ExportJobStatus(String status) { this.status = status; }
    }

    /**
     * 添加产品到任务列表（批量添加模式）
     * @param requestBody 包含筛选条件和当前任务列表
     * @return 更新后的任务列表
     */
    @PostMapping("/add-task")
    public Result<List<ExportTaskItem>> addToTaskList(@RequestBody Map<String, Object> requestBody) {
        try {
            // 解析请求参数
            AdvancedExportRequest request = parseRequest(requestBody);

            // 手动转换 currentTasks (LinkedHashMap -> ExportTaskItem)
            List<ExportTaskItem> currentTasks = parseCurrentTasks(requestBody);

            // 添加到任务列表
            List<ExportTaskItem> updatedTasks = advancedExportService.addToTaskList(request, currentTasks);

            return Result.success(updatedTasks);
        } catch (Exception e) {
            return Result.error("添加任务失败: " + e.getMessage());
        }
    }

    /**
     * 异步提交导出任务，立即返回 taskId，后台生成文件
     */
    @PostMapping("/submit-taobao-excel")
    public Result<String> submitTaobaoExcel(
            @RequestParam(required = false, defaultValue = "1000") Integer splitSize,
            @RequestBody List<Map<String, Object>> tasksRaw) {
        if (splitSize == null || splitSize <= 0) splitSize = 1000;

        List<ExportTaskItem> tasks = parseTasks(tasksRaw);
        String taskId = UUID.randomUUID().toString();
        ExportJobStatus status = new ExportJobStatus("pending");
        jobStatusMap.put(taskId, status);

        final int finalSplitSize = splitSize;
        exportExecutor.submit(() -> {
            try {
                String dateStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
                Path exportDir = Paths.get("/app/exports");
                if (!Files.exists(exportDir)) Files.createDirectories(exportDir);

                String filename;
                byte[] fileBytes;

                if (tasks.size() <= finalSplitSize) {
                    filename = dateStr + "_1.xlsx";  // ✅ 改为 日期_1.xlsx
                    fileBytes = advancedExportService.generateTaobaoExcel(tasks);
                } else {
                    filename = dateStr + ".zip";     // ✅ 改为 日期.zip
                    ByteArrayOutputStream zipBaos = new ByteArrayOutputStream();
                    try (ZipOutputStream zos = new ZipOutputStream(zipBaos)) {
                        int partCount = 1;
                        for (int i = 0; i < tasks.size(); i += finalSplitSize) {
                            List<ExportTaskItem> subList = tasks.subList(i, Math.min(i + finalSplitSize, tasks.size()));
                            byte[] excelBytes = advancedExportService.generateTaobaoExcel(subList);
                            ZipEntry entry = new ZipEntry(dateStr + "_" + partCount + ".xlsx"); // ✅ 改为 日期_表序号.xlsx
                            zos.putNextEntry(entry);
                            zos.write(excelBytes);
                            zos.closeEntry();
                            partCount++;
                        }
                    }
                    fileBytes = zipBaos.toByteArray();
                }

                Files.write(exportDir.resolve(filename), fileBytes);
                status.filename = filename;
                status.status = "done";
            } catch (Exception e) {
                status.status = "error";
                status.errorMsg = e.getMessage();
            }
        });

        return Result.success(taskId);
    }

    /**
     * 查询异步导出任务状态
     */
    @GetMapping("/task-status/{taskId}")
    public Result<Map<String, String>> getTaskStatus(@PathVariable String taskId) {
        ExportJobStatus status = jobStatusMap.get(taskId);
        if (status == null) return Result.error("任务不存在");
        Map<String, String> result = new HashMap<>();
        result.put("status", status.status);
        if ("done".equals(status.status)) result.put("filename", status.filename);
        if ("error".equals(status.status)) result.put("errorMsg", status.errorMsg);
        return Result.success(result);
    }

    /**
     * 下载已生成的导出文件
     */
    @GetMapping("/download/{taskId}")
    public ResponseEntity<byte[]> downloadExport(@PathVariable String taskId) throws IOException {
        ExportJobStatus status = jobStatusMap.get(taskId);
        if (status == null || !"done".equals(status.status)) {
            return ResponseEntity.notFound().build();
        }
        Path filePath = Paths.get("/app/exports", status.filename);
        if (!Files.exists(filePath)) return ResponseEntity.notFound().build();

        byte[] fileBytes = Files.readAllBytes(filePath);
        String encodedFilename = URLEncoder.encode(status.filename, StandardCharsets.UTF_8);
        boolean isZip = status.filename.endsWith(".zip");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(isZip ? MediaType.parseMediaType("application/zip")
                : MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + encodedFilename + "\"; filename*=UTF-8''" + encodedFilename);
        headers.setContentLength(fileBytes.length);

        // 下载后清理状态
        jobStatusMap.remove(taskId);

        return ResponseEntity.ok().headers(headers).body(fileBytes);
    }

    /**
     * 异步提交导出任务 (CSV格式)，立即返回 taskId，后台生成文件
     */
    @PostMapping("/submit-taobao-csv")
    public Result<String> submitTaobaoCsv(
            @RequestParam(required = false, defaultValue = "1000") Integer splitSize,
            @RequestBody List<Map<String, Object>> tasksRaw) {
        if (splitSize == null || splitSize <= 0) splitSize = 1000;

        List<ExportTaskItem> tasks = parseTasks(tasksRaw);
        String taskId = UUID.randomUUID().toString();
        ExportJobStatus status = new ExportJobStatus("pending");
        jobStatusMap.put(taskId, status);

        final int finalSplitSize = splitSize;
        exportExecutor.submit(() -> {
            try {
                // 生成日期前缀，例如：20240402_112656
                String dateStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
                Path exportDir = Paths.get("/app/exports");
                if (!Files.exists(exportDir)) {
                    Files.createDirectories(exportDir);
                }

                String filename;
                byte[] fileBytes;

                if (tasks.size() <= finalSplitSize) {
                    // 【修改点1】：单文件时，名称直接为 "日期_1.csv"
                    filename = dateStr + "_1.csv";

                    // 🌟 调用 Service 层已有的 CSV 生成方法
                    fileBytes = advancedExportService.generateTaobaoCsv(tasks);
                } else {
                    // 【修改点2】：如果是打包ZIP，ZIP文件名可以保留 "_CSV.zip" 区分，或者直接叫 "日期.zip"
                    filename = dateStr + ".zip";

                    ByteArrayOutputStream zipBaos = new ByteArrayOutputStream();
                    try (ZipOutputStream zos = new ZipOutputStream(zipBaos)) {
                        int partCount = 1;
                        for (int i = 0; i < tasks.size(); i += finalSplitSize) {
                            List subList = tasks.subList(i, Math.min(i + finalSplitSize, tasks.size()));
                            byte[] csvBytes = advancedExportService.generateTaobaoCsv(subList);

                            // 【修改点3】：Zip内部的文件名改为 "日期_表序号.csv"，例如 "20240402_112656_1.csv"
                            ZipEntry entry = new ZipEntry(dateStr + "_" + partCount + ".csv");

                            zos.putNextEntry(entry);
                            zos.write(csvBytes);
                            zos.closeEntry();
                            partCount++;
                        }
                    }
                    fileBytes = zipBaos.toByteArray();
                }

                Files.write(exportDir.resolve(filename), fileBytes);
                status.filename = filename;
                status.status = "done";
            } catch (Exception e) {
                status.status = "error";
                status.errorMsg = e.getMessage();
            }
        });

        return Result.success(taskId);
    }
    /**
     * 导出任务列表为淘宝Excel格式 (支持超过 N 条自动分表打 ZIP 包)
     * @param splitSize 自动分表的每表条数
     * @param tasksRaw 任务列表
     * @return Excel文件或ZIP压缩包的字节流
     */
    @PostMapping("/export-taobao-excel")
    public ResponseEntity<byte[]> exportTaobaoExcel(
            @RequestParam(required = false, defaultValue = "1000") Integer splitSize,
            @RequestBody List<Map<String, Object>> tasksRaw) {
        try {
            // 防御性处理：如果前端传了 0 或负数，强制设为 1000
            if (splitSize == null || splitSize <= 0) {
                splitSize = 1000;
            }

            // 手动转换任务列表
            List<ExportTaskItem> tasks = tasksRaw.stream().map(taskMap -> {
                ExportTaskItem task = new ExportTaskItem();
                if (taskMap.containsKey("productCode")) {
                    task.setProductCode((String) taskMap.get("productCode"));
                }
                if (taskMap.containsKey("model")) {
                    task.setModel((String) taskMap.get("model"));
                }
                if (taskMap.containsKey("brand")) {
                    task.setBrand((String) taskMap.get("brand"));
                }
                if (taskMap.containsKey("shopId")) {
                    task.setShopId(((Number) taskMap.get("shopId")).intValue());
                }
                if (taskMap.containsKey("shopName")) {
                    task.setShopName((String) taskMap.get("shopName"));
                }
                if (taskMap.containsKey("discounts")) {
                    @SuppressWarnings("unchecked")
                    List<Number> discountNumbers = (List<Number>) taskMap.get("discounts");
                    List<BigDecimal> discounts = discountNumbers.stream()
                            .map(n -> new BigDecimal(n.toString()))
                            .collect(Collectors.toList());
                    task.setDiscounts(discounts);
                }
                if (taskMap.containsKey("addedAt")) {
                    task.setAddedAt(((Number) taskMap.get("addedAt")).longValue());
                }
                return task;
            }).collect(Collectors.toList());

            // 生成文件名日期前缀
            String dateStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));

            // ==============================================================================
            // 核心逻辑：判断是否需要分表
            // ==============================================================================
            if (tasks.size() <= splitSize) {
                // 【情况 A】数据量小于等于设定的条数，走原逻辑，直接导出单个 XLSX 文件
                byte[] excelBytes = advancedExportService.generateTaobaoExcel(tasks);
                // 🌟 修改点 1：单文件导出的名称
                String filename = dateStr + "_1.xlsx";
                String encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8);

                // 落盘逻辑
                try {
                    java.nio.file.Path exportDir = java.nio.file.Paths.get("/app/exports");
                    if (!java.nio.file.Files.exists(exportDir)) {
                        java.nio.file.Files.createDirectories(exportDir);
                    }
                    java.nio.file.Path filePath = exportDir.resolve(filename);
                    java.nio.file.Files.write(filePath, excelBytes);
                    System.out.println("高级导出单文件成功落盘: " + filePath.toString());
                } catch (Exception e) {
                    e.printStackTrace();
                    System.err.println("高级导出：保存到本地硬盘失败！但仍会继续发送给浏览器。");
                }

                // 设置响应头
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
                headers.set(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + encodedFilename + "\"; filename*=UTF-8''" + encodedFilename);
                headers.setContentLength(excelBytes.length);

                return ResponseEntity.ok()
                        .headers(headers)
                        .body(excelBytes);

            } else {
                // 【情况 B】数据量大于设定的条数，切割数据并打包为 ZIP 文件
                ByteArrayOutputStream zipBaos = new ByteArrayOutputStream();
                try (ZipOutputStream zos = new ZipOutputStream(zipBaos)) {
                    int partCount = 1;
                    for (int i = 0; i < tasks.size(); i += splitSize) {
                        // 切出子列表
                        List<ExportTaskItem> subList = tasks.subList(i, Math.min(i + splitSize, tasks.size()));

                        // 生成子列表的 Excel 字节
                        byte[] excelBytes = advancedExportService.generateTaobaoExcel(subList);

                        // 🌟 修改点 2：压缩包内部子文件的名称
                        ZipEntry entry = new ZipEntry(dateStr + "_" + partCount + ".xlsx");
                        zos.putNextEntry(entry);
                        zos.write(excelBytes);
                        zos.closeEntry();

                        partCount++;
                    }
                }

                byte[] zipBytes = zipBaos.toByteArray();
                // 🌟 修改点 3：压缩包本身的名称
                String filename = dateStr + ".zip";
                String encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8);

                // 落盘逻辑
                try {
                    java.nio.file.Path exportDir = java.nio.file.Paths.get("/app/exports");
                    if (!java.nio.file.Files.exists(exportDir)) {
                        java.nio.file.Files.createDirectories(exportDir);
                    }
                    java.nio.file.Path filePath = exportDir.resolve(filename);
                    java.nio.file.Files.write(filePath, zipBytes);
                    System.out.println("高级导出ZIP包成功落盘: " + filePath.toString());
                } catch (Exception e) {
                    e.printStackTrace();
                    System.err.println("高级导出：保存ZIP到本地硬盘失败！但仍会继续发送给浏览器。");
                }

                // 设置响应头
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.parseMediaType("application/zip"));
                headers.set(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + encodedFilename + "\"; filename*=UTF-8''" + encodedFilename);
                headers.setContentLength(zipBytes.length);

                return ResponseEntity.ok()
                        .headers(headers)
                        .body(zipBytes);
            }

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().build();
        }
    }

    // ==================== 辅助方法 ====================

    private void saveToExportDir(String filename, byte[] bytes) {
        try {
            Path dir = Paths.get("/app/exports");
            if (!Files.exists(dir)) Files.createDirectories(dir);
            Files.write(dir.resolve(filename), bytes);
        } catch (Exception e) {
            System.err.println("落盘失败: " + e.getMessage());
        }
    }

    private ResponseEntity<byte[]> buildFileResponse(byte[] bytes, String filename, boolean isZip) throws Exception {
        String encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(isZip ? MediaType.parseMediaType("application/zip")
                : MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + encodedFilename + "\"; filename*=UTF-8''" + encodedFilename);
        headers.setContentLength(bytes.length);
        return ResponseEntity.ok().headers(headers).body(bytes);
    }

    private List<ExportTaskItem> parseTasks(List<Map<String, Object>> tasksRaw) {
        return tasksRaw.stream().map(taskMap -> {
            ExportTaskItem task = new ExportTaskItem();
            if (taskMap.containsKey("productCode")) task.setProductCode((String) taskMap.get("productCode"));
            if (taskMap.containsKey("model")) task.setModel((String) taskMap.get("model"));
            if (taskMap.containsKey("brand")) task.setBrand((String) taskMap.get("brand"));
            if (taskMap.containsKey("shopId")) task.setShopId(((Number) taskMap.get("shopId")).intValue());
            if (taskMap.containsKey("shopName")) task.setShopName((String) taskMap.get("shopName"));
            if (taskMap.containsKey("discounts")) {
                @SuppressWarnings("unchecked")
                List<Number> discountNumbers = (List<Number>) taskMap.get("discounts");
                task.setDiscounts(discountNumbers.stream()
                        .map(n -> new BigDecimal(n.toString()))
                        .collect(Collectors.toList()));
            }
            if (taskMap.containsKey("addedAt")) task.setAddedAt(((Number) taskMap.get("addedAt")).longValue());
            return task;
        }).collect(Collectors.toList());
    }

    /**
     * 从Map解析currentTasks列表（处理LinkedHashMap -> ExportTaskItem转换）
     */
    @SuppressWarnings("unchecked")
    private List<ExportTaskItem> parseCurrentTasks(Map<String, Object> requestBody) {
        if (!requestBody.containsKey("currentTasks")) {
            return new ArrayList<>();
        }

        List<Map<String, Object>> taskMaps = (List<Map<String, Object>>) requestBody.get("currentTasks");

        return taskMaps.stream().map(taskMap -> {
            ExportTaskItem task = new ExportTaskItem();

            if (taskMap.containsKey("productCode")) {
                task.setProductCode((String) taskMap.get("productCode"));
            }
            if (taskMap.containsKey("model")) {
                task.setModel((String) taskMap.get("model"));
            }
            if (taskMap.containsKey("brand")) {
                task.setBrand((String) taskMap.get("brand"));
            }
            if (taskMap.containsKey("shopId")) {
                task.setShopId(((Number) taskMap.get("shopId")).intValue());
            }
            if (taskMap.containsKey("shopName")) {
                task.setShopName((String) taskMap.get("shopName"));
            }
            if (taskMap.containsKey("discounts")) {
                List<Number> discountNumbers = (List<Number>) taskMap.get("discounts");
                List<BigDecimal> discounts = discountNumbers.stream()
                        .map(n -> new BigDecimal(n.toString()))
                        .collect(Collectors.toList());
                task.setDiscounts(discounts);
            }
            if (taskMap.containsKey("addedAt")) {
                task.setAddedAt(((Number) taskMap.get("addedAt")).longValue());
            }

            return task;
        }).collect(Collectors.toList());
    }

    /**
     * 从Map解析AdvancedExportRequest对象 (安全增强版)
     */
    private AdvancedExportRequest parseRequest(Map<String, Object> requestBody) {
        AdvancedExportRequest request = new AdvancedExportRequest();

        // 1. shopId 处理
        Object shopIdObj = requestBody.get("shopId");
        if (shopIdObj != null) {
            request.setShopId(((Number) shopIdObj).intValue());
        }

        // 2. categoryIds 处理
        Object categoryIdsObj = requestBody.get("categoryIds");
        if (categoryIdsObj != null) {
            @SuppressWarnings("unchecked")
            List<Object> ids = (List<Object>) categoryIdsObj;
            request.setCategoryIds(ids.stream()
                    .filter(Objects::nonNull) // 过滤掉数组里的空元素
                    .map(id -> ((Number) id).intValue())
                    .collect(Collectors.toList()));
        }

        // 3. brands 处理
        Object brandsObj = requestBody.get("brands");
        if (brandsObj != null) {
            @SuppressWarnings("unchecked")
            List<String> brandsList = (List<String>) brandsObj;
            request.setBrands(brandsList);
        }

        // 4. hasImage 处理 (Boolean 不需要 intValue)
        request.setHasImage((Boolean) requestBody.get("hasImage"));

        // 5. stockMin 处理 (这里最容易出 null 报错)
        Object stockMinObj = requestBody.get("stockMin");
        if (stockMinObj != null) {
            request.setStockMin(((Number) stockMinObj).intValue());
        }

        // 6. stockMax 处理
        Object stockMaxObj = requestBody.get("stockMax");
        if (stockMaxObj != null) {
            request.setStockMax(((Number) stockMaxObj).intValue());
        }

        // 7. discounts 处理
        Object discountsObj = requestBody.get("discounts");
        if (discountsObj != null) {
            @SuppressWarnings("unchecked")
            List<Number> discountsList = (List<Number>) discountsObj;
            request.setDiscounts(discountsList.stream()
                    .map(n -> new java.math.BigDecimal(n.toString()))
                    .collect(Collectors.toList()));
        }

        Object matchAnyObj = requestBody.get("matchAny");
        if (matchAnyObj != null) {
            request.setMatchAny((Boolean) matchAnyObj);
        }

        return request;
    }
}