package com.lcsc.service.crawler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lcsc.entity.CategoryLevel1Code;
import com.lcsc.entity.CategoryLevel2Code;
import com.lcsc.entity.CategoryLevel3Code;
import com.lcsc.entity.Product;
import com.lcsc.entity.ImageLink;
import com.lcsc.service.CategoryLevel1CodeService;
import com.lcsc.service.CategoryLevel2CodeService;
import com.lcsc.service.CategoryLevel3CodeService;
import com.lcsc.service.ProductService;
import com.lcsc.service.ImageLinkService;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.concurrent.CompletableFuture;

/**
 * 数据导出服务
 * 支持Excel和CSV格式的产品数据导出 (优化百万级数据导出)
 *
 * @author lcsc-crawler
 * @since 2025-09-03
 */
@Service
public class DataExportService {

    @Autowired private ProductService productService;
    @Autowired private ImageLinkService imageLinkService;
    @Value("${crawler.storage.export-dir:exports}") private String exportDir;
    @Value("${crawler.storage.base-path}") private String storageBasePath;
    @Autowired private CategoryLevel1CodeService categoryLevel1CodeService;
    @Autowired private CategoryLevel2CodeService categoryLevel2CodeService;
    @Autowired private CategoryLevel3CodeService categoryLevel3CodeService;

    // 默认兜底图，防数据库无记录
    private static final String DEFAULT_GLOBAL_NO_IMAGE = "https://assets.lcsc.com/images/no-image.jpg";
    // 每次从数据库拉取的条数
    private static final int BATCH_SIZE = 10000;

    private static final String[] EXCEL_HEADERS = {
            "产品编号", "型号", "品牌", "封装", "简介", "库存数量",
            "一级分类名称", "二级分类名称", "三级分类名称", "图片名称",
            "主图URL", "PDF URL",
            "阶梯价1_数量", "阶梯价1_价格", "阶梯价2_数量", "阶梯价2_价格",
            "阶梯价3_数量", "阶梯价3_价格", "阶梯价4_数量", "阶梯价4_价格",
            "阶梯价5_数量", "阶梯价5_价格", "阶梯价6_数量", "阶梯价6_价格",
            "宝贝描述", "额外参数"
    };

    /**
     * 导出所有产品到Excel
     */
    public CompletableFuture<ExportResult> exportAllProductsToExcel() {
        return CompletableFuture.supplyAsync(() -> {
            LambdaQueryWrapper<Product> wrapper = new LambdaQueryWrapper<>();
            wrapper.orderByDesc(Product::getLastCrawledAt);
            String fileName = generateFileName("all_products", "xlsx");
            return doExportToExcel(wrapper, fileName, "所有产品数据", null);
        });
    }

    /**
     * 根据条件导出产品到Excel (带shopId)
     */
    public CompletableFuture<ExportResult> exportProductsToExcel(
            List<Integer> categoryLevel1Ids, List<Integer> categoryLevel2Ids, List<Integer> categoryLevel3Ids,
            String brand, String productCode, String model, Boolean hasImage, Integer minStock, Integer maxStock, Boolean matchAny, Integer shopId) {
        return CompletableFuture.supplyAsync(() -> {
            LambdaQueryWrapper<Product> wrapper = buildConditionWrapper(categoryLevel1Ids, categoryLevel2Ids, categoryLevel3Ids, brand, productCode, model, hasImage, minStock, maxStock, matchAny);
            String fileName = generateFileName("filtered_products", "xlsx");
            String sheetName = buildSheetName(categoryLevel1Ids, categoryLevel2Ids, brand);
            return doExportToExcel(wrapper, fileName, sheetName, shopId);
        });
    }

    public CompletableFuture<ExportResult> exportProductsToExcel(
            List<Integer> categoryLevel1Ids, List<Integer> categoryLevel2Ids, List<Integer> categoryLevel3Ids,
            String brand, String productCode, String model, Boolean hasImage, Integer minStock, Integer maxStock, Boolean matchAny) {
        return exportProductsToExcel(categoryLevel1Ids, categoryLevel2Ids, categoryLevel3Ids, brand, productCode, model, hasImage, minStock, maxStock, matchAny, null);
    }

