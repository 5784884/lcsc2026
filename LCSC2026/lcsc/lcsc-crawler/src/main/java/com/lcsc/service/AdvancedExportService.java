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
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * 高级导出服务 - 淘宝CSV/Excel格式
 */
@Service
public class AdvancedExportService {

    private static final Logger log = LoggerFactory.getLogger(AdvancedExportService.class);

    // CSV固定值
    private static final String FIXED_CID = "50018871";  // 宝贝类目ID（固定值）
    private static final String OPTION_CODE_PREFIX = "1627207:-100";  // 选项编号前缀

    // 全局兜底图（当店铺也没上传无图时使用）
    private static final String GLOBAL_DEFAULT_IMAGE = "https://assets.lcsc.com/images/no-image.jpg";

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

        // 2. 查询店铺信息
        Shop shop = shopMapper.selectById(request.getShopId());
        if (shop == null) {
            throw new RuntimeException("店铺不存在: " + request.getShopId());
        }

        // 3. 转换为任务项
        List<ExportTaskItem> newTasks = products.stream().map(product -> {
            ExportTaskItem task = new ExportTaskItem();
            task.setProductCode(product.getProductCode());
            task.setModel(product.getModel());
            task.setBrand(product.getBrand());
            task.setShopId(shop.getId());
            task.setShopName(shop.getShopName());
            task.setDiscounts(request.getDiscounts());
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
     * 生成淘宝CSV文件
     * @param tasks 任务列表
     * @return CSV文件字节数组
     */
    public byte[] generateTaobaoCsv(List<ExportTaskItem> tasks) throws IOException {
        log.info("开始生成淘宝CSV文件, 任务数量: {}", tasks.size());

        if (tasks == null || tasks.isEmpty()) {
            return new byte[0];
        }

        // 1. 查询所有产品详情
        Set<String> productCodes = tasks.stream()
                .map(ExportTaskItem::getProductCode)
                .collect(Collectors.toSet());

        List<Product> products = productMapper.selectList(
                new LambdaQueryWrapper<Product>()
                        .in(Product::getProductCode, productCodes)
        );

        // 填充自定义分类名称
        enrichCategoryNames(products);

        Map<String, Product> productMap = products.stream()
                .collect(Collectors.toMap(Product::getProductCode, p -> p));

        // 2. 查询所有图片链接
        Map<String, Map<Integer, String>> imageLinkMap = loadImageLinks();

        // 3. 查询所有店铺信息
        Set<Integer> shopIds = tasks.stream()
                .map(ExportTaskItem::getShopId)
                .collect(Collectors.toSet());

        List<Shop> shops = shopMapper.selectBatchIds(shopIds);
        Map<Integer, Shop> shopMap = shops.stream()
                .collect(Collectors.toMap(Shop::getId, s -> s));

        // 4. 构建CSV内容
        StringBuilder csv = new StringBuilder();

        // CSV头部（第1-3行）
        appendCsvHeader(csv);

        // 产品数据行
        for (ExportTaskItem task : tasks) {
            Product product = productMap.get(task.getProductCode());
            if (product == null) {
                log.warn("产品不存在: {}", task.getProductCode());
                continue;
            }

            Shop shop = shopMap.get(task.getShopId());
            if (shop == null) {
                log.warn("店铺不存在: {}", task.getShopId());
                continue;
            }

            appendProductRow(csv, product, shop, task, imageLinkMap);
        }

        log.info("淘宝CSV文件生成完成");
        return csv.toString().getBytes(StandardCharsets.UTF_8); // 注意：淘宝助理可能需要 GBK，视具体情况调整
    }

    /**
     * 生成淘宝Excel文件
     * @param tasks 任务列表
     * @return Excel文件字节数组
     */
    public byte[] generateTaobaoExcel(List<ExportTaskItem> tasks) throws IOException {
        log.info("开始生成淘宝Excel文件, 任务数量: {}", tasks.size());

        if (tasks == null || tasks.isEmpty()) {
            return new byte[0];
        }

        // 1. 查询所有产品详情
        Set<String> productCodes = tasks.stream()
                .map(ExportTaskItem::getProductCode)
                .collect(Collectors.toSet());

        List<Product> products = productMapper.selectList(
                new LambdaQueryWrapper<Product>()
                        .in(Product::getProductCode, productCodes)
        );

        // 填充自定义分类名称
        enrichCategoryNames(products);

        Map<String, Product> productMap = products.stream()
                .collect(Collectors.toMap(Product::getProductCode, p -> p));

        // 2. 查询所有图片链接
        Map<String, Map<Integer, String>> imageLinkMap = loadImageLinks();

        // 3. 查询所有店铺信息
        Set<Integer> shopIds = tasks.stream()
                .map(ExportTaskItem::getShopId)
                .collect(Collectors.toSet());

        List<Shop> shops = shopMapper.selectBatchIds(shopIds);
        Map<Integer, Shop> shopMap = shops.stream()
                .collect(Collectors.toMap(Shop::getId, s -> s));

        // 4. 创建Excel工作簿
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {

            Sheet sheet = workbook.createSheet("淘宝导入");

            // 创建表头（第1-3行）
            createExcelHeader(sheet);

            // 创建产品数据行（从第4行开始，rowIndex=3）
            int rowIndex = 3;
            for (ExportTaskItem task : tasks) {
                Product product = productMap.get(task.getProductCode());
                if (product == null) {
                    log.warn("产品不存在: {}", task.getProductCode());
                    continue;
                }

                Shop shop = shopMap.get(task.getShopId());
                if (shop == null) {
                    log.warn("店铺不存在: {}", task.getShopId());
                    continue;
                }

                createProductRow(sheet, rowIndex++, product, shop, task, imageLinkMap);
            }

            // 自动调整列宽（仅对前20列，避免性能问题）
            for(int i=0; i<20; i++) {
                sheet.autoSizeColumn(i);
            }

            workbook.write(outputStream);
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
                                  ExportTaskItem task, Map<String, Map<Integer, String>> imageLinkMap) {
        Row row = sheet.createRow(rowIndex);
        int col = 0;

        // 0. title: 型号、封装 三级分类、二级分类、一级分类 (优先自定义)
        row.createCell(col++).setCellValue(buildTitle(product));

        // 1. cid: 固定值
        row.createCell(col++).setCellValue(FIXED_CID);

        // 2. seller_cids: 店铺分类码（优先使用sellerCategoryId，fallback到shop.getId()）
        String sellerCids = shop.getSellerCategoryId() != null && !shop.getSellerCategoryId().isEmpty()
                ? shop.getSellerCategoryId()
                : String.valueOf(shop.getId());
        row.createCell(col++).setCellValue(sellerCids + ";");

        // 3. stuff_status: 新旧程度（默认值0）
        row.createCell(col++).setCellValue(0);

        // 4-6. location_state, location_city, item_type: 空
        col += 3;

        // 7. price: 最高阶价格*折扣
        BigDecimal price = calculatePrice(product, task.getDiscounts());
        row.createCell(col++).setCellValue(price.doubleValue());

        // 8. auction_increment: 空
        col++;

        // 9. num: (阶梯级数+2)*1000000
        int ladderCount = getLadderCount(product);
        int num = (ladderCount + 2) * 1000000;
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

        // 20. description: ✅ 格式已更新为与产品管理表一致
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

        // 30. skuProps: 价格:1000000::选项编号组合
        row.createCell(col++).setCellValue(buildSkuProps(product, task.getDiscounts(), ladderCount));

        // 31-32. inputPids, inputValues: 空（2个字段）
        col += 2;

        // 33. outer_id: 品牌（&替换为空格）
        String outerId = product.getBrand() != null ? product.getBrand().replace("&", " ") : "";
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

        // 59. input_custom_cpv: 自定义属性值（选项编号:买X-Y个选这个）
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
    /**
     * 根据请求条件查询产品
     */
    private List<Product> queryProductsByRequest(AdvancedExportRequest request) {
        LambdaQueryWrapper<Product> wrapper = new LambdaQueryWrapper<>();

        // 1. 分类：解析前端传来的带前缀 ID (10亿级为L2, 20亿级为L3, 否则为L1)
        if (request.getCategoryIds() != null && !request.getCategoryIds().isEmpty()) {
            List<Integer> l1Ids = new ArrayList<>();
            List<Integer> l2Ids = new ArrayList<>();
            List<Integer> l3Ids = new ArrayList<>();

            for (Integer rawId : request.getCategoryIds()) {
                if (rawId > 2000000000) {
                    l3Ids.add(rawId - 2000000000); // 提取三级分类真实ID
                } else if (rawId > 1000000000) {
                    l2Ids.add(rawId - 1000000000); // 提取二级分类真实ID
                } else {
                    l1Ids.add(rawId);              // 一级分类真实ID
                }
            }

            // 组装精准的层级查询条件
            wrapper.and(w -> {
                boolean hasCondition = false;
                if (!l1Ids.isEmpty()) {
                    w.in(Product::getCategoryLevel1Id, l1Ids);
                    hasCondition = true;
                }
                if (!l2Ids.isEmpty()) {
                    if (hasCondition) w.or();
                    w.in(Product::getCategoryLevel2Id, l2Ids);
                    hasCondition = true;
                }
                if (!l3Ids.isEmpty()) {
                    if (hasCondition) w.or();
                    w.in(Product::getCategoryLevel3Id, l3Ids);
                }
            });
        }

        // 2. 品牌：品牌多选也需要使用 in
        if (request.getBrands() != null && !request.getBrands().isEmpty()) {
            wrapper.in(Product::getBrand, request.getBrands());
        }

        // 3. 图片：叠加 AND
        if (request.getHasImage() != null) {
            if (request.getHasImage()) {
                wrapper.and(w -> w.isNotNull(Product::getProductImageUrlBig).ne(Product::getProductImageUrlBig, ""));
            } else {
                wrapper.and(w -> w.isNull(Product::getProductImageUrlBig).or().eq(Product::getProductImageUrlBig, ""));
            }
        }

        // 4. 库存：叠加 AND
        if (request.getStockMin() != null) wrapper.ge(Product::getTotalStockQuantity, request.getStockMin());
        if (request.getStockMax() != null) wrapper.le(Product::getTotalStockQuantity, request.getStockMax());

        return productMapper.selectList(wrapper);
    }
    /**
     * 加载所有图片链接（image_name -> shop_id -> image_link）
     */
    private Map<String, Map<Integer, String>> loadImageLinks() {
        List<ImageLink> links = imageLinkMapper.selectList(null);
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
                                  Map<String, Map<Integer, String>> imageLinkMap) {
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
        BigDecimal price = calculatePrice(product, task.getDiscounts());
        csv.append(price.toString()).append(",");

        // 8. auction_increment: 空
        csv.append(",");

        // 9. num: (阶梯级数+2)*1000000
        int ladderCount = getLadderCount(product);
        int num = (ladderCount + 2) * 1000000;
        csv.append(num).append(",");

        // 10-19. valid_thru~list_time
        csv.append(",,0,0,,1,,0,,");

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

        // 30. skuProps
        String skuProps = buildSkuProps(product, task.getDiscounts(), ladderCount);
        csv.append(skuProps).append(",");

        // 31-32. inputPids, inputValues: 空
        csv.append(",,");

        // 33. outer_id
        String outerId = product.getBrand() != null ? product.getBrand().replace("&", " ") : "";
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

        // 59. input_custom_cpv
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

    private BigDecimal calculatePrice(Product product, List<BigDecimal> discounts) {
        BigDecimal minPrice = null;
        for (int i = 1; i <= 6; i++) {
            BigDecimal price = getLadderPriceByIndex(product, i);
            if (price != null && price.compareTo(BigDecimal.ZERO) > 0) {
                if (minPrice == null || price.compareTo(minPrice) < 0) {
                    minPrice = price;
                }
            }
        }

        if (minPrice == null) {
            return BigDecimal.ZERO;
        }

        if (discounts == null || discounts.isEmpty()) {
            return minPrice.setScale(2, RoundingMode.UP);
        }

        BigDecimal discount = discounts.get(0).divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP);
        BigDecimal result = minPrice.multiply(discount);

        return result.setScale(2, RoundingMode.UP);
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

    private String buildSkuProps(Product product, List<BigDecimal> discounts, int ladderCount) {
        StringBuilder props = new StringBuilder();
        int totalOptions = ladderCount + 2;

        if (discounts == null) {
            discounts = new ArrayList<>();
        }
        if (discounts.isEmpty()) {
            discounts.add(BigDecimal.valueOf(100));
        }

        for (int i = 0; i < totalOptions; i++) {
            BigDecimal price;
            if (i < ladderCount) {
                price = getLadderPriceByIndex(product, i + 1);
            } else {
                price = getLadderPriceByIndex(product, 1);
            }

            if (price == null) {
                price = BigDecimal.ZERO;
            }

            BigDecimal discount = discounts.get(Math.min(i, discounts.size() - 1))
                    .divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP);
            BigDecimal finalPrice = price.multiply(discount).setScale(5, RoundingMode.HALF_UP);

            props.append(finalPrice.toString())
                    .append(":1000000::")
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
                text = "选数量相符的选项";
            } else if (i == totalOptions - 1) {
                text = "买多少个填多少件";
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
}