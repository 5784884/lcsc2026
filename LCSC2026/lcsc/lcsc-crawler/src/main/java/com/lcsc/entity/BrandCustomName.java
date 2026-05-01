package com.lcsc.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("brand_custom_names")
public class BrandCustomName {

    @TableId(type = IdType.AUTO)
    private Integer id;

    private String originalName;

    private String customName;

    // 🌟 新增的打折方案字段
    @TableField("discount_scheme")
    private Integer discountScheme;

    private LocalDateTime updatedAt;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }

    public String getOriginalName() { return originalName; }
    public void setOriginalName(String originalName) { this.originalName = originalName; }

    public String getCustomName() { return customName; }
    public void setCustomName(String customName) { this.customName = customName; }

    // 🌟 必须同时有 get 和 set 方法，且必须有上面的变量定义
    public Integer getDiscountScheme() { return discountScheme; }
    public void setDiscountScheme(Integer discountScheme) { this.discountScheme = discountScheme; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}