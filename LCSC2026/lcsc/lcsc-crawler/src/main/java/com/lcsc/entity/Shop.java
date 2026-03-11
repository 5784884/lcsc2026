package com.lcsc.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * 店铺及运费模板实体类
 * (已移除 noImageUrl 字段以适配现有数据库)
 */
@TableName("shops")
public class Shop {

    @TableId(type = IdType.AUTO)
    private Integer id;

    /**
     * 店铺名称
     */
    private String shopName;

    /**
     * 运费模板ID码
     */
    private String shippingTemplateId;

    /**
     * 店铺分类码
     */
    private String sellerCategoryId;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;

    // Getter and Setter methods
    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getShopName() {
        return shopName;
    }

    public void setShopName(String shopName) {
        this.shopName = shopName;
    }

    public String getShippingTemplateId() {
        return shippingTemplateId;
    }

    public void setShippingTemplateId(String shippingTemplateId) {
        this.shippingTemplateId = shippingTemplateId;
    }

    public String getSellerCategoryId() {
        return sellerCategoryId;
    }

    public void setSellerCategoryId(String sellerCategoryId) {
        this.sellerCategoryId = sellerCategoryId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}