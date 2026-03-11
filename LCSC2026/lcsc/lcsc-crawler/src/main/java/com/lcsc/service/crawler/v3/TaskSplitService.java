package com.lcsc.service.crawler.v3;

import com.lcsc.dto.BrandSplitUnit;
import com.lcsc.dto.SplitUnit;
import com.lcsc.service.crawler.LcscApiService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * 任务拆分服务.
 * 负责检测是否需要拆分任务，以及执行品牌拆分策略.
 *
 * @author Claude Code
 * @since 2025-11-21
 */
@Service
public class TaskSplitService {

    private static final Logger log = LoggerFactory.getLogger(TaskSplitService.class);

    /**
     * 立创API的硬性上限（API最多返回5000条记录）.
     */
    private static final int LCSC_API_HARD_LIMIT = 5000;

    /**
     * 触发拆分的产品数量阈值（保守策略，留500条buffer）.
     */
    private static final int SPLIT_THRESHOLD = 4500;

    /**
     * 最大拆分子任务数量限制.
     * 注意：如果品牌/封装数量超过此限制，多余的部分会被忽略！
     * 设置为500以支持大分类（如 Circular Cable Assemblies 有100+品牌）
     */
    private static final int MAX_SPLIT_TASKS = 500;

    @Autowired
    private LcscApiService lcscApiService;

    /**
     * 检测是否需要拆分任务.
     *
     * @param totalProducts 产品总数
     * @return true 如果需要拆分，false 否则
     */
    public boolean needSplit(int totalProducts) {
        // 关键修复：当totalProducts正好等于5000时，强制拆分！
        // 因为这是API的硬限制，表示实际产品数 >= 5000，还有更多数据未被发现
        if (totalProducts == LCSC_API_HARD_LIMIT) {
            log.warn("⚠️ 产品总数 {} 触及API硬限制，实际产品数可能更多，强制触发拆分！", totalProducts);
            return true;
        }

        // 常规检测：超过阈值就拆分
        boolean need = totalProducts > SPLIT_THRESHOLD;
        if (need) {
            log.info("产品总数 {} 超过拆分阈值 {}，需要拆分", totalProducts, SPLIT_THRESHOLD);
        } else {
            log.debug("产品总数 {} 未超过拆分阈值 {}，无需拆分", totalProducts, SPLIT_THRESHOLD);
        }
        return need;
    }

