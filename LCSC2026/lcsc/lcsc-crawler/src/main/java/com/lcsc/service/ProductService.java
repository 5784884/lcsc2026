package com.lcsc.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.lcsc.entity.CategoryLevel1Code;
import com.lcsc.entity.CategoryLevel2Code;
import com.lcsc.entity.CategoryLevel3Code;
import com.lcsc.entity.Product;
import com.lcsc.mapper.ProductMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class ProductService extends ServiceImpl<ProductMapper, Product> {

    @Autowired
    private CategoryLevel1CodeService categoryLevel1CodeService;

    @Autowired
    private CategoryLevel2CodeService categoryLevel2CodeService;

    @Autowired
    private CategoryLevel3CodeService categoryLevel3CodeService;

    /**
     * 全参数分页查询（完美支持多层级、多选、OR查询）
     */
    public IPage<Product> getProductPage(int current, int
                                                 size, String productCode, String brand,
                                         String model, String packageName,
                                         List<Integer> categoryLevel1Id,
                                         List<Integer> categoryLevel2Id,
                                         List<Integer> categoryLevel3Id,
                                         Boolean hasImage, Integer minStock, Integer maxStock, Boolean matchAny)
    {
        Page<Product> page = new Page<>(current, size);
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
        if (brand != null && !brand.trim().isEmpty()) wrapper.like(Product::getBrand, brand);
        if (model != null && !model.trim().isEmpty()) wrapper.like(Product::getModel, model);
        if (packageName != null && !packageName.trim().isEmpty()) wrapper.like(Product::getPackageName, packageName);

        // ==== 🌟 核心终极修复：跨级别混选查询逻辑 ====
        // 必须把所有的分类条件包裹在一个 AND ( ... OR ... OR ... ) 里
        // 这样才不会和其他条件（比如品牌、有无图）产生串联冲突
        boolean hasL1 = categoryLevel1Id != null && !categoryLevel1Id.isEmpty();
        boolean hasL2 = categoryLevel2Id != null && !categoryLevel2Id.isEmpty();
        boolean hasL3 = categoryLevel3Id != null && !categoryLevel3Id.isEmpty();

        if (hasL1 || hasL2 || hasL3) {
            wrapper.and(w -> {
                if (hasL1) {
                    w.in(Product::getCategoryLevel1Id, categoryLevel1Id);
                }
                if (hasL2) {
                    // 如果前面已经有条件了，用 or() 拼接；如果前面是空的，直接拼接。
                    if (hasL1) { w.or(); }
                    w.in(Product::getCategoryLevel2Id, categoryLevel2Id);
                }
                if (hasL3) {
                    // 同理，前面有任何条件，就加上 or()
                    if (hasL1 || hasL2) { w.or(); }
                    w.in(Product::getCategoryLevel3Id, categoryLevel3Id);
                }
            });
        }
        // ==================================================

        // ==== 🌟 智能路由：任意满足(OR) 与 叠加满足(AND) ====
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
        // ==================================================

        wrapper.orderByDesc(Product::getLastCrawledAt);
        IPage<Product> result = page(page, wrapper);
        enrichCategoryNames(result.getRecords());
        return result;
    }

    /**
     * 简单分页查询（兼容旧调用）
     */
    public IPage<Product> getProductPage(int current, int size, String productCode, String brand) {
        return getProductPage(current, size, productCode, brand, null, null, null, null, null, null, null, null, null);
    }

    /**
     * 恢复 saveOrUpdateProduct 方法
     */
    public boolean saveOrUpdateProduct(Product product) {
        if (product == null || product.getProductCode() == null) return false;
        Product existing = getByProductCode(product.getProductCode());
        if (existing != null) {
            product.setId(existing.getId());
        }
        return saveOrUpdate(product);
    }

    public Product getByProductCode(String productCode) {
        LambdaQueryWrapper<Product> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Product::getProductCode, productCode);
        Product product = getOne(wrapper);
        if (product != null) enrichCategoryNames(Collections.singletonList(product));
        return product;
    }

    /**
     * 恢复 getProductListByCategory 方法
     */
    public List<Product> getProductListByCategory(Integer l1Id, Integer l2Id) {
        LambdaQueryWrapper<Product> wrapper = new LambdaQueryWrapper<>();
        if (l1Id != null) wrapper.eq(Product::getCategoryLevel1Id, l1Id);
        if (l2Id != null) wrapper.eq(Product::getCategoryLevel2Id, l2Id);
        List<Product> list = list(wrapper);
        enrichCategoryNames(list);
        return list;
    }

    /**
     * 恢复 getProductStatistics 方法
     */
    public Map<String, Object> getProductStatistics() {
        Map<String, Object> stats = new HashMap<>();
        long total = count();
        long hasStock = count(new LambdaQueryWrapper<Product>().gt(Product::getTotalStockQuantity, 0));
        stats.put("totalProducts", total);
        stats.put("productsWithStock", hasStock);
        stats.put("productsWithoutStock", total - hasStock);
        return stats;
    }

    /**
     * 分类名称填充核心逻辑
     */
    private void enrichCategoryNames(List<Product> products) {
        if (products == null || products.isEmpty()) return;

        Set<Integer> l1Ids = products.stream().map(Product::getCategoryLevel1Id).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Integer> l2Ids = products.stream().map(Product::getCategoryLevel2Id).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Integer> l3Ids = products.stream().map(Product::getCategoryLevel3Id).filter(Objects::nonNull).collect(Collectors.toSet());

        Map<Integer, String> l1Map = l1Ids.isEmpty() ? Collections.emptyMap() :
                categoryLevel1CodeService.listByIds(l1Ids).stream().collect(Collectors.toMap(CategoryLevel1Code::getId, CategoryLevel1Code::getCategoryLevel1Name, (a, b) -> a));
        Map<Integer, String> l2Map = l2Ids.isEmpty() ? Collections.emptyMap() :
                categoryLevel2CodeService.listByIds(l2Ids).stream().collect(Collectors.toMap(CategoryLevel2Code::getId, CategoryLevel2Code::getCategoryLevel2Name, (a, b) -> a));
        Map<Integer, String> l3Map = l3Ids.isEmpty() ? Collections.emptyMap() :
                categoryLevel3CodeService.listByIds(l3Ids).stream().collect(Collectors.toMap(CategoryLevel3Code::getId, CategoryLevel3Code::getCategoryLevel3Name, (a, b) -> a));

        for (Product p : products) {
            if (p.getCategoryLevel1Id() != null) p.setCategoryLevel1Name(l1Map.get(p.getCategoryLevel1Id()));
            if (p.getCategoryLevel2Id() != null) p.setCategoryLevel2Name(l2Map.get(p.getCategoryLevel2Id()));
            if (p.getCategoryLevel3Id() != null) p.setCategoryLevel3Name(l3Map.get(p.getCategoryLevel3Id()));
        }
    }

    /**
     * 获取所有品牌列表
     */
    public List<String> getAllBrands() {
        LambdaQueryWrapper<Product> wrapper = new LambdaQueryWrapper<>();
        wrapper.select(Product::getBrand)
                .isNotNull(Product::getBrand)
                .ne(Product::getBrand, "")
                .groupBy(Product::getBrand);

        List<Product> list = list(wrapper);

        return list.stream()
                .map(Product::getBrand)
                .sorted()
                .collect(Collectors.toList());
    }
}