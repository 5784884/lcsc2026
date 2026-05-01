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
     * 品牌专属折扣方案1：指定品牌列表（原始名称或自定义名称，换行分隔）
     */
    private String brandDiscounts1Brands;

    /**
     * 品牌专属折扣方案1：6级折扣
     */
    private List<BigDecimal> brandDiscounts1;

    /**
     * 品牌专属折扣方案2：指定品牌列表
     */
    private String brandDiscounts2Brands;

    /**
     * 品牌专属折扣方案2：6级折扣
     */
    private List<BigDecimal> brandDiscounts2;

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

    public String getBrandDiscounts1Brands() { return brandDiscounts1Brands; }
    public void setBrandDiscounts1Brands(String brandDiscounts1Brands) { this.brandDiscounts1Brands = brandDiscounts1Brands; }

    public List<BigDecimal> getBrandDiscounts1() { return brandDiscounts1; }
    public void setBrandDiscounts1(List<BigDecimal> brandDiscounts1) { this.brandDiscounts1 = brandDiscounts1; }

    public String getBrandDiscounts2Brands() { return brandDiscounts2Brands; }
    public void setBrandDiscounts2Brands(String brandDiscounts2Brands) { this.brandDiscounts2Brands = brandDiscounts2Brands; }

    public List<BigDecimal> getBrandDiscounts2() { return brandDiscounts2; }
    public void setBrandDiscounts2(List<BigDecimal> brandDiscounts2) { this.brandDiscounts2 = brandDiscounts2; }

    public Long getAddedAt() {
        return addedAt;
    }

    public void setAddedAt(Long addedAt) {
        this.addedAt = addedAt;
    }
}