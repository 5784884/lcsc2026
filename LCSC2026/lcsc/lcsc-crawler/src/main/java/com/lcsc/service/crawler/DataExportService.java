package com.lcsc.service.crawler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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
 * 支持Excel和CSV格式的产品数据导出
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
            try {
                List<Product> products = productService.list();
                enrichCategoryNames(products);
                enrichShopNoImageInfo(products, null);
                String fileName = generateFileName("all_products", "xlsx");
                return exportProductsToExcel(products, fileName, "所有产品数据");
            } catch (Exception e) {
                return new ExportResult(false, "导出失败: " + e.getMessage(), null, 0);
            }
        });
    }

    /**
     * 根据条件导出产品到Excel (带shopId) - 已升级为List参数
     */
    public CompletableFuture<ExportResult> exportProductsToExcel(
            List<Integer> categoryLevel1Ids, List<Integer> categoryLevel2Ids, List<Integer> categoryLevel3Ids,
            String brand, String productCode, String model, Boolean hasStock, Integer shopId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                List<Product> products = getProductsByCondition(categoryLevel1Ids, categoryLevel2Ids, categoryLevel3Ids, brand, productCode, model, hasStock);
                enrichShopNoImageInfo(products, shopId);
                String fileName = generateFileName("filtered_products", "xlsx");
                String sheetName = buildSheetName(categoryLevel1Ids, categoryLevel2Ids, brand);
                return exportProductsToExcel(products, fileName, sheetName);
            } catch (Exception e) {
                return new ExportResult(false, "导出失败: " + e.getMessage(), null, 0);
            }
        });
    }

    public CompletableFuture<ExportResult> exportProductsToExcel(
            List<Integer> categoryLevel1Ids, List<Integer> categoryLevel2Ids, List<Integer> categoryLevel3Ids,
            String brand, String productCode, String model, Boolean hasStock) {
        return exportProductsToExcel(categoryLevel1Ids, categoryLevel2Ids, categoryLevel3Ids, brand, productCode, model, hasStock, null);
    }

    /**
     * 导出产品数据到CSV (解决 Excel 乱码版) - 已升级为List参数
     */
    public CompletableFuture<ExportResult> exportProductsToCSV(
            List<Integer> categoryLevel1Ids, List<Integer> categoryLevel2Ids, List<Integer> categoryLevel3Ids,
            String brand, String productCode, String model, Boolean hasStock, Integer shopId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                List<Product> products = getProductsByCondition(categoryLevel1Ids, categoryLevel2Ids, categoryLevel3Ids, brand, productCode, model, hasStock);
                enrichShopNoImageInfo(products, shopId);
                String fileName = generateFileName("products", "csv");
                Path exportDirectory = resolveExportDir();
                Files.createDirectories(exportDirectory);
                Path filePath = exportDirectory.resolve(fileName);

                try (OutputStream os = Files.newOutputStream(filePath)) {
                    os.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
                    try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8))) {
                        writer.write(String.join(",", EXCEL_HEADERS));
                        writer.newLine();
                        for (Product product : products) {
                            writer.write(formatProductToCsv(product));
                            writer.newLine();
                        }
                    }
                }
                return new ExportResult(true, "导出成功", filePath.toString(), products.size());
            } catch (Exception e) {
                return new ExportResult(false, "导出失败: " + e.getMessage(), null, 0);
            }
        });
    }

    /**
     * 按分类ID列表导出产品 (高级导出：支持一/二/三级混合ID导出)
     */
    public CompletableFuture<ExportResult> exportProductsByCategories(
            List<Integer> categoryIds, String format, Integer shopId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                LambdaQueryWrapper<Product> queryWrapper = new LambdaQueryWrapper<>();
                if (categoryIds != null && !categoryIds.isEmpty()) {
                    // 核心修复：支持混合多选，只要它属于传过来的任何一级、二级或三级ID，全部查出来
                    queryWrapper.and(w -> w.in(Product::getCategoryLevel1Id, categoryIds)
                            .or().in(Product::getCategoryLevel2Id, categoryIds)
                            .or().in(Product::getCategoryLevel3Id, categoryIds));
                }
                List<Product> products = productService.list(queryWrapper);

                if (products.isEmpty()) {
                    return new ExportResult(false, "所选分类没有产品数据", null, 0);
                }
                enrichCategoryNames(products);
                enrichShopNoImageInfo(products, shopId);

                String fileName = generateFileName("products_by_categories", format.equals("csv") ? "csv" : "xlsx");
                if (format.equals("csv")) {
                    return exportProductsToCSVFile(products, fileName);
                } else {
                    return exportProductsToExcel(products, fileName, "按分类导出");
                }
            } catch (Exception e) {
                return new ExportResult(false, "导出失败: " + e.getMessage(), null, 0);
            }
        });
    }

    public CompletableFuture<ExportResult> exportProductsByCategories(List<Integer> categoryIds, String format) {
        return exportProductsByCategories(categoryIds, format, null);
    }

    // 核心条件查询修复：支持List，并补齐了丢失的三级分类查询
    private List<Product> getProductsByCondition(List<Integer> categoryLevel1Ids, List<Integer> categoryLevel2Ids, List<Integer> categoryLevel3Ids, String brand, String productCode, String model, Boolean hasStock) {
        LambdaQueryWrapper<Product> wrapper = new LambdaQueryWrapper<>();
        if (productCode != null && !productCode.trim().isEmpty()) wrapper.like(Product::getProductCode, productCode.trim());
        if (brand != null && !brand.trim().isEmpty()) wrapper.like(Product::getBrand, brand.trim());
        if (model != null && !model.trim().isEmpty()) wrapper.like(Product::getModel, model.trim());

        // 🌟 终极修复：使用 OR 组合不同层级的分类查询
        boolean hasL1 = categoryLevel1Ids != null
                && !categoryLevel1Ids.isEmpty();
        boolean hasL2 = categoryLevel2Ids != null
                && !categoryLevel2Ids.isEmpty();
        boolean hasL3 = categoryLevel3Ids != null
                && !categoryLevel3Ids.isEmpty();

        if
        (hasL1 || hasL2 || hasL3) {
            wrapper.and(w -> {
                boolean isFirst = true
                        ;
                if
                (hasL1) {
                    w.in(Product::getCategoryLevel1Id, categoryLevel1Ids);
                    isFirst =
                            false
                    ;
                }
                if
                (hasL2) {
                    if
                    (!isFirst) w.or();
                    w.in(Product::getCategoryLevel2Id, categoryLevel2Ids);
                    isFirst =
                            false
                    ;
                }
                if
                (hasL3) {
                    if
                    (!isFirst) w.or();
                    w.in(Product::getCategoryLevel3Id, categoryLevel3Ids);
                }
            });
        }

        if (hasStock != null
        ) {
            if (hasStock) wrapper.gt(Product::getTotalStockQuantity, 0
            );
            else wrapper.le(Product::getTotalStockQuantity, 0
            );
        }
        wrapper.orderByDesc(Product::getLastCrawledAt);
        List<Product> list = productService.list(wrapper);
        enrichCategoryNames(list);
        return
                list;
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

    private ExportResult exportProductsToExcel(List<Product> products, String fileName, String sheetName) {
        try {
            Path exportDirectory = resolveExportDir();
            Files.createDirectories(exportDirectory);
            Path filePath = exportDirectory.resolve(fileName);

            try (Workbook workbook = new XSSFWorkbook()) {
                Sheet sheet = workbook.createSheet(sheetName);
                CellStyle headerStyle = createHeaderStyle(workbook);
                CellStyle dataStyle = createDataStyle(workbook);

                Row headerRow = sheet.createRow(0);
                for (int i = 0; i < EXCEL_HEADERS.length; i++) {
                    Cell cell = headerRow.createCell(i);
                    cell.setCellValue(EXCEL_HEADERS[i]);
                    cell.setCellStyle(headerStyle);
                }

                int rowNum = 1;
                for (Product product : products) {
                    Row row = sheet.createRow(rowNum++);
                    fillProductRow(row, product, dataStyle);
                }

                for (int i = 0; i < EXCEL_HEADERS.length; i++) {
                    sheet.autoSizeColumn(i);
                    if (sheet.getColumnWidth(i) > 15000) {
                        sheet.setColumnWidth(i, 15000);
                    }
                }

                try (FileOutputStream fos = new FileOutputStream(filePath.toFile())) {
                    workbook.write(fos);
                }
            }
            return new ExportResult(true, "导出成功", filePath.toString(), products.size());
        } catch (Exception e) {
            return new ExportResult(false, "导出失败: " + e.getMessage(), null, 0);
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

    private ExportResult exportProductsToCSVFile(List<Product> products, String fileName) {
        try {
            Path exportDirectory = resolveExportDir();
            Files.createDirectories(exportDirectory);
            Path filePath = exportDirectory.resolve(fileName);

            try (OutputStream os = Files.newOutputStream(filePath)) {
                os.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});

                try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8))) {
                    writer.write(String.join(",", EXCEL_HEADERS));
                    writer.newLine();
                    for (Product product : products) {
                        writer.write(formatProductToCsv(product));
                        writer.newLine();
                    }
                }
            }

            return new ExportResult(true, "导出成功", filePath.toString(), products.size());
        } catch (Exception e) {
            return new ExportResult(false, "导出失败: " + e.getMessage(), null, 0);
        }
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