    /**
     * 执行品牌拆分策略.
     * 调用立创API获取该分类下的所有品牌，并为每个品牌创建拆分单元.
     *
     * @param catalogId 分类的catalog ID
     * @param categoryName 分类名称（用于日志）
     * @return 品牌拆分单元列表
     */
    public List<BrandSplitUnit> splitByBrand(String catalogId, String categoryName) {
        log.info("开始执行品牌拆分策略: catalogId={}, categoryName={}", catalogId, categoryName);

        try {
            // 1. 调用API获取筛选参数组
            Map<String, Object> filterParams = new HashMap<>();
            filterParams.put("catalogIdList", List.of(catalogId));

            CompletableFuture<Map<String, Object>> future = lcscApiService.getQueryParamGroup(filterParams);
            Map<String, Object> paramGroups = future.join();

            log.info("获取到筛选参数组: {}", paramGroups.keySet());

            // 2. 提取品牌列表
            List<BrandSplitUnit> brandUnits = extractBrandList(paramGroups, catalogId);

            if (brandUnits.isEmpty()) {
                log.warn("未找到任何品牌，无法拆分: catalogId={}", catalogId);
                return Collections.emptyList();
            }

            // 3. 限制拆分数量
            if (brandUnits.size() > MAX_SPLIT_TASKS) {
                log.warn("品牌数量 {} 超过最大限制 {}，仅取前 {} 个品牌",
                        brandUnits.size(), MAX_SPLIT_TASKS, MAX_SPLIT_TASKS);
                brandUnits = brandUnits.subList(0, MAX_SPLIT_TASKS);
            }

            // 4. 按产品数量降序排序（产品多的品牌优先爬取）
            brandUnits.sort(Comparator.comparingInt(BrandSplitUnit::getProductCount).reversed());

            log.info("品牌拆分完成: 共 {} 个品牌", brandUnits.size());
            logBrandSplitSummary(brandUnits);

            return brandUnits;

        } catch (Exception e) {
            log.error("品牌拆分失败: catalogId={}, error={}", catalogId, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * 从参数组中提取品牌列表.
     *
     * @param paramGroups API返回的参数组
     * @param catalogId 分类ID
     * @return 品牌拆分单元列表
     */
    @SuppressWarnings("unchecked")
    private List<BrandSplitUnit> extractBrandList(Map<String, Object> paramGroups, String catalogId) {
        List<BrandSplitUnit> brandUnits = new ArrayList<>();

        // 尝试多种可能的品牌字段名称（立创API返回的是Manufacturer）
        String[] possibleBrandKeys = {"Manufacturer", "manufacturer", "Brand", "brand", "brandList", "brands"};

        for (String key : possibleBrandKeys) {
            if (paramGroups.containsKey(key)) {
                Object brandData = paramGroups.get(key);

                if (brandData instanceof List) {
                    List<Map<String, Object>> brandList = (List<Map<String, Object>>) brandData;
                    log.info("找到品牌列表字段: {}, 共 {} 个品牌", key, brandList.size());

                    for (Map<String, Object> brand : brandList) {
                        try {
                            // 提取品牌ID和名称（字段名可能有多种形式）
                            String brandId = extractStringValue(brand, "brandId", "id", "catalogId");
                            String brandName = extractStringValue(brand, "brandName", "name", "catalogName");
                            int productCount = extractIntValue(brand, "productNum", "count", "num");

                            if (brandId != null && !brandId.isEmpty()) {
                                BrandSplitUnit unit = new BrandSplitUnit(
                                        brandId,
                                        brandName != null ? brandName : "未知品牌",
                                        productCount,
                                        catalogId
                                );
                                brandUnits.add(unit);
                            }
                        } catch (Exception e) {
                            log.warn("解析品牌数据失败: {}, error={}", brand, e.getMessage());
                        }
                    }

                    break; // 找到品牌列表后退出循环
                }
            }
        }

        return brandUnits;
    }

    /**
     * 从Map中提取字符串值（支持多个可能的键名）.
     */
    private String extractStringValue(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            if (map.containsKey(key)) {
                Object value = map.get(key);
                if (value != null) {
                    return value.toString();
                }
            }
        }
        return null;
    }

    /**
     * 从Map中提取整数值（支持多个可能的键名）.
     */
    private int extractIntValue(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            if (map.containsKey(key)) {
                Object value = map.get(key);
                if (value instanceof Number) {
                    return ((Number) value).intValue();
                }
                if (value instanceof String) {
                    try {
                        return Integer.parseInt((String) value);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        return 0;
    }

    /**
     * 智能拆分策略.
     * 根据当前拆分深度选择不同的拆分维度：
     * - Level 0: 按品牌(Brand/Manufacturer)拆分
     * - Level 1: 按封装(Package/Encap)拆分
     * - Level 2+: 按其他参数拆分
     *
     * @param catalogId 分类的catalog ID
     * @param categoryName 分类名称（用于日志）
     * @param currentSplitLevel 当前拆分深度
     * @param accumulatedFilters 已累积的筛选参数
     * @return 拆分单元列表
     */
    public List<SplitUnit> smartSplit(String catalogId, String categoryName,
                                      int currentSplitLevel,
                                      Map<String, Object> accumulatedFilters) {
        log.info("开始智能拆分: catalogId={}, categoryName={}, splitLevel={}, accumulatedFilters={}",
                catalogId, categoryName, currentSplitLevel, accumulatedFilters.keySet());

        try {
            // 1. 构建API请求参数
            // ⚠️ 关键修复：传递累积的筛选参数，让API返回"已筛选条件下"的可用拆分维度
            // 例如：传递brandIdList后，API返回该品牌下的封装列表，而不是整个分类的封装列表
            Map<String, Object> filterParams = new HashMap<>(accumulatedFilters);
            filterParams.put("catalogIdList", List.of(catalogId));

            log.info("智能拆分: 带累积筛选参数调用API (splitLevel={}, filters={})",
                    currentSplitLevel, accumulatedFilters.keySet());

            // 2. 调用API获取筛选参数组
            CompletableFuture<Map<String, Object>> future = lcscApiService.getQueryParamGroup(filterParams);
            Map<String, Object> paramGroups = future.join();

            log.info("获取到筛选参数组: {}", paramGroups.keySet());

            // ⚠️ 调试日志：输出每个参数组的数据量和前3条数据
            for (Map.Entry<String, Object> entry : paramGroups.entrySet()) {
                if (entry.getValue() instanceof List) {
                    List<?> list = (List<?>) entry.getValue();
                    log.info("  - 参数组 [{}]: {} 个选项", entry.getKey(), list.size());
                    if (!list.isEmpty() && list.size() <= 5) {
                        // 如果选项数量<=5，打印全部
                        log.info("    完整数据: {}", list);
                    } else if (!list.isEmpty()) {
                        // 否则打印前3条
                        log.info("    前3条示例: {}", list.stream().limit(3).toList());
                    }
                }
            }

            // 3. 根据当前拆分深度选择拆分维度
            List<SplitUnit> splitUnits = switch (currentSplitLevel) {
                case 0 -> extractBrandSplitUnits(paramGroups, catalogId);
                case 1 -> extractPackageSplitUnits(paramGroups, catalogId);
                default -> extractParameterSplitUnits(paramGroups, catalogId, currentSplitLevel);
            };

            // ⚠️ 智能回退策略：如果当前维度拆分失败，尝试下一个维度
            if (splitUnits.isEmpty() && currentSplitLevel == 1) {
                log.warn("⚠️ 封装(Package)拆分失败（返回空列表），尝试回退到参数(Parameter)拆分");
                splitUnits = extractParameterSplitUnits(paramGroups, catalogId, currentSplitLevel);
                if (!splitUnits.isEmpty()) {
                    log.info("✅ 参数拆分成功，找到 {} 个拆分单元", splitUnits.size());
                } else {
                    // 最终兜底方案：布尔筛选组合拆分（isStock, isOtherSuppliers, isAsianBrand, isDeals, isEnvironment）
                    // 注意：跳过价格区间拆分，因为立创API不支持priceFrom/priceTo参数
                    log.warn("⚠️ 参数拆分也失败，尝试最终兜底方案：布尔筛选组合拆分");
                    splitUnits = extractBooleanFilterSplitUnits(catalogId);
                    if (!splitUnits.isEmpty()) {
                        log.info("✅ 布尔筛选组合拆分成功，找到 {} 个拆分单元", splitUnits.size());
                    }
                }
            }

            if (splitUnits.isEmpty()) {
                log.warn("未找到可用的拆分维度: catalogId={}, splitLevel={}", catalogId, currentSplitLevel);
                return Collections.emptyList();
            }

            // 4. 限制拆分数量并记录被忽略的数据
            if (splitUnits.size() > MAX_SPLIT_TASKS) {
                // 先排序，确保取前N个是产品数量最多的
                splitUnits.sort(Comparator.comparingInt(SplitUnit::getProductCount).reversed());

                // 计算被忽略的产品数量
                int totalProducts = splitUnits.stream().mapToInt(SplitUnit::getProductCount).sum();
                int keptProducts = splitUnits.subList(0, MAX_SPLIT_TASKS).stream()
                        .mapToInt(SplitUnit::getProductCount).sum();
                int ignoredProducts = totalProducts - keptProducts;
                int ignoredUnits = splitUnits.size() - MAX_SPLIT_TASKS;

                log.error("========== 警告：拆分数量超限，部分数据将被忽略！ ==========");
                log.error("原始拆分数量: {}, 限制: {}, 被忽略: {} 个拆分单元",
                        splitUnits.size(), MAX_SPLIT_TASKS, ignoredUnits);
                log.error("被忽略的产品数量: {} (占总数 {}%)",
                        ignoredProducts, Math.round(ignoredProducts * 100.0 / totalProducts));
                log.error("如需爬取全部数据，请增加 MAX_SPLIT_TASKS 配置");
                log.error("================================================================");

                splitUnits = new ArrayList<>(splitUnits.subList(0, MAX_SPLIT_TASKS));
            } else {
                // 5. 按产品数量降序排序
                splitUnits.sort(Comparator.comparingInt(SplitUnit::getProductCount).reversed());
            }

            log.info("智能拆分完成: 维度={}, 共 {} 个拆分单元",
                    splitUnits.isEmpty() ? "无" : splitUnits.get(0).getDimensionName(),
                    splitUnits.size());
            logSplitSummary(splitUnits);

            return splitUnits;

        } catch (Exception e) {
            log.error("智能拆分失败: catalogId={}, splitLevel={}, error={}",
                    catalogId, currentSplitLevel, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * 提取品牌拆分单元（Level 0）.
     */
    @SuppressWarnings("unchecked")
    private List<SplitUnit> extractBrandSplitUnits(Map<String, Object> paramGroups, String catalogId) {
        List<SplitUnit> splitUnits = new ArrayList<>();

        // 立创API返回的品牌字段名
        String[] possibleBrandKeys = {"Manufacturer", "manufacturer", "Brand", "brand", "brandList", "brands"};

        for (String key : possibleBrandKeys) {
            if (paramGroups.containsKey(key)) {
                Object brandData = paramGroups.get(key);

                if (brandData instanceof List) {
                    List<Map<String, Object>> brandList = (List<Map<String, Object>>) brandData;
                    log.info("找到品牌列表字段: {}, 共 {} 个品牌", key, brandList.size());

                    for (Map<String, Object> brand : brandList) {
                        try {
                            String brandId = extractStringValue(brand, "brandId", "id", "catalogId");
                            String brandName = extractStringValue(brand, "brandName", "name", "catalogName");
                            int productCount = extractIntValue(brand, "productNum", "count", "num");

                            if (brandId != null && !brandId.isEmpty()) {
                                SplitUnit unit = new SplitUnit(
                                        "Brand",
                                        brandId,
                                        brandName != null ? brandName : "未知品牌",
                                        productCount,
                                        catalogId
                                );
                                // 设置API筛选参数
                                unit.addFilterParam("brandIdList", List.of(brandId));
                                splitUnits.add(unit);
                            }
                        } catch (Exception e) {
                            log.warn("解析品牌数据失败: {}, error={}", brand, e.getMessage());
                        }
                    }
                    break;
                }
            }
        }

        return splitUnits;
    }

    /**
     * 提取封装拆分单元（Level 1）.
     */
    @SuppressWarnings("unchecked")
    private List<SplitUnit> extractPackageSplitUnits(Map<String, Object> paramGroups, String catalogId) {
        List<SplitUnit> splitUnits = new ArrayList<>();

        // 立创API返回的封装字段名
        String[] possiblePackageKeys = {"Package", "package", "Encap", "encap", "encapStandard", "packageList"};

        for (String key : possiblePackageKeys) {
            if (paramGroups.containsKey(key)) {
                Object packageData = paramGroups.get(key);

                if (packageData instanceof List) {
                    List<Map<String, Object>> packageList = (List<Map<String, Object>>) packageData;
                    log.info("找到封装列表字段: {}, 共 {} 个封装", key, packageList.size());

                    // ⚠️ 调试日志：输出封装列表的详细内容
                    if (!packageList.isEmpty()) {
                        log.info("封装列表前3个示例数据: {}",
                                packageList.stream().limit(3).toList());
                    }

                    for (Map<String, Object> pkg : packageList) {
                        try {
                            // 封装通常使用值本身作为ID
                            String encapValue = extractStringValue(pkg, "paramValue", "value", "encapValue", "name");
                            int productCount = extractIntValue(pkg, "productNum", "count", "num");

                            log.debug("解析封装数据: encapValue={}, productCount={}, 原始数据={}",
                                    encapValue, productCount, pkg);

                            if (encapValue != null && !encapValue.isEmpty() && !"-".equals(encapValue)) {
                                SplitUnit unit = new SplitUnit(
                                        "Package",
                                        encapValue,  // 封装值作为ID
                                        encapValue,
                                        productCount,
                                        catalogId
                                );
                                // 设置API筛选参数
                                unit.addFilterParam("encapValueList", List.of(encapValue));
                                splitUnits.add(unit);
                            } else {
                                log.warn("跳过无效封装: encapValue={} (原始数据: {})", encapValue, pkg);
                            }
                        } catch (Exception e) {
                            log.warn("解析封装数据失败: {}, error={}", pkg, e.getMessage());
                        }
                    }
                    break;
                }
            }
        }

        return splitUnits;
    }

    /**
     * 提取其他参数拆分单元（Level 2+）.
     * 会尝试找到可用的参数维度进行拆分.
     */
    @SuppressWarnings("unchecked")
    private List<SplitUnit> extractParameterSplitUnits(Map<String, Object> paramGroups,
                                                       String catalogId, int splitLevel) {
        List<SplitUnit> splitUnits = new ArrayList<>();

        // 排除已使用的维度
        Set<String> excludeKeys = Set.of(
                "Manufacturer", "manufacturer", "Brand", "brand", "brandList", "brands",
                "Package", "package", "Encap", "encap", "encapStandard", "packageList",
                "Packaging"  // 也排除Packaging
        );

        // 优先级顺序：先尝试数值型参数（更容易拆分），再尝试文本型参数
        List<String> preferredKeys = List.of(
                "Voltage", "voltage", "Current", "current", "Resistance", "resistance",
                "Capacitance", "capacitance", "Frequency", "frequency", "Power", "power",
                "Temperature", "temperature", "Length", "length", "Width", "width"
        );

        // 先尝试优先级参数
        for (String preferredKey : preferredKeys) {
            if (paramGroups.containsKey(preferredKey)) {
                Object paramData = paramGroups.get(preferredKey);
                if (paramData instanceof List) {
                    List<Map<String, Object>> paramList = (List<Map<String, Object>>) paramData;
                    if (!paramList.isEmpty()) {
                        log.info("⭐ 使用优先参数维度: {}, 共 {} 个选项", preferredKey, paramList.size());
                        return buildSplitUnitsFromParamList(preferredKey, paramList, catalogId);
                    }
                }
            }
        }

        // 如果优先参数都没有，遍历所有参数组
        log.info("优先参数不可用，遍历所有参数组寻找可用维度...");
        for (Map.Entry<String, Object> entry : paramGroups.entrySet()) {
            String paramKey = entry.getKey();

            // 跳过已使用的维度
            if (excludeKeys.contains(paramKey)) {
                log.debug("跳过已使用维度: {}", paramKey);
                continue;
            }

            Object paramData = entry.getValue();
            if (!(paramData instanceof List)) {
                continue;
            }

            List<Map<String, Object>> paramList = (List<Map<String, Object>>) paramData;
            if (paramList.isEmpty()) {
                continue;
            }

            log.info("🔍 尝试使用参数维度: {}, 共 {} 个选项", paramKey, paramList.size());

            // 输出前3条数据用于调试
            if (paramList.size() <= 3) {
                log.info("  完整数据: {}", paramList);
            } else {
                log.info("  前3条示例: {}", paramList.stream().limit(3).toList());
            }

            List<SplitUnit> units = buildSplitUnitsFromParamList(paramKey, paramList, catalogId);
            if (!units.isEmpty()) {
                log.info("✅ 成功使用参数维度 [{}] 进行拆分，找到 {} 个有效拆分单元", paramKey, units.size());
                return units;
            } else {
                log.warn("❌ 参数维度 [{}] 无有效拆分单元（可能全是'-'或null）", paramKey);
            }
        }

        log.warn("⚠️ 未找到任何可用的参数拆分维度");
        return splitUnits;
    }

    /**
     * 从参数列表构建拆分单元
     */
    private List<SplitUnit> buildSplitUnitsFromParamList(String paramKey,
                                                         List<Map<String, Object>> paramList,
                                                         String catalogId) {
        List<SplitUnit> splitUnits = new ArrayList<>();

        for (Map<String, Object> param : paramList) {
            try {
                String paramValue = extractStringValue(param, "paramValue", "value", "name");
                int productCount = extractIntValue(param, "productNum", "count", "num");

                log.debug("解析参数 [{}]: paramValue={}, productCount={}", paramKey, paramValue, productCount);

                if (paramValue != null && !paramValue.isEmpty() && !"-".equals(paramValue) && productCount > 0) {
                    SplitUnit unit = new SplitUnit(
                            paramKey,  // 使用参数名作为维度名
                            paramValue,
                            paramValue,
                            productCount,
                            catalogId
                    );
                    // 设置API筛选参数（使用paramNameValueMap格式）
                    Map<String, List<String>> paramNameValueMap = new HashMap<>();
                    paramNameValueMap.put(paramKey, List.of(paramValue));
                    unit.addFilterParam("paramNameValueMap", paramNameValueMap);
                    splitUnits.add(unit);
                } else {
                    log.debug("跳过无效参数值: paramKey={}, paramValue={}, productCount={}",
                            paramKey, paramValue, productCount);
                }
            } catch (Exception e) {
                log.warn("解析参数数据失败: paramKey={}, param={}, error={}",
                        paramKey, param, e.getMessage());
            }
        }

        return splitUnits;
    }

    /**
     * 提取价格区间拆分单元（最后的兜底方案）.
     * 将产品按价格区间拆分，突破5000条API限制。
     *
     * 价格区间划分策略：
     * - 0-1元
     * - 1-5元
     * - 5-10元
     * - 10-50元
     * - 50-100元
     * - 100-500元
     * - 500元以上
     *
     * @param catalogId 分类ID
     * @return 价格区间拆分单元列表
     */
    private List<SplitUnit> extractPriceSplitUnits(String catalogId) {
        List<SplitUnit> splitUnits = new ArrayList<>();

        log.info("💰 开始价格区间拆分（最后的兜底方案）");

        // 定义价格区间 (单位：元，使用CNY)
        // 格式: [起始价格, 结束价格, 显示名称]
        // ⚠️ 重点细分高价区间，因为电子元件多集中在高价段
        Object[][] priceRanges = {
                {0.0, 1.0, "0-1元"},
                {1.0, 5.0, "1-5元"},
                {5.0, 10.0, "5-10元"},
                {10.0, 50.0, "10-50元"},
                {50.0, 100.0, "50-100元"},
                {100.0, 200.0, "100-200元"},
                {200.0, 500.0, "200-500元"},
                {500.0, 1000.0, "500-1000元"},
                {1000.0, 2000.0, "1000-2000元"},
                {2000.0, 5000.0, "2000-5000元"},
                {5000.0, null, "5000元以上"}  // null表示无上限
        };

        for (Object[] range : priceRanges) {
            Double priceFrom = (Double) range[0];
            Double priceTo = (Double) range[1];
            String displayName = (String) range[2];

            SplitUnit unit = new SplitUnit(
                    "PriceRange",  // 维度名称
                    displayName,   // 使用显示名称作为ID
                    displayName,   // 显示名称
                    0,             // 产品数量未知（API不返回）
                    catalogId
            );

            // 设置API筛选参数
            unit.addFilterParam("priceFrom", priceFrom);
            if (priceTo != null) {
                unit.addFilterParam("priceTo", priceTo);
            }

            splitUnits.add(unit);

            log.info("  创建价格区间: {} (priceFrom={}, priceTo={})",
                    displayName, priceFrom, priceTo != null ? priceTo : "无上限");
        }

        log.info("💰 价格区间拆分完成: 共 {} 个区间", splitUnits.size());

        return splitUnits;
    }

    /**
     * 提取布尔筛选组合拆分单元（最终兜底方案）.
     * 当品牌、封装、参数、价格区间都不可用时，使用布尔筛选项组合进行拆分。
     *
     * 可用的布尔筛选项（立创API支持）：
     * - isStock: 是否有库存
     * - isOtherSuppliers: 是否其他供应商
     * - isAsianBrand: 是否亚洲品牌
     * - isDeals: 是否促销
     * - isEnvironment: 是否环保（ROHS认证）
     *
     * 策略：不是生成全部32种组合（2^5），而是生成以下几种有意义的组合：
     * 1. isStock=true（有库存）
     * 2. isStock=false（无库存）
     * 3. isOtherSuppliers=true（其他供应商）
     * 4. isAsianBrand=true（亚洲品牌）
     * 5. isDeals=true（促销）
     * 6. isEnvironment=true（ROHS）
     * 7. 默认（全部false）
     *
     * @param catalogId 分类ID
     * @return 布尔筛选组合拆分单元列表
     */
    private List<SplitUnit> extractBooleanFilterSplitUnits(String catalogId) {
        List<SplitUnit> splitUnits = new ArrayList<>();

        log.info("🔘 开始布尔筛选组合拆分（最终兜底方案）");

        // 定义有意义的布尔筛选组合
        // 格式: [过滤器名称, 显示名称, 参数Map]
        List<Object[]> filterCombinations = List.of(
                new Object[]{"isStock_true", "有库存", Map.of("isStock", true)},
                new Object[]{"isStock_false", "无库存", Map.of("isStock", false)},
                new Object[]{"isOtherSuppliers_true", "其他供应商", Map.of("isOtherSuppliers", true)},
                new Object[]{"isAsianBrand_true", "亚洲品牌", Map.of("isAsianBrand", true)},
                new Object[]{"isDeals_true", "促销产品", Map.of("isDeals", true)},
                new Object[]{"isEnvironment_true", "ROHS认证", Map.of("isEnvironment", true)},
                new Object[]{"default", "默认筛选", Map.of()}  // 全部false
        );

        for (Object[] combo : filterCombinations) {
            String filterId = (String) combo[0];
            String displayName = (String) combo[1];
            @SuppressWarnings("unchecked")
            Map<String, Object> filters = (Map<String, Object>) combo[2];

            SplitUnit unit = new SplitUnit(
                    "BooleanFilter",  // 维度名称
                    filterId,         // 过滤器ID
                    displayName,      // 显示名称
                    0,                // 产品数量未知（API不返回）
                    catalogId
            );

            // 设置API筛选参数
            filters.forEach(unit::addFilterParam);

            splitUnits.add(unit);

            log.info("  创建布尔筛选组合: {} (filters={})", displayName, filters);
        }

        log.info("🔘 布尔筛选组合拆分完成: 共 {} 种组合", splitUnits.size());

        return splitUnits;
    }

    /**
     * 记录拆分汇总信息.
     */
    private void logSplitSummary(List<SplitUnit> splitUnits) {
        if (splitUnits.isEmpty()) {
            return;
        }

        int totalProducts = splitUnits.stream()
                .mapToInt(SplitUnit::getProductCount)
                .sum();

        log.info("=== 拆分汇总 ===");
        log.info("维度: {}", splitUnits.get(0).getDimensionName());
        log.info("总拆分数: {}", splitUnits.size());
        log.info("总产品数: {}", totalProducts);
        log.info("拆分详情:");

        int displayCount = Math.min(10, splitUnits.size());
        for (int i = 0; i < displayCount; i++) {
            SplitUnit unit = splitUnits.get(i);
            log.info("  {}. {} (ID: {}) - {} 个产品",
                    i + 1, unit.getFilterValue(), unit.getFilterId(), unit.getProductCount());
        }

        if (splitUnits.size() > displayCount) {
            log.info("  ... 还有 {} 个拆分单元", splitUnits.size() - displayCount);
        }

        log.info("================");
    }

    /**
     * 记录品牌拆分汇总信息.
     */
    private void logBrandSplitSummary(List<BrandSplitUnit> brandUnits) {
        int totalProducts = brandUnits.stream()
                .mapToInt(BrandSplitUnit::getProductCount)
                .sum();

        log.info("=== 品牌拆分汇总 ===");
        log.info("总品牌数: {}", brandUnits.size());
        log.info("总产品数: {}", totalProducts);
        log.info("品牌详情:");

        // 只显示前10个品牌的详情
        int displayCount = Math.min(10, brandUnits.size());
        for (int i = 0; i < displayCount; i++) {
            BrandSplitUnit unit = brandUnits.get(i);
            log.info("  {}. {} (ID: {}) - {} 个产品",
                    i + 1, unit.getBrandName(), unit.getBrandId(), unit.getProductCount());
        }

        if (brandUnits.size() > displayCount) {
            log.info("  ... 还有 {} 个品牌", brandUnits.size() - displayCount);
        }

        log.info("=====================");
    }
}
