package com.lcsc.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 高级导出请求DTO - 淘宝CSV格式
 * 用于批量添加模式的筛选条件
 */
public class AdvancedExportRequest {

    /**
     * 选择的店铺ID（单选，必填）
     * 用于关联运费模板、店铺分类码
     */
    private Integer shopId;

    // ================== 新增：接收前端传来的分类ID数组 ==================
    /**
     * 选择的分类ID数组（支持多选，包含L1/L2/L3混合）
     */
    private List<Integer> categoryIds;
    // ==============================================================

    /**
     * 品牌名称（可选，多选）
     */
    private List<String> brands;

    /**
     * 是否有图片（可选）
     * - true: 只选有图片的产品
     * - false: 只选无图片的产品
     * - null: 不限
     */
    private Boolean hasImage;

    /**
     * 库存最小值（可选）
     */
    private Integer stockMin;

    /**
     * 库存最大值（可选）
     */
    private Integer stockMax;

    /**
     * 6级价格折扣配置（百分比，必填）
     * 例如：[90, 88, 85, 82, 80, 78] 表示一级打9折，二级打88折...
     * 数组长度必须为6
     */
    private List<BigDecimal> discounts;

    // --- Getters and Setters ---
    public Integer getShopId() {
        return shopId;
    }

    public void setShopId(Integer shopId) {
        this.shopId = shopId;
    }

    // ================== 新增的 Getter 和 Setter ==================
    public List<Integer> getCategoryIds() {
        return categoryIds;
    }

    public void setCategoryIds(List<Integer> categoryIds) {
        this.categoryIds = categoryIds;
    }
    // ==========================================================

    public List<String> getBrands() {
        return brands;
    }

    public void setBrands(List<String> brands) {
        this.brands = brands;
    }

    public Boolean getHasImage() {
        return hasImage;
    }

    public void setHasImage(Boolean hasImage) {
        this.hasImage = hasImage;
    }

    public Integer getStockMin() {
        return stockMin;
    }

    public void setStockMin(Integer stockMin) {
        this.stockMin = stockMin;
    }

    public Integer getStockMax() {
        return stockMax;
    }

    public void setStockMax(Integer stockMax) {
        this.stockMax = stockMax;
    }

    public List<BigDecimal> getDiscounts() {
        return discounts;
    }

    public void setDiscounts(List<BigDecimal> discounts) {
        this.discounts = discounts;
    }
}