package com.lcsc.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lcsc.dto.AdvancedExportRequest;
import com.lcsc.dto.ExportTaskItem;
import com.lcsc.entity.*;
import com.lcsc.mapper.ImageLinkMapper;
import com.lcsc.mapper.ProductMapper;
import com.lcsc.mapper.ShopMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

import org.apache.poi.ss.usermodel.*;
// 🌟 性能优化：引入流式大数据处理的 SXSSFWorkbook
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

/**
 * 高级导出服务 - 淘宝CSV/Excel格式 (大数据量性能优化版)
 */
@Service
public class AdvancedExportService {

    private static final Logger log = LoggerFactory.getLogger(AdvancedExportService.class);

    // CSV固定值
    private static final String FIXED_CID = "50018871";  // 宝贝类目ID（固定值）
    private static final String OPTION_CODE_PREFIX = "1627207:-100";  // 选项编号前缀

    // 全局兜底图（当店铺也没上传无图时使用）
    private static final String GLOBAL_DEFAULT_IMAGE = "https://assets.lcsc.com/images/no-image.jpg";

    // 🌟 性能优化：几十万数据的批处理大小
    private static final int BATCH_SIZE = 1000;

    // 从配置文件(或环境变量)读取两个自定义选项的名称，提供默认值兜底
    @Value("${taobao.sku.custom-name1:选数量相符的选项}")
    private String customSkuName1;

    @Value("${taobao.sku.custom-name2:买多少个填多少件}")
    private String customSkuName2;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private ShopMapper shopMapper;

    @Autowired
    private ImageLinkMapper imageLinkMapper;

    @Autowired
    private com.lcsc.mapper.CategoryLevel1CodeMapper categoryLevel1CodeMapper;

    @Autowired
    private com.lcsc.mapper.CategoryLevel2CodeMapper categoryLevel2CodeMapper;

    @Autowired
    private com.lcsc.mapper.CategoryLevel3CodeMapper categoryLevel3CodeMapper;

    @Autowired
    private com.lcsc.service.BrandCustomNameService brandCustomNameService;

    /**
     * 添加产品到任务列表
     * @param request 筛选条件
     * @param currentTasks 当前任务列表
     * @return 更新后的任务列表（按productCode去重）
     */
    public List<ExportTaskItem> addToTaskList(AdvancedExportRequest request, List<ExportTaskItem> currentTasks) {
        log.info("添加产品到任务列表, shopId={}, categoryIds={}, brands={}",
                request.getShopId(), request.getCategoryIds(), request.getBrands());

        // 1. 查询符合条件的产品
        List<Product> products = queryProductsByRequest(request);
        log.info("查询到 {} 个符合条件的产品", products.size());

        // 2. 查询店铺信息（通用模式 shopId=0 时跳过）
        final String shopName;
        final Integer shopId = request.getShopId();
        if (shopId != null && shopId != 0) {
            Shop shop = shopMapper.selectById(shopId);
            if (shop == null) {
                throw new RuntimeException("店铺不存在: " + shopId);
            }
            shopName = shop.getShopName();
        } else {
            shopName = "通用";
        }

        // 3. 转换为任务项
        List<ExportTaskItem> newTasks = products.stream().map(product -> {
            ExportTaskItem task = new ExportTaskItem();
            task.setProductCode(product.getProductCode());
            task.setModel(product.getModel());
            task.setBrand(product.getBrand());
            task.setShopId(shopId != null ? shopId : 0);
            task.setShopName(shopName);
            task.setDiscounts(request.getDiscounts());
            task.setBrandDiscounts1(request.getBrandDiscounts1());
            task.setBrandDiscounts2(request.getBrandDiscounts2());
            task.setAddedAt(System.currentTimeMillis());
            return task;
        }).collect(Collectors.toList());

        // 4. 合并到现有任务列表并去重（按productCode）
        Map<String, ExportTaskItem> taskMap = new LinkedHashMap<>();

        // 先添加现有任务
        if (currentTasks != null) {
            for (ExportTaskItem task : currentTasks) {
                taskMap.put(task.getProductCode(), task);
            }
        }

        // 再添加新任务（如果已存在则覆盖）
        for (ExportTaskItem task : newTasks) {
            taskMap.put(task.getProductCode(), task);
        }

        List<ExportTaskItem> result = new ArrayList<>(taskMap.values());
        log.info("任务列表更新完成, 总计 {} 个产品", result.size());
        return result;
    }

    /**
     * 生成淘宝CSV文件 (分批次处理防 OOM)
     * @param tasks 任务列表
     * @return CSV文件字节数组
     */
    public byte[] generateTaobaoCsv(List<ExportTaskItem> tasks) throws IOException {
        log.info("开始生成淘宝CSV文件, 任务数量: {}", tasks.size());

        if (tasks == null || tasks.isEmpty()) {
            return new byte[0];
        }

        // 查询所有非通用店铺信息
        Set<Integer> shopIds = tasks.stream()
                .map(ExportTaskItem::getShopId)
                .filter(id -> id != null && id != 0)
                .collect(Collectors.toSet());

        Map<Integer, Shop> shopMap = shopIds.isEmpty() ? new HashMap<>() :
                shopMapper.selectBatchIds(shopIds).stream().collect(Collectors.toMap(Shop::getId, s -> s));

        // 获取包含完整配置（自定义名称、打折方案）的品牌配置映射
        Map<String, BrandCustomName> brandConfigMap = brandCustomNameService.list().stream()
                .collect(Collectors.toMap(BrandCustomName::getOriginalName, b -> b, (v1, v2) -> v2));

        // 🌟 核心优化：使用 ByteArrayOutputStream 替代 StringBuilder 进行流式写入
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            // 构建临时 StringBuilder 仅用于处理表头和当前批次的行
            outputStream.write(new byte[] { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF });
            StringBuilder csvBatch = new StringBuilder();

            // 写入表头
            appendCsvHeader(csvBatch);
            outputStream.write(csvBatch.toString().getBytes(StandardCharsets.UTF_8));
            csvBatch.setLength(0); // 清空以便复用

            // 分批处理数据
            for (int i = 0; i < tasks.size(); i += BATCH_SIZE) {
                List<ExportTaskItem> batchTasks = tasks.subList(i, Math.min(i + BATCH_SIZE, tasks.size()));

                Set<String> productCodes = batchTasks.stream()
                        .map(ExportTaskItem::getProductCode)
                        .collect(Collectors.toSet());

                List<Product> products = productMapper.selectList(
                        new LambdaQueryWrapper<Product>()
                                .in(Product::getProductCode, productCodes)
                );

                enrichCategoryNames(products);
                Map<String, Product> productMap = products.stream()
                        .collect(Collectors.toMap(Product::getProductCode, p -> p));

                Set<String> neededImageNames = products.stream()
                        .map(Product::getImageName)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());
                neededImageNames.add("no-image.jpg");
                Map<String, Map<Integer, String>> imageLinkMap = loadImageLinksByNames(neededImageNames);

                for (ExportTaskItem task : batchTasks) {
                    Product product = productMap.get(task.getProductCode());
                    if (product == null) continue;
                    boolean isGeneric = task.getShopId() == null || task.getShopId() == 0;
                    if (isGeneric) {
                        appendProductRowGeneric(csvBatch, product, task, brandConfigMap);
                    } else {
                        Shop shop = shopMap.get(task.getShopId());
                        if (shop == null) continue;
                        appendProductRow(csvBatch, product, shop, task, imageLinkMap, brandConfigMap);
                    }
                }

                // 🌟 核心优化：每处理完一个批次，就写入底层的 ByteArrayOutputStream，并清空 StringBuilder
                // 这样内存中永远只会保留一个 BATCH_SIZE (比如 1000 条) 大小的字符串数据，极大节省内存
                outputStream.write(csvBatch.toString().getBytes(StandardCharsets.UTF_8));
                csvBatch.setLength(0);
            }