    /**
     * 导出产品数据到CSV
     */
    public CompletableFuture<ExportResult> exportProductsToCSV(
            List<Integer> categoryLevel1Ids, List<Integer> categoryLevel2Ids, List<Integer> categoryLevel3Ids,
            String brand, String productCode, String model, Boolean hasImage, Integer minStock, Integer maxStock, Boolean matchAny, Integer shopId) {
        return CompletableFuture.supplyAsync(() -> {
            LambdaQueryWrapper<Product> wrapper = buildConditionWrapper(categoryLevel1Ids, categoryLevel2Ids, categoryLevel3Ids, brand, productCode, model, hasImage, minStock, maxStock, matchAny);
            String fileName = generateFileName("products", "csv");
            return doExportToCSVFile(wrapper, fileName, shopId);
        });
    }

    /**
     * 按分类ID列表导出产品 (高级导出：支持一/二/三级混合ID导出)
     */
    public CompletableFuture<ExportResult> exportProductsByCategories(
            List<Integer> categoryIds, String format, Integer shopId) {
        return CompletableFuture.supplyAsync(() -> {
            LambdaQueryWrapper<Product> queryWrapper = new LambdaQueryWrapper<>();
            if (categoryIds != null && !categoryIds.isEmpty()) {
                queryWrapper.and(w -> w.in(Product::getCategoryLevel1Id, categoryIds)
                        .or().in(Product::getCategoryLevel2Id, categoryIds)
                        .or().in(Product::getCategoryLevel3Id, categoryIds));
            }
            queryWrapper.orderByDesc(Product::getLastCrawledAt);

            String fileName = generateFileName("products_by_categories", format.equals("csv") ? "csv" : "xlsx");
            if (format.equals("csv")) {
                return doExportToCSVFile(queryWrapper, fileName, shopId);
            } else {
                return doExportToExcel(queryWrapper, fileName, "按分类导出", shopId);
            }
        });
    }

    public CompletableFuture<ExportResult> exportProductsByCategories(List<Integer> categoryIds, String format) {
        return exportProductsByCategories(categoryIds, format, null);
    }

    // ===================== 核心分页导出逻辑：Excel =====================
    private ExportResult doExportToExcel(LambdaQueryWrapper<Product> wrapper, String fileName, String sheetName, Integer shopId) {
        try {
            Path exportDirectory = resolveExportDir();
            Files.createDirectories(exportDirectory);
            Path filePath = exportDirectory.resolve(fileName);

            // 🌟 核心优化 1：使用 SXSSFWorkbook（流式写入，内存中仅保留 1000 行，防止内存溢出）
            try (SXSSFWorkbook workbook = new SXSSFWorkbook(1000)) {
                // 压缩临时文件
                workbook.setCompressTempFiles(true);
                Sheet sheet = workbook.createSheet(sheetName);
                CellStyle headerStyle = createHeaderStyle(workbook);
                CellStyle dataStyle = createDataStyle(workbook);

                // 写表头
                Row headerRow = sheet.createRow(0);
                for (int i = 0; i < EXCEL_HEADERS.length; i++) {
                    Cell cell = headerRow.createCell(i);
                    cell.setCellValue(EXCEL_HEADERS[i]);
                    cell.setCellStyle(headerStyle);
                    // 🌟 核心优化 2：取消 autoSizeColumn，设定固定列宽 (提高几十倍写入速度)
                    sheet.setColumnWidth(i, 4000);
                }

                int rowNum = 1;
                long pageNo = 1;
                int totalExported = 0;

                // 🌟 核心优化 3：分页查库，每次只加载 10000 条，不撑爆内存
                while (true) {
                    Page<Product> page = new Page<>(pageNo, BATCH_SIZE);
                    productService.page(page, wrapper);
                    List<Product> records = page.getRecords();

                    if (records == null || records.isEmpty()) {
                        break;
                    }

                    enrichCategoryNames(records);
                    enrichShopNoImageInfo(records, shopId);

                    for (Product product : records) {
                        Row row = sheet.createRow(rowNum++);
                        fillProductRow(row, product, dataStyle);
                    }

                    totalExported += records.size();

                    // 如果拉取到的数据不足一页，说明到底了，退出循环
                    if (records.size() < BATCH_SIZE) {
                        break;
                    }
                    pageNo++;
                }

                if (totalExported == 0) {
                    return new ExportResult(false, "没有符合条件的数据", null, 0);
                }

                // 写入文件系统
                try (FileOutputStream fos = new FileOutputStream(filePath.toFile())) {
                    workbook.write(fos);
                }

                // 🌟 核心清理：清除缓存在磁盘的临时文件
                workbook.dispose();
            }
            return new ExportResult(true, "导出成功", filePath.toString(), getRecordCountEstimation(wrapper));
        } catch (Exception e) {
            e.printStackTrace();
            return new ExportResult(false, "导出失败: " + e.getMessage(), null, 0);
        }
    }

