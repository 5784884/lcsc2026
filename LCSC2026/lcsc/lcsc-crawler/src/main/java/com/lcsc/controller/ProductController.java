package com.lcsc.controller;

import java.util.Objects;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.lcsc.common.Result;
import com.lcsc.entity.Product;
import com.lcsc.service.ProductService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 产品管理控制器
 *
 * @author lcsc-crawler
 * @since 2024-01-01
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = {"http://localhost:5173", "http://localhost:5174", "http://localhost:5177", "http://127.0.0.1:5177"})
public class ProductController {

    @Autowired
    private ProductService productService;

    @Autowired
    private com.lcsc.service.crawler.DataExportService dataExportService;

    @Value("${crawler.storage.base-path}")
    private String storageBasePath;

    // --- 全新的资源浏览器 API ---

    @GetMapping("/resources/folders")
    public Result<Map<String, Object>> getResourceFolders(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "20") int size
    ) {
        try {
            Path basePath = Paths.get(storageBasePath);
            if (!Files.exists(basePath) || !Files.isDirectory(basePath)) {
                return Result.page(new ArrayList<>(), 0L, (long)current, (long)size);
            }

            List<Map<String, Object>> folderInfoList;
            try (Stream<Path> stream = Files.list(basePath)) {
                folderInfoList = stream
                        .filter(Files::isDirectory)
                        .map(productDir -> {
                            String productCode = productDir.getFileName().toString();
                            Map<String, Object> info = new HashMap<>();
                            info.put("productCode", productCode);
                            try {
                                info.put("imageCount", countFiles(productDir.resolve("images")));
                                info.put("pdfCount", countFiles(productDir.resolve("pdfs")));
                                info.put("lastModified", Files.getLastModifiedTime(productDir).toMillis());
                            } catch (IOException e) {
                                info.put("imageCount", 0);
                                info.put("pdfCount", 0);
                                info.put("lastModified", 0);
                            }
                            return info;
                        })
                        .sorted(Comparator.comparing(m -> (long)m.get("lastModified"), Comparator.reverseOrder()))
                        .collect(Collectors.toList());
            }

            long total = folderInfoList.size();
            int fromIndex = (current - 1) * size;
            int toIndex = (int)Math.min(fromIndex + size, total);

            if (fromIndex >= total) {
                return Result.page(new ArrayList<>(), total, (long)current, (long)size);
            }

            List<Map<String, Object>> paginatedList = folderInfoList.subList(fromIndex, toIndex);

            return Result.page(paginatedList, total, (long)current, (long)size);

        } catch (IOException e) {
            return Result.error("获取资源文件夹失败: " + e.getMessage());
        }
    }

    private long countFiles(Path directory) throws IOException {
        if (!Files.exists(directory) || !Files.isDirectory(directory)) {
            return 0;
        }
        try (Stream<Path> stream = Files.list(directory)) {
            return stream.filter(Files::isRegularFile).count();
        }
    }


    // --- 产品管理 API ---

    // --- 产品管理 API ---

    @PostMapping("/products/page") // ✅ 1. 改为 PostMapping
    public Result<Map<String, Object>> getProductPage(@RequestBody Map<String, Object> params) { // ✅ 2. 使用 @RequestBody 接收大体积 JSON

        // 1. 解析分页参数
        Integer current = params.get("current") != null ? Integer.valueOf(params.get("current").toString()) : 1;
        Integer size = params.get("size") != null ? Integer.valueOf(params.get("size").toString()) : 20;

        // 2. 解析基础字符串参数
        String productCode = (String) params.get("productCode");
        String brand = (String) params.get("brand");
        String model = (String) params.get("model");
        String packageName = (String) params.get("packageName");

        // 3. 复用你写好的 parseCategoryIdList 方法解析分类
        List<Integer> categoryLevel1Id = parseCategoryIdList(params.get("categoryLevel1Id"));
        List<Integer> categoryLevel2Id = parseCategoryIdList(params.get("categoryLevel2Id"));
        List<Integer> categoryLevel3Id = parseCategoryIdList(params.get("categoryLevel3Id"));

        // 4. 解析布尔值和数值
        Boolean hasImage = null;
        if (params.get("hasImage") != null && !params.get("hasImage").toString().trim().isEmpty()) {
            hasImage = Boolean.valueOf(params.get("hasImage").toString());
        }

        Integer minStock = null;
        if (params.get("minStock") != null && !params.get("minStock").toString().trim().isEmpty()) {
            minStock = Integer.valueOf(params.get("minStock").toString());
        }

        Integer maxStock = null;
        if (params.get("maxStock") != null && !params.get("maxStock").toString().trim().isEmpty()) {
            maxStock = Integer.valueOf(params.get("maxStock").toString());
        }

        Boolean matchAny = false;
        if (params.get("matchAny") != null && !params.get("matchAny").toString().trim().isEmpty()) {
            matchAny = Boolean.valueOf(params.get("matchAny").toString());
        }

        // 5. 调用 Service 层进行查询
        IPage<Product> result = productService.getProductPage(current, size, productCode, brand,
                model, packageName, categoryLevel1Id,
                categoryLevel2Id, categoryLevel3Id, hasImage, minStock, maxStock, matchAny);

        return Result.page(result.getRecords(), result.getTotal(), current.longValue(), size.longValue());
    }
    @GetMapping("/products/{id}")
    public Result<Product> getProductById(@PathVariable Long id) {
        Product product = productService.getById(id);
        if (product == null) {
            return Result.notFound("产品不存在");
        }
        return Result.success(product);
    }

    @GetMapping("/products/code/{productCode}")
    public Result<Product> getProductByCode(@PathVariable String productCode) {
        Product product = productService.getByProductCode(productCode);
        if (product == null) {
            return Result.notFound("产品不存在");
        }
        return Result.success(product);
    }

    @PostMapping("/products")
    public Result<String> addProduct(@RequestBody Product product) {
        boolean success = productService.save(product);
        if (success) {
            return Result.success("产品添加成功");
        } else {
            return Result.error("产品添加失败");
        }
    }

    @PutMapping("/products/{id}")
    public Result<String> updateProduct(@PathVariable Long id, @RequestBody Product product) {
        product.setId(id.intValue());
        boolean success = productService.updateById(product);
        if (success) {
            return Result.success("产品更新成功");
        } else {
            return Result.error("产品更新失败");
        }
    }

    @DeleteMapping("/products/{id}")
    public Result<String> deleteProduct(@PathVariable Long id) {
        boolean success = productService.removeById(id);
        if (success) {
            return Result.success("产品删除成功");
        } else {
            return Result.error("产品删除失败");
        }
    }

    @DeleteMapping("/products/batch")
    public Result<String> deleteProductBatch(@RequestBody List<Long> ids) {
        boolean success = productService.removeByIds(ids);
        if (success) {
            return Result.success("批量删除成功，共删除" + ids.size() + "条记录");
        } else {
            return Result.error("批量删除失败");
        }
    }

    @GetMapping("/products/brand/{brand}")
    public Result<List<Product>> getProductListByBrand(@PathVariable String brand) {
        IPage<Product> result = productService.getProductPage(1, 1000, null, brand);
        return Result.success(result.getRecords());
    }

    @GetMapping("/products/category")
    public Result<List<Product>> getProductListByCategory(
            @RequestParam(required = false) Integer categoryLevel1Id,
            @RequestParam(required = false) Integer categoryLevel2Id
    ) {
        List<Product> products = productService.getProductListByCategory(categoryLevel1Id, categoryLevel2Id);
        return Result.success(products);
    }

    @GetMapping("/products/statistics")
    public Result<Map<String, Object>> getProductStatistics() {
        Map<String, Object> statistics = productService.getProductStatistics();
        return Result.success(statistics);
    }

    @GetMapping("/products/{productCode}/resources")
    public Result<Map<String, Object>> getProductResources(@PathVariable String productCode) {
        try {
            Map<String, Object> resources = new HashMap<>();
            List<Map<String, Object>> allFiles = new ArrayList<>();
            Path productPath = Paths.get(storageBasePath, productCode);

            addFilesToList(allFiles, productPath.resolve("images"), "image");
            addFilesToList(allFiles, productPath.resolve("pdfs"), "pdf");

            Map<String, List<Map<String, Object>>> groupedFiles = allFiles.stream()
                    .collect(Collectors.groupingBy(file -> (String) file.get("category")));

            resources.put("all", allFiles);
            resources.put("images", groupedFiles.getOrDefault("image", new ArrayList<>()));
            resources.put("pdfs", groupedFiles.getOrDefault("pdf", new ArrayList<>()));
            resources.put("total", allFiles.size());

            return Result.success(resources);

        } catch (Exception e) {
            return Result.error("获取产品资源失败: " + e.getMessage());
        }
    }

    private void addFilesToList(List<Map<String, Object>> allFiles, Path directory, String category) throws IOException {
        if (!Files.exists(directory) || !Files.isDirectory(directory)) {
            return;
        }
        try (Stream<Path> stream = Files.list(directory)) {
            stream.filter(Files::isRegularFile).forEach(file -> {
                try {
                    String filename = file.getFileName().toString();
                    Map<String, Object> fileInfo = new HashMap<>();
                    fileInfo.put("filename", filename);
                    fileInfo.put("size", Files.size(file));
                    fileInfo.put("lastModified", Files.getLastModifiedTime(file).toMillis());
                    fileInfo.put("url", "/api/resources/" + file.getParent().getParent().getFileName().toString() + "/" + category + "/" + filename);
                    fileInfo.put("category", category);
                    fileInfo.put("type", category.equals("image") ? "image" : "datasheet");
                    allFiles.add(fileInfo);
                } catch (IOException e) {
                    // Ignore individual file errors
                }
            });
        }
    }

    @GetMapping("/resources/{productCode}/{type}/{filename}")
    public ResponseEntity<Resource> getProductResource(@PathVariable String productCode, @PathVariable String type, @PathVariable String filename) {
        try {
            if (filename.contains("..") || productCode.contains("..")) {
                return ResponseEntity.badRequest().build();
            }

            // 将单数形式转换为复数形式，匹配实际目录结构
            String directoryType = type.equals("image") ? "images" : "pdfs";
            Path filePath = Paths.get(storageBasePath, productCode, directoryType, filename);
            if (!Files.exists(filePath)) {
                return ResponseEntity.notFound().build();
            }

            Resource resource = new FileSystemResource(filePath.toFile());
            String contentType = Files.probeContentType(filePath);
            if (contentType == null) contentType = "application/octet-stream";

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                    .body(resource);

        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    // ================ 新增：字符串转List的解析器 ================
    // ================ 终极增强版：多形态参数解析器 ================
    private List<Integer> parseCategoryIdList(Object param) {
        if (param == null) return null;

        // 情况1：前端直接传过来了 JSON 数组（如 List<Integer> 或 List<String>）
        if (param instanceof List) {
            List<?> list = (List<?>) param;
            if (list.isEmpty()) return null;

            return list.stream()
                    .filter(Objects::nonNull)
                    .map(item -> {
                        if (item instanceof Integer) return (Integer) item;
                        if (item instanceof Number) return ((Number) item).intValue();
                        try {
                            return Integer.valueOf(item.toString().trim());
                        } catch (NumberFormatException e) {
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        }

        // 情况2：前端传过来的是逗号分隔的字符串（如 "157, 158"）
        String str = param.toString().trim();
        if (str.isEmpty() || "undefined".equals(str) || "[]".equals(str)) return null;

        try {
            // 清理可能误传的方括号，比如 "[157, 158]"
            str = str.replace("[", "").replace("]", "");
            if (str.trim().isEmpty()) return null;

            return Arrays.stream(str.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(Integer::valueOf)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            // 解析失败不报错，默默忽略条件（防崩溃）
            return null;
        }
    }
    // =======================================================
    // --- 产品导出 API ---

    /**
     * 导出所有产品到Excel
     */
    @GetMapping("/products/export/excel/all")
    public CompletableFuture<Result<Map<String, Object>>> exportAllProductsToExcel() {
        return dataExportService.exportAllProductsToExcel()
                .thenApply(result -> {
                    if (result.isSuccess()) {
                        Map<String, Object> data = new HashMap<>();
                        data.put("filename", Paths.get(result.getFilePath()).getFileName().toString());
                        data.put("recordCount", result.getRecordCount());
                        data.put("message", result.getMessage());
                        return Result.success(data);
                    } else {
                        return Result.error(result.getMessage());
                    }
                });
    }

    /**
     * 根据搜索条件导出产品到Excel (已升级为支持集合查询)
     */
    @PostMapping("/products/export/excel")
    public CompletableFuture<Result<Map<String, Object>>> exportProductsToExcel(
            @RequestBody Map<String, Object> params) {

        List<Integer> l1Ids = parseCategoryIdList(params.get("categoryLevel1Id"));
        List<Integer> l2Ids = parseCategoryIdList(params.get("categoryLevel2Id"));
        List<Integer> l3Ids = parseCategoryIdList(params.get("categoryLevel3Id"));

        String brand = (String) params.get("brand");
        String productCode = (String) params.get("productCode");
        String model = (String) params.get("model");

        Boolean hasImage = null;
        if (params.get("hasImage") != null && !params.get("hasImage").toString().trim().isEmpty()) {
            hasImage = Boolean.valueOf(params.get("hasImage").toString());
        }
        Integer minStock = null;
        if (params.get("minStock") != null && !params.get("minStock").toString().trim().isEmpty()) {
            minStock = Integer.valueOf(params.get("minStock").toString());
        }
        Integer maxStock = null;
        if (params.get("maxStock") != null && !params.get("maxStock").toString().trim().isEmpty()) {
            maxStock = Integer.valueOf(params.get("maxStock").toString());
        }
        // --- 新增解析 matchAny ---
        Boolean matchAny = false;
        if (params.get("matchAny") != null && !params.get("matchAny").toString().trim().isEmpty()) {
            matchAny = Boolean.valueOf(params.get("matchAny").toString());
        }

        // 💥 修复这里：把原来的 hasStock 换成了 hasImage, minStock, maxStock，并加上了 matchAny 和 null (shopId)
        return dataExportService.exportProductsToExcel(l1Ids, l2Ids, l3Ids, brand, productCode, model, hasImage, minStock, maxStock, matchAny, null) // <-- 补上了 null 参数
                .thenApply(result -> {
                    if (result.isSuccess()) {
                        Map<String, Object> data = new HashMap<>();
                        data.put("filename", Paths.get(result.getFilePath()).getFileName().toString());
                        data.put("recordCount", result.getRecordCount());
                        data.put("message", result.getMessage());
                        return Result.success(data);
                    } else {
                        return Result.error(result.getMessage());
                    }
                });
    }

    /**
     * 导出产品到CSV (已升级为支持集合查询)
     */
    @PostMapping("/products/export/csv")
    public CompletableFuture<Result<Map<String, Object>>> exportProductsToCSV(
            @RequestBody Map<String, Object> params) {

        List<Integer> l1Ids = parseCategoryIdList(params.get("categoryLevel1Id"));
        List<Integer> l2Ids = parseCategoryIdList(params.get("categoryLevel2Id"));
        List<Integer> l3Ids = parseCategoryIdList(params.get("categoryLevel3Id"));

        String brand = (String) params.get("brand");
        String productCode = (String) params.get("productCode");
        String model = (String) params.get("model");

        // 💥 修复这里：补上了缺失的声明
        Boolean hasImage = null;
        if (params.get("hasImage") != null && !params.get("hasImage").toString().trim().isEmpty()) {
            hasImage = Boolean.valueOf(params.get("hasImage").toString());
        }
        Integer minStock = null;
        if (params.get("minStock") != null && !params.get("minStock").toString().trim().isEmpty()) {
            minStock = Integer.valueOf(params.get("minStock").toString());
        }
        Integer maxStock = null;
        if (params.get("maxStock") != null && !params.get("maxStock").toString().trim().isEmpty()) {
            maxStock = Integer.valueOf(params.get("maxStock").toString());
        }
// --- 新增解析 matchAny ---
        Boolean matchAny = false;
        if (params.get("matchAny") != null && !params.get("matchAny").toString().trim().isEmpty()) {
            matchAny = Boolean.valueOf(params.get("matchAny").toString());
        }
        // 💥 修复这里：把原来的 hasStock 换成了 hasImage, minStock, maxStock，并加上了 matchAny 和 null (shopId)
        return dataExportService.exportProductsToCSV(l1Ids, l2Ids, l3Ids, brand, productCode, model, hasImage, minStock, maxStock, matchAny, null) // <-- 补上了 null 参数
                .thenApply(result -> {
                    if (result.isSuccess()) {
                        Map<String, Object> data = new HashMap<>();
                        data.put("filename", Paths.get(result.getFilePath()).getFileName().toString());
                        data.put("recordCount", result.getRecordCount());
                        data.put("message", result.getMessage());
                        return Result.success(data);
                    } else {
                        return Result.error(result.getMessage());
                    }
                });
    }

    /**
     */
    @GetMapping("/products/export/download/{filename:.+}")
    public ResponseEntity<Resource> downloadExportFile(@PathVariable String filename) {
        try {
            if (filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
                return ResponseEntity.badRequest().build();
            }

            // 强制写死绝对路径，和写入端完美一致
            Path filePath = Paths.get("/app/exports").resolve(filename);

            if (!java.nio.file.Files.exists(filePath)) {
                return ResponseEntity.notFound().build();
            }

            Resource resource = new org.springframework.core.io.FileSystemResource(filePath.toFile());
            String contentType = java.nio.file.Files.probeContentType(filePath);
            if (contentType == null) {
                contentType = "application/octet-stream";
            }

            String encodedFilename = java.net.URLEncoder.encode(filename, "UTF-8").replaceAll("\\+", "%20");

            return ResponseEntity.ok()
                    .contentType(org.springframework.http.MediaType.parseMediaType(contentType))
                    .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + encodedFilename + "\"")
                    .body(resource);

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 按分类ID列表导出产品
     */
    @PostMapping("/products/export/by-categories")
    public CompletableFuture<Result<Map<String, Object>>> exportProductsByCategories(
            @RequestBody Map<String, Object> params) {

        List<Integer> categoryIds = (List<Integer>) params.get("categoryIds");
        String format = (String) params.getOrDefault("format", "excel");

        if (categoryIds == null || categoryIds.isEmpty()) {
            return CompletableFuture.completedFuture(
                    Result.error("分类ID列表不能为空")
            );
        }

        return dataExportService.exportProductsByCategories(categoryIds, format)
                .thenApply(result -> {
                    if (result.isSuccess()) {
                        Map<String, Object> data = new HashMap<>();
                        data.put("filename", Paths.get(result.getFilePath()).getFileName().toString());
                        data.put("recordCount", result.getRecordCount());
                        data.put("message", result.getMessage());
                        return Result.success(data);
                    } else {
                        return Result.error(result.getMessage());
                    }
                });
    }

    /**
     * 获取所有品牌列表
     */
    @GetMapping("/products/brands")
    public Result<List<String>> getAllBrands() {
        try {
            List<String> brands = productService.getAllBrands();
            return Result.success(brands);
        } catch (Exception e) {
            return Result.error("获取品牌列表失败: " + e.getMessage());
        }
    }
}