            log.info("淘宝CSV文件生成完成");
            return outputStream.toByteArray();
        }
    }

    /**
     * 生成淘宝Excel文件 (SXSSFWorkbook 流式写入防 OOM)
     * @param tasks 任务列表
     * @return Excel文件字节数组
     */
    public byte[] generateTaobaoExcel(List<ExportTaskItem> tasks) throws IOException {
        log.info("开始生成淘宝Excel文件, 任务数量: {}", tasks.size());

        if (tasks == null || tasks.isEmpty()) {
            return new byte[0];
        }

        // 查询所有非通用店铺信息
        Set<Integer> shopIds = tasks.stream()
                .map(ExportTaskItem::getShopId)
                .filter(id -> id != null && id != 0)
                .collect(Collectors.toSet());

        Map<Integer, Shop> shopMap = shopIds.isEmpty() ? new HashMap<>() :
                shopMapper.selectBatchIds(shopIds).stream().collect(Collectors.toMap(Shop::getId, s -> s));

        // 获取包含完整配置的品牌配置映射
        Map<String, BrandCustomName> brandConfigMap = brandCustomNameService.list().stream()
                .collect(Collectors.toMap(BrandCustomName::getOriginalName, b -> b, (v1, v2) -> v2));

        try (SXSSFWorkbook workbook = new SXSSFWorkbook(1000);
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {

            workbook.setCompressTempFiles(true);
            Sheet sheet = workbook.createSheet("淘宝导入");
            createExcelHeader(sheet);

            int rowIndex = 3;

            for (int i = 0; i < tasks.size(); i += BATCH_SIZE) {
                List<ExportTaskItem> batchTasks = tasks.subList(i, Math.min(i + BATCH_SIZE, tasks.size()));

                Set<String> productCodes = batchTasks.stream()
                        .map(ExportTaskItem::getProductCode)
                        .collect(Collectors.toSet());

                List<Product> products = productMapper.selectList(
                        new LambdaQueryWrapper<Product>()
                                .in(Product::getProductCode, productCodes)
                );

                enrichCategoryNames(products);
                Map<String, Product> productMap = products.stream()
                        .collect(Collectors.toMap(Product::getProductCode, p -> p));

                Set<String> neededImageNames = products.stream()
                        .map(Product::getImageName)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());
                neededImageNames.add("no-image.jpg");
                Map<String, Map<Integer, String>> imageLinkMap = loadImageLinksByNames(neededImageNames);

                for (ExportTaskItem task : batchTasks) {
                    Product product = productMap.get(task.getProductCode());
                    if (product == null) continue;
                    boolean isGeneric = task.getShopId() == null || task.getShopId() == 0;
                    if (isGeneric) {
                        createProductRowGeneric(sheet, rowIndex++, product, task, brandConfigMap);
                    } else {
                        Shop shop = shopMap.get(task.getShopId());
                        if (shop == null) continue;
                        createProductRow(sheet, rowIndex++, product, shop, task, imageLinkMap, brandConfigMap);
                    }
                }
            }

            for (int i = 0; i < 20; i++) {
                sheet.setColumnWidth(i, 4000);
            }

            workbook.write(outputStream);
            workbook.dispose();
            log.info("淘宝Excel文件生成完成");
            return outputStream.toByteArray();
        }
    }

    /**
     * 创建Excel表头（第1-3行）
     */
    private void createExcelHeader(Sheet sheet) {
        // 第1行：版本信息
        Row row1 = sheet.createRow(0);
        row1.createCell(0).setCellValue("version 1.00");
        row1.createCell(1).setCellValue("Excel由系统导出");

        // 第2行：英文列名
        Row row2 = sheet.createRow(1);
        String[] englishHeaders = {"title", "cid", "seller_cids", "stuff_status", "location_state", "location_city", "item_type", "price", "auction_increment", "num", "valid_thru", "freight_payer", "post_fee", "ems_fee", "express_fee", "has_invoice", "has_warranty", "approve_status", "has_showcase", "list_time", "description", "cateProps", "postage_id", "has_discount", "modified", "upload_fail_msg", "picture_status", "auction_point", "picture", "video", "skuProps", "inputPids", "inputValues", "outer_id", "propAlias", "auto_fill", "num_id", "local_cid", "navigation_type", "user_name", "syncStatus", "is_lighting_consigment", "is_xinpin", "foodparame", "features", "buyareatype", "global_stock_type", "global_stock_country", "sub_stock_type", "item_size", "item_weight", "sell_promise", "custom_design_flag", "wireless_desc", "barcode", "sku_barcode", "newprepay", "subtitle", "cpv_memo", "input_custom_cpv", "qualification", "add_qualification", "o2o_bind_service", "departure_place", "car_cascade", "legal_customs", "exSkuProps", "deliveryTimeType", "tbDeliveryTime", "nutrientTable", "exFoodParam", "item_volumn", "image_video_type", "shopping_title", "ysbCheckTask", "subStock", "multiDiscountPromotion", "shopping_title2", "useSizeMapping", "sizeMapping", "shippingArea"};
        for (int i = 0; i < englishHeaders.length; i++) {
            row2.createCell(i).setCellValue(englishHeaders[i]);
        }

        // 第3行：中文列名
        Row row3 = sheet.createRow(2);
        String[] chineseHeaders = {"宝贝名称", "宝贝类目", "店铺类目", "新旧程度", "省", "城市", "出售方式", "宝贝价格", "加价幅度", "宝贝数量", "有效期", "运费承担", "平邮", "EMS", "快递", "发票", "保修", "放入仓库", "橱柜推荐", "开始时间", "宝贝描述", "宝贝属性", "邮费模板ID", "会员打折", "修改时间", "上传状态", "图片状态", "返点比例", "新图片", "视频", "销售属性组合", "用户输入ID串", "用户输入名-值对", "商家编码", "销售属性别名", "代充类型", "数字ID", "本地ID", "宝贝分类", "用户名称", "宝贝状态", "闪电发货", "新品", "食品专项", "尺码库", "采购地", "库存类型", "国家地区", "库存计数", "物流体积", "物流重量", "退换货承诺", "定制工具", "无线详情", "商品条形码", "sku 条形码", "7天退货", "宝贝卖点", "属性值备注", "自定义属性值", "商品资质", "增加商品资质", "关联线下服务", "发货地", "汽车品牌", "报关方式", "扩展Sku", "发货时效", "预售时间", "成份表", "扩展食品安全", "物流体积", "主图视频比例", "导购标题", "商品预检", "拍下减库存", "多件优惠", "导购标题2", "使用商品尺寸表", "商品尺寸表", "新发货地"};
        for (int i = 0; i < chineseHeaders.length; i++) {
            row3.createCell(i).setCellValue(chineseHeaders[i]);
        }
    }

    /**
     * 创建产品数据行
     */
    private void createProductRow(Sheet sheet, int rowIndex, Product product, Shop shop,
                                  ExportTaskItem task, Map<String, Map<Integer, String>> imageLinkMap,
                                  Map<String, BrandCustomName> brandConfigMap) {
        Row row = sheet.createRow(rowIndex);
        int col = 0;

        // 0. title: 型号、封装 三级分类、二级分类、一级分类 (优先自定义)
        row.createCell(col++).setCellValue(buildTitle(product));

        // 1. cid: 固定值
        row.createCell(col++).setCellValue(FIXED_CID);

        // 2. seller_cids: 店铺分类码
        String sellerCids = shop.getSellerCategoryId() != null && !shop.getSellerCategoryId().isEmpty()
                ? shop.getSellerCategoryId()
                : String.valueOf(shop.getId());
        row.createCell(col++).setCellValue(sellerCids + ";");

        // 3. stuff_status: 新旧程度（默认值0）
        row.createCell(col++).setCellValue(0);

        // 4-6. location_state, location_city, item_type: 空
        col += 3;

        // 7. price: 最高阶价格*折扣
        BigDecimal price = calculatePrice(product, resolveDiscounts(product, task, brandConfigMap));
        row.createCell(col++).setCellValue(price.toPlainString());

        // 8. auction_increment: 空
        col++;

        // 9. num: (阶梯数 * 真实库存) + 2个自定义SKU(固定1)
        int ladderCount = getLadderCount(product);
        int stockQty = product.getTotalStockQuantity() != null ? product.getTotalStockQuantity() : 0;
        int num = (ladderCount * stockQty) + 2;
        row.createCell(col++).setCellValue(num);

        // 10-11. valid_thru, freight_payer: 空
        col += 2;

        // 12. post_fee: 0
        row.createCell(col++).setCellValue(0);

        // 13. ems_fee: 0
        row.createCell(col++).setCellValue(0);

        // 14. express_fee: 空
        col++;

        // 15. has_invoice: 1
        row.createCell(col++).setCellValue(1);

        // 16. has_warranty: 空
        col++;

        // 17. approve_status: 0
        row.createCell(col++).setCellValue(0);

        // 18-19. has_showcase, list_time: 空
        col += 2;

        // 20. description: 格式已更新为与产品管理表一致
        row.createCell(col++).setCellValue(buildDescription(product));

        // 21. cateProps: 选项编号组合
        row.createCell(col++).setCellValue(buildCateProps(ladderCount));

        // 22. postage_id: 店铺运费模板ID
        row.createCell(col++).setCellValue(shop.getShippingTemplateId() != null ? shop.getShippingTemplateId() : "");

        // 23-27. has_discount~auction_point: 空
        col += 5;

        // 28. picture: :1:0:|+图片链接
        row.createCell(col++).setCellValue(buildPicture(product, shop.getId(), imageLinkMap));

        // 29. video: 空
        col++;

        // 30. skuProps: 调用真实库存，自定义选为固定1
        row.createCell(col++).setCellValue(buildSkuProps(product, resolveDiscounts(product, task, brandConfigMap), ladderCount));

        // 31-32. inputPids, inputValues: 空（2个字段）
        col += 2;

        // 33. outer_id: 品牌（优先自定义名称，&替换为空格）
        String outerId = resolveBrandName(product.getBrand(), brandConfigMap).replace("&", " ");
        row.createCell(col++).setCellValue(outerId);

        // 34. propAlias: 销售属性别名（空）
        col++;

        // 35-44. auto_fill~features: 空（10个字段）
        col += 10;

        // 45. buyareatype: 采购地（默认值0）
        row.createCell(col++).setCellValue(0);

        // 46-47. global_stock_type, global_stock_country: 空（2个字段）
        col += 2;

        // 48. sub_stock_type: 库存计数（默认值0）
        row.createCell(col++).setCellValue(0);

        // 49-58. item_size~cpv_memo: 空（10个字段）
        col += 10;

        // 59. input_custom_cpv: 自定义属性值（选项编号:买X-Y个选这个），由环境变量提供后两个
        row.createCell(col++).setCellValue(buildPropAlias(product, ladderCount));

        // 60-62. qualification~o2o_bind_service: 空（3个字段）
        col += 3;

        // 63. departure_place: 发货地（默认值0）
        row.createCell(col++).setCellValue(0);

        // 64-66. car_cascade~exSkuProps: 空（3个字段）
        col += 3;

        // 67. deliveryTimeType: 发货时效（默认值0）
        row.createCell(col++).setCellValue(0);

        // 68-74. tbDeliveryTime~ysbCheckTask: 空（7个字段）
        col += 7;

        // 75. subStock: 拍下减库存（默认值1）
        row.createCell(col++).setCellValue(1);
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 根据请求条件查询产品
     */
    private List<Product> queryProductsByRequest(AdvancedExportRequest request) {
        LambdaQueryWrapper<Product> wrapper = new LambdaQueryWrapper<>();

        // 🌟 性能优化：限制查询字段，排除可能长达几万字符的 description 等，防止查询 2 万条时前端卡死！
        wrapper.select(Product::getProductCode, Product::getModel, Product::getBrand);

        // 1. 分类
        if (request.getCategoryIds() != null && !request.getCategoryIds().isEmpty()) {
            List<Integer> l1Ids = new ArrayList<>();
            List<Integer> l2Ids = new ArrayList<>();
            List<Integer> l3Ids = new ArrayList<>();

            for (Integer rawId : request.getCategoryIds()) {
                if (rawId > 2000000000) {
                    l3Ids.add(rawId - 2000000000);
                } else if (rawId > 1000000000) {
                    l2Ids.add(rawId - 1000000000);
                } else {
                    l1Ids.add(rawId);
                }
            }

            // ==== 🌟 核心终极修复：跨级别混选查询逻辑 ====
            // 必须独立使用 or() 连接各个分类条件，消除层级强绑定
            wrapper.and(w -> {
                if (!l1Ids.isEmpty()) {
                    w.in(Product::getCategoryLevel1Id, l1Ids);
                }
                if (!l2Ids.isEmpty()) {
                    if (!l1Ids.isEmpty()) { w.or(); }
                    w.in(Product::getCategoryLevel2Id, l2Ids);
                }
                if (!l3Ids.isEmpty()) {
                    if (!l1Ids.isEmpty() || !l2Ids.isEmpty()) { w.or(); }
                    w.in(Product::getCategoryLevel3Id, l3Ids);
                }
            });
            // ==================================================
        }

        // 2. 品牌
        if (request.getBrands() != null && !request.getBrands().isEmpty()) {
            wrapper.in(Product::getBrand, request.getBrands());
        }

        // 3. 智能路由 (任意满足 OR / 叠加 AND)
        boolean hasStockCondition = (request.getStockMin() != null || request.getStockMax() != null);
        boolean hasImgCondition = (request.getHasImage() != null);

        if (Boolean.TRUE.equals(request.getMatchAny()) && hasStockCondition && hasImgCondition) {
            wrapper.and(w -> {
                w.nested(stockW -> {
                    if (request.getStockMin() != null) stockW.ge(Product::getTotalStockQuantity, request.getStockMin());
                    if (request.getStockMax() != null) stockW.le(Product::getTotalStockQuantity, request.getStockMax());
                }).or().nested(imgW -> {
                    if (request.getHasImage()) {
                        imgW.isNotNull(Product::getProductImageUrlBig).ne(Product::getProductImageUrlBig, "");
                    } else {
                        imgW.and(iw2 -> iw2.isNull(Product::getProductImageUrlBig).or().eq(Product::getProductImageUrlBig, ""));
                    }
                });
            });
        } else {
            if (request.getStockMin() != null) wrapper.ge(Product::getTotalStockQuantity, request.getStockMin());
            if (request.getStockMax() != null) wrapper.le(Product::getTotalStockQuantity, request.getStockMax());

            if (request.getHasImage() != null) {
                if (request.getHasImage()) {
                    wrapper.isNotNull(Product::getProductImageUrlBig).ne(Product::getProductImageUrlBig, "");
                } else {
                    wrapper.and(w -> w.isNull(Product::getProductImageUrlBig).or().eq(Product::getProductImageUrlBig, ""));
                }
            }
        }

        // 🌟 性能优化：直接让数据库排序，减轻前端2万数据排序卡顿
        wrapper.orderByAsc(Product::getProductCode);

        return productMapper.selectList(wrapper);
    }

    /**
     * 按名称按需加载图片链接
     */
    private Map<String, Map<Integer, String>> loadImageLinksByNames(Set<String> imageNames) {
        if (imageNames == null || imageNames.isEmpty()) {
            return new HashMap<>();
        }

        List<ImageLink> links = imageLinkMapper.selectList(
                new LambdaQueryWrapper<ImageLink>().in(ImageLink::getImageName, imageNames)
        );

        Map<String, Map<Integer, String>> map = new HashMap<>();
        for (ImageLink link : links) {
            map.computeIfAbsent(link.getImageName(), k -> new HashMap<>())
                    .put(link.getShopId(), link.getImageLink());
        }
        return map;
    }

    /**
     * 追加CSV头部（第1-3行）
     */

    private void appendCsvHeader(StringBuilder csv) {
        // 第1行：版本信息
        csv.append("version 1.00,Csv由Tbup理货员导出,");
        for (int i = 0; i < 78; i++) csv.append(",");
        csv.append("\n");

        // 第2行：英文列名
        csv.append("title,cid,seller_cids,stuff_status,location_state,location_city,item_type,price,auction_increment,num,valid_thru,freight_payer,post_fee,ems_fee,express_fee,has_invoice,has_warranty,approve_status,has_showcase,list_time,description,cateProps,postage_id,has_discount,modified,upload_fail_msg,picture_status,auction_point,picture,video,skuProps,inputPids,inputValues,outer_id,propAlias,auto_fill,num_id,local_cid,navigation_type,user_name,syncStatus,is_lighting_consigment,is_xinpin,foodparame,features,buyareatype,global_stock_type,global_stock_country,sub_stock_type,item_size,item_weight,sell_promise,custom_design_flag,wireless_desc,barcode,sku_barcode,newprepay,subtitle,cpv_memo,input_custom_cpv,qualification,add_qualification,o2o_bind_service,departure_place,car_cascade,legal_customs,exSkuProps,deliveryTimeType,tbDeliveryTime,nutrientTable,exFoodParam,item_volumn,image_video_type,shopping_title,ysbCheckTask,subStock,multiDiscountPromotion,shopping_title2,useSizeMapping,sizeMapping,shippingArea\n");

        // 第3行：中文列名
        csv.append("宝贝名称,宝贝类目,店铺类目,新旧程度,省,城市,出售方式,宝贝价格,加价幅度,宝贝数量,有效期,运费承担,平邮,EMS,快递,发票,保修,放入仓库,橱柜推荐,开始时间,宝贝描述,宝贝属性,邮费模板ID,会员打折,修改时间,上传状态,图片状态,返点比例,新图片,视频,销售属性组合,用户输入ID串,用户输入名-值对,商家编码,销售属性别名,代充类型,数字ID,本地ID,宝贝分类,用户名称,宝贝状态,闪电发货,新品,食品专项,尺码库,采购地,库存类型,国家地区,库存计数,物流体积,物流重量,退换货承诺,定制工具,无线详情,商品条形码,sku 条形码,7天退货,宝贝卖点,属性值备注,自定义属性值,商品资质,增加商品资质,关联线下服务,发货地,汽车品牌,报关方式,扩展Sku,发货时效,预售时间,成份表,扩展食品安全,物流体积,主图视频比例,导购标题,商品预检,拍下减库存,多件优惠,导购标题2,使用商品尺寸表,商品尺寸表,新发货地\n");
    }

    /**
     * 追加产品数据行
     */
    private void appendProductRow(StringBuilder csv, Product product, Shop shop, ExportTaskItem task,
                                  Map<String, Map<Integer, String>> imageLinkMap, Map<String, BrandCustomName> brandConfigMap) {
        // 0. title: 型号、封装 三级分类、二级分类、一级分类
        String title = buildTitle(product);
        csv.append(escapeCsv(title)).append(",");

        // 1. cid: 固定值
        csv.append(FIXED_CID).append(",");

        // 2. seller_cids: 店铺分类码
        String sellerCids = shop.getSellerCategoryId() != null && !shop.getSellerCategoryId().isEmpty()
                ? shop.getSellerCategoryId()
                : String.valueOf(shop.getId());
        csv.append(sellerCids).append(";,");

        // 3. stuff_status: 新旧程度（默认值0）
        csv.append("0,");

        // 4-6. location_state, location_city, item_type: 空
        csv.append(",,,");

        // 7. price: 最高阶价格*折扣
        BigDecimal price = calculatePrice(product, resolveDiscounts(product, task, brandConfigMap));
        csv.append(price.toPlainString()).append(",");

        // 8. auction_increment: 空
        csv.append(",");

        // 9. num: (阶梯数 * 真实库存) + 2个自定义SKU(固定1)
        int ladderCount = getLadderCount(product);
        int stockQty = product.getTotalStockQuantity() != null ? product.getTotalStockQuantity() : 0;
        int num = (ladderCount * stockQty) + 2;
        csv.append(num).append(",");

        // 10-19. valid_thru~list_time
        // 修改后（末尾是3个逗号）：
        csv.append(",,0,0,,1,,0,,,");

        // 20. description
        String description = buildDescription(product);
        csv.append(escapeCsv(description)).append(",");

        // 21. cateProps
        String cateProps = buildCateProps(ladderCount);
        csv.append(cateProps).append(",");

        // 22. postage_id
        csv.append(shop.getShippingTemplateId() != null ? shop.getShippingTemplateId() : "").append(",");

        // 23-27. has_discount~auction_point: 空
        csv.append(",,,,,");

        // 28. picture
        String picture = buildPicture(product, shop.getId(), imageLinkMap);
        csv.append(picture).append(",");

        // 29. video: 空
        csv.append(",");

        // 30. skuProps: 调用真实库存，自定义选为固定1
        String skuProps = buildSkuProps(product, resolveDiscounts(product, task, brandConfigMap), ladderCount);
        csv.append(skuProps).append(",");

        // 31-32. inputPids, inputValues: 空
        csv.append(",,");

        // 33. outer_id
        String outerId = resolveBrandName(product.getBrand(), brandConfigMap).replace("&", " ");
        csv.append(escapeCsv(outerId)).append(",");

        // 34. propAlias: 空
        csv.append(",");

        // 35-44. auto_fill~features: 空
        csv.append(",,,,,,,,,,");

        // 45. buyareatype: 0
        csv.append("0,");

        // 46-47. global_stock_type, global_stock_country: 空
        csv.append(",,");

        // 48. sub_stock_type: 0
        csv.append("0,");

        // 49-58. item_size~cpv_memo: 空
        csv.append(",,,,,,,,,,");

        // 59. input_custom_cpv: 由环境变量提供后两个
        String inputCustomCpv = buildPropAlias(product, ladderCount);
        csv.append(escapeCsv(inputCustomCpv)).append(",");

        // 60-62. qualification~o2o_bind_service: 空
        csv.append(",,,");

        // 63. departure_place: 0
        csv.append("0,");

        // 64-66. car_cascade~exSkuProps: 空
        csv.append(",,,");

        // 67. deliveryTimeType: 0
        csv.append("0,");

        // 68-74. tbDeliveryTime~ysbCheckTask: 空
        csv.append(",,,,,,,");

        // 75. subStock: 1
        csv.append("1,");

        // 76-79. multiDiscountPromotion~shippingArea: 空
        csv.append(",,,,");

        csv.append("\n");
    }

    // ==================== CSV字段生成方法 ====================

    /**
     * 构建title: 型号 封装 三级分类(优先自定义) 二级分类(优先自定义) 一级分类(优先自定义)
     */
    private String buildTitle(Product product) {
        StringBuilder title = new StringBuilder();

        // 1. 型号
        if (product.getModel() != null && !product.getModel().isEmpty()) {
            title.append(product.getModel());
        }

        // 2. 封装（用空格分隔）
        if (product.getPackageName() != null && !product.getPackageName().isEmpty()) {
            if (title.length() > 0) title.append(" ");
            title.append(product.getPackageName());
        }

        // 3. 三级分类（优先自定义，用空格分隔）
        String l3Name = product.getCategoryLevel3CustomName() != null && !product.getCategoryLevel3CustomName().isEmpty()
                ? product.getCategoryLevel3CustomName()
                : product.getCategoryLevel3Name();
        if (l3Name != null && !l3Name.isEmpty()) {
            if (title.length() > 0) title.append(" ");
            title.append(l3Name);
        }

        // 4. 二级分类（优先自定义，用空格分隔）
        String l2Name = product.getCategoryLevel2CustomName() != null && !product.getCategoryLevel2CustomName().isEmpty()
                ? product.getCategoryLevel2CustomName()
                : product.getCategoryLevel2Name();
        if (l2Name != null && !l2Name.isEmpty()) {
            if (title.length() > 0) title.append(" ");
            title.append(l2Name);
        }

        // 5. 一级分类（优先自定义，用空格分隔）
        String l1Name = product.getCategoryLevel1CustomName() != null && !product.getCategoryLevel1CustomName().isEmpty()
                ? product.getCategoryLevel1CustomName()
                : product.getCategoryLevel1Name();
        if (l1Name != null && !l1Name.isEmpty()) {
            if (title.length() > 0) title.append(" ");
            title.append(l1Name);
        }

        // 替换多余的空格，保持整洁
        return title.toString().trim().replaceAll("\\s+", " ");
    }

    // 🌟🌟 核心逻辑：计算宝贝价格 (取最后一级阶梯价 × 最后一级折扣，保留所有小数位) 🌟🌟
    private BigDecimal calculatePrice(Product product, List<BigDecimal> discounts) {
        BigDecimal minPrice = null;
        int validLadderCount = 1; // 记录最后一个有效阶梯的索引

        // 遍历找到最低价（即最后一级阶梯价），同时确认有效阶梯数量
        for (int i = 1; i <= 6; i++) {
            BigDecimal price = getLadderPriceByIndex(product, i);
            if (price != null && price.compareTo(BigDecimal.ZERO) > 0) {
                if (minPrice == null || price.compareTo(minPrice) < 0) {
                    minPrice = price;
                }
                validLadderCount = i;
            }
        }

        if (minPrice == null) {
            return BigDecimal.ZERO;
        }

        if (discounts == null || discounts.isEmpty()) {
            return minPrice; // 保持原精度
        }

        // 🌟 核心修改：折扣改为【最后一级折扣】
        // 索引从 0 开始，所以阶梯数减 1
        int lastDiscountIdx = Math.max(0, validLadderCount - 1);
        lastDiscountIdx = Math.min(lastDiscountIdx, discounts.size() - 1); // 防越界

        BigDecimal discount = discounts.get(lastDiscountIdx).divide(new BigDecimal("100"));

        // 最终价格直接相乘，不截断，保留自然小数
        return minPrice.multiply(discount);
    }
    private String buildDescription(Product product) {
        String parametersText = product.getParametersText();

        StringBuilder html = new StringBuilder();
        html.append("<span style=\"color:#E53333;\"><h1>主要参数: <br />");

        if (parametersText != null && !parametersText.trim().isEmpty()) {
            String[] params = parametersText.split("\\s+");
            for (String param : params) {
                if (param.contains(":")) {
                    String[] kv = param.split(":", 2);
                    if (kv.length == 2) {
                        html.append(kv[0].trim()).append(" : ").append(kv[1].trim()).append("<br />");
                    }
                }
            }
        }

        html.append("</h1></span>");
        return html.toString();
    }

    private String buildCateProps(int ladderCount) {
        StringBuilder props = new StringBuilder();
        int totalOptions = ladderCount + 2;
        for (int i = 1; i <= totalOptions; i++) {
            props.append(OPTION_CODE_PREFIX).append(i).append(";");
        }
        return props.toString();
    }

    private String buildPicture(Product product, int shopId, Map<String, Map<Integer, String>> imageLinkMap) {
        String imageUrl = null;

        if (product.getImageName() != null && imageLinkMap.containsKey(product.getImageName())) {
            Map<Integer, String> shopLinks = imageLinkMap.get(product.getImageName());
            if (shopLinks != null && shopLinks.containsKey(shopId)) {
                imageUrl = shopLinks.get(shopId);
            }
        }

        if (imageUrl == null || imageUrl.isEmpty()) {
            imageUrl = product.getProductImageUrlBig();
        }

        if (imageUrl == null || imageUrl.isEmpty()) {
            if (imageLinkMap.containsKey("no-image.jpg")) {
                Map<Integer, String> noImageLinks = imageLinkMap.get("no-image.jpg");
                if (noImageLinks != null && noImageLinks.containsKey(shopId)) {
                    imageUrl = noImageLinks.get(shopId);
                }
            }
            if ((imageUrl == null || imageUrl.isEmpty()) && imageLinkMap.containsKey("no-image.jpg")) {
                Map<Integer, String> noImageLinks = imageLinkMap.get("no-image.jpg");
                if (noImageLinks != null && noImageLinks.containsKey(shopId)) {
                    imageUrl = noImageLinks.get(shopId);
                }
            }
        }

        if (imageUrl == null || imageUrl.isEmpty()) {
            imageUrl = GLOBAL_DEFAULT_IMAGE;
        }

        return ":1:0:|" + imageUrl;
    }

    // 🌟🌟 核心逻辑：生成SKU属性 🌟🌟
    private String buildSkuProps(Product product, List<BigDecimal> discounts, int ladderCount) {
        StringBuilder props = new StringBuilder();
        int totalOptions = ladderCount + 2;

        int stockQty = product.getTotalStockQuantity() != null ? product.getTotalStockQuantity() : 0;

        if (discounts == null) {
            discounts = new ArrayList<>();
        }
        if (discounts.isEmpty()) {
            discounts.add(BigDecimal.valueOf(100));
        }

        for (int i = 0; i < totalOptions; i++) {
            BigDecimal price;
            int discountIdx;
            int skuStock;

            if (i < ladderCount) {
                // 【正常阶梯：按照实际阶梯取价和取折扣】
                price = getLadderPriceByIndex(product, i + 1);
                discountIdx = i;
                skuStock = stockQty;
            } else {
                // 【自定义 SKU：最后两个选项】
                // 🌟 核心修改：基础价取【最后一级阶梯价】，折扣取【最后一级折扣】
                price = getLadderPriceByIndex(product, ladderCount);
                discountIdx = ladderCount - 1; // 改为最后一级折扣
                skuStock = 1;
            }

            if (price == null) {
                price = BigDecimal.ZERO;
            }

            BigDecimal discount = discounts.get(Math.min(discountIdx, discounts.size() - 1))
                    .divide(new BigDecimal("100"));

            // 绝对不进行进位或截断操作
            BigDecimal finalPrice = price.multiply(discount);

            props.append(finalPrice.stripTrailingZeros().toPlainString())
                    .append(":")
                    .append(skuStock)
                    .append("::")
                    .append(OPTION_CODE_PREFIX)
                    .append(i + 1)
                    .append(";");
        }

        return props.toString();
    }

    private String buildPropAlias(Product product, int ladderCount) {
        StringBuilder alias = new StringBuilder();
        int totalOptions = ladderCount + 2;

        for (int i = 0; i < totalOptions; i++) {
            String optionCode = OPTION_CODE_PREFIX + (i + 1);
            String text;

            if (i == totalOptions - 2) {
                text = customSkuName1;
            } else if (i == totalOptions - 1) {
                text = customSkuName2;
            } else if (i < ladderCount - 1) {
                int currentQty = getLadderQuantityByIndex(product, i + 1);
                int nextQty = getLadderQuantityByIndex(product, i + 2);
                text = "买" + currentQty + "-" + (nextQty - 1) + "个选这个";
            } else {
                int currentQty = getLadderQuantityByIndex(product, i + 1);
                text = "买" + currentQty + "个起选这个";
            }

            alias.append(optionCode).append(":").append(text).append(";");
        }

        return alias.toString();
    }

    // ==================== 阶梯价格辅助方法 ====================

    private int getLadderCount(Product product) {
        for (int i = 6; i >= 1; i--) {
            BigDecimal price = getLadderPriceByIndex(product, i);
            if (price != null && price.compareTo(BigDecimal.ZERO) > 0) {
                return i;
            }
        }
        return 1;
    }

    private BigDecimal getLadderPriceByIndex(Product product, int index) {
        switch (index) {
            case 1: return product.getLadderPrice1Price();
            case 2: return product.getLadderPrice2Price();
            case 3: return product.getLadderPrice3Price();
            case 4: return product.getLadderPrice4Price();
            case 5: return product.getLadderPrice5Price();
            case 6: return product.getLadderPrice6Price();
            default: return null;
        }
    }

    private int getLadderQuantityByIndex(Product product, int index) {
        switch (index) {
            case 1: return product.getLadderPrice1Quantity() != null ? product.getLadderPrice1Quantity() : 0;
            case 2: return product.getLadderPrice2Quantity() != null ? product.getLadderPrice2Quantity() : 0;
            case 3: return product.getLadderPrice3Quantity() != null ? product.getLadderPrice3Quantity() : 0;
            case 4: return product.getLadderPrice4Quantity() != null ? product.getLadderPrice4Quantity() : 0;
            case 5: return product.getLadderPrice5Quantity() != null ? product.getLadderPrice5Quantity() : 0;
            case 6: return product.getLadderPrice6Quantity() != null ? product.getLadderPrice6Quantity() : 0;
            default: return 0;
        }
    }

    private String escapeCsv(String value) {
        if (value == null) {
            return "";
        }

        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }

        return value;
    }

    // ==================== 通用模式（shopId=0）行输出 ====================

    /**
     * 通用模式 CSV 行：C列(seller_cids)=空，W列(postage_id)=空，AC列(picture)=图片名称:1:0:|;
     */

    private void appendProductRowGeneric(StringBuilder csv, Product product, ExportTaskItem task, Map<String, BrandCustomName> brandConfigMap) {
        int ladderCount = getLadderCount(product);
        int stockQty = product.getTotalStockQuantity() != null ? product.getTotalStockQuantity() : 0;
        int num = (ladderCount * stockQty) + 2;
        BigDecimal price = calculatePrice(product, resolveDiscounts(product, task, brandConfigMap));

        csv.append(escapeCsv(buildTitle(product))).append(","); // 0. title
        csv.append(FIXED_CID).append(",");                      // 1. cid
        csv.append(",");                                         // 2. seller_cids = 空
        csv.append("0,");                                        // 3. stuff_status
        csv.append(",,,");                                       // 4-6. 空

        // 🌟 使用 toPlainString() 保持输出字符串精确
        csv.append(price.toPlainString()).append(",");

        csv.append(",");                                         // 8. 空
        csv.append(num).append(",");                             // 9. num
        csv.append(",,0,0,,1,,0,,,");                          // 10-19
        csv.append(escapeCsv(buildDescription(product))).append(","); // 20. description
        csv.append(buildCateProps(ladderCount)).append(",");     // 21. cateProps
        csv.append(",");                                         // 22. postage_id = 空
        csv.append(",,,,,");                                     // 23-27. 空

        // 🌟 28. picture = 图片名称:1:0:|; (智能去后缀，无图统配 no-image)
        String imageName = product.getImageName();
        if (imageName == null || imageName.trim().isEmpty()) {
            imageName = "no-image"; // 无图产品统一使用 no-image
        } else if (imageName.contains(".")) {
            imageName = imageName.substring(0, imageName.lastIndexOf(".")); // 去掉 .jpg 等后缀
        }
        csv.append(imageName).append(":1:0:|;").append(",");

        csv.append(",");                                         // 29. video
        csv.append(escapeCsv(buildSkuProps(product, resolveDiscounts(product, task, brandConfigMap), ladderCount))).append(","); // 30. skuProps
        csv.append(",,");                                        // 31-32. 空
        String outerId = resolveBrandName(product.getBrand(), brandConfigMap).replace("&", " ");
        csv.append(escapeCsv(outerId)).append(",");              // 33. outer_id
        csv.append(",");                                         // 34. 空
        csv.append(",,,,,,,,,,");                               // 35-44. 空
        csv.append("0,");                                        // 45. buyareatype
        csv.append(",,");                                        // 46-47. 空
        csv.append("0,");                                        // 48. sub_stock_type
        csv.append(",,,,,,,,,,");                               // 49-58. 空
        csv.append(escapeCsv(buildPropAlias(product, ladderCount))).append(","); // 59. input_custom_cpv
        csv.append(",,,");                                       // 60-62. 空
        csv.append("0,");                                        // 63. departure_place
        csv.append(",,,");                                       // 64-66. 空
        csv.append("0,");                                        // 67. deliveryTimeType
        csv.append(",,,,,,,");                                   // 68-74. 空
        csv.append("1,");                                        // 75. subStock
        csv.append(",,,,");                                      // 76-79. 空
        csv.append("\n");
    }

    /**
     * 通用模式 Excel 行：C列(seller_cids)=空，W列(postage_id)=空，AC列(picture)=图片名称:1:0:|;
     */
    private void createProductRowGeneric(Sheet sheet, int rowIndex, Product product, ExportTaskItem task, Map<String, BrandCustomName> brandConfigMap) {
        Row row = sheet.createRow(rowIndex);
        int col = 0;
        int ladderCount = getLadderCount(product);
        int stockQty = product.getTotalStockQuantity() != null ? product.getTotalStockQuantity() : 0;
        int num = (ladderCount * stockQty) + 2;

        row.createCell(col++).setCellValue(buildTitle(product));          // 0. title
        row.createCell(col++).setCellValue(FIXED_CID);                    // 1. cid
        col++;                                                             // 2. seller_cids = 空
        row.createCell(col++).setCellValue(0);                            // 3. stuff_status
        col += 3;                                                          // 4-6. 空

        // 🌟 同样必须使用 toPlainString()
        row.createCell(col++).setCellValue(calculatePrice(product, resolveDiscounts(product, task, brandConfigMap)).toPlainString());

        col++;                                                             // 8. 空
        row.createCell(col++).setCellValue(num);                          // 9. num
        col += 2;                                                          // 10-11. 空
        row.createCell(col++).setCellValue(0);                            // 12. post_fee
        row.createCell(col++).setCellValue(0);                            // 13. ems_fee
        col++;                                                             // 14. 空
        row.createCell(col++).setCellValue(1);                            // 15. has_invoice
        col++;                                                             // 16. 空
        row.createCell(col++).setCellValue(0);                            // 17. approve_status
        col += 2;                                                          // 18-19. 空
        row.createCell(col++).setCellValue(buildDescription(product));    // 20. description
        row.createCell(col++).setCellValue(buildCateProps(ladderCount));  // 21. cateProps
        col++;                                                             // 22. postage_id = 空
        col += 5;                                                          // 23-27. 空

        // 🌟 28. picture = 图片名称:1:0:|; (智能去后缀，无图统配 no-image)
        String imageName = product.getImageName();
        if (imageName == null || imageName.trim().isEmpty()) {
            imageName = "no-image"; // 无图产品统一使用 no-image
        } else if (imageName.contains(".")) {
            imageName = imageName.substring(0, imageName.lastIndexOf(".")); // 去掉 .jpg 等后缀
        }
        row.createCell(col++).setCellValue(imageName + ":1:0:|;");

        col++;                                                             // 29. video
        row.createCell(col++).setCellValue(buildSkuProps(product, resolveDiscounts(product, task, brandConfigMap), ladderCount)); // 30. skuProps
        col += 2;                                                          // 31-32. 空
        String outerId = resolveBrandName(product.getBrand(), brandConfigMap).replace("&", " ");
        row.createCell(col++).setCellValue(outerId);                      // 33. outer_id
        col += 11;                                                         // 34-44. 空
        row.createCell(col++).setCellValue(0);                            // 45. buyareatype
        col += 2;                                                          // 46-47. 空
        row.createCell(col++).setCellValue(0);                            // 48. sub_stock_type
        col += 10;                                                         // 49-58. 空
        row.createCell(col++).setCellValue(buildPropAlias(product, ladderCount)); // 59. input_custom_cpv
        col += 3;                                                          // 60-62. 空
        row.createCell(col++).setCellValue(0);                            // 63. departure_place
        col += 3;                                                          // 64-66. 空
        row.createCell(col++).setCellValue(0);                            // 67. deliveryTimeType
        col += 7;                                                          // 68-74. 空
        row.createCell(col++).setCellValue(1);                            // 75. subStock
    }

    // ==================== 填充自定义分类名称 ====================

    private void enrichCategoryNames(List<Product> products) {
        if (products == null || products.isEmpty()) return;

        Set<Integer> l1Ids = products.stream().map(Product::getCategoryLevel1Id).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Integer> l2Ids = products.stream().map(Product::getCategoryLevel2Id).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Integer> l3Ids = products.stream().map(Product::getCategoryLevel3Id).filter(Objects::nonNull).collect(Collectors.toSet());

        Map<Integer, CategoryLevel1Code> l1Map = l1Ids.isEmpty() ? Collections.emptyMap() :
                categoryLevel1CodeMapper.selectBatchIds(l1Ids).stream().collect(Collectors.toMap(CategoryLevel1Code::getId, c -> c));

        Map<Integer, CategoryLevel2Code> l2Map = l2Ids.isEmpty() ? Collections.emptyMap() :
                categoryLevel2CodeMapper.selectBatchIds(l2Ids).stream().collect(Collectors.toMap(CategoryLevel2Code::getId, c -> c));

        Map<Integer, CategoryLevel3Code> l3Map = l3Ids.isEmpty() ? Collections.emptyMap() :
                categoryLevel3CodeMapper.selectBatchIds(l3Ids).stream().collect(Collectors.toMap(CategoryLevel3Code::getId, c -> c));

        for (Product p : products) {
            if (p == null) continue;

            // 填充一级分类
            if (p.getCategoryLevel1Id() != null && l1Map.containsKey(p.getCategoryLevel1Id())) {
                CategoryLevel1Code c = l1Map.get(p.getCategoryLevel1Id());
                p.setCategoryLevel1Name(c.getCategoryLevel1Name());
                p.setCategoryLevel1CustomName(c.getCustomName() != null && !c.getCustomName().isEmpty() ? c.getCustomName() : c.getCategoryLevel1Name());
            }
            // 填充二级分类
            if (p.getCategoryLevel2Id() != null && l2Map.containsKey(p.getCategoryLevel2Id())) {
                CategoryLevel2Code c = l2Map.get(p.getCategoryLevel2Id());
                p.setCategoryLevel2Name(c.getCategoryLevel2Name());
                p.setCategoryLevel2CustomName(c.getCustomName() != null && !c.getCustomName().isEmpty() ? c.getCustomName() : c.getCategoryLevel2Name());
            }
            // 填充三级分类
            if (p.getCategoryLevel3Id() != null && l3Map.containsKey(p.getCategoryLevel3Id())) {
                CategoryLevel3Code c = l3Map.get(p.getCategoryLevel3Id());
                p.setCategoryLevel3Name(c.getCategoryLevel3Name());
                p.setCategoryLevel3CustomName(c.getCustomName() != null && !c.getCustomName().isEmpty() ? c.getCustomName() : c.getCategoryLevel3Name());
            }
        }
    }

    private String resolveBrandName(String originalBrand, Map<String, BrandCustomName> configMap) {
        if (originalBrand == null) return "";
        if (configMap != null && configMap.containsKey(originalBrand)) {
            String custom = configMap.get(originalBrand).getCustomName();
            if (custom != null && !custom.isEmpty()) return custom;
        }
        return originalBrand;
    }

    private List<BigDecimal> resolveDiscounts(Product product, ExportTaskItem task, Map<String, BrandCustomName> configMap) {
        String originalBrand = product.getBrand();
        if (originalBrand != null && configMap != null && configMap.containsKey(originalBrand)) {
            Integer scheme = configMap.get(originalBrand).getDiscountScheme();
            if (scheme != null) {
                if (scheme == 1 && task.getBrandDiscounts1() != null && !task.getBrandDiscounts1().isEmpty()) {
                    return task.getBrandDiscounts1();
                } else if (scheme == 2 && task.getBrandDiscounts2() != null && !task.getBrandDiscounts2().isEmpty()) {
                    return task.getBrandDiscounts2();
                }
            }
        }
        return task.getDiscounts(); // 走默认折扣
    }
}