    // ===================== 核心分页导出逻辑：CSV =====================
    private ExportResult doExportToCSVFile(LambdaQueryWrapper<Product> wrapper, String fileName, Integer shopId) {
        try {
            Path exportDirectory = resolveExportDir();
            Files.createDirectories(exportDirectory);
            Path filePath = exportDirectory.resolve(fileName);

            int totalExported = 0;

            try (OutputStream os = Files.newOutputStream(filePath)) {
                // 写入 BOM 头防止乱码
                os.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});

                try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8))) {
                    writer.write(String.join(",", EXCEL_HEADERS));
                    writer.newLine();

                    long pageNo = 1;
                    // 分页查询防止内存溢出
                    while (true) {
                        Page<Product> page = new Page<>(pageNo, BATCH_SIZE);
                        productService.page(page, wrapper);
                        List<Product> records = page.getRecords();

                        if (records == null || records.isEmpty()) {
                            break;
                        }

                        enrichCategoryNames(records);
                        enrichShopNoImageInfo(records, shopId);

                        for (Product product : records) {
                            writer.write(formatProductToCsv(product));
                            writer.newLine();
                        }

                        totalExported += records.size();

                        if (records.size() < BATCH_SIZE) {
                            break;
                        }
                        pageNo++;
                    }
                }
            }

            if (totalExported == 0) {
                return new ExportResult(false, "没有符合条件的数据", null, 0);
            }

            return new ExportResult(true, "导出成功", filePath.toString(), totalExported);
        } catch (Exception e) {
            e.printStackTrace();
            return new ExportResult(false, "导出失败: " + e.getMessage(), null, 0);
        }
    }

    // ===================== 辅助工具方法 =====================
    private int getRecordCountEstimation(LambdaQueryWrapper<Product> wrapper) {
        // 返回大概记录数给前端显示（避免精确count耗时太久）
        return Math.toIntExact(productService.count(wrapper));
    }

    private LambdaQueryWrapper<Product> buildConditionWrapper(
            List<Integer> categoryLevel1Ids, List<Integer> categoryLevel2Ids, List<Integer> categoryLevel3Ids,
            String brand, String productCode, String model, Boolean hasImage, Integer minStock, Integer maxStock, Boolean matchAny) {

        LambdaQueryWrapper<Product> wrapper = new LambdaQueryWrapper<>();
        if (productCode != null && !productCode.trim().isEmpty()) {
            if (productCode.contains("\n") || productCode.contains("\r")) {
                List<String> codeList = Arrays.stream(productCode.split("[\\r\\n]+"))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.toList());

                if (!codeList.isEmpty()) {
                    wrapper.in(Product::getProductCode, codeList);
                }
            } else {
                wrapper.like(Product::getProductCode, productCode.trim());
            }
        }
        if (brand != null && !brand.trim().isEmpty()) wrapper.like(Product::getBrand, brand.trim());
        if (model != null && !model.trim().isEmpty()) wrapper.like(Product::getModel, model.trim());

        boolean hasL1 = categoryLevel1Ids != null && !categoryLevel1Ids.isEmpty();
        boolean hasL2 = categoryLevel2Ids != null && !categoryLevel2Ids.isEmpty();
        boolean hasL3 = categoryLevel3Ids != null && !categoryLevel3Ids.isEmpty();

        if (hasL1 || hasL2 || hasL3) {
            wrapper.and(w -> {
                boolean isFirst = true;
                if (hasL1) { w.in(Product::getCategoryLevel1Id, categoryLevel1Ids); isFirst = false; }
                if (hasL2) { if (!isFirst) w.or(); w.in(Product::getCategoryLevel2Id, categoryLevel2Ids); isFirst = false; }
                if (hasL3) { if (!isFirst) w.or(); w.in(Product::getCategoryLevel3Id, categoryLevel3Ids); }
            });
        }

        // ==== 智能路由 (任意满足 OR / 叠加 AND) ====
        boolean hasStockCondition = (minStock != null || maxStock != null);
        boolean hasImgCondition = (hasImage != null);

        if (Boolean.TRUE.equals(matchAny) && hasStockCondition && hasImgCondition) {
            wrapper.and(w -> {
                w.nested(stockW -> {
                    if (minStock != null) stockW.ge(Product::getTotalStockQuantity, minStock);
                    if (maxStock != null) stockW.le(Product::getTotalStockQuantity, maxStock);
                }).or().nested(imgW -> {
                    if (hasImage) {
                        imgW.isNotNull(Product::getProductImageUrlBig).ne(Product::getProductImageUrlBig, "");
                    } else {
                        imgW.and(iw2 -> iw2.isNull(Product::getProductImageUrlBig).or().eq(Product::getProductImageUrlBig, ""));
                    }
                });
            });
        } else {
            if (minStock != null) wrapper.ge(Product::getTotalStockQuantity, minStock);
            if (maxStock != null) wrapper.le(Product::getTotalStockQuantity, maxStock);

            if (hasImage != null) {
                if (hasImage) {
                    wrapper.isNotNull(Product::getProductImageUrlBig).ne(Product::getProductImageUrlBig, "");
                } else {
                    wrapper.and(w -> w.isNull(Product::getProductImageUrlBig).or().eq(Product::getProductImageUrlBig, ""));
                }
            }
        }

        wrapper.orderByDesc(Product::getLastCrawledAt);
        return wrapper;
    }

    private void enrichShopNoImageInfo(List<Product> products, Integer defaultShopId) {
        if (products == null || products.isEmpty()) {
            return;
        }

        Set<Integer> shopIds = products.stream()
                .map(Product::getShopId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        if (defaultShopId != null) {
            shopIds.add(defaultShopId);
        }

        Map<Integer, String> shopImageMap = new HashMap<>();
        if (!shopIds.isEmpty()) {
            LambdaQueryWrapper<ImageLink> query = new LambdaQueryWrapper<>();
            query.in(ImageLink::getShopId, shopIds);
            query.like(ImageLink::getImageName, "no-image.jpg");
            List<ImageLink> links = imageLinkService.list(query);

            for (ImageLink link : links) {
                if (link.getShopId() != null && link.getImageLink() != null) {
                    shopImageMap.putIfAbsent(link.getShopId(), link.getImageLink());
                }
            }
        }

        for (Product p : products) {
            Integer targetShopId = p.getShopId() != null ? p.getShopId() : defaultShopId;
            String targetUrl = DEFAULT_GLOBAL_NO_IMAGE;

            if (targetShopId != null && shopImageMap.containsKey(targetShopId)) {
                String foundUrl = shopImageMap.get(targetShopId);
                if (foundUrl != null && !foundUrl.trim().isEmpty()) {
                    targetUrl = foundUrl;
                }
            }
            p.setShopNoImageUrl(targetUrl);
        }
    }

    private void fillProductRow(Row row, Product product, CellStyle dataStyle) {
        int cellIndex = 0;
        createCell(row, cellIndex++, product.getProductCode(), dataStyle);
        createCell(row, cellIndex++, product.getModel(), dataStyle);
        createCell(row, cellIndex++, product.getBrand(), dataStyle);
        createCell(row, cellIndex++, product.getPackageName(), dataStyle);

        String intro = String.format("%s %s %s %s %s",
                nvl(product.getModel()),
                nvl(product.getPackageName()),
                nvl(product.getCategoryLevel3CustomName()),
                nvl(product.getCategoryLevel2CustomName()),
                nvl(product.getCategoryLevel1CustomName())
        ).trim().replaceAll("\\s+", " ");
        createCell(row, cellIndex++, intro, dataStyle);

        createCell(row, cellIndex++, product.getTotalStockQuantity(), dataStyle);
        createCell(row, cellIndex++, product.getCategoryLevel1Name(), dataStyle);
        createCell(row, cellIndex++, product.getCategoryLevel2Name(), dataStyle);
        createCell(row, cellIndex++, product.getCategoryLevel3Name(), dataStyle);
        createCell(row, cellIndex++, product.getImageName(), dataStyle);

        String finalImageUrl = product.getProductImageUrlBig();
        if (finalImageUrl == null || finalImageUrl.trim().isEmpty()) {
            finalImageUrl = product.getShopNoImageUrl();
            if (finalImageUrl == null || finalImageUrl.trim().isEmpty()) {
                finalImageUrl = DEFAULT_GLOBAL_NO_IMAGE;
            }
        }
        createCell(row, cellIndex++, ":1:0|" + finalImageUrl, dataStyle);

        createCell(row, cellIndex++, product.getPdfUrl(), dataStyle);
        createCell(row, cellIndex++, product.getLadderPrice1Quantity(), dataStyle);
        createCell(row, cellIndex++, product.getLadderPrice1Price(), dataStyle);
        createCell(row, cellIndex++, product.getLadderPrice2Quantity(), dataStyle);
        createCell(row, cellIndex++, product.getLadderPrice2Price(), dataStyle);
        createCell(row, cellIndex++, product.getLadderPrice3Quantity(), dataStyle);
        createCell(row, cellIndex++, product.getLadderPrice3Price(), dataStyle);
        createCell(row, cellIndex++, product.getLadderPrice4Quantity(), dataStyle);
        createCell(row, cellIndex++, product.getLadderPrice4Price(), dataStyle);
        createCell(row, cellIndex++, product.getLadderPrice5Quantity(), dataStyle);
        createCell(row, cellIndex++, product.getLadderPrice5Price(), dataStyle);
        createCell(row, cellIndex++, product.getLaderPrice6Quantity(), dataStyle);
        createCell(row, cellIndex++, product.getLaderPrice6Price(), dataStyle);
        createCell(row, cellIndex++, formatBabyDescriptionHtml(product.getParametersText()), dataStyle);
        createCell(row, cellIndex++, product.getParametersText(), dataStyle);
    }

    private String formatProductToCsv(Product product) {
        String intro = String.format("%s %s %s %s %s",
                nvl(product.getModel()),
                nvl(product.getPackageName()),
                nvl(product.getCategoryLevel3CustomName()),
                nvl(product.getCategoryLevel2CustomName()),
                nvl(product.getCategoryLevel1CustomName())
        ).trim().replaceAll("\\s+", " ");

        String imgUrl = product.getProductImageUrlBig();
        if (imgUrl == null || imgUrl.trim().isEmpty()) {
            imgUrl = product.getShopNoImageUrl();
            if (imgUrl == null || imgUrl.trim().isEmpty()) {
                imgUrl = DEFAULT_GLOBAL_NO_IMAGE;
            }
        }

        return String.join(",",
                csvEscape(product.getProductCode()),
                csvEscape(product.getModel()),
                csvEscape(product.getBrand()),
                csvEscape(product.getPackageName()),
                csvEscape(intro),
                csvEscape(product.getTotalStockQuantity()),
                csvEscape(product.getCategoryLevel1Name()),
                csvEscape(product.getCategoryLevel2Name()),
                csvEscape(product.getCategoryLevel3Name()),
                csvEscape(product.getImageName()),
                csvEscape(imgUrl),
                csvEscape(product.getPdfUrl()),
                csvEscape(product.getLadderPrice1Quantity()),
                csvEscape(product.getLadderPrice1Price()),
                csvEscape(product.getLadderPrice2Quantity()),
                csvEscape(product.getLadderPrice2Price()),
                csvEscape(product.getLadderPrice3Quantity()),
                csvEscape(product.getLadderPrice3Price()),
                csvEscape(product.getLadderPrice4Quantity()),
                csvEscape(product.getLadderPrice4Price()),
                csvEscape(product.getLadderPrice5Quantity()),
                csvEscape(product.getLadderPrice5Price()),
                csvEscape(product.getLaderPrice6Quantity()),
                csvEscape(product.getLaderPrice6Price()),
                csvEscape(formatBabyDescriptionHtml(product.getParametersText())),
                csvEscape(product.getParametersText())
        );
    }

    private String formatBabyDescriptionHtml(String parametersText) {
        if (parametersText == null || parametersText.trim().isEmpty()) return "";
        StringBuilder html = new StringBuilder();
        html.append("<span style=\"color:#E53333;\"><h1>主要参数: <br />");
        String[] params = parametersText.split("\\s+");
        for (String param : params) {
            if (param.contains(":")) {
                String[] kv = param.split(":", 2);
                if (kv.length == 2) {
                    html.append(kv[0].trim()).append(" : ").append(kv[1].trim()).append("<br />");
                }
            }
        }
        html.append("</h1></span>");
        return html.toString();
    }

    private void createCell(Row row, int columnIndex, Object value, CellStyle style) {
        Cell cell = row.createCell(columnIndex);
        if (value != null) {
            if (value instanceof String) cell.setCellValue((String) value);
            else if (value instanceof Integer) cell.setCellValue((Integer) value);
            else if (value instanceof Long) cell.setCellValue((Long) value);
            else if (value instanceof java.math.BigDecimal) cell.setCellValue(((java.math.BigDecimal) value).doubleValue());
            else cell.setCellValue(value.toString());
        }
        cell.setCellStyle(style);
    }

    private String csvEscape(Object value) {
        if (value == null) return "";
        String str = value.toString();
        if (str.contains(",") || str.contains("\"") || str.contains("\n")) {
            return "\"" + str.replace("\"", "\"\"") + "\"";
        }
        return str;
    }

    private CellStyle createHeaderStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 12);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        style.setAlignment(HorizontalAlignment.CENTER);
        return style;
    }

    private CellStyle createDataStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        style.setWrapText(true);
        return style;
    }

    private String generateFileName(String prefix, String extension) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        return prefix + "_" + timestamp + "." + extension;
    }

    private String buildSheetName(List<Integer> categoryLevel1Ids, List<Integer> categoryLevel2Ids, String brand) {
        StringBuilder sb = new StringBuilder("产品数据");
        if (categoryLevel1Ids != null && !categoryLevel1Ids.isEmpty()) sb.append("_分类").append(categoryLevel1Ids.get(0));
        if (categoryLevel2Ids != null && !categoryLevel2Ids.isEmpty()) sb.append("_子分类").append(categoryLevel2Ids.get(0));
        if (brand != null && !brand.isEmpty()) sb.append("_").append(brand);
        return sb.toString();
    }

    private Path resolveExportDir() {
        return Paths.get("/app/exports");
    }

    private void enrichCategoryNames(List<Product> products) {
        if (products == null || products.isEmpty()) return;
        Set<Integer> l1Ids = products.stream().map(Product::getCategoryLevel1Id).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Integer> l2Ids = products.stream().map(Product::getCategoryLevel2Id).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Integer> l3Ids = products.stream().map(Product::getCategoryLevel3Id).filter(Objects::nonNull).collect(Collectors.toSet());

        Map<Integer, CategoryLevel1Code> l1Map = l1Ids.isEmpty() ? Collections.emptyMap() : categoryLevel1CodeService.listByIds(l1Ids).stream().collect(Collectors.toMap(CategoryLevel1Code::getId, c -> c));
        Map<Integer, CategoryLevel2Code> l2Map = l2Ids.isEmpty() ? Collections.emptyMap() : categoryLevel2CodeService.listByIds(l2Ids).stream().collect(Collectors.toMap(CategoryLevel2Code::getId, c -> c));
        Map<Integer, CategoryLevel3Code> l3Map = l3Ids.isEmpty() ? Collections.emptyMap() : categoryLevel3CodeService.listByIds(l3Ids).stream().collect(Collectors.toMap(CategoryLevel3Code::getId, c -> c));

        for (Product p : products) {
            if (p == null) continue;
            if (p.getCategoryLevel1Id() != null && l1Map.containsKey(p.getCategoryLevel1Id())) {
                CategoryLevel1Code c = l1Map.get(p.getCategoryLevel1Id());
                p.setCategoryLevel1Name(c.getCategoryLevel1Name());
                p.setCategoryLevel1CustomName(c.getCustomName() != null && !c.getCustomName().isEmpty() ? c.getCustomName() : c.getCategoryLevel1Name());
            }
            if (p.getCategoryLevel2Id() != null && l2Map.containsKey(p.getCategoryLevel2Id())) {
                CategoryLevel2Code c = l2Map.get(p.getCategoryLevel2Id());
                p.setCategoryLevel2Name(c.getCategoryLevel2Name());
                p.setCategoryLevel2CustomName(c.getCustomName() != null && !c.getCustomName().isEmpty() ? c.getCustomName() : c.getCategoryLevel2Name());
            }
            if (p.getCategoryLevel3Id() != null && l3Map.containsKey(p.getCategoryLevel3Id())) {
                CategoryLevel3Code c = l3Map.get(p.getCategoryLevel3Id());
                p.setCategoryLevel3Name(c.getCategoryLevel3Name());
                p.setCategoryLevel3CustomName(c.getCustomName() != null && !c.getCustomName().isEmpty() ? c.getCustomName() : c.getCategoryLevel3Name());
            }
        }
    }

    private String nvl(String str) {
        return str == null ? "" : str;
    }

    public static class ExportResult {
        private boolean success;
        private String message;
        private String filePath;
        private int recordCount;

        public ExportResult(boolean success, String message, String filePath, int recordCount) {
            this.success = success;
            this.message = message;
            this.filePath = filePath;
            this.recordCount = recordCount;
        }

        public boolean isSuccess() { return success; }
        public String getMessage() { return message; }
        public String getFilePath() { return filePath; }
        public int getRecordCount() { return recordCount; }
    }
}