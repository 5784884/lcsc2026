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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.Objects;
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
                String filename = dateStr + "_高级导出.xlsx";
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
                        ZipEntry entry = new ZipEntry(dateStr + "_高级导出_part" + partCount + ".xlsx");
                        zos.putNextEntry(entry);
                        zos.write(excelBytes);
                        zos.closeEntry();

                        partCount++;
                    }
                }

                byte[] zipBytes = zipBaos.toByteArray();
                // 🌟 修改点 3：压缩包本身的名称
                String filename = dateStr + "_高级导出.zip";
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