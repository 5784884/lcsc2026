package com.lcsc.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 高级导出任务项
 * 用于批量添加模式：用户可多次添加产品到任务列表，最后统一导出
 */
public class ExportTaskItem {
    /**
     * 产品编号（唯一标识，用于去重）
     */
    private String productCode;

    /**
     * 产品型号
     */
    private String model;

    /**
     * 品牌名称
     */
    private String brand;

    /**
     * 关联的店铺ID
     */
    private Integer shopId;

    /**
     * 店铺名称（用于前端显示）
     */
    private String shopName;

    /**
     * 6级价格折扣配置（百分比）
     * 例如：[90, 88, 85, 82, 80, 78] 表示一级价格打9折，二级打88折...
     * 添加时保存，导出时应用
     */
    private List<BigDecimal> discounts;

    /**
     * 添加时间戳（用于排序）
     */
    private Long addedAt;

    // --- Getters and Setters ---
    public String getProductCode() {
        return productCode;
    }

    public void setProductCode(String productCode) {
        this.productCode = productCode;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getBrand() {
        return brand;
    }

    public void setBrand(String brand) {
        this.brand = brand;
    }

    public Integer getShopId() {
        return shopId;
    }

    public void setShopId(Integer shopId) {
        this.shopId = shopId;
    }

    public String getShopName() {
        return shopName;
    }

    public void setShopName(String shopName) {
        this.shopName = shopName;
    }

    public List<BigDecimal> getDiscounts() {
        return discounts;
    }

    public void setDiscounts(List<BigDecimal> discounts) {
        this.discounts = discounts;
    }

    public Long getAddedAt() {
        return addedAt;
    }

    public void setAddedAt(Long addedAt) {
        this.addedAt = addedAt;
    